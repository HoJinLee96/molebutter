package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceEditingFailure.Kind.*;

@Service
public final class DefaultMarketplaceEditing implements MarketplaceEditing {
    private final BusinessAccess access;
    private final MarketplaceDrafts drafts;
    private final DraftStore draftStore;
    private final SubmissionStore submissions;
    private final EditingStore store;
    private final Map<String,MarketplaceProductAdapter> adapters;
    private final TransactionTemplate tx;
    public DefaultMarketplaceEditing(BusinessAccess access,MarketplaceDrafts drafts,DraftStore draftStore,
            SubmissionStore submissions,EditingStore store,List<MarketplaceProductAdapter> adapters,PlatformTransactionManager manager) {
        this.access=access;this.drafts=drafts;this.draftStore=draftStore;this.submissions=submissions;this.store=store;
        var configured=new LinkedHashMap<String,MarketplaceProductAdapter>();
        for(var adapter:adapters)if(configured.putIfAbsent(adapter.market(),adapter)!=null)throw new IllegalStateException("Duplicate marketplace product adapter");
        this.adapters=Map.copyOf(configured);this.tx=new TransactionTemplate(manager);
    }
    record Basis(Document reference,MarketplaceWriteGateway.Mapping mapping) {}
    private String market(Document d){
        if(d.selectedMarkets().size()==1){String selected=d.selectedMarkets().getFirst().name();var adapter=adapters.get(selected);if(adapter!=null&&adapter.supports(d))return selected;}
        return "COUPANG";
    }
    private String accountFor(Document d){var adapter=adapters.get(market(d));return adapter==null?null:adapter.accountKey();}
    @Override public Session start(Long actor,String draftId) {
        access.productActor(actor,true);
        var basis=tx.execute(t->{var d=draftStore.find(id(draftId),true);checkRegistration(actor,d);return new Basis(d,ensureMapping(d));});
        var markets=new LinkedHashSet<>(basis.reference().selectedMarkets());
        if(basis.mapping()!=null)markets.add(Market.valueOf(market(basis.reference())));
        var targets=markets.stream().map(m->observe(actor,basis,m)).toList();
        var session=new Session(Long.toString(BusinessIds.next()),draftId,basis.reference().revision(),Instant.now().plusSeconds(600).toString(),targets);
        return tx.execute(t->{access.productActor(actor,true);checkBasis(basis);store.insert(actor,accountFor(basis.reference()),basis.mapping(),session);return session;});
    }
    /** Bind trusted server observations to the durable editing/execution pipeline. */
    Session startObserved(Long actor,String draftId,CoupangEditor.EditorDocument source,String expiresAt){
        var adapter=(CoupangProductDocuments)adapters.get("COUPANG");
        return startObserved(actor,draftId,"COUPANG",source.basic().sellerProductId(),null,expiresAt,(d,m)->adapter.project(d,m,source));
    }
    Session startObservedNaver(Long actor,String draftId,NaverEditor.EditorDocument source,String expiresAt){
        var adapter=(NaverProductDocuments)adapters.get("NAVER");
        return startObserved(actor,draftId,"NAVER",source.originProductNo(),source.channelProductNo(),expiresAt,(d,m)->adapter.project(d,source,m));
    }
    private Session startObserved(Long actor,String draftId,String market,String productId,String channelId,String expiresAt,
                                  java.util.function.BiFunction<Document,MarketplaceWriteGateway.Mapping,Document> project){
        access.productActor(actor,true);
        return tx.execute(t->{
            var d=draftStore.find(id(draftId),true);checkRegistration(actor,d);var mapping=ensureMapping(d);
            // Naver origin GET may omit the channel number; an absent value cannot contradict a trusted mapping.
            if(!market.equals(market(d))||mapping==null||!mapping.sellerProductId().equals(productId)||mapping.channelProductId()!=null&&(channelId!=null||!"NAVER".equals(market))&&!Objects.equals(mapping.channelProductId(),channelId))throw new MarketplaceEditingFailure(CONFLICT);
            var observed=project.apply(d,mapping);
            var session=new Session(Long.toString(BusinessIds.next()),draftId,d.revision(),expiresAt,List.of(new Target(market,"UPDATE","READY",Instant.now().toString(),null,observed)));
            store.insert(actor,accountFor(d),mapping,session);return session;
        });
    }
    @Override public Session saveReference(Long actor,String sessionId,Document reference) {
        access.productActor(actor,true);
        return tx.execute(t->{
            var savedSession=require(actor,sessionId,true);
            if(reference==null||!Objects.equals(reference.id(),savedSession.session().draftId())||reference.revision()==null||reference.revision()!=savedSession.session().revision())throw new MarketplaceEditingFailure(CONFLICT);
            var saved=drafts.save(actor,reference.id(),reference.revision(),reference);
            var s=savedSession.session();
            var markets=new LinkedHashSet<>(saved.selectedMarkets());if(savedSession.mapping()!=null)markets.add(Market.valueOf(market(saved)));
            var targets=markets.stream().map(m->{
                var prior=s.targets().stream().filter(target->target.market().equals(m.name())).findFirst().orElse(null);
                if(prior!=null&&"UPDATE".equals(prior.mode()))return prior;
                var adapter=adapters.get(m.name());boolean supported=adapter!=null&&adapter.supports(saved);
                return new Target(m.name(),"CREATE",supported?"READY":"UNSUPPORTED",null,null,supported?adapter.newDocument(saved):saved);
            }).toList();
            var next=new Session(s.id(),s.draftId(),saved.revision(),s.expiresAt(),targets);
            store.update(next);return next;
        });
    }
    @Override public Session refresh(Long actor,String sessionId,String marketValue) {
        access.productActor(actor,true);Market market;
        try{market=Market.valueOf(marketValue);}catch(RuntimeException invalid){throw new InputValidationFailure("재조회할 마켓을 확인해 주세요.");}
        var original=tx.execute(t->require(actor,sessionId,false));
        if(original.session().targets().stream().noneMatch(t->t.market().equals(marketValue)))throw new InputValidationFailure("연결된 마켓을 확인해 주세요.");
        var basis=new Basis(drafts.get(actor,original.session().draftId()),original.mapping());
        var fresh=observe(actor,basis,market);
        return tx.execute(t->{
            var current=require(actor,sessionId,true);
            if(!current.equals(original))throw new MarketplaceEditingFailure(CONFLICT);
            var s=current.session();var targets=s.targets().stream().map(target->target.market().equals(marketValue)?fresh:target).toList();
            var next=new Session(Long.toString(BusinessIds.next()),s.draftId(),s.revision(),s.expiresAt(),targets);
            store.revoke(s.id());store.insert(actor,current.account(),current.mapping(),next);return next;
        });
    }
    /** Called under a short transaction at prepare/queue time. No remote I/O. */
    EditingStore.Stored require(Long actor,String value,boolean lock) {
        access.productActor(actor,true);var first=store.find(id(value),false);
        if(!Objects.equals(actor,first.actor()))throw new MarketplaceEditingFailure(NOT_FOUND);
        // All mutation flows lock draft -> session -> mapping, including execution queueing.
        var d=draftStore.find(id(first.session().draftId()),lock);checkRegistration(actor,d);
        var s=lock?store.find(id(value),true):first;
        if(!Objects.equals(actor,s.actor()))throw new MarketplaceEditingFailure(NOT_FOUND);
        if(s.revoked())throw new MarketplaceEditingFailure(CONFLICT);
        if(!Instant.now().isBefore(Instant.parse(s.session().expiresAt())))throw new MarketplaceEditingFailure(EXPIRED);
        if(!Objects.equals(accountFor(d),s.account()))throw new MarketplaceEditingFailure(CONFLICT);
        if(d.revision()!=s.session().revision()||!Objects.equals(s.mapping(),ensureMapping(d)))throw new MarketplaceEditingFailure(CONFLICT);
        return s;
    }
    private void checkRegistration(Long actor,Document d){
        var meta=draftStore.registration(id(d.id()));if(!Set.of("COUPANG_REGISTRATION","NAVER_REGISTRATION").contains(meta.kind()))return;
        if(!Objects.equals(actor,meta.actor()))throw new MarketplaceEditingFailure(NOT_FOUND);
        if(!Objects.equals(accountFor(d),meta.account()))throw new MarketplaceEditingFailure(CONFLICT);
    }
    private void checkBasis(Basis basis) {
        var d=draftStore.find(id(basis.reference().id()),true);
        if(d.revision().longValue()!=basis.reference().revision().longValue()||!Objects.equals(basis.mapping(),ensureMapping(d)))throw new MarketplaceEditingFailure(CONFLICT);
    }
    /** Upgrade legacy imports without allowing browser-supplied links. */
    private MarketplaceWriteGateway.Mapping ensureMapping(Document d) {
        String market=market(d),account=accountFor(d);if(account==null)throw new MarketplaceEditingFailure(CONFLICT);
        var meta=draftStore.registration(id(d.id()));
        if((market+"_REGISTRATION").equals(meta.kind())&&!account.equals(meta.account()))throw new MarketplaceEditingFailure(CONFLICT);
        String imported=submissions.importAccount(id(d.id()));if(imported!=null&&!account.equals(imported))throw new MarketplaceEditingFailure(CONFLICT);
        var mapping=submissions.mapping(id(d.id()),market,account,true);if(mapping!=null)return mapping;
        var adapter=adapters.get(market);var legacy=adapter==null?null:adapter.legacyMapping(d,account);
        if(legacy==null)return null;
        if(imported==null)throw new MarketplaceEditingFailure(CONFLICT);
        submissions.saveMapping(id(d.id()),d.revision(),market,legacy,false);return legacy;
    }
    private Target observe(Long actor,Basis basis,Market market) {
        boolean linked=market.name().equals(market(basis.reference()))&&basis.mapping()!=null;
        String mode=linked?"UPDATE":"CREATE";var adapter=adapters.get(market.name());
        if(adapter==null||!adapter.supports(basis.reference()))return new Target(market.name(),mode,"UNSUPPORTED",null,null,basis.reference());
        if(!linked)return new Target(market.name(),mode,"READY",null,null,adapter.newDocument(basis.reference()));
        try{return new Target(market.name(),mode,"READY",Instant.now().toString(),null,adapter.observe(actor,basis.reference(),basis.mapping()));}
        catch(MarketplaceFailure|InputValidationFailure failure){return new Target(market.name(),mode,"FAILED",Instant.now().toString(),failure.getMessage(),null);}
    }
    static long id(String value){return DefaultMarketplaceSubmissions.id(value);}
    @Scheduled(fixedDelay=3600000,initialDelay=3600000) public void cleanup(){store.cleanup();}
}
