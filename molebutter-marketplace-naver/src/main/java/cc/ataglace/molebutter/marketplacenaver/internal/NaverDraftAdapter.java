package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;

import java.util.*;
import cc.ataglace.molebutter.marketplace.api.NaverEditor;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Bridges the dedicated SmartStore form to durable internal drafts without leaking remote source. */
final class NaverDraftAdapter {
    private NaverDraftAdapter() {}
    static Document document(String id,Long revision,NaverEditor.Input value){
        var input=NaverEditPatch.normalize(value);var f=input.fields();String prefix="originProduct.detailAttribute.";
        var common=new Common(text(f,prefix+"sellerCodeInfo.sellerManagementCode"),text(f,"originProduct.name"),"",text(f,prefix+"naverShoppingSearchInfo.brandName"),text(f,prefix+"naverShoppingSearchInfo.manufacturerName"),text(f,prefix+"originAreaInfo.originAreaCode"),"",text(f,prefix+"naverShoppingSearchInfo.modelName"),text(f,prefix+"afterServiceInfo.afterServiceGuideContent"),text(f,prefix+"taxType"),Boolean.FALSE.equals(f.get(prefix+"minorPurchasable"))?"ADULT_ONLY":"EVERYONE");
        var options=new ArrayList<Option>();for(var o:input.options()){var attributes=new ArrayList<Attribute>();for(int i=0;i<o.values().size();i++)attributes.add(new Attribute(i<input.optionNames().size()?input.optionNames().get(i):"옵션 "+(i+1),o.values().get(i)));options.add(new Option(o.id(),String.join(" / ",o.values()),o.sellerManagerCode(),number(o.price()),number(o.stockQuantity()),List.copyOf(attributes)));}
        var images=input.images().stream().map(i->new Image(i.id(),i.assetId(),i.url(),i.representative(),i.order(),null,i.representative()?"REPRESENTATION":"DETAIL")).toList();
        var contents=input.description().isEmpty()?List.<Content>of():List.of(new Content(NaverEditPatch.uuid("naver:description:"+Objects.toString(id,"new")),"HTML",input.description(),null));
        var naver=new Naver(null,text(f,"originProduct.statusType"),text(f,"originProduct.saleType"),text(f,prefix+"originAreaInfo.originAreaCode"),null,text(f,prefix+"afterServiceInfo.afterServiceTelephoneNumber"),List.of(),List.of(),text(f,"smartstoreChannelProduct.channelProductName"),f.get("smartstoreChannelProduct.naverShoppingRegistration") instanceof Boolean b?b:null,text(f,"smartstoreChannelProduct.channelProductDisplayStatusType"),input);
        var config=new MarketConfig(text(f,"originProduct.leafCategoryId"),null,null,naver,null);
        return MarketplaceDocuments.normalize(new Document(id,revision,common,List.copyOf(options),"COMBINATION".equals(input.optionMode())?StockMode.OPTION:StockMode.PRODUCT,text(f,"originProduct.stockQuantity"),List.of(),new Media(images,contents),null,List.of(Market.NAVER),Map.of(Market.NAVER,config)));
    }
    static NaverEditor.Input from(Document document){
        var config=document.markets()==null?null:document.markets().get(Market.NAVER);
        if(config!=null&&config.naver()!=null&&config.naver().editorInput()!=null)return NaverEditPatch.normalize(config.naver().editorInput());
        throw NaverEditPatch.invalid("스마트스토어 전용 초안 입력이 없습니다. 상품을 다시 조회해 주세요.");
    }
    static Document observed(Document reference,NaverEditor.EditorDocument source,MarketplaceWriteGateway.Mapping mapping){
        var input=source.input();var options=new ArrayList<NaverEditor.Option>();
        var remoteByLocal=new HashMap<String,String>();for(var identity:source.optionIdentities())remoteByLocal.put(identity.id(),identity.remoteId());
        for(var option:input.options()){
            String remote=remoteByLocal.get(option.id()),local=option.id();
            if(mapping!=null&&mapping.options()!=null)for(var link:mapping.options())if(Objects.equals(remote,link.sellerProductItemId())){local=link.optionId();break;}
            options.add(new NaverEditor.Option(local,option.values(),option.price(),option.stockQuantity(),option.sellerManagerCode(),option.usable()));
        }
        // Keep the existing image UUIDs when the same source URL survives a refresh.
        var images=new ArrayList<NaverEditor.Image>();NaverEditor.Input before=null;
        try{before=from(reference);}catch(RuntimeException ignored){}
        var consumed=new HashSet<String>();for(var image:input.images()){
            String local=image.id();if(before!=null)for(var old:before.images())if(Objects.equals(old.url(),image.url())&&!consumed.contains(old.id())){local=old.id();consumed.add(local);break;}
            images.add(new NaverEditor.Image(local,image.assetId(),image.url(),image.representative(),image.order()));
        }
        return document(reference.id(),reference.revision(),new NaverEditor.Input(input.fields(),input.optionMode(),input.optionNames(),List.copyOf(options),List.copyOf(images),input.description()));
    }
    private static String text(Map<String,Object> fields,String key){return Objects.toString(fields.get(key),"");}
    private static String number(Long value){return value==null?"":Long.toString(value);}
}
