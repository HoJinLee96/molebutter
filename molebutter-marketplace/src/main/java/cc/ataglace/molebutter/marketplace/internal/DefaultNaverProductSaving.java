package cc.ataglace.molebutter.marketplace.internal;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceEditingFailure.Kind.*;

/** Bind a trusted initial SmartStore projection to explicit changes without accepting source JSON. */
@Service
public final class DefaultNaverProductSaving implements NaverProductSaving {
    private final BusinessAccess access;
    private final NaverCatalog catalog;
    private final NaverProductDocuments documents;
    private final DraftStore drafts;
    private final SubmissionStore store;
    private final DefaultMarketplaceEditing editing;
    private final MarketplaceSubmissions submissions;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String,Observed> observations=new LinkedHashMap<>();
    private record Observed(Long actor,String account,String productId,Instant expires,NaverEditor.EditorDocument document){}
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultNaverProductSaving(BusinessAccess access,NaverCatalog catalog,NaverProductDocuments documents,DraftStore drafts,
            SubmissionStore store,DefaultMarketplaceEditing editing,MarketplaceSubmissions submissions,PlatformTransactionManager manager){
        this(access,catalog,documents,drafts,store,editing,submissions,manager,Clock.systemUTC());
    }
    DefaultNaverProductSaving(BusinessAccess access,NaverCatalog catalog,NaverProductDocuments documents,DraftStore drafts,
            SubmissionStore store,DefaultMarketplaceEditing editing,MarketplaceSubmissions submissions,PlatformTransactionManager manager,Clock clock){
        this.access=access;this.catalog=catalog;this.documents=documents;this.drafts=drafts;this.store=store;this.editing=editing;
        this.submissions=submissions;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    @Override public Observation observe(Long actor,String productId){
        access.productActor(actor,true);product(productId);var source=catalog.editor(actor,productId);
        if(source==null||source.input()==null||!productId.equals(source.originProductNo()))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE,"NAVER");
        String token=UUID.randomUUID().toString()+UUID.randomUUID();var expires=clock.instant().plusSeconds(600);String account=documents.accountKey();
        synchronized(observations){observations.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expires()));
            if(observations.size()>=2000)throw new InputValidationFailure("열린 상품 편집 화면이 너무 많습니다. 잠시 후 다시 조회해 주세요.");
            observations.put(token,new Observed(actor,account,productId,expires,source));}
        Long mapped=store.mappedDraft("NAVER",account,productId);var imported=mapped==null?drafts.imported("NAVER",account,productId):null;
        return new Observation(token,expires.toString(),source,mapped!=null?Long.toString(mapped):imported==null?null:imported.id());
    }
    @Override public MarketplaceSubmissions.Preview prepare(Long actor,String productId,Prepare input){
        access.productActor(actor,true);product(productId);if(input==null||input.token()==null)throw new MarketplaceEditingFailure(NOT_FOUND);
        Observed original;synchronized(observations){original=observations.get(input.token());}
        if(original==null||!Objects.equals(actor,original.actor())||!productId.equals(original.productId())||!documents.accountKey().equals(original.account()))throw new MarketplaceEditingFailure(NOT_FOUND);
        if(!clock.instant().isBefore(original.expires()))throw new MarketplaceEditingFailure(EXPIRED);
        var edited=documents.normalize(input.input());checkLimits(original.document(),edited);
        var reference=importObserved(actor,original.document());
        var session=editing.startObservedNaver(actor,reference.id(),original.document(),original.expires().toString());
        // Existing mappings can use other internal UUIDs; diff after rekeying to the session's observation.
        var observed=documents.input(session.targets().getFirst().document());
        var mapping=store.mapping(Long.parseLong(reference.id()),"NAVER",documents.accountKey(),false);
        var rekeyed=rekey(original.document(),edited,mapping,observed);
        var changes=documents.diff(observed,rekeyed);
        return submissions.prepare(actor,new MarketplaceEditing.PrepareRequest(reference.id(),reference.revision(),session.id(),false,List.of(new MarketplaceEditing.TargetChanges("NAVER",changes))));
    }
    private MarketplaceDrafts.Document importObserved(Long actor,NaverEditor.EditorDocument source){
        String account=documents.accountKey(),product=source.originProductNo();
        Long existing=store.mappedDraft("NAVER",account,product);if(existing!=null)return drafts.find(existing,false);
        var imported=drafts.imported("NAVER",account,product);if(imported!=null)return tx.execute(t->link(imported,source));
        var document=documents.document(null,null,source.input());
        try{return tx.execute(t->{access.productActor(actor,true);var found=drafts.imported("NAVER",account,product);return link(found==null?drafts.insert(actor,BusinessIds.next(),document,"NAVER",account,product):found,source);});}
        catch(DuplicateKeyException duplicate){var found=drafts.imported("NAVER",account,product);if(found!=null)return tx.execute(t->link(found,source));throw duplicate;}
    }
    private MarketplaceDrafts.Document link(MarketplaceDrafts.Document d,NaverEditor.EditorDocument source){
        var mapping=new MarketplaceWriteGateway.Mapping(documents.accountKey(),source.originProductNo(),source.optionIdentities().stream().map(o->new MarketplaceWriteGateway.OptionMapping(o.id(),o.remoteId(),null)).toList(),source.channelProductNo());
        store.saveMapping(Long.parseLong(d.id()),d.revision(),"NAVER",mapping,false);return d;
    }
    private static NaverEditor.Input rekey(NaverEditor.EditorDocument original,NaverEditor.Input edited,MarketplaceWriteGateway.Mapping mapping,NaverEditor.Input observed){
        if(mapping==null)throw new MarketplaceEditingFailure(CONFLICT);
        var ids=new HashMap<String,String>();
        for(var old:original.optionIdentities())for(var current:mapping.options())if(Objects.equals(old.remoteId(),current.sellerProductItemId())){ids.put(old.id(),current.optionId());break;}
        var options=edited.options().stream().map(o->new NaverEditor.Option(ids.getOrDefault(o.id(),o.id()),o.values(),o.price(),o.stockQuantity(),o.sellerManagerCode(),o.usable())).toList();
        var imageIds=new HashMap<String,String>();var consumed=new HashSet<String>();
        // Images have no remote option identifier. Match the trusted source occurrences and order,
        // then preserve user reordering while substituting the existing internal UUID only.
        for(var old:original.input().images().stream().sorted(Comparator.comparingInt(NaverEditor.Image::order)).toList()){
            var matches=observed.images().stream().filter(i->!consumed.contains(i.id())&&Objects.equals(old.url(),i.url())).sorted(Comparator.comparingInt(NaverEditor.Image::order)).toList();
            var current=matches.stream().filter(i->i.order()==old.order()).findFirst().orElse(matches.isEmpty()?null:matches.getFirst());
            if(current!=null){imageIds.put(old.id(),current.id());consumed.add(current.id());}
        }
        var images=edited.images().stream().map(i->new NaverEditor.Image(imageIds.getOrDefault(i.id(),i.id()),i.assetId(),i.url(),i.representative(),i.order())).toList();
        return new NaverEditor.Input(edited.fields(),edited.optionMode(),edited.optionNames(),options,images,edited.description());
    }
    private static void checkLimits(NaverEditor.EditorDocument source,NaverEditor.Input edited){
        var limits=source.limits();if(limits==null)throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE,"NAVER");
        if(limits.groupProduct())throw new InputValidationFailure("그룹상품은 이 화면에서 수정할 수 없습니다. 스마트스토어에서 수정해 주세요.");
        if(limits.categoryReadonly()&&!Objects.equals(source.input().fields().get("originProduct.leafCategoryId"),edited.fields().get("originProduct.leafCategoryId")))throw new InputValidationFailure("기존 상품 카테고리는 변경할 수 없습니다.");
        if(limits.modelReadonly()&&!Objects.equals(source.input().fields().get("originProduct.detailAttribute.naverShoppingSearchInfo.modelId"),edited.fields().get("originProduct.detailAttribute.naverShoppingSearchInfo.modelId")))throw new InputValidationFailure("기존 상품 모델 연결은 변경할 수 없습니다.");
        if(limits.optionStructureReadonly()&&(!Objects.equals(source.input().optionMode(),edited.optionMode())||!Objects.equals(source.input().optionNames(),edited.optionNames())||!source.input().options().stream().map(o->List.of(o.id(),o.values())).toList().equals(edited.options().stream().map(o->List.of(o.id(),o.values())).toList())))throw new InputValidationFailure("기존 상품 옵션 구성은 변경할 수 없습니다.");
    }
    private static void product(String value){if(value==null||!value.matches("[0-9]{1,30}"))throw new InputValidationFailure("원상품 번호를 확인해 주세요.");}
}
