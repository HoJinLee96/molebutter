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
    public boolean supports(Document d){var m=d.markets().get(Market.NAVER);return d.selectedMarkets().equals(List.of(Market.NAVER))&&m!=null&&m.naver()!=null&&m.naver().editorInput()!=null;}
    public Document normalize(Document d){
        if(d==null||d.markets()==null)return d;
        var config=d.markets().get(Market.NAVER);if(config==null||config.naver()==null||config.naver().editorInput()==null)return d;
        var n=config.naver();var typed=new Naver(n.channelId(),n.status(),n.saleType(),n.originCode(),n.deliveryTemplateId(),n.afterServiceTelephone(),n.attributes(),n.notices(),n.channelProductName(),n.naverShoppingRegistration(),n.channelProductDisplayStatusType(),normalize(n.editorInput()));
        var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());markets.put(Market.NAVER,new MarketConfig(config.categoryCode(),config.overrides(),config.coupang(),typed,config.esm()));
        return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),Map.copyOf(markets));
    }
    public NaverEditor.Input normalize(NaverEditor.Input input){return NaverEditPatch.normalize(input);}
    public Document document(String id,Long revision,NaverEditor.Input input){return NaverDraftAdapter.document(id,revision,input);}
    public NaverEditor.Input input(Document document){return NaverDraftAdapter.from(document);}
    public Document newDocument(Document reference){return normalize(reference);}
    public Document project(Document reference,NaverEditor.EditorDocument source,Mapping mapping){return NaverDraftAdapter.observed(reference,source,mapping);}
    public Document observe(Long actor,Document reference,Mapping mapping){return project(reference,catalog.editor(actor,mapping.sellerProductId()),mapping);}
    public List<MarketplaceEditing.Change> diff(NaverEditor.Input observed,NaverEditor.Input edited){return NaverEditPatch.diff(observed,edited);}
    public Document apply(Document document,List<MarketplaceEditing.Change> changes){return NaverEditPatch.apply(document,changes);}
}
