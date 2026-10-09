package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;

import java.util.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

final class CoupangRegistrationInput {
    private CoupangRegistrationInput() {}
    private static final Set<String> BASIC=Set.of("sellerProductName","displayProductName","generalProductName","brand","productGroup","displayCategoryCode");
    private static final Set<String> PRODUCT_IDENTITIES=Set.of("Manufacturer Part Number","Global Trade Item Number");
    static Document convert(CoupangProductRegistrations.Input input){
        if(input==null||input.options()==null||input.options().isEmpty()||input.options().size()>200)throw invalid("옵션은 1~200개로 입력해 주세요.");
        var basic=map(input.basic());if(!BASIC.containsAll(basic.keySet()))throw invalid("신규 상품 기본 입력을 확인해 주세요.");
        var settings=new LinkedHashMap<>(map(input.settings()));if(basic.get("productGroup")!=null&&!basic.get("productGroup").isBlank())settings.put("productGroup",basic.get("productGroup"));var delivery=map(input.delivery());var options=new ArrayList<Option>();var configs=new ArrayList<CoupangOption>();var images=new ArrayList<Image>();var contents=new ArrayList<Content>();var ids=new HashSet<String>();
        if(input.options().stream().anyMatch(Objects::isNull))throw invalid("옵션 입력을 확인해 주세요.");
        var first=input.options().getFirst();var shared=map(first.registration());
        var filters=list(first.attributes()).stream().filter(a->"NONE".equals(a.exposed())&&!PRODUCT_IDENTITIES.contains(a.name())).toList();
        String category=first.noticeCategory()==null?"":first.noticeCategory();var notices=list(first.notices()).stream().filter(n->Objects.equals(n.category(),category)).toList();
        for(var row:input.options()){
            String id=uuid(row.id());if(!ids.add(id))throw invalid("옵션 식별자가 중복되었습니다.");
            var registration=new LinkedHashMap<>(map(row.registration()));
            var sharedKeys=new HashSet<>(registration.keySet());sharedKeys.addAll(shared.keySet());sharedKeys.removeAll(Set.of("originalPrice","salePrice","maximumBuyCount","externalVendorSku"));
            for(String key:sharedKeys){
                if(shared.containsKey(key))registration.put(key,shared.get(key));else registration.remove(key);
            }
            var attrs=new ArrayList<>(list(row.attributes()).stream().filter(a->!"NONE".equals(a.exposed())||PRODUCT_IDENTITIES.contains(a.name())).toList());
            attrs.addAll(filters);String name=optionName(attrs);
            options.add(new Option(id,name,registration.getOrDefault("externalVendorSku",""),registration.getOrDefault("salePrice",""),registration.getOrDefault("maximumBuyCount",""),List.of()));
            configs.add(new CoupangOption(id,null,null,fields(registration),List.copyOf(attrs),notices,list(first.certifications())));
            for(var image:list(row.images()))images.add(new Image(image.id()==null?UUID.randomUUID().toString():uuid(image.id()),image.assetId(),image.url(),"REPRESENTATION".equals(image.type()),image.order()==null?images.size():image.order(),id,image.type()));
            for(var content:list(row.contents()))contents.add(new Content(content.id()==null?UUID.randomUUID().toString():uuid(content.id()),"IMAGE".equals(content.detailType())?"IMAGE":"HTML",content.content(),id));
        }
        var common=new Common(options.getFirst().sku(),basic.get("sellerProductName"),basic.get("generalProductName"),basic.get("brand"),settings.get("manufacture"),"","",shared.get("modelNo"),"",shared.get("taxType"),shared.get("adultOnly"));
        var d=new Delivery(delivery.get("deliveryMethod"),delivery.get("deliveryCompanyCode"),delivery.get("deliveryChargeType"),delivery.get("deliveryCharge"),delivery.get("freeShipOverAmount"),delivery.get("returnCharge"),delivery.get("deliveryChargeOnReturn"),delivery.get("remoteAreaDeliverable"),delivery.get("unionDeliveryType"),delivery.get("outboundShippingPlaceCode"),delivery.get("returnCenterCode"),delivery.get("returnChargeName"),delivery.get("companyContactNumber"),delivery.get("returnZipCode"),delivery.get("returnAddress"),delivery.get("returnAddressDetail"));
        var overrides=new Overrides(null,basic.get("displayProductName"),null,List.of(),null,null);
        var cp=new Coupang(null,fields(delivery),fields(settings),List.copyOf(configs),list(input.documents()),null);
        return MarketplaceDocuments.normalize(new Document(null,null,common,List.copyOf(options),StockMode.OPTION,"",List.of(),new Media(List.copyOf(images),List.copyOf(contents)),d,List.of(Market.COUPANG),Map.of(Market.COUPANG,new MarketConfig(basic.get("displayCategoryCode"),overrides,cp,null,null))));
    }
    static CoupangProductRegistrations.Input input(Document doc){
        var config=doc.markets().get(Market.COUPANG);var c=config.coupang();var common=doc.common();var basic=new LinkedHashMap<String,String>();
        basic.put("sellerProductName",common.name());basic.put("displayProductName",config.overrides().productName());basic.put("generalProductName",common.productName());basic.put("brand",common.brand());basic.put("productGroup",DraftCoupangProjection.fields(c.settings()).getOrDefault("productGroup",""));basic.put("displayCategoryCode",config.categoryCode());
        var options=doc.options().stream().map(o->{var cfg=c.options().stream().filter(v->v.optionId().equals(o.id())).findFirst().orElseThrow();
            var images=doc.media().images().stream().filter(i->o.id().equals(i.optionId())).map(i->new CoupangProductRegistrations.Image(i.id(),i.assetId(),i.url(),i.imageType(),i.order())).toList();
            var contents=doc.media().contents().stream().filter(i->o.id().equals(i.optionId())).map(i->new CoupangProductRegistrations.Content(i.id(),"HTML".equals(i.type())?"HTML":"IMAGE","HTML".equals(i.type())?"TEXT":"IMAGE",i.value())).toList();
            return new CoupangProductRegistrations.Option(o.id(),cfg.attributes(),images,contents,cfg.notices(),DraftCoupangProjection.fields(cfg.registration()),cfg.certifications(),cfg.notices().isEmpty()?"":cfg.notices().getFirst().category());
        }).toList();return new CoupangProductRegistrations.Input(basic,options,DraftCoupangProjection.fields(c.delivery()),DraftCoupangProjection.fields(c.settings()),c.documents());
    }
    static String optionName(List<CoupangCatalog.Attribute> attributes){
        var purchase=attributes.stream().filter(a->"EXPOSED".equals(a.exposed())&&!PRODUCT_IDENTITIES.contains(a.name())).toList();
        if(purchase.isEmpty())return "단일 상품";
        var ordered=new ArrayList<CoupangCatalog.Attribute>();purchase.stream().filter(a->"색상".equals(a.name())).forEach(ordered::add);purchase.stream().filter(a->!"색상".equals(a.name())).forEach(ordered::add);
        return String.join(" ",ordered.stream().map(a->a.value()==null?"":a.value().trim()).filter(v->!v.isEmpty()).toList());
    }
    private static Map<String,String> map(Map<String,String> m){return m==null?Map.of():m;}
    private static List<CoupangCatalog.Field> fields(Map<String,String> m){return m.entrySet().stream().map(e->new CoupangCatalog.Field(e.getKey(),e.getValue())).toList();}
    private static <T> List<T> list(List<T> value){if(value==null)return List.of();if(value.stream().anyMatch(Objects::isNull))throw invalid("입력 항목을 확인해 주세요.");return value;}
    private static String uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}catch(RuntimeException e){throw invalid("내부 옵션·이미지 식별자를 확인해 주세요.");}}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
