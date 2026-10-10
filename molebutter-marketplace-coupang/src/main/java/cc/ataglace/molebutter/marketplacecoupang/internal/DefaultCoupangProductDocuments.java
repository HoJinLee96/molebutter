package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.util.*;
import java.math.BigInteger;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;
import cc.ataglace.molebutter.media.api.ImageAssets;
import static cc.ataglace.molebutter.marketplacecoupang.internal.DraftCoupangProjection.*;

/** Provider rules and document conversion; persistence and execution belong to core. */
@Component
final class DefaultCoupangProductDocuments implements CoupangProductDocuments {
    private final CoupangEditor coupang;
    private final CoupangBrands brands;
    private final CoupangProductClient client;
    private final ObjectMapper json;
    DefaultCoupangProductDocuments(CoupangEditor coupang,CoupangBrands brands,CoupangProductClient client,ObjectMapper json){
        this.coupang=coupang;this.brands=brands;this.client=client;this.json=json;
    }
    public String market(){return "COUPANG";}
    public String accountKey(){return client.accountKey();}
    public boolean supports(Document d){return d.selectedMarkets().contains(Market.COUPANG);}
    public Document observe(Long actor,Document reference,Mapping mapping){return project(reference,mapping,coupang.edit(actor,mapping.sellerProductId()));}
    public Document importObserved(CoupangEditor.EditorDocument source){return DraftCoupangImport.convert(source);}
    public Document project(Document reference,Mapping mapping,CoupangEditor.EditorDocument source){return CoupangEditingProjection.latest(reference,mapping,source);}
    public Document registration(CoupangProductRegistrations.Input input){return CoupangRegistrationInput.convert(input);}
    public CoupangProductRegistrations.Input registrationInput(Document document){return CoupangRegistrationInput.input(document);}
    public Document apply(Document document,List<MarketplaceEditing.Change> changes){return CoupangEditPatch.apply(document,changes,json);}
    public List<MarketplaceEditing.Change> expand(Document document,List<MarketplaceEditing.Change> changes){return CoupangEditPatch.expand(document,changes,json);}
    public Mapping legacyMapping(Document d,String account){
        var config=d.markets().get(Market.COUPANG);var c=config==null?null:config.coupang();
        if(c!=null&&c.sellerProductId()!=null&&c.source()==null)throw new MarketplaceEditingFailure(MarketplaceEditingFailure.Kind.CONFLICT);
        return c==null||c.sellerProductId()==null?null:new Mapping(account,c.sellerProductId(),nullableList(c.options()).stream().map(o->new OptionMapping(o.optionId(),o.sellerProductItemId(),o.vendorItemId())).toList());
    }
    public Document newDocument(Document d) {
        var m=d.markets().get(Market.COUPANG);
        if(m==null)m=new MarketConfig("",null,null,null,null);
        var over=m.overrides();var c=d.common();
        var common=new Common(c.productCode(),over==null?c.name():DraftCoupangProjection.inherited(over.name(),c.name()),c.productName(),over==null?c.brand():DraftCoupangProjection.inherited(over.brand(),c.brand()),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly());
        final var configured=m;
        var options=d.options().stream().map(o->{var v=DraftCoupangProjection.override(configured,o);return v==null?o:new Option(o.id(),DraftCoupangProjection.inherited(v.name(),o.name()),DraftCoupangProjection.inherited(v.sku(),o.sku()),DraftCoupangProjection.inherited(v.price(),o.price()),DraftCoupangProjection.inherited(v.quantity(),o.quantity()),o.attributes());}).toList();
        var media=over==null?d.media():new Media(d.media().images().stream().filter(i->over.imageIds()==null||over.imageIds().contains(i.id())).toList(),over.description()==null?d.media().contents():List.of(new Content(UUID.randomUUID().toString(),"HTML",over.description(),null)));
        var cp=m.coupang();var existing=cp==null?List.<CoupangOption>of():DraftCoupangProjection.nullableList(cp.options());
        var linked=options.stream().map(o->existing.stream().filter(v->v.optionId().equals(o.id())).findFirst().orElseGet(()->new CoupangOption(o.id(),null,null,
            Map.of("maximumBuyForPerson","0","maximumBuyForPersonPeriod","1","unitCount","1","outboundShippingTimeDay","1","parallelImported","NOT_PARALLEL_IMPORTED","overseasPurchased","NOT_OVERSEAS_PURCHASED","pccNeeded","false").entrySet().stream().map(e->new CoupangCatalog.Field(e.getKey(),e.getValue())).toList(),
            o.attributes().stream().map(a->new CoupangCatalog.Attribute(a.name(),a.value(),"EXPOSED")).toList(),List.of(),List.of()))).toList();
        var initialized=new Coupang(null,cp==null?List.of():cp.delivery(),cp==null?List.of():cp.settings(),linked,cp==null?List.of():cp.documents(),null);
        // Resolved values are now editable directly; only the distinct display product name remains an override.
        var ready=new MarketConfig(m.categoryCode(),new Overrides(null,over==null?null:over.productName(),null,List.of(),null,null),initialized,m.naver(),m.esm());
        var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());markets.put(Market.COUPANG,ready);
        return new Document(d.id(),d.revision(),common,options,d.stockMode(),d.productQuantity(),d.services(),media,d.delivery(),d.selectedMarkets(),Map.copyOf(markets));
    }
    public void requireBrand(Long actor,Document before,Document after){
        if(!after.selectedMarkets().contains(Market.COUPANG))return;
        var m=after.markets().get(Market.COUPANG);if(m==null)throw new InputValidationFailure("쿠팡 마켓 설정을 확인해 주세요.");var old=before==null?null:before.markets().get(Market.COUPANG);
        String name=m.overrides()==null?after.common().brand():DraftCoupangProjection.inherited(m.overrides().brand(),after.common().brand());
        String brandId=DraftCoupangProjection.settings(after,m).get("brandId");
        if(old!=null){String oldName=old.overrides()==null?before.common().brand():DraftCoupangProjection.inherited(old.overrides().brand(),before.common().brand());String oldId=DraftCoupangProjection.settings(before,old).get("brandId");if(old.coupang()!=null&&old.coupang().sellerProductId()!=null){if(!Objects.equals(m.overrides()==null?null:m.overrides().brand(),old.overrides()==null?null:old.overrides().brand())||!Objects.equals(brandId,oldId))throw new InputValidationFailure("기존 쿠팡 브랜드는 변경할 수 없습니다.");return;}if(brandId!=null&&!brandId.isBlank()&&Objects.equals(name,oldName)&&Objects.equals(brandId,oldId))return;}
        brands.requireSelection(actor,brandId,name);
    }
    public void protectNew(Document d){
        var config=d.markets().get(Market.COUPANG);if(config==null||config.coupang()==null)return;
        var c=config.coupang();if(c.source()!=null||c.sellerProductId()!=null||DraftCoupangProjection.nullableList(c.options()).stream().anyMatch(o->o.sellerProductItemId()!=null||o.vendorItemId()!=null))throw new InputValidationFailure("기존 쿠팡 상품은 가져오기로 연결해 주세요.");
    }
    public void protectImport(Document before,Document after){
        var previous=before.markets().get(Market.COUPANG);var incoming=after.markets().get(Market.COUPANG);
        if(previous==null||previous.coupang()==null||previous.coupang().source()==null){protectNew(after);return;}
        var c=previous.coupang();
        if(incoming==null||incoming.coupang()==null||!Objects.equals(c.source(),incoming.coupang().source())||!Objects.equals(c.sellerProductId(),incoming.coupang().sellerProductId()))throw new InputValidationFailure("가져온 상품의 연결 정보를 변경할 수 없습니다.");
        var limits=c.source().limits();
        if(limits.categoryReadOnly()&&!Objects.equals(previous.categoryCode(),incoming.categoryCode()))throw new InputValidationFailure("기존 쿠팡 카테고리는 변경할 수 없습니다.");
        var beforeIds=before.options().stream().map(Option::id).toList();var afterIds=after.options().stream().map(Option::id).toList();
        if(limits.optionStructureReadOnly()&&!beforeIds.equals(afterIds))throw new InputValidationFailure("기존 쿠팡 옵션 구성은 변경할 수 없습니다.");
        var beforeMappings=DraftCoupangProjection.nullableList(c.options());var nextMappings=DraftCoupangProjection.nullableList(incoming.coupang().options());
        if(beforeMappings.size()!=nextMappings.size())throw new InputValidationFailure("기존 쿠팡 옵션 연결을 변경할 수 없습니다.");
        for(var mapping:beforeMappings){
            var next=nextMappings.stream().filter(o->Objects.equals(o.optionId(),mapping.optionId())).findFirst().orElseThrow(()->new InputValidationFailure("기존 쿠팡 옵션 연결을 변경할 수 없습니다."));
            if(!Objects.equals(mapping.sellerProductItemId(),next.sellerProductItemId())||!Objects.equals(mapping.vendorItemId(),next.vendorItemId()))throw new InputValidationFailure("기존 쿠팡 옵션 ID는 변경할 수 없습니다.");
            if(limits.purchaseAttributesReadOnly()){
                var original=DraftCoupangProjection.nullableList(mapping.attributes()).stream().filter(a->"EXPOSED".equals(a.exposed())).toList();var proposed=DraftCoupangProjection.nullableList(next.attributes()).stream().filter(a->"EXPOSED".equals(a.exposed())).toList();
                if(!original.equals(proposed))throw new InputValidationFailure("기존 쿠팡 구매 속성은 변경할 수 없습니다.");
                var oldOption=before.options().stream().filter(o->o.id().equals(mapping.optionId())).findFirst().orElseThrow();var nextOption=after.options().stream().filter(o->o.id().equals(mapping.optionId())).findFirst().orElseThrow();
                if(!oldOption.attributes().equals(nextOption.attributes()))throw new InputValidationFailure("기존 쿠팡 구매 속성은 변경할 수 없습니다.");
            }
        }
    }
    public void validate(Long actor,Document d,MarketConfig m,String requestId,Map<String,ImageAssets.Asset> metadata,List<Issue> errors,List<Issue> unverified){
        var market=Market.COUPANG;String p="markets.COUPANG";
        if(m.coupang()==null||m.coupang().sellerProductId()==null)errors.addAll(CoupangCreateValidation.issues(d,m));
        if(!m.categoryCode().matches("[0-9]{1,15}")){issue(errors,market,p+".categoryCode","유효한 카테고리 코드를 입력해 주세요.");return;}
        CoupangEditor.CategoryRules rules;
        try{rules=coupang.category(actor,m.categoryCode(),requestId);}catch(MarketplaceFailure e){issue(errors,market,p+".categoryCode",e.getMessage());return;}
        var delivery=delivery(d,m);
        for(String k:List.of("deliveryMethod","deliveryCompanyCode","deliveryChargeType","deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","remoteAreaDeliverable","unionDeliveryType","outboundShippingPlaceCode","returnCenterCode","returnChargeName","companyContactNumber","returnZipCode","returnAddress","returnAddressDetail","returnCharge"))require(errors,market,p+".coupang.delivery."+k,delivery.get(k),k);
        for(String k:List.of("deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","returnCharge"))number(errors,market,p+".coupang.delivery."+k,delivery.get(k),k,9_007_199_254_740_991L);
        choice(errors,market,p+".coupang.delivery.deliveryMethod",delivery.get("deliveryMethod"),Set.of("SEQUENCIAL","COLD_FRESH","MAKE_ORDER","AGENT_BUY","VENDOR_DIRECT"));choice(errors,market,p+".coupang.delivery.deliveryChargeType",delivery.get("deliveryChargeType"),Set.of("FREE","NOT_FREE","CHARGE_RECEIVED","CONDITIONAL_FREE"));choice(errors,market,p+".coupang.delivery.remoteAreaDeliverable",delivery.get("remoteAreaDeliverable"),Set.of("Y","N"));choice(errors,market,p+".coupang.delivery.unionDeliveryType",delivery.get("unionDeliveryType"),Set.of("UNION_DELIVERY","NOT_UNION_DELIVERY"));
        if("FREE".equals(delivery.get("deliveryChargeType"))&&!"0".equals(delivery.get("freeShipOverAmount")))issue(errors,market,p+".coupang.delivery.freeShipOverAmount","무료배송 기준은 0으로 입력해 주세요.");
        if("CONDITIONAL_FREE".equals(delivery.get("deliveryChargeType"))){var v=delivery.get("freeShipOverAmount");if(!integer(v,9_007_199_254_740_991L)||Long.parseLong(v)<100||Long.parseLong(v)%100!=0)issue(errors,market,p+".coupang.delivery.freeShipOverAmount","조건부 무료배송 기준은 100원 단위로 입력해 주세요.");}
        var settings=settings(d,m);if(m.coupang()==null||m.coupang().sellerProductId()==null){require(errors,market,p+".coupang.settings.brandId",settings.get("brandId"),"검색해서 선택한 브랜드");require(errors,market,"common.brand",d.common().brand(),"브랜드");}var start=date(errors,market,p+".coupang.settings.saleStartedAt",settings.get("saleStartedAt"));var end=date(errors,market,p+".coupang.settings.saleEndedAt",settings.get("saleEndedAt"));if(start!=null&&end!=null&&end.isBefore(start))issue(errors,market,p+".coupang.settings.saleEndedAt","판매 종료일시는 시작일시 이후로 입력해 주세요.");
        var pendingImages=new HashSet<String>();
        for(int n=0;n<d.options().size();n++){
            var o=d.options().get(n);var config=optionConfig(m,o);String path=p+".coupang.options."+n;var override=override(m,o);var itemName=override==null?o.name():inherited(override.name(),o.name());
            if(itemName.isBlank()||itemName.codePointCount(0,itemName.length())>150)issue(errors,market,"options."+n+".name","쿠팡 옵션명은 1~150자로 입력해 주세요.");
            var registration=DraftCoupangProjection.registration(d,m,o);
            boolean approved=config!=null&&config.vendorItemId()!=null;
            for(String k:List.of("originalPrice","salePrice","maximumBuyCount","maximumBuyForPerson","maximumBuyForPersonPeriod","outboundShippingTimeDay","unitCount")){
                if(approved&&Set.of("originalPrice","salePrice","maximumBuyCount").contains(k))continue;
                number(errors,market,path+".registration."+k,registration.get(k),k,k.equals("maximumBuyCount")?99999:9_007_199_254_740_991L);
            }
            if("0".equals(registration.get("unitCount")))issue(errors,market,path+".registration.unitCount","판매 단위는 1 이상으로 입력해 주세요.");
            for(var k:List.of("adultOnly","taxType","parallelImported","overseasPurchased","pccNeeded"))require(errors,market,path+".registration."+k,registration.get(k),k);
            choice(errors,market,path+".registration.adultOnly",registration.get("adultOnly"),Set.of("EVERYONE","ADULT_ONLY"));choice(errors,market,path+".registration.taxType",registration.get("taxType"),Set.of("TAX","FREE"));choice(errors,market,path+".registration.parallelImported",registration.get("parallelImported"),Set.of("PARALLEL_IMPORTED","NOT_PARALLEL_IMPORTED"));choice(errors,market,path+".registration.overseasPurchased",registration.get("overseasPurchased"),Set.of("OVERSEAS_PURCHASED","NOT_OVERSEAS_PURCHASED"));choice(errors,market,path+".registration.pccNeeded",registration.get("pccNeeded"),Set.of("true","false"));
            var tags=Arrays.stream(registration.getOrDefault("searchTags","").split(",")).map(String::trim).filter(v->!v.isEmpty()).toList();if(tags.size()>20||tags.stream().anyMatch(v->v.codePointCount(0,v.length())>20))issue(errors,market,path+".registration.searchTags","검색어는 20개 이하, 각 20자 이하로 입력해 주세요.");
            var attrs=attributes(m,o);var groups=new HashMap<String,List<Boolean>>();
            for(var rule:rules.attributes()){
                String value=attrs.get(rule.name());boolean present=!MarketplaceDocuments.blank(value);
                if("MANDATORY".equals(rule.required())){
                    if(rule.groupNumber()!=null&&!"NONE".equals(rule.groupNumber()))groups.computeIfAbsent(rule.groupNumber(),k->new ArrayList<>()).add(present);
                    else if(!present)issue(errors,market,path+".attributes",rule.name()+"을 입력해 주세요.");
                }
                if(present){if(value.codePointCount(0,value.length())>30)issue(errors,market,path+".attributes",rule.name()+"은 30자 이하로 입력해 주세요.");if("NUMBER".equals(rule.dataType())&&!value.matches("-?\\d+(?:\\.\\d+)?(?:\\s*[^\\d\\s]+)?"))issue(errors,market,path+".attributes",rule.name()+"의 숫자 형식을 확인해 주세요.");if("DATE".equals(rule.dataType()))try{java.time.LocalDate.parse(value);}catch(RuntimeException e){issue(errors,market,path+".attributes",rule.name()+"의 날짜 형식을 확인해 주세요.");}}
            }
            for(var group:groups.values())if(group.stream().filter(Boolean::booleanValue).count()!=1)issue(errors,market,path+".attributes","그룹 필수 속성 중 한 개만 입력해 주세요.");
            var notices=notices(d,m,o);String noticeCategory=config==null||nullableList(config.notices()).isEmpty()?null:config.notices().getFirst().category();var category=rules.notices().stream().filter(c->Objects.equals(c.name(),noticeCategory)).findFirst().orElse(null);
            if(!rules.notices().isEmpty()&&category==null)issue(errors,market,path+".notices","상품고시 유형을 선택해 주세요.");
            if(category!=null)for(var field:category.fields())if("MANDATORY".equals(field.required()))require(errors,market,path+".notices",notices.containsKey(category.name()+"\u0001"+field.name())?notices.get(category.name()+"\u0001"+field.name()):notices.get(field.name()),field.name());
            for(var r:rules.certifications())if("MANDATORY".equals(r.required())){
                var cert=config==null?null:nullableList(config.certifications()).stream().filter(c->Objects.equals(c.type(),r.type())).findFirst().orElse(null);
                if(cert==null||"CODE".equals(r.dataType())&&MarketplaceDocuments.blank(cert.code()))issue(errors,market,path+".certifications",r.name()+"을 입력해 주세요.");
                else if("DOCUMENT".equals(r.dataType())&&nullableList(cert.attachments()).isEmpty())issue(errors,market,path+".certifications",r.name()+" 첨부가 필요합니다.");
            }
            if(images(d,m,o).stream().filter(i->i.imageType().equals("USED_PRODUCT")).count()>4)issue(errors,market,"media.images","중고상태 이미지는 4개 이하로 지정해 주세요.");
            for(var image:images(d,m,o))if(image.assetId()!=null&&metadata.containsKey(image.assetId())){var a=metadata.get(image.assetId());if(a.bytes()>3*1024*1024||a.width()!=a.height()||a.width()<500||a.width()>5000)issue(errors,market,"media.images","쿠팡 이미지는 3MiB 이하, 500~5000px 정사각형이어야 합니다.");}
            for(var image:images(d,m,o))if(image.assetId()==null&&pendingImages.add(image.id()))issue(unverified,market,"media.images","URL 이미지의 크기·용량 규격 확인이 필요합니다.");
            var description=m.overrides()==null?null:m.overrides().description();if(description!=null?description.isBlank():d.media().contents().stream().noneMatch(c->(c.optionId()==null||c.optionId().equals(o.id()))&&!c.value().isBlank()))issue(errors,market,"media.contents","옵션 "+(n+1)+"의 설명을 입력해 주세요.");
        }
        for(var rule:rules.documents()){
            boolean required="MANDATORY".equals(rule.required())||"MANDATORY_PARALLEL_IMPORTED".equals(rule.required())&&d.options().stream().anyMatch(o->"PARALLEL_IMPORTED".equals(DraftCoupangProjection.registration(d,m,o).get("parallelImported")))||"MANDATORY_OVERSEAS_PURCHASED".equals(rule.required())&&d.options().stream().anyMatch(o->"OVERSEAS_PURCHASED".equals(DraftCoupangProjection.registration(d,m,o).get("overseasPurchased")));
            if(required&&(m.coupang()==null||nullableList(m.coupang().documents()).stream().noneMatch(v->Objects.equals(v.templateName(),rule.name())&&(!MarketplaceDocuments.blank(v.path())||!MarketplaceDocuments.blank(v.vendorPath())))))issue(errors,market,p+".coupang.documents",rule.name()+"을 입력해 주세요.");
        }
    }
    private static LocalDateTime date(List<Issue> e,Market m,String path,String v){require(e,m,path,v,"판매 일시");if(MarketplaceDocuments.blank(v))return null;try{var d=LocalDateTime.parse(v);if(d.getYear()>2099)throw new IllegalArgumentException();return d;}catch(RuntimeException x){issue(e,m,path,"2099년 이하의 유효한 판매 일시를 입력해 주세요.");return null;}}
    private static void choice(List<Issue> e,Market m,String path,String value,Set<String> allowed){if(!MarketplaceDocuments.blank(value)&&!allowed.contains(value))issue(e,m,path,"선택값을 확인해 주세요.");}
    private static boolean integer(String v,long max){if(v==null||!v.matches("[0-9]{1,16}"))return false;return new BigInteger(v).compareTo(BigInteger.valueOf(max))<=0;}
    private static void number(List<Issue> e,Market m,String path,String v,String name,long max){if(!integer(v,max))issue(e,m,path,name+"은 0~"+max+"의 정수로 입력해 주세요.");}
    private static void require(List<Issue> e,Market m,String path,String v,String name){if(MarketplaceDocuments.blank(v))issue(e,m,path,name+"을 입력해 주세요.");}
    private static void issue(List<Issue> e,Market m,String path,String message){e.add(new Issue(m==null?null:m.name(),path,message));}

}
