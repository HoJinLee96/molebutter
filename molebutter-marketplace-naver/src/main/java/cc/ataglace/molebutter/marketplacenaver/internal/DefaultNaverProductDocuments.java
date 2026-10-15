package cc.ataglace.molebutter.marketplacenaver.internal;

import java.util.*;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

/** Naver typed inputs and source projection are kept outside the shared workflows. */
@Component
final class DefaultNaverProductDocuments implements NaverProductDocuments {
    private final NaverCatalog catalog;
    private final NaverGateway gateway;
    DefaultNaverProductDocuments(NaverCatalog catalog,NaverGateway gateway){this.catalog=catalog;this.gateway=gateway;}
    public String market(){return "NAVER";}
    public String accountKey(){return gateway.accountKey();}
    public boolean supports(Document d){return d!=null&&d.markets()!=null&&d.selectedMarkets()!=null&&d.selectedMarkets().contains(Market.NAVER)&&d.markets().get(Market.NAVER)!=null;}
    public Document normalize(Document d){
        if(d==null||d.markets()==null)return d;
        var config=d.markets().get(Market.NAVER);if(config==null||config.naver()==null)return d;
        var n=config.naver();var typed=new Naver(n.channelId(),n.status(),n.saleType(),n.originCode(),n.deliveryTemplateId(),n.afterServiceTelephone(),n.attributes(),n.notices(),n.channelProductName(),n.naverShoppingRegistration(),n.channelProductDisplayStatusType(),n.editorInput()==null?null:normalize(n.editorInput()),n.brandId(),n.editorState()==null?null:NaverCommonProjection.state(n.editorState()));
        var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());markets.put(Market.NAVER,new MarketConfig(config.categoryCode(),config.overrides(),config.coupang(),typed,config.esm(),config.lotteon(),config.addressBook()));
        return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.copyOf(markets));
    }
    public void validateInput(NaverEditor.Input input,boolean create){new NaverWriteValidation(new tools.jackson.databind.ObjectMapper()).validateInput(input,create);}
    public void validateSettings(Long actor,Document d,boolean create,List<MarketplaceEditing.Change> changes){if(create)MarketplaceCommonSettings.validate(d);if(create||changes.stream().anyMatch(c->c.path().contains(".deliveryInfo.")))NaverDeliveryPolicy.verify(input(d),query->new tools.jackson.databind.ObjectMapper().valueToTree(catalog.metadata(actor,"bundle-group",query)));}
    public NaverEditor.Input normalize(NaverEditor.Input input){return NaverEditPatch.normalize(input);}
    public Document document(String id,Long revision,NaverEditor.Input input){return NaverDraftAdapter.document(id,revision,input);}
    public NaverEditor.Input input(Document document){return NaverDraftAdapter.from(document);}
    public Document newDocument(Document reference){return NaverCommonProjection.project(normalize(reference));}
    public Document project(Document reference,NaverEditor.EditorDocument source,Mapping mapping){return NaverDraftAdapter.observed(reference,source,mapping);}
    public Document observe(Long actor,Document reference,Mapping mapping){return observeTarget(actor,reference,mapping).document();}
    public Observation observeTarget(Long actor,Document reference,Mapping mapping){
        var source=catalog.editor(actor,mapping.sellerProductId());if(mapping.channelProductId()!=null)gateway.session(()->gateway.channel(mapping.channelProductId()));var projected=project(reference,source,mapping);
        var ids=new ArrayList<NaverEditor.OptionIdentity>();
        for(var identity:source.optionIdentities()){
            String local=identity.id();if(mapping.options()!=null)for(var link:mapping.options())if(Objects.equals(identity.remoteId(),link.sellerProductItemId())){local=link.optionId();break;}
            ids.add(new NaverEditor.OptionIdentity(local,identity.remoteId()));
        }
        return new Observation(projected,new NaverEditor.Context(source.limits(),List.copyOf(ids)));
    }
    public boolean supportsPendingEdits(){return true;}
    public MarketplaceEditing.PendingRebase rebase(Document observed,Document latest,List<MarketplaceEditing.Change> changes){
        var before=input(observed);var current=input(latest);var desired=input(apply(observed,changes));
        var pending=new ArrayList<MarketplaceEditing.Change>();var conflicts=new ArrayList<MarketplaceEditing.Conflict>();
        for(var change:NaverEditPatch.granular(before,NaverEditPatch.diff(before,desired))){
            Object old=NaverEditPatch.value(before,change),now=NaverEditPatch.value(current,change),value=NaverEditPatch.value(desired,change);
            if(NaverEditPatch.equivalent(now,value))continue;
            pending.add(new MarketplaceEditing.Change(change.path(),change.optionId(),value));
            if(!NaverEditPatch.equivalent(old,now))conflicts.add(new MarketplaceEditing.Conflict(change.path(),change.optionId(),old,now,value));
        }
        return new MarketplaceEditing.PendingRebase(List.copyOf(pending),List.copyOf(conflicts));
    }
    public String replayReason(Document d,List<MarketplaceEditing.Change> fields){var input=input(d);if(!"NONE".equals(input.optionMode())&&fields.stream().anyMatch(c->!c.path().equals(NaverEditPatch.PREFIX+"options.stockQuantity")))return "현재 옵션 상품은 선택한 옵션 재고 수정만 지원합니다. 나머지는 스마트스토어센터에서 수정해 주세요.";return null;}
    public List<MarketplaceEditing.Change> comparisonFields(Document d){return NaverEditPatch.snapshot(input(d));}
    public List<MarketplaceEditing.Change> normalizeChanges(Document d,List<MarketplaceEditing.Change> changes){return NaverEditPatch.granular(input(d),changes);}
    public Document saveTarget(Document reference,Document desired){
        var config=reference.markets().get(Market.NAVER);var state=config==null||config.naver()==null?null:config.naver().editorState();
        return NaverDraftAdapter.withInput(reference,input(desired),state);
    }
    public List<MarketplaceEditing.Change> diff(NaverEditor.Input observed,NaverEditor.Input edited){return NaverEditPatch.diff(observed,edited);}
    public Document apply(Document document,List<MarketplaceEditing.Change> changes){return NaverEditPatch.apply(document,changes);}
}
