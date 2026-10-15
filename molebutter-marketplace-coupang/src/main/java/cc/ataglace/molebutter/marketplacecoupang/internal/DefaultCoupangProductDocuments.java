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
import cc.ataglace.molebutter.media.api.R2Images;
import cc.ataglace.molebutter.common.api.OperationFailure;
import static cc.ataglace.molebutter.marketplacecoupang.internal.DraftCoupangProjection.*;

/** Provider rules and document conversion; persistence and execution belong to core. */
@Component
final class DefaultCoupangProductDocuments implements CoupangProductDocuments {
    private final CoupangEditor coupang;
    private final CoupangBrands brands;
    private final CoupangProductClient client;
    private final ObjectMapper json;
    private final R2Images r2;
    private final ImageAssets assets;
    DefaultCoupangProductDocuments(CoupangEditor coupang,CoupangBrands brands,CoupangProductClient client,ObjectMapper json,R2Images r2){
        this(coupang,brands,client,json,r2,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    DefaultCoupangProductDocuments(CoupangEditor coupang,CoupangBrands brands,CoupangProductClient client,ObjectMapper json,R2Images r2,ImageAssets assets){
        this.coupang=coupang;this.brands=brands;this.client=client;this.json=json;this.r2=r2;this.assets=assets;
    }
    public void validateSettings(Long actor,Document d,boolean create,List<MarketplaceEditing.Change> changes){if(!create&&changes.stream().anyMatch(c->c.path().startsWith("delivery.")||c.path().startsWith("markets.COUPANG.coupang.delivery")))client.writeSession(()->{CoupangDeliveryPolicy.verify(client,d,d.markets().get(Market.COUPANG));return null;});}
    public String market(){return "COUPANG";}
    public String accountKey(){return client.accountKey();}
    public boolean supports(Document d){return d.selectedMarkets().contains(Market.COUPANG);}
    public Document observe(Long actor,Document reference,Mapping mapping){return project(reference,mapping,coupang.edit(actor,mapping.sellerProductId()));}
    public Document importObserved(CoupangEditor.EditorDocument source){checkListing(source);return DraftCoupangImport.convert(source);}
    public Document project(Document reference,Mapping mapping,CoupangEditor.EditorDocument source){checkListing(source);return CoupangEditingProjection.latest(reference,mapping,source);}
    private static void checkListing(CoupangEditor.EditorDocument source){if(source!=null&&source.basic()!=null&&Set.of("상품삭제","DELETED").contains(Objects.toString(source.basic().statusName(),"")))throw new MarketplaceListingMissing("COUPANG_DELETED");}
    public Document registration(CoupangProductRegistrations.Input input){return CoupangRegistrationInput.convert(input);}
    public CoupangProductRegistrations.Input registrationInput(Document document){return CoupangRegistrationInput.input(document);}
    public Document apply(Document document,List<MarketplaceEditing.Change> changes){return CoupangEditPatch.apply(document,changes,json);}
    public List<MarketplaceEditing.Change> expand(Document document,List<MarketplaceEditing.Change> changes){return CoupangEditPatch.expand(document,changes,json);}
    public boolean supportsPendingEdits(){return true;}
    public String replayReason(Document d,List<MarketplaceEditing.Change> fields){return fields.stream().anyMatch(c->c.path().equals("markets.COUPANG.coupang.options.registration.offerCondition"))?"쿠팡 상품 상태는 등록 후 변경할 수 없습니다.":fields.stream().anyMatch(c->Set.of("common.brand","common.productName","common.origin","common.material","common.afterService").contains(c.path()))?"쿠팡의 기존 상품에서 이 항목의 수정은 지원하지 않습니다.":null;}
    public List<MarketplaceEditing.Change> observedComparisonFields(Document d){return comparisonFields(d).stream().filter(c->!Set.of("common.origin","common.material","common.afterService").contains(c.path())).filter(c->!Set.of("options.price","options.quantity").contains(c.path())||c.value()!=null&&!c.value().toString().isBlank()).toList();}
    public List<MarketplaceEditing.Change> comparisonFields(Document d){return CoupangComparison.fields(d,json);}
    public List<MarketplaceEditing.ConflictKey> comparisonKnown(Document d,List<MarketplaceEditing.ConflictKey> known,Document transmitted){return CoupangComparison.known(d,known,transmitted,json);}
    public boolean comparisonSupported(MarketplaceEditing.Change c){return CoupangComparison.supported(c);}
    public Object comparisonValue(Document d,MarketplaceEditing.Change c){return CoupangComparison.value(c,json);}
    public Object comparisonDisplay(Document d,MarketplaceEditing.Change c){return CoupangComparison.display(c,json);}
    public String comparisonLabel(MarketplaceEditing.Change c){return CoupangComparison.label(c);}
    public String comparisonSource(Document d,MarketplaceEditing.Change c){return CoupangComparison.source(d,c);}
    public String comparisonIssue(Document d,MarketplaceEditing.Change c){try{CoupangComparison.value(c,json);return null;}catch(IllegalArgumentException invalid){return invalid.getMessage();}}
    public List<MarketplaceEditing.Change> normalizeChanges(Document d,List<MarketplaceEditing.Change> changes){return expand(d,changes);}
    public MarketplaceEditing.PendingRebase rebase(Document observed,Document latest,List<MarketplaceEditing.Change> changes){
        var pending=new ArrayList<MarketplaceEditing.Change>();var conflicts=new ArrayList<MarketplaceEditing.Conflict>();
        apply(observed,changes);
        for(var change:changes){var old=CoupangEditPatch.value(observed,change,json);var now=CoupangEditPatch.value(latest,change,json);var desired=json.valueToTree(change.value());
            if(CoupangEditPatch.equivalent(now,desired,change))continue;pending.add(change);
            if(!CoupangEditPatch.equivalent(old,now,change))conflicts.add(new MarketplaceEditing.Conflict(change.path(),change.optionId(),old,now,desired));
        }
        return new MarketplaceEditing.PendingRebase(List.copyOf(pending),List.copyOf(conflicts));
    }
    public Mapping legacyMapping(Document d,String account){
        var config=d.markets().get(Market.COUPANG);var c=config==null?null:config.coupang();
        if(c!=null&&c.sellerProductId()!=null&&c.source()==null)throw new MarketplaceEditingFailure(MarketplaceEditingFailure.Kind.CONFLICT);
        return c==null||c.sellerProductId()==null?null:new Mapping(account,c.sellerProductId(),nullableList(c.options()).stream().map(o->new OptionMapping(o.optionId(),o.sellerProductItemId(),o.vendorItemId())).toList());
    }
    public Document newDocument(Document d) {
        var m=d.markets().get(Market.COUPANG);
        if(m==null)m=new MarketConfig("",null,null,null,null);
        var over=m.overrides();var c=d.common();
        var common=new Common(c.productCode(),over==null?c.name():DraftCoupangProjection.inherited(over.name(),c.name()),c.productName(),over==null?c.brand():DraftCoupangProjection.inherited(over.brand(),c.brand()),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly(),c.afterServiceManager(),c.afterServiceTelephone(),c.saleStartedAt(),c.saleEndedAt());
        final var configured=m;
        var options=d.options().stream().map(o->{var v=DraftCoupangProjection.override(configured,o);var sku=Objects.toString(o.sku(),"");return new Option(o.id(),v==null?o.name():inherited(v.name(),o.name()),v==null?sku:inherited(v.sku(),sku),v==null?o.price():inherited(v.price(),o.price()),v==null?o.quantity():inherited(v.quantity(),o.quantity()),o.attributes(),o.originalPrice());}).toList();
        var media=over==null?d.media():new Media(d.media().images().stream().filter(i->over.imageIds()==null||over.imageIds().contains(i.id())).toList(),over.description()==null?d.media().contents():List.of(new Content(UUID.randomUUID().toString(),"HTML",over.description(),null)),d.media().imageOverrides(),over.description()==null?d.media().contentOverrides():Map.of());
        var cp=m.coupang();var existing=cp==null?List.<CoupangOption>of():DraftCoupangProjection.nullableList(cp.options());
        var linked=options.stream().map(o->{
            var row=existing.stream().filter(v->v.optionId().equals(o.id())).findFirst().orElse(null);
            var attributes=new ArrayList<CoupangCatalog.Attribute>(row==null?List.of():DraftCoupangProjection.nullableList(row.attributes()));
            for(var a:o.attributes()){
                int index=-1;for(int i=0;i<attributes.size();i++)if(attributes.get(i).name().equals(a.name())){index=i;break;}
                if(index<0)attributes.add(new CoupangCatalog.Attribute(a.name(),a.value(),"EXPOSED"));
                else if("EXPOSED".equals(attributes.get(index).exposed()))attributes.set(index,new CoupangCatalog.Attribute(a.name(),a.value(),"EXPOSED"));
            }
            if(row!=null){
                var registration=new ArrayList<>(nullableList(row.registration()).stream().filter(f->!"externalVendorSku".equals(f.name())).toList());
                registration.add(new CoupangCatalog.Field("externalVendorSku",o.sku()));
                return new CoupangOption(row.optionId(),row.sellerProductItemId(),row.vendorItemId(),List.copyOf(registration),List.copyOf(attributes),row.notices(),row.certifications());
            }
            return new CoupangOption(o.id(),null,null,
                Map.of("externalVendorSku",o.sku(),"maximumBuyForPerson","0","maximumBuyForPersonPeriod","1","unitCount","1","outboundShippingTimeDay",MarketplaceDocuments.blank(d.delivery().shippingDays())?"1":d.delivery().shippingDays(),"parallelImported","NOT_PARALLEL_IMPORTED","overseasPurchased","NOT_OVERSEAS_PURCHASED","pccNeeded","false").entrySet().stream().map(e->new CoupangCatalog.Field(e.getKey(),e.getValue())).toList(),
                List.copyOf(attributes),List.of(),List.of());
        }).toList();
        var selectedAddressFields=new HashSet<String>();
        if(m.addressBook()!=null){
            if(m.addressBook().outbound()!=null)selectedAddressFields.add("outboundShippingPlaceCode");
            if(m.addressBook().returns()!=null)selectedAddressFields.addAll(Set.of("returnCenterCode","returnChargeName","companyContactNumber","returnZipCode","returnAddress","returnAddressDetail"));
        }
        var settings=new ArrayList<>(cp==null?List.<CoupangCatalog.Field>of():nullableList(cp.settings()));for(String key:List.of("saleStartedAt","saleEndedAt")){String value=key.equals("saleStartedAt")?d.common().saleStartedAt():d.common().saleEndedAt();if(!MarketplaceDocuments.blank(value)&&settings.stream().noneMatch(v->v.name().equals(key)))settings.add(new CoupangCatalog.Field(key,value));}
        var initialized=new Coupang(null,cp==null?List.of():nullableList(cp.delivery()).stream().filter(v->!selectedAddressFields.contains(v.name())).toList(),settings,linked,cp==null?List.of():cp.documents(),null);
        // Resolved values are now editable directly; only the distinct display product name remains an override.
        var ready=new MarketConfig(m.categoryCode(),new Overrides(null,over==null?c.name():inherited(over.productName(),c.name()),null,List.of(),null,null),initialized,m.naver(),m.esm(),m.lotteon(),null);
        var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());markets.put(Market.COUPANG,ready);
        return new Document(d.id(),d.revision(),common,options,d.stockMode(),d.productQuantity(),d.services(),media,addressDelivery(d.delivery(),m.addressBook()),d.selectedMarkets(),Map.copyOf(markets));
    }
    private static Delivery addressDelivery(Delivery d,AddressBook book){
        if(book==null)return d;
        var out=book.outbound();var ret=book.returns();
        return new Delivery(d.method(),d.carrier(),d.chargeType(),d.charge(),d.freeOver(),d.returnCharge(),d.initialReturnCharge(),d.remoteArea(),d.bundle(),out==null?d.outboundCode():out.code(),ret==null?d.returnCode():ret.code(),ret==null?d.returnName():ret.name(),ret==null?d.returnContact():ret.phone(),ret==null?d.returnZip():ret.zipCode(),ret==null?d.returnAddress():ret.address(),ret==null?d.returnAddressDetail():ret.detail(),d.shippingDays(),d.jejuCharge(),d.remoteCharge());
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
        var beforeMappings=DraftCoupangProjection.nullableList(c.options());var nextMappings=DraftCoupangProjection.nullableList(incoming.coupang().options());
        var afterIds=after.options().stream().map(Option::id).collect(java.util.stream.Collectors.toSet());
        if(limits.optionStructureReadOnly()&&beforeMappings.stream().anyMatch(o->!afterIds.contains(o.optionId())))throw new InputValidationFailure("기존 쿠팡 옵션 구성은 변경할 수 없습니다.");
        if(beforeMappings.size()!=nextMappings.size())throw new InputValidationFailure("기존 쿠팡 옵션 연결을 변경할 수 없습니다.");
        for(var mapping:beforeMappings){
            var next=nextMappings.stream().filter(o->Objects.equals(o.optionId(),mapping.optionId())).findFirst().orElseThrow(()->new InputValidationFailure("기존 쿠팡 옵션 연결을 변경할 수 없습니다."));
            if(!Objects.equals(mapping.sellerProductItemId(),next.sellerProductItemId())||!Objects.equals(mapping.vendorItemId(),next.vendorItemId()))throw new InputValidationFailure("기존 쿠팡 옵션 ID는 변경할 수 없습니다.");
            // Names and exposure are immutable; fresh value permissions are checked during prepare and execution.
            for(var attribute:DraftCoupangProjection.nullableList(mapping.attributes()))if(CoupangPurchaseAttributes.purchase(attribute.exposed(),attribute.name())){
                var replacement=DraftCoupangProjection.nullableList(next.attributes()).stream().filter(a->Objects.equals(a.name(),attribute.name())).findFirst().orElse(null);
                if(replacement==null||!Objects.equals(attribute.exposed(),replacement.exposed()))throw new InputValidationFailure("기존 구매옵션의 이름 변경·삭제는 지원하지 않습니다.");
            }
        }
    }
    public void validate(Long actor,Document d,MarketConfig m,String requestId,Map<String,ImageAssets.Asset> metadata,List<Issue> errors,List<Issue> unverified){
        validate(actor,d,m,requestId,metadata,errors,unverified,true);
    }
    public void validateFields(Long actor,Document d,MarketConfig m,String requestId,Map<String,ImageAssets.Asset> metadata,List<Issue> errors,List<Issue> unverified){validate(actor,d,m,requestId,metadata,errors,unverified,false);}
    private void validate(Long actor,Document d,MarketConfig m,String requestId,Map<String,ImageAssets.Asset> metadata,List<Issue> errors,List<Issue> unverified,boolean inspectImages){
        var market=Market.COUPANG;String p="markets.COUPANG";
        if(m.coupang()==null||m.coupang().sellerProductId()==null){errors.addAll(CoupangCreateValidation.issues(d,m));try{if(!MarketplaceDocuments.blank(d.delivery().jejuCharge())||!MarketplaceDocuments.blank(d.delivery().remoteCharge()))client.writeSession(()->{CoupangDeliveryPolicy.verify(client,d,m);return null;});}catch(cc.ataglace.molebutter.common.api.InputValidationFailure e){errors.add(new Issue("COUPANG","delivery",e.getMessage()));}if(MarketplaceDocuments.blank(client.registrationUserId()))issue(errors,market,"configuration.coupang.vendorUserId",CoupangProductClient.REGISTRATION_USER_REQUIRED);}
        if(!m.categoryCode().matches("[0-9]{1,15}")){issue(errors,market,p+".categoryCode","유효한 카테고리 코드를 입력해 주세요.");return;}
        CoupangEditor.CategoryRules rules;
        try{rules=coupang.category(actor,m.categoryCode(),requestId);}catch(MarketplaceFailure e){issue(errors,market,p+".categoryCode",e.getMessage());return;}
        var delivery=delivery(d,m);
        for(String k:List.of("deliveryMethod","deliveryCompanyCode","deliveryChargeType","deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","remoteAreaDeliverable","unionDeliveryType","outboundShippingPlaceCode","returnCenterCode","returnChargeName","companyContactNumber","returnZipCode","returnAddress","returnAddressDetail","returnCharge"))require(errors,market,p+".coupang.delivery."+k,delivery.get(k),deliveryLabel(k));
        for(String k:List.of("deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","returnCharge"))if(!MarketplaceDocuments.blank(delivery.get(k)))number(errors,market,p+".coupang.delivery."+k,delivery.get(k),deliveryLabel(k),9_007_199_254_740_991L);
        choice(errors,market,p+".coupang.delivery.deliveryMethod",delivery.get("deliveryMethod"),Set.of("SEQUENCIAL","COLD_FRESH","MAKE_ORDER","AGENT_BUY","VENDOR_DIRECT"));choice(errors,market,p+".coupang.delivery.deliveryChargeType",delivery.get("deliveryChargeType"),Set.of("FREE","NOT_FREE","CHARGE_RECEIVED","CONDITIONAL_FREE"));choice(errors,market,p+".coupang.delivery.remoteAreaDeliverable",delivery.get("remoteAreaDeliverable"),Set.of("Y","N"));choice(errors,market,p+".coupang.delivery.unionDeliveryType",delivery.get("unionDeliveryType"),Set.of("UNION_DELIVERY","NOT_UNION_DELIVERY"));
        if("FREE".equals(delivery.get("deliveryChargeType"))&&!"0".equals(delivery.get("freeShipOverAmount")))issue(errors,market,p+".coupang.delivery.freeShipOverAmount","무료배송 기준은 0으로 입력해 주세요.");
        if("CONDITIONAL_FREE".equals(delivery.get("deliveryChargeType"))){var v=delivery.get("freeShipOverAmount");if(integer(v,9_007_199_254_740_991L)&&(Long.parseLong(v)<100||Long.parseLong(v)%100!=0))issue(errors,market,p+".coupang.delivery.freeShipOverAmount","조건부 무료배송 기준은 100원 단위로 입력해 주세요.");}
        var settings=settings(d,m);if(m.coupang()==null||m.coupang().sellerProductId()==null){require(errors,market,p+".coupang.settings.brandId",settings.get("brandId"),"검색해서 선택한 브랜드");require(errors,market,"common.brand",d.common().brand(),"브랜드");}var start=date(errors,market,p+".coupang.settings.saleStartedAt",settings.get("saleStartedAt"));var end=date(errors,market,p+".coupang.settings.saleEndedAt",settings.get("saleEndedAt"));if(start!=null&&end!=null&&end.isBefore(start))issue(errors,market,p+".coupang.settings.saleEndedAt","판매 종료일시는 시작일시 이후로 입력해 주세요.");
        var checkedImages=new HashMap<String,R2Images.Image>();var failedImages=new HashSet<String>();
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
                if(m.coupang()!=null&&m.coupang().sellerProductId()!=null&&CoupangPurchaseAttributes.purchase(rule.exposed(),rule.name()))continue; // Changed purchase fields are checked against a fresh read by the write gateway.
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
            if(inspectImages)for(var image:images(d,m,o))if(image.assetId()!=null&&metadata.containsKey(image.assetId())){try{var a=assets==null?metadata.get(image.assetId()):assets.editedMetadata(actor,image.assetId());for(String error:cc.ataglace.molebutter.media.api.ImageRules.errors("COUPANG",image.imageType(),a.mimeType(),a.width(),a.height(),a.bytes()))issue(errors,market,"media.images",error);}catch(InputValidationFailure e){issue(errors,market,"media.images",e.getMessage());}}
            if(inspectImages)for(var image:images(d,m,o))if(image.assetId()==null){
                var source=m.coupang()==null?null:m.coupang().source();
                boolean preserved=source!=null&&config!=null&&source.options().stream().filter(v->Objects.equals(v.sellerProductItemId(),config.sellerProductItemId())).flatMap(v->v.images().stream()).anyMatch(v->Objects.equals(v.url(),image.url()));
                if(preserved||failedImages.contains(image.url()))continue;
                try{
                    var a=checkedImages.get(image.url());
                    if(a==null){a=r2.resolve(actor,image.url());checkedImages.put(image.url(),a);}
                    if(a.bytes()>3*1024*1024||a.width()!=a.height()||a.width()<500||a.width()>5000||!Set.of("image/jpeg","image/png").contains(a.mimeType())){
                        if(failedImages.add(image.url()))issue(errors,market,"media.images","쿠팡 이미지는 3MiB 이하, 500~5000px 정사각형 JPG·PNG여야 합니다. ("+imageName(a.url())+" · "+a.width()+"×"+a.height()+"px, "+a.bytes()+"바이트)");
                    }
                }catch(InputValidationFailure|OperationFailure e){if(failedImages.add(image.url()))issue(errors,market,"media.images",imageName(image.url())+": "+e.getMessage());}
            }
            var description=m.overrides()==null?null:m.overrides().description();if(description!=null?description.isBlank():MarketplaceDocuments.contentsFor(d,o.id()).stream().noneMatch(c->!c.value().isBlank()))issue(errors,market,"media.contents","옵션 "+(n+1)+"의 설명을 입력해 주세요.");
        }
        for(var rule:rules.documents()){
            boolean required="MANDATORY".equals(rule.required())||"MANDATORY_PARALLEL_IMPORTED".equals(rule.required())&&d.options().stream().anyMatch(o->"PARALLEL_IMPORTED".equals(DraftCoupangProjection.registration(d,m,o).get("parallelImported")))||"MANDATORY_OVERSEAS_PURCHASED".equals(rule.required())&&d.options().stream().anyMatch(o->"OVERSEAS_PURCHASED".equals(DraftCoupangProjection.registration(d,m,o).get("overseasPurchased")));
            if(required&&(m.coupang()==null||nullableList(m.coupang().documents()).stream().noneMatch(v->Objects.equals(v.templateName(),rule.name())&&(!MarketplaceDocuments.blank(v.path())||!MarketplaceDocuments.blank(v.vendorPath())))))issue(errors,market,p+".coupang.documents",rule.name()+"을 입력해 주세요.");
        }
    }
    private static String imageName(String url){try{String path=java.net.URI.create(url).getPath();return path.substring(path.lastIndexOf('/')+1);}catch(RuntimeException e){return "선택한 이미지";}}
    private static String deliveryLabel(String key){return switch(key){
        case "deliveryMethod"->"배송 방식";case "deliveryCompanyCode"->"택배사";case "deliveryChargeType"->"배송비 유형";case "deliveryCharge"->"배송비";case "freeShipOverAmount"->"조건부 무료배송 기준 금액";case "deliveryChargeOnReturn"->"초도 반품 배송비";case "returnCharge"->"반품 배송비";case "remoteAreaDeliverable"->"도서산간 배송 여부";case "unionDeliveryType"->"묶음 배송 여부";case "outboundShippingPlaceCode"->"출고지";case "returnCenterCode"->"반품지";case "returnChargeName"->"반품지명";case "companyContactNumber"->"반품 연락처";case "returnZipCode"->"반품 우편번호";case "returnAddress"->"반품 주소";case "returnAddressDetail"->"반품 상세 주소";default->key;
    };}
    private static LocalDateTime date(List<Issue> e,Market m,String path,String v){require(e,m,path,v,path.endsWith("saleStartedAt")?"판매 시작일시":"판매 종료일시");if(MarketplaceDocuments.blank(v))return null;try{var d=LocalDateTime.parse(v);if(d.getYear()>2099)throw new IllegalArgumentException();return d;}catch(RuntimeException x){issue(e,m,path,"2099년 이하의 유효한 판매 일시를 입력해 주세요.");return null;}}
    private static void choice(List<Issue> e,Market m,String path,String value,Set<String> allowed){if(!MarketplaceDocuments.blank(value)&&!allowed.contains(value))issue(e,m,path,"선택값을 확인해 주세요.");}
    private static boolean integer(String v,long max){if(v==null||!v.matches("[0-9]{1,16}"))return false;return new BigInteger(v).compareTo(BigInteger.valueOf(max))<=0;}
    private static void number(List<Issue> e,Market m,String path,String v,String name,long max){if(!integer(v,max))issue(e,m,path,name+"은 0~"+max+"의 정수로 입력해 주세요.");}
    private static void require(List<Issue> e,Market m,String path,String v,String name){if(MarketplaceDocuments.blank(v))issue(e,m,path,name+"을 입력해 주세요.");}
    private static void issue(List<Issue> e,Market m,String path,String message){e.add(new Issue(m==null?null:m.name(),path,message));}

}
