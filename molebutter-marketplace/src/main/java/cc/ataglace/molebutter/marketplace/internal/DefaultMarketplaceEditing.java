package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.util.*;
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
    private final TargetEditStore pending;
    private final cc.ataglace.molebutter.media.api.ImageAssets assets;
    private final Map<String,MarketplaceProductAdapter> adapters;
    private final TransactionTemplate tx;
    private final ComparisonMedia comparisonMedia;
    public DefaultMarketplaceEditing(BusinessAccess access,MarketplaceDrafts drafts,DraftStore draftStore,
            SubmissionStore submissions,EditingStore store,TargetEditStore pending,List<MarketplaceProductAdapter> adapters,PlatformTransactionManager manager,cc.ataglace.molebutter.media.api.ImageAssets assets) {
        this(access,drafts,draftStore,submissions,store,pending,adapters,manager,assets,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultMarketplaceEditing(BusinessAccess access,MarketplaceDrafts drafts,DraftStore draftStore,SubmissionStore submissions,EditingStore store,TargetEditStore pending,List<MarketplaceProductAdapter> adapters,PlatformTransactionManager manager,cc.ataglace.molebutter.media.api.ImageAssets assets,ComparisonMedia comparisonMedia){
        this.comparisonMedia=comparisonMedia;
        this.access=access;this.drafts=drafts;this.draftStore=draftStore;this.submissions=submissions;this.store=store;this.pending=pending;this.assets=assets;
        var configured=new LinkedHashMap<String,MarketplaceProductAdapter>();
        for(var adapter:adapters)if(configured.putIfAbsent(adapter.market(),adapter)!=null)throw new IllegalStateException("Duplicate marketplace product adapter");
        this.adapters=Map.copyOf(configured);this.tx=new TransactionTemplate(manager);
    }
    record Basis(Document reference,Map<String,EditingStore.Binding> bindings) {}
    private String accountFor(String market){var adapter=adapters.get(market);return adapter==null?null:adapter.accountKey();}
    private Set<String> targetMarkets(Document reference){
        var metadata=draftStore.registration(id(reference.id()));
        if(metadata!=null&&metadata.kind().endsWith("_REGISTRATION"))return new LinkedHashSet<>(List.of(metadata.kind().replace("_REGISTRATION","")));
        if(metadata!=null&&"MARKET_ONLY".equals(metadata.kind())){String market=submissions.importMarket(id(reference.id()));if(market==null)throw new MarketplaceEditingFailure(NOT_FOUND);return new LinkedHashSet<>(List.of(market));}
        var result=new LinkedHashSet<String>();reference.selectedMarkets().forEach(m->result.add(m.name()));
        // Transmission selection must not determine whether provider input can be saved.
        if(reference.markets()!=null)for(String market:List.of("COUPANG","NAVER","ELEVENST","LOTTEON"))if(adapters.containsKey(market)&&reference.markets().containsKey(Market.valueOf(market)))result.add(market);
        result.addAll(submissions.linkedMarkets(id(reference.id())));return result;
    }
    private boolean supports(Document reference,String market){
        var adapter=adapters.get(market);if(adapter==null)return false;if(adapter.supports(reference))return true;
        if(!List.of("COUPANG","NAVER","ELEVENST","LOTTEON").contains(market)||!reference.markets().containsKey(Market.valueOf(market)))return false;
        var selected=new LinkedHashSet<>(reference.selectedMarkets());selected.add(Market.valueOf(market));
        return adapter.supports(new Document(reference.id(),reference.revision(),reference.common(),reference.options(),reference.stockMode(),reference.productQuantity(),reference.services(),reference.media(),reference.delivery(),List.copyOf(selected),reference.markets()));
    }
    private Document editableReference(Document d){
        var metadata=draftStore.registration(id(d.id()));if(metadata==null||!"COMMON".equals(metadata.kind()))return d;
        var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());
        for(String market:List.of("COUPANG","NAVER","ELEVENST","LOTTEON"))if(adapters.containsKey(market))markets.putIfAbsent(Market.valueOf(market),new MarketConfig("",null,null,null,null));
        return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.copyOf(markets));
    }
    private long generation(Document d,String market){return Math.max(1,submissions.listingVersion(id(d.id()),market,accountFor(market)));}
    private Map<String,EditingStore.Binding> bindings(Document reference){
        var result=new LinkedHashMap<String,EditingStore.Binding>();
        for(String market:targetMarkets(reference))if(adapters.containsKey(market))result.put(market,new EditingStore.Binding(accountFor(market),ensureMapping(reference,market),generation(reference,market)));
        return Map.copyOf(result);
    }
    @Override public Session start(Long actor,String draftId) {
        return start(actor,draftId,null,false);
    }
    @Override public Session startLazy(Long actor,String draftId,String initialMarket){
        return start(actor,draftId,initialMarket,true);
    }
    private Session start(Long actor,String draftId,String initialMarket,boolean lazy) {
        access.productActor(actor,true);
        var basis=tx.execute(t->{var d=draftStore.find(id(draftId),true);checkRegistration(actor,d);if(lazy)d=editableReference(d);return new Basis(d,bindings(d));});
        if(initialMarket!=null&&!targetMarkets(basis.reference()).contains(initialMarket))throw new InputValidationFailure("편집 대상 마켓을 확인해 주세요.");
        var targets=targetMarkets(basis.reference()).stream().map(m->{
            var binding=basis.bindings().get(m);
            if(lazy&&!Objects.equals(initialMarket,m)&&binding!=null&&binding.mapping()!=null)
                return new Target(m,"UPDATE","UNLOADED",null,null,null);
            return resume(basis,observe(actor,basis,m));
        }).toList();
        prepareMedia(actor,basis,targets);
        var session=new Session(Long.toString(BusinessIds.next()),draftId,basis.reference().revision(),Instant.now().plusSeconds(600).toString(),targets);
        return tx.execute(t->{access.productActor(actor,true);checkBasis(basis);settlePending(actor,basis,targets);var decorated=compared(basis,session);store.insert(actor,basis.bindings(),decorated);return decorated;});
    }
    Session startObserved(Long actor,String draftId,CoupangEditor.EditorDocument source,String expiresAt){
        var adapter=(CoupangProductDocuments)adapters.get("COUPANG");
        return startObserved(actor,draftId,"COUPANG",source.basic().sellerProductId(),null,expiresAt,(d,m)->adapter.project(d,m,source),null);
    }
    Session startObservedNaver(Long actor,String draftId,NaverEditor.EditorDocument source,String expiresAt){
        var adapter=(NaverProductDocuments)adapters.get("NAVER");
        return startObserved(actor,draftId,"NAVER",source.originProductNo(),source.channelProductNo(),expiresAt,(d,m)->adapter.project(d,source,m),new NaverEditor.Context(source.limits(),source.optionIdentities()));
    }
    private Session startObserved(Long actor,String draftId,String market,String productId,String channelId,String expiresAt,
                                  java.util.function.BiFunction<Document,MarketplaceWriteGateway.Mapping,Document> project,NaverEditor.Context context){
        access.productActor(actor,true);
        return tx.execute(t->{
            var d=draftStore.find(id(draftId),true);checkRegistration(actor,d);var mapping=ensureMapping(d,market);
            // Naver origin GET may omit the channel number; an absent value cannot contradict a trusted mapping.
            if(mapping==null||!mapping.sellerProductId().equals(productId)||mapping.channelProductId()!=null&&(channelId!=null||!"NAVER".equals(market))&&!Objects.equals(mapping.channelProductId(),channelId))throw new MarketplaceEditingFailure(CONFLICT);
            var observed=project.apply(d,mapping);var binding=new EditingStore.Binding(accountFor(market),mapping,generation(d,market));
            submissions.observedListing(id(d.id()),market,binding.account(),binding.listingVersion(),"ACTIVE",null,observed);
            var session=new Session(Long.toString(BusinessIds.next()),draftId,d.revision(),expiresAt,List.of(new Target(market,"UPDATE","READY",Instant.now().toString(),null,observed,List.of(),List.of(),context).withListing(new ListingInfo("ACTIVE",null,Instant.now().toString(),binding.listingVersion(),false))));
            store.insert(actor,Map.of(market,binding),session);return session;
        });
    }
    @Override public Session saveReference(Long actor,String sessionId,Document reference) {
        return saveDraft(actor,sessionId,new DraftSaveRequest(reference,List.of())).session();
    }
    @Override public DraftSaveResult saveDraft(Long actor,String sessionId,DraftSaveRequest request){
        access.productActor(actor,true);
        if(request==null||request.reference()==null||request.targets()==null||request.targets().size()>6)throw new InputValidationFailure("공통 정보와 마켓별 임시 변경을 확인해 주세요.");
        return tx.execute(t->{
            var stored=require(actor,sessionId,true);var reference=request.reference();
            if(!Objects.equals(reference.id(),stored.session().draftId())||reference.revision()==null||reference.revision()!=stored.session().revision())throw new MarketplaceEditingFailure(CONFLICT);
            var before=draftStore.find(id(reference.id()),true);protectUpdateInputs(before,reference,stored);
            var changes=new LinkedHashMap<String,TargetChanges>();var intents=new LinkedHashMap<String,MarketplaceWriteGateway.EditIntent>();
            for(var proposed:request.targets()){
                if(proposed==null||proposed.market()==null||proposed.changes()==null||proposed.changes().size()>500||changes.putIfAbsent(proposed.market(),proposed)!=null)throw new InputValidationFailure("마켓별 임시 변경을 확인해 주세요.");
                var target=target(stored.session(),proposed.market());var adapter=adapters.get(proposed.market());
                if(adapter==null||!adapter.supportsPendingEdits()||!"READY".equals(target.status())||target.document()==null)throw new InputValidationFailure("마켓의 최신값을 다시 조회해 주세요.");
                var binding=requireTarget(stored,proposed.market(),true);
                var normalized="UPDATE".equals(target.mode())?adapter.normalizeChanges(target.document(),proposed.changes()):proposed.changes();
                proposed=new TargetChanges(proposed.market(),normalized,proposed.resolvedConflicts());changes.put(proposed.market(),proposed);
                requireResolvedConflicts(target,proposed);
                var baseline="CREATE".equals(target.mode())&&!submissions.recreating(id(reference.id()),proposed.market(),binding.account())?adapter.newDocument(reference):target.document();
                var desired=adapter.apply(baseline,proposed.changes());
                rememberSaved(actor,before,target,binding,adapter,desired,proposed.changes());
                reference=adapter.saveTarget(reference,desired);
                if(submissions.recreating(id(reference.id()),proposed.market(),binding.account()))submissions.observedListing(id(reference.id()),proposed.market(),binding.account(),binding.listingVersion(),"RECREATE_READY",null,desired);
                if("UPDATE".equals(target.mode())||"COUPANG".equals(proposed.market())){
                    // An unchanged visited target must not block a common-only save during approval.
                    // Local intention can be saved while immutable execution results remain pending.
                    intents.put(proposed.market(),new MarketplaceWriteGateway.EditIntent(baseline,List.copyOf(proposed.changes())));
                }
            }
            var saved=drafts instanceof DefaultMarketplaceDrafts implementation
                ?implementation.saveEditor(actor,reference.id(),reference.revision(),reference)
                :drafts.save(actor,reference.id(),reference.revision(),reference);
            for(var entry:intents.entrySet())pending.save(actor,id(saved.id()),saved.revision(),entry.getKey(),stored.binding(entry.getKey()),entry.getValue());
            pending.revision(id(saved.id()),saved.revision());
            var nextBindings=new LinkedHashMap<String,EditingStore.Binding>();if(stored.bindings()!=null)nextBindings.putAll(stored.bindings());
            else for(var old:stored.session().targets()){var binding=stored.binding(old.market());if(binding!=null)nextBindings.put(old.market(),binding);}
            var markets=targetMarkets(saved);markets.addAll(nextBindings.keySet());var nextTargets=new ArrayList<Target>();
            for(String market:markets){
                if(!nextBindings.containsKey(market)&&adapters.containsKey(market))nextBindings.put(market,new EditingStore.Binding(accountFor(market),ensureMapping(saved,market),generation(saved,market)));
                var prior=stored.session().targets().stream().filter(v->market.equals(v.market())).findFirst().orElse(null);
                if(prior!=null&&"UPDATE".equals(prior.mode())){
                    var intent=intents.get(market);nextTargets.add(intent==null?prior:new Target(market,prior.mode(),prior.status(),prior.observedAt(),prior.error(),prior.document(),intent.changes(),List.of(),prior.naverContext()).withListing(prior.listing()));
                }else{
                    var adapter=adapters.get(market);var targetReference=saved;boolean supported=supports(saved,market);
                    if(!supported&&prior!=null&&"READY".equals(prior.status())){targetReference=editableReference(saved);supported=supports(targetReference,market);}
                    nextTargets.add(resume(new Basis(saved,Map.copyOf(nextBindings)),new Target(market,"CREATE",supported?"READY":"UNSUPPORTED",null,null,supported?(submissions.recreating(id(saved.id()),market,nextBindings.get(market).account())?submissions.listing(id(saved.id()),market,nextBindings.get(market).account(),false).observed():adapter.newDocument(targetReference)):saved)));
                }
                if(!nextBindings.containsKey(market)&&adapters.containsKey(market))nextBindings.put(market,new EditingStore.Binding(accountFor(market),ensureMapping(saved,market),generation(saved,market)));
            }
            nextTargets.replaceAll(target->{var b=nextBindings.get(target.market());return b!=null&&submissions.recreating(id(saved.id()),target.market(),b.account())?target.withListing(new ListingInfo("RECREATE_READY",null,null,b.listingVersion(),false)):target;});
            var s=stored.session();var next=new Session(s.id(),s.draftId(),saved.revision(),s.expiresAt(),List.copyOf(nextTargets));
            var referenced=new HashSet<>(MarketplaceDocuments.assetIds(saved));referenced.addAll(submissions.recreationAssetIds(id(saved.id())));
            for(var entry:nextBindings.entrySet()){var remembered=pending.find(id(saved.id()),entry.getKey(),entry.getValue().account(),false);var adapter=adapters.get(entry.getKey());if(remembered!=null&&adapter!=null)referenced.addAll(MarketplaceDocuments.assetIds(adapter.apply(remembered.intent().observed(),remembered.intent().changes())));}
            assets.replaceReferences(actor,saved.id(),referenced);
            next=compared(new Basis(saved,Map.copyOf(nextBindings)),next);
            referenced.addAll(pending.savedAssetIds(id(saved.id())));assets.replaceReferences(actor,saved.id(),referenced);
            store.update(next,Map.copyOf(nextBindings));return new DraftSaveResult(saved,next);
        });
    }
    private void protectUpdateInputs(Document before,Document reference,EditingStore.Stored stored){
        if(reference.markets()==null)throw new InputValidationFailure("마켓별 입력을 확인해 주세요.");
        for(var target:stored.session().targets())if("UPDATE".equals(target.mode())&&"LOTTEON".equals(target.market())){
            if(!Objects.equals(LotteonInput.get(before),LotteonInput.get(reference)))throw new InputValidationFailure("롯데ON 연결 상품 변경은 최신 조회값의 변경 항목으로 저장해 주세요.");
        }
        for(var target:stored.session().targets())if("UPDATE".equals(target.mode())&&"ELEVENST".equals(target.market())){
            var old=before.markets().get(Market.ELEVENST);var proposed=reference.markets().get(Market.ELEVENST);
            if(!Objects.equals(old==null||old.elevenst()==null?null:old.elevenst().editorInput(),proposed==null||proposed.elevenst()==null?null:proposed.elevenst().editorInput()))throw new InputValidationFailure("연결 상품의 입력 변경은 최신 조회값의 변경 항목으로 저장해 주세요.");
        }
        for(var target:stored.session().targets())if("UPDATE".equals(target.mode())&&"NAVER".equals(target.market())){
            var old=before.markets().get(Market.NAVER);var proposed=reference.markets().get(Market.NAVER);
            var oldInput=old==null||old.naver()==null?null:old.naver().editorInput();var newInput=proposed==null||proposed.naver()==null?null:proposed.naver().editorInput();
            if(!Objects.equals(oldInput,newInput))throw new InputValidationFailure("연결 상품의 입력 변경은 최신 조회값의 변경 항목으로 저장해 주세요.");
        }
    }
    private void requireResolvedConflicts(Target target,TargetChanges proposed){
        if(proposed.resolvedConflicts().size()>500)throw new InputValidationFailure("외부 변경 확인 항목을 확인해 주세요.");
        var known=new HashSet<ConflictKey>();for(var conflict:target.conflicts())known.add(new ConflictKey(conflict.path(),conflict.optionId()));
        var resolved=new HashSet<ConflictKey>();for(var key:proposed.resolvedConflicts())if(key==null||!known.contains(key)||!resolved.add(key))throw new InputValidationFailure("외부 변경 확인 항목을 다시 확인해 주세요.");
        for(var change:proposed.changes())if(change!=null){var key=new ConflictKey(change.path(),change.optionId());if(known.contains(key)&&!resolved.contains(key))throw new InputValidationFailure("외부에서 변경된 항목을 확인하고 최신값 사용 또는 내 변경 다시 적용을 선택해 주세요.");}
    }
    @Override public Session refresh(Long actor,String sessionId,String market) {
        access.productActor(actor,true);
        var original=tx.execute(t->{var s=require(actor,sessionId,false);target(s.session(),market);requireTarget(s,market,false);return s;});
        var reference=drafts.get(actor,original.session().draftId());if(!"UNSUPPORTED".equals(target(original.session(),market).status()))reference=editableReference(reference);
        var basis=new Basis(reference,Map.of(market,original.binding(market)));var fresh=resume(basis,observe(actor,basis,market));
        prepareMedia(actor,basis,List.of(fresh));
        return tx.execute(t->{
            var current=require(actor,sessionId,true);requireTarget(current,market,true);if(!current.equals(original))throw new MarketplaceEditingFailure(CONFLICT);
            var s=current.session();var targets=s.targets().stream().map(v->v.market().equals(market)?fresh:v).toList();
            var next=new Session(Long.toString(BusinessIds.next()),s.draftId(),s.revision(),s.expiresAt(),targets);
            var bindings=current.bindings();if(bindings==null){var legacy=new LinkedHashMap<String,EditingStore.Binding>();for(var old:s.targets()){var b=current.binding(old.market());if(b!=null)legacy.put(old.market(),b);}bindings=Map.copyOf(legacy);}
            settlePending(actor,basis,List.of(fresh));next=compared(new Basis(referenceForComparison(basis),bindings),next);store.revoke(s.id());store.insert(actor,bindings,next);return next;
        });
    }
    /** Core identity/revision checks; provider identity is checked only for the selected target. */
    EditingStore.Stored require(Long actor,String value,boolean lock) {
        access.productActor(actor,true);var first=store.find(id(value),false);if(!Objects.equals(actor,first.actor()))throw new MarketplaceEditingFailure(NOT_FOUND);
        var d=draftStore.find(id(first.session().draftId()),lock);checkRegistration(actor,d);var s=lock?store.find(id(value),true):first;
        if(!Objects.equals(actor,s.actor()))throw new MarketplaceEditingFailure(NOT_FOUND);
        if(s.revoked())throw new MarketplaceEditingFailure(CONFLICT);
        if(!Instant.now().isBefore(Instant.parse(s.session().expiresAt())))throw new MarketplaceEditingFailure(EXPIRED);
        if(d.revision()!=s.session().revision())throw new MarketplaceEditingFailure(CONFLICT);return s;
    }
    EditingStore.Binding requireTarget(EditingStore.Stored stored,String market,boolean lock){
        var binding=stored.binding(market);if(binding==null||!Objects.equals(accountFor(market),binding.account()))throw new MarketplaceEditingFailure(CONFLICT);
        var reference=draftStore.find(id(stored.session().draftId()),lock);
        if(binding.listingVersion()!=generation(reference,market)||!sameMapping(binding.mapping(),ensureMapping(reference,market)))throw new MarketplaceEditingFailure(CONFLICT);return binding;
    }
    private void checkRegistration(Long actor,Document d){
        var meta=draftStore.registration(id(d.id()));if(!Set.of("COUPANG_REGISTRATION","NAVER_REGISTRATION","ELEVENST_REGISTRATION","LOTTEON_REGISTRATION").contains(meta.kind()))return;
        if(!Objects.equals(actor,meta.actor()))throw new MarketplaceEditingFailure(NOT_FOUND);
        String market=meta.kind().substring(0,meta.kind().length()-"_REGISTRATION".length());if(!Objects.equals(accountFor(market),meta.account()))throw new MarketplaceEditingFailure(CONFLICT);
    }
    private void checkBasis(Basis basis) {
        var d=draftStore.find(id(basis.reference().id()),true);if(d.revision().longValue()!=basis.reference().revision().longValue())throw new MarketplaceEditingFailure(CONFLICT);
        for(var entry:basis.bindings().entrySet())if(!Objects.equals(accountFor(entry.getKey()),entry.getValue().account())||!sameMapping(entry.getValue().mapping(),ensureMapping(d,entry.getKey())))throw new MarketplaceEditingFailure(CONFLICT);
    }
    /** Upgrade legacy imports only for their original provider. Browser links are never trusted. */
    private MarketplaceWriteGateway.Mapping ensureMapping(Document d,String market) {
        String account=accountFor(market);if(account==null)throw new MarketplaceEditingFailure(CONFLICT);
        var meta=draftStore.registration(id(d.id()));if((market+"_REGISTRATION").equals(meta.kind())&&!account.equals(meta.account()))throw new MarketplaceEditingFailure(CONFLICT);
        String imported=submissions.importAccount(id(d.id())),importMarket=submissions.importMarket(id(d.id()));if(imported!=null&&importMarket==null)importMarket="COUPANG";
        boolean original=market.equals(importMarket);if(original&&imported!=null&&!account.equals(imported))throw new MarketplaceEditingFailure(CONFLICT);
        var mapping=submissions.mapping(id(d.id()),market,account,true);if(mapping!=null)return mapping;
        if(submissions.recreating(id(d.id()),market,account))return null;
        if(submissions.linkedMarkets(id(d.id())).contains(market))throw new MarketplaceEditingFailure(CONFLICT);
        if(!original)return null;
        var adapter=adapters.get(market);var legacy=adapter==null?null:adapter.legacyMapping(d,account);if(legacy!=null)submissions.saveMapping(id(d.id()),d.revision(),market,legacy,false);return legacy;
    }
    private Target observe(Long actor,Basis basis,String market) {
        var binding=basis.bindings().get(market);boolean linked=binding!=null&&binding.mapping()!=null;String mode=linked?"UPDATE":"CREATE";var adapter=adapters.get(market);
        if(adapter==null||!linked&&!supports(basis.reference(),market))return new Target(market,mode,"UNSUPPORTED",null,null,basis.reference());
        if(!linked){var saved=submissions.listing(id(basis.reference().id()),market,binding.account(),false);boolean recreate=saved!=null&&"RECREATE_READY".equals(saved.state());return new Target(market,mode,"READY",null,null,recreate?saved.observed():adapter.newDocument(basis.reference())).withListing(new ListingInfo(recreate?"RECREATE_READY":"ACTIVE",null,null,binding.listingVersion(),false));}
        try{var observed=adapter.observeTarget(actor,basis.reference(),binding.mapping());recordListing(basis,market,"ACTIVE",null,observed.document());return new Target(market,mode,adapter.readOnlyReason(observed.document())==null?"READY":"UNSUPPORTED",Instant.now().toString(),adapter.readOnlyReason(observed.document()),observed.document(),List.of(),List.of(),observed.naverContext()).withListing(new ListingInfo("ACTIVE",null,Instant.now().toString(),binding.listingVersion(),false));}
        catch(MarketplaceListingMissing missing){recordListing(basis,market,"DELETED_CONFIRMED",missing.reason(),null);var state=submissions.listing(id(basis.reference().id()),market,binding.account(),false);var saved=pending.find(id(basis.reference().id()),market,binding.account(),false);var document=state!=null&&state.observed()!=null?state.observed():basis.reference();return new Target(market,"UPDATE","DELETED",Instant.now().toString(),missing.getMessage(),document,saved==null?List.of():saved.intent().changes(),List.of()).withListing(new ListingInfo("DELETED_CONFIRMED",missing.reason(),Instant.now().toString(),binding.listingVersion(),!submissions.active(id(basis.reference().id()),binding.account(),-1)));}
        catch(MarketplaceFailure|InputValidationFailure failure){return new Target(market,mode,"FAILED",Instant.now().toString(),failure.getMessage(),null).withListing(new ListingInfo("UNVERIFIED",null,Instant.now().toString(),binding.listingVersion(),false));}
    }
    private void recordListing(Basis basis,String market,String state,String reason,Document document){tx.executeWithoutResult(t->{var d=draftStore.find(id(basis.reference().id()),true);var binding=basis.bindings().get(market);if(d.revision().equals(basis.reference().revision())&&binding.listingVersion()==generation(d,market))submissions.observedListing(id(d.id()),market,binding.account(),binding.listingVersion(),state,reason,document);});}
    private Target resume(Basis basis,Target fresh){return resumePending(basis,fresh).withListing(fresh.listing());}
    private Target resumePending(Basis basis,Target fresh){
        var adapter=adapters.get(fresh.market());if(adapter==null||!adapter.supportsPendingEdits()||!"READY".equals(fresh.status()))return fresh;
        var binding=basis.bindings().get(fresh.market());var saved=pending.find(id(basis.reference().id()),fresh.market(),binding.account(),false);if(saved==null)return fresh;
        if(registeredCreate(basis,fresh.market(),binding,saved))return fresh;
        if(saved.version()!=1||!sameMapping(saved.mapping(),binding.mapping()))return new Target(fresh.market(),fresh.mode(),"FAILED",fresh.observedAt(),"보관된 변경의 상품 연결이 달라졌습니다. 입력과 연결을 확인해 주세요.",fresh.document(),saved.intent().changes(),List.of(),fresh.naverContext());
        if("CREATE".equals(fresh.mode())){try{adapter.apply(fresh.document(),saved.intent().changes());return new Target(fresh.market(),fresh.mode(),fresh.status(),fresh.observedAt(),fresh.error(),fresh.document(),saved.intent().changes(),List.of(),fresh.naverContext());}catch(InputValidationFailure failure){return new Target(fresh.market(),fresh.mode(),"FAILED",fresh.observedAt(),failure.getMessage(),fresh.document(),saved.intent().changes(),List.of(),fresh.naverContext());}}
        try{var rebased=adapter.rebase(saved.intent().observed(),fresh.document(),adapter.normalizeChanges(saved.intent().observed(),saved.intent().changes()));return new Target(fresh.market(),fresh.mode(),fresh.status(),fresh.observedAt(),fresh.error(),fresh.document(),rebased.changes(),rebased.conflicts(),fresh.naverContext());}
        catch(InputValidationFailure failure){return new Target(fresh.market(),fresh.mode(),"FAILED",fresh.observedAt(),failure.getMessage(),fresh.document(),saved.intent().changes(),List.of(),fresh.naverContext());}
    }
    /** Retire confirmed desired values without rebasing unresolved edits onto a new observation. */
    private void settlePending(Long actor,Basis basis,List<Target> targets){
        for(var target:targets){
            var adapter=adapters.get(target.market());var binding=basis.bindings().get(target.market());
            if(adapter==null||!adapter.supportsPendingEdits()||!"UPDATE".equals(target.mode())||!"READY".equals(target.status())||binding==null)continue;
            savedTarget(actor,basis,target,true);
            var saved=pending.find(id(basis.reference().id()),target.market(),binding.account(),true);
            if(saved!=null&&registeredCreate(basis,target.market(),binding,saved)){pending.delete(id(basis.reference().id()),target.market(),binding.account());continue;}
            if(saved!=null&&saved.version()==1&&sameMapping(saved.mapping(),binding.mapping())&&!saved.intent().changes().equals(target.pendingChanges()))
                pending.save(actor,id(basis.reference().id()),basis.reference().revision(),target.market(),binding,new MarketplaceWriteGateway.EditIntent(saved.intent().observed(),target.pendingChanges()));
        }
    }
    private Document referenceForComparison(Basis basis){return basis.reference();}
    private Session compared(Basis basis,Session session){return new Session(session.id(),session.draftId(),session.revision(),session.expiresAt(),session.targets().stream().map(target->{
        var adapter=adapters.get(target.market());var binding=basis.bindings().get(target.market());
        if(adapter==null||binding==null||target.document()==null||!"UPDATE".equals(target.mode())||!Set.of("READY","UNSUPPORTED").contains(target.status()))return target.withComparison(null);
        var saved=savedTarget(null,basis,target,false);return target.withComparison(TargetComparisons.compare(adapter,target,saved,session.revision(),binding.listingVersion(),comparisonMedia==null?Map.of():comparisonMedia.decisions(basis.reference().id(),target.market(),binding,adapter,target.document(),saved==null?null:saved.document())));
    }).toList());}
    private void prepareMedia(Long actor,Basis basis,List<Target> targets){
        if(comparisonMedia==null)return;
        for(var target:targets)if("COUPANG".equals(target.market())&&Set.of("READY","UNSUPPORTED").contains(target.status())&&"UPDATE".equals(target.mode())){
            var saved=savedTarget(actor,basis,target,false);if(saved==null)continue;var binding=basis.bindings().get(target.market());var transmitted=submissions.transmittedIntent(id(basis.reference().id()),target.market(),binding);
            comparisonMedia.prepare(actor,basis.reference(),target.market(),binding,adapters.get(target.market()),target.document(),saved.document(),transmitted==null?null:transmitted.execution(),transmitted==null?null:adapters.get(target.market()).apply(transmitted.intent().observed(),transmitted.intent().changes()));
        }
    }
    private TargetEditStore.SavedTarget comparisonKnown(TargetEditStore.SavedTarget saved,MarketplaceProductAdapter adapter,Document transmitted){
        var known=adapter.comparisonKnown(saved.document(),saved.known(),transmitted);return new TargetEditStore.SavedTarget(saved.mapping(),saved.document(),known==null?saved.known():known,saved.resolutions(),saved.updatedAt());
    }
    private TargetEditStore.SavedTarget savedTarget(Long actor,Basis basis,Target target,boolean initialize){
        var binding=basis.bindings().get(target.market());var adapter=adapters.get(target.market());long draft=id(basis.reference().id());
        var sent=submissions.lastIntent(draft,target.market(),binding);var transmitted=sent==null?null:adapter.apply(sent.observed(),sent.changes());
        var saved=pending.saved(draft,target.market(),binding,false);
        if(saved!=null){if(!sameMapping(saved.mapping(),binding.mapping())){
            // CREATE acquired its ID without changing the connection generation. Require server execution proof.
            if(saved.mapping()!=null||binding.mapping()==null||submissions.lastIntent(draft,target.market(),binding)==null)return null;
            saved=new TargetEditStore.SavedTarget(binding.mapping(),saved.document(),saved.known(),saved.resolutions(),saved.updatedAt());if(initialize)pending.saveTarget(actor,draft,target.market(),binding,saved);
        }saved=comparisonKnown(saved,adapter,transmitted);if(initialize)pending.saveTarget(actor,draft,target.market(),binding,saved);return saved;}
        Document candidate=null;var remembered=pending.find(draft,target.market(),binding.account(),false);
        if(remembered!=null&&remembered.version()==1&&(sameMapping(remembered.mapping(),binding.mapping())||registeredCreate(basis,target.market(),binding,remembered)))candidate=adapter.apply(remembered.intent().observed(),remembered.intent().changes());
        if(candidate==null){var config=basis.reference().markets().get(Market.valueOf(target.market()));boolean nativeSaved=config!=null&&switch(target.market()){case "NAVER"->config.naver()!=null&&config.naver().editorInput()!=null;case "LOTTEON"->config.lotteon()!=null&&config.lotteon().editorInput()!=null;case "ELEVENST"->config.elevenst()!=null&&config.elevenst().editorInput()!=null;default->false;};if(nativeSaved)candidate=basis.reference();}
        if(candidate==null&&binding.mapping()!=null){var intent=submissions.lastIntent(draft,target.market(),binding);if(intent!=null)candidate=adapter.apply(intent.observed(),intent.changes());}
        if(candidate==null)return null;
        saved=new TargetEditStore.SavedTarget(binding.mapping(),candidate,adapter.comparisonFields(candidate).stream().map(TargetComparisons::key).toList(),List.of(),null);
        saved=comparisonKnown(saved,adapter,transmitted);if(initialize)pending.saveTarget(actor,draft,target.market(),binding,saved);return saved;
    }
    private void rememberSaved(Long actor,Document reference,Target target,EditingStore.Binding binding,MarketplaceProductAdapter adapter,Document desired,List<Change> changes){
        var saved=savedTarget(actor,new Basis(reference,Map.of(target.market(),binding)),target,true);
        if(changes.isEmpty())return;
        var document=saved==null?desired:adapter.apply(saved.document(),changes);
        var known=new LinkedHashSet<ConflictKey>(saved==null?List.of():saved.known());changes.forEach(c->known.add(TargetComparisons.key(c)));
        pending.saveTarget(actor,id(reference.id()),target.market(),binding,new TargetEditStore.SavedTarget(binding.mapping(),document,List.copyOf(known),saved==null?List.of():saved.resolutions(),null));
    }
    ResolveResult resolve(Long actor,Resolve request){
        access.productActor(actor,true);
        if(request==null||request.revision()==null||request.listingVersion()==null||request.comparisonToken()==null||request.requestId()==null||!request.requestId().matches("[0-9a-fA-F-]{36}")||request.choices()==null||request.choices().isEmpty()||request.choices().size()>500)throw new InputValidationFailure("비교 선택 항목을 확인해 주세요.");
        try{UUID.fromString(request.requestId());}catch(RuntimeException invalid){throw new InputValidationFailure("요청 번호를 확인해 주세요.");}
        String hash=TargetComparisons.hash(request);var repeated=pending.resolution(actor,request.requestId());
        if(repeated!=null){if(!hash.equals(repeated.hash()))throw new MarketplaceEditingFailure(CONFLICT);var d=drafts.get(actor,Long.toString(repeated.draft()));if(d.revision()!=repeated.revision())throw new MarketplaceEditingFailure(CONFLICT);var s=store.find(id(request.sessionId()),false);if(s==null||!Objects.equals(s.actor(),actor)||s.session().revision()!=repeated.revision())throw new MarketplaceEditingFailure(CONFLICT);return new ResolveResult(true,null,d,s.session(),null);}
        var original=tx.execute(t->{var s=require(actor,request.sessionId(),false);var binding=requireTarget(s,request.market(),false);if(s.session().revision()!=request.revision()||binding.listingVersion()!=request.listingVersion())throw new MarketplaceEditingFailure(CONFLICT);return s;});
        var reference=drafts.get(actor,original.session().draftId());var binding=original.binding(request.market());var adapter=adapters.get(request.market());var previous=target(original.session(),request.market());
        if(previous.document()==null||!"UPDATE".equals(previous.mode()))throw new InputValidationFailure("연결 상품의 최신값을 먼저 조회해 주세요.");
        var oldComparison=previous.comparison();if(oldComparison==null||!Objects.equals(oldComparison.policyVersion(),TargetComparisons.POLICY_VERSION)||!request.comparisonToken().equals(oldComparison.token()))throw new MarketplaceEditingFailure(CONFLICT);
        var selected=new LinkedHashMap<String,ComparisonRow>();
        for(var choice:request.choices()){
            if(choice==null||!Set.of("MARKET","APP").contains(choice.action())||selected.containsKey(choice.key()))throw new InputValidationFailure("비교 선택을 확인해 주세요.");
            var row=oldComparison.rows().stream().filter(r->r.key().equals(choice.key())).findFirst().orElseThrow(()->new MarketplaceEditingFailure(CONFLICT));
            if(choice.action().equals("MARKET")?!row.adoptAllowed():!row.replayAllowed())throw new InputValidationFailure(Objects.toString(row.reason(),"선택할 수 없는 항목입니다."));selected.put(choice.key(),row);
        }
        // Remote reads are outside the lock/transaction and retain each provider's pacing.
        var basis=new Basis(reference,Map.of(request.market(),binding));var fresh=observe(actor,basis,request.market());
        prepareMedia(actor,basis,List.of(fresh));
        return tx.execute(t->{
            var current=require(actor,request.sessionId(),true);requireTarget(current,request.market(),true);if(!current.equals(original)){var duplicate=pending.resolution(actor,request.requestId(),true);if(duplicate!=null&&hash.equals(duplicate.hash())&&current.session().revision()==duplicate.revision())return new ResolveResult(true,null,draftStore.find(id(current.session().draftId()),true),current.session(),null);throw new MarketplaceEditingFailure(CONFLICT);}
            var saved=savedTarget(actor,basis,previous,true);var oldFields=TargetComparisons.observedFields(adapter,previous.document());var freshFields=TargetComparisons.observedFields(adapter,fresh.document());
            boolean changed=!Set.of("READY","UNSUPPORTED").contains(fresh.status())||!previous.document().options().stream().map(Option::id).collect(java.util.stream.Collectors.toSet()).equals(fresh.document()==null?Set.of():fresh.document().options().stream().map(Option::id).collect(java.util.stream.Collectors.toSet()))||selected.values().stream().flatMap(r->r.fields().stream()).anyMatch(k->!TargetComparisons.sameField(adapter,previous.document(),oldFields.get(k),fresh.document(),freshFields.get(k)));
            var freshComparison=TargetComparisons.compare(adapter,fresh,saved,request.revision(),binding.listingVersion(),comparisonMedia==null?Map.of():comparisonMedia.decisions(reference.id(),request.market(),binding,adapter,fresh.document(),saved==null?null:saved.document()));
            for(var row:selected.values())if(row.evidenceToken()!=null){var latest=freshComparison.rows().stream().filter(r->r.key().equals(row.key())).findFirst().orElse(null);if(latest==null||!Objects.equals(row.evidenceToken(),latest.evidenceToken()))changed=true;}
            if(changed){var resumed=resume(basis,fresh);var s=current.session();var next=new Session(s.id(),s.draftId(),s.revision(),s.expiresAt(),s.targets().stream().map(v->v.market().equals(request.market())?resumed:v).toList());next=compared(new Basis(reference,current.bindings()),next);store.update(next,current.bindings());return new ResolveResult(false,"선택한 마켓 값이나 옵션 구성이 변경되었습니다. 입력을 유지한 채 다시 비교해 주세요.",reference,next,null);}
            var savedFields=saved==null?Map.<ConflictKey,Change>of():TargetComparisons.fields(adapter,saved.document());
            var prior=pending.find(id(reference.id()),request.market(),binding.account(),true);
            Document pendingBasis=prior==null?previous.document():prior.intent().observed();
            var pendingChanges=new LinkedHashMap<ConflictKey,Change>();if(prior!=null)for(var c:adapter.normalizeChanges(pendingBasis,prior.intent().changes()))pendingChanges.put(TargetComparisons.key(c),c);
            Document appDocument=saved==null?fresh.document():saved.document();var known=new LinkedHashSet<ConflictKey>(saved==null?List.of():saved.known());var proofs=new ArrayList<TargetEditStore.Proof>(saved==null?List.of():saved.resolutions());
            for(var choice:request.choices()){
                var row=selected.get(choice.key());var selectedLatest=row.fields().stream().map(freshFields::get).toList();
                pendingBasis=adapter.apply(pendingBasis,selectedLatest);
                for(var field:row.fields())pendingChanges.remove(field);
                if(choice.action().equals("MARKET")){appDocument=adapter.apply(appDocument,selectedLatest);known.addAll(row.fields());}
                else for(var field:row.fields()){var desired=savedFields.get(field);if(!TargetComparisons.sameField(adapter,saved.document(),desired,fresh.document(),freshFields.get(field)))pendingChanges.put(field,desired);}
                proofs.removeIf(p->p.key().equals(choice.key()));proofs.add(new TargetEditStore.Proof(choice.key(),choice.action(),TargetComparisons.hash(row.observed()),TargetComparisons.hash(row.saved())));
            }
            // Only the provider-owned input changes. Common reference and other providers survive.
            var nextReference=adapter.saveTarget(reference,appDocument);var d=drafts instanceof DefaultMarketplaceDrafts impl?impl.saveEditor(actor,reference.id(),reference.revision(),nextReference):drafts.save(actor,reference.id(),reference.revision(),nextReference);
            pending.saveTarget(actor,id(d.id()),request.market(),binding,new TargetEditStore.SavedTarget(binding.mapping(),appDocument,List.copyOf(known),List.copyOf(proofs),null));
            pending.save(actor,id(d.id()),d.revision(),request.market(),binding,new MarketplaceWriteGateway.EditIntent(pendingBasis,List.copyOf(pendingChanges.values())));pending.revision(id(d.id()),d.revision());
            var resumed=resume(new Basis(d,basis.bindings()),fresh);var s=current.session();var next=new Session(s.id(),s.draftId(),d.revision(),s.expiresAt(),s.targets().stream().map(v->v.market().equals(request.market())?resumed:v).toList());next=compared(new Basis(d,current.bindings()),next);store.update(next,current.bindings());
            var refs=new HashSet<>(MarketplaceDocuments.assetIds(d));refs.addAll(pending.assetIds(id(d.id()),List.copyOf(adapters.values())));refs.addAll(pending.savedAssetIds(id(d.id())));refs.addAll(submissions.recreationAssetIds(id(d.id())));assets.replaceReferences(actor,d.id(),refs);
            pending.resolution(actor,request.requestId(),hash,id(d.id()),request.market(),binding.listingVersion(),d.revision());return new ResolveResult(true,null,d,next,null);
        });
    }
    private boolean registeredCreate(Basis basis,String market,EditingStore.Binding binding,TargetEditStore.Saved saved){return saved.version()==1&&saved.mapping()==null&&binding.mapping()!=null&&(submissions.applied(id(basis.reference().id()),market,binding.account(),saved.revision())||submissions.registeredIntent(id(basis.reference().id()),market,binding,saved.intent()));}
    private static Target target(Session session,String market){return session.targets().stream().filter(t->Objects.equals(t.market(),market)).findFirst().orElseThrow(()->new InputValidationFailure("편집 세션의 마켓을 확인해 주세요."));}
    static boolean sameMapping(MarketplaceWriteGateway.Mapping a,MarketplaceWriteGateway.Mapping b){
        if(a==null||b==null)return a==b;
        return Objects.equals(a.accountKey(),b.accountKey())&&Objects.equals(a.sellerProductId(),b.sellerProductId())&&Objects.equals(a.channelProductId(),b.channelProductId())&&optionMappings(a).equals(optionMappings(b));
    }
    private static Map<String,MarketplaceWriteGateway.OptionMapping> optionMappings(MarketplaceWriteGateway.Mapping mapping){var result=new HashMap<String,MarketplaceWriteGateway.OptionMapping>();if(mapping.options()!=null)for(var option:mapping.options())if(result.put(option.optionId(),option)!=null)throw new MarketplaceEditingFailure(CONFLICT);return result;}
    static long id(String value){return DefaultMarketplaceSubmissions.id(value);}
    @Scheduled(fixedDelay=3600000,initialDelay=3600000) public void cleanup(){store.cleanup();}
}
