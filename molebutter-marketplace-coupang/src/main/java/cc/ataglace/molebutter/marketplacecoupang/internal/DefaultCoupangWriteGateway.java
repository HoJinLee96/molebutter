package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;
import cc.ataglace.molebutter.marketplace.api.MarketplaceCommonSettings;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditConflict;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.media.api.R2Images;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing;
import cc.ataglace.molebutter.marketplace.api.CoupangCatalog;
import cc.ataglace.molebutter.marketplace.api.CoupangBrands;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import static cc.ataglace.molebutter.marketplacecoupang.internal.DraftCoupangProjection.*;

/** Builds immutable wire snapshots from fresh remote state, never from a browser's raw JSON. */
@Component
final class DefaultCoupangWriteGateway implements MarketplaceWriteGateway {
    private final BusinessAccess access;
    private final CoupangProductClient client;
    private final ImageAssets assets;
    private final R2Images r2;
    private final ObjectMapper json;
    private final CoupangBrands brands;
    private final cc.ataglace.molebutter.media.api.ImageInspection inspection;
    private static final Set<String> NUMBERS=Set.of("displayCategoryCode","sellerProductId","sellerProductItemId","vendorItemId","outboundShippingPlaceCode","deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","returnCharge","originalPrice","salePrice","maximumBuyCount","maximumBuyForPerson","maximumBuyForPersonPeriod","outboundShippingTimeDay","unitCount","autoPricingInfo.minSalePrice");
    private static final Set<String> BOOLS=Set.of("requested","pccNeeded","bestPriceGuaranteed3P","emptyBarcode","autoPricingInfo.active");
    private static final Set<String> SETTINGS=Set.of("saleStartedAt","saleEndedAt","manufacture","brandId","productGroup","extraInfoMessage","bundleInfo.bundleType");
    private static final Set<String> REGISTRATION=Set.of("externalVendorSku","originalPrice","salePrice","maximumBuyCount","maximumBuyForPerson","maximumBuyForPersonPeriod","outboundShippingTimeDay","unitCount","adultOnly","taxType","parallelImported","overseasPurchased","pccNeeded","bestPriceGuaranteed3P","barcode","emptyBarcode","emptyBarcodeReason","modelNo","searchTags","offerCondition","offerDescription","autoPricingInfo.minSalePrice","autoPricingInfo.active");
    DefaultCoupangWriteGateway(BusinessAccess access,CoupangProductClient client,ImageAssets assets,ObjectMapper json,CoupangBrands brands,R2Images r2){this(access,client,assets,json,brands,r2,null);}
    @org.springframework.beans.factory.annotation.Autowired
    DefaultCoupangWriteGateway(BusinessAccess access,CoupangProductClient client,ImageAssets assets,ObjectMapper json,CoupangBrands brands,R2Images r2,cc.ataglace.molebutter.media.api.ImageInspection inspection){this.r2=r2;this.access=access;this.client=client;this.assets=assets;this.json=json;this.brands=brands;this.inspection=inspection;}

    public boolean validateCommonDraft(){return true;}
    @Override public Prepared saveWorkflow(Prepared p){
        if(p.steps().stream().anyMatch(s->s.type()==Type.APPROVAL))return p;
        var parent=p.steps().stream().filter(s->s.type()==Type.CREATE||s.type()==Type.PRODUCT&&s.path().equals(CoupangProductClient.PATH)).reduce((a,b)->b).orElse(null);
        if(parent==null)return p; // Dedicated price/stock/delivery updates need no sales approval.
        var steps=new ArrayList<Step>();
        for(var s:p.steps()){
            if(s==parent){var body=json.readTree(s.bodyJson()).deepCopy().asObject();body.put("requested",false);var expected=json.readTree(s.expectedJson()).deepCopy().asObject();if(s.type()==Type.CREATE||expected.has("requested"))expected.put("requested",false);parent=new Step(s.id(),s.type(),s.optionId(),s.method(),s.path(),s.query(),wire(body),s.baselineJson(),wire(expected));steps.add(parent);}
            else steps.add(s);
        }
        steps.add(new Step(UUID.randomUUID().toString(),Type.APPROVAL,null,"PUT",CoupangProductClient.PATH+"/{sellerProductId}/approvals","",null,"{}",json.writeValueAsString(parent)));
        return new Prepared(p.accountKey(),p.mapping(),List.copyOf(steps),p.changes().stream().filter(c->!"requested".equals(c.path())).toList(),p.preparedAt(),p.expectedSkus(),p.editIntent(),p.market(),p.schemaVersion());
    }
    private static boolean approvalFollows(Prepared p,Step step){return p.steps().stream().anyMatch(s->s.type()==Type.APPROVAL)&&(step.type()==Type.CREATE||step.type()==Type.PRODUCT&&step.path().equals(CoupangProductClient.PATH));}
    private Result saved(Prepared p,Step step,Result result){return approvalFollows(p,step)&&result.state()==State.ACCEPTED&&"SUCCESS".equals(result.code())?new Result(State.CONFIRMED,result.mapping(),"SAVED_FOR_APPROVAL","쿠팡 저장을 완료했습니다. 이어서 판매 승인을 요청합니다.",result.attemptedAt(),result.requestJson()):result;}
    public Document projectChanges(Document document,List<MarketplaceEditing.Change> changes){return CoupangEditPatch.apply(document,changes,json);}
    public Prepared prepare(Long actor,Document input,Mapping existing,boolean requested){
        access.productActor(actor,true);
        var d=MarketplaceDocuments.normalize(input);var m=d.markets().get(Market.COUPANG);
        if(m==null||!d.selectedMarkets().contains(Market.COUPANG))throw invalid("쿠팡을 선택해 주세요.");
        if(!d.services().isEmpty())throw invalid("서비스 선택지의 쿠팡 전송 규격 확인이 필요합니다. 선택지를 제거하거나 별도 상품 옵션으로 구성해 주세요.");
        Mapping mapping=existing;
        if(mapping==null&&m.coupang()!=null&&m.coupang().sellerProductId()!=null){
            mapping=new Mapping(client.accountKey(),m.coupang().sellerProductId(),m.coupang().options().stream().map(o->new OptionMapping(o.optionId(),o.sellerProductItemId(),o.vendorItemId())).toList());
        }
        if(mapping!=null&&!client.accountKey().equals(mapping.accountKey()))throw invalid("쿠팡 계정이 변경되었습니다. 연결 정보를 확인해 주세요.");
        final Mapping selected=mapping;
        if(selected==null){
            MarketplaceCommonSettings.validate(d);requireRegistrationUser();
            var creationIssues=CoupangCreateValidation.issues(d,m);if(!creationIssues.isEmpty())throw invalid(creationIssues.getFirst().message());
            brands.requireSelection(actor,settings(d,m).get("brandId"),m.overrides()==null?d.common().brand():inherited(m.overrides().brand(),d.common().brand()));
        }
        return client.writeSession(()->{if(selected==null)requireRegionalPolicy(d,m);return build(actor,d,m,selected,requested);});
    }
    public Prepared prepareSelected(Long actor,Document reference,Mapping mapping,boolean requested,Document observed,List<MarketplaceEditing.Change> changes){
        access.productActor(actor,true);
        if(mapping==null||mapping.sellerProductId()==null){var p=prepare(actor,CoupangEditPatch.apply(observed,changes,json),mapping,requested);return new Prepared(p.accountKey(),p.mapping(),p.steps(),p.changes(),p.preparedAt(),p.expectedSkus(),new EditIntent(observed,List.copyOf(changes)));}
        if(mapping==null||mapping.sellerProductId()==null||!client.accountKey().equals(mapping.accountKey()))throw invalid("쿠팡 상품 연결을 확인해 주세요.");
        var expanded=CoupangEditPatch.expand(observed,changes,json);
        return client.writeSession(()->buildSelected(actor,reference,mapping,requested,observed,expanded));
    }
    private Prepared buildSelected(Long actor,Document reference,Mapping mapping,boolean requested,Document observed,List<MarketplaceEditing.Change> changes){
        for(var c:changes)if(Set.of("options.price","options.quantity").contains(c.path())&&blank(scalar(CoupangEditPatch.value(observed,c,json))))throw invalid("현재 가격·재고를 확인한 뒤 변경해 주세요.");
        ObjectNode raw=(ObjectNode)client.rawProduct(mapping.sellerProductId());
        var current=new HashMap<String,CoupangCatalog.CurrentInventory>();var errors=new HashMap<String,String>();var inventoryValues=new HashMap<String,JsonNode>();
        for(var link:mapping.options()){
            boolean price=selected(changes,"options.price",link.optionId()),stock=selected(changes,"options.quantity",link.optionId());
            String currentId=null;for(var row:raw.path("items"))if(Objects.equals(link.sellerProductItemId(),optionalId(row.path("sellerProductItemId"))))currentId=optionalId(row.path("vendorItemId"));
            if((price||stock)&&currentId!=null){var value=client.rawInventory(currentId,price,stock);inventoryValues.put(currentId,value);
                Long currentPrice=inventoryNumber(value,"salePrice"),currentStock=inventoryNumber(value,"amountInStock");
                if(price&&currentPrice==null||stock&&currentStock==null)throw invalid("현재 가격·재고 조회 결과를 확인해 주세요.");
                current.put(currentId,new CoupangCatalog.CurrentInventory(currentId,currentPrice,currentStock,value.path("onSale").isBoolean()?value.path("onSale").asBoolean():null));
            }
        }
        var latest=CoupangEditingProjection.latest(reference,mapping,client.editorDocument(raw,current,errors));
        changes=CoupangEditPatch.rebaseCollections(observed,latest,changes,json);
        var desired=CoupangEditPatch.apply(latest,changes,json);
        for(var c:changes){
            var before=CoupangEditPatch.value(observed,c,json);var now=CoupangEditPatch.value(latest,c,json);var wanted=CoupangEditPatch.value(desired,c,json);
            if(!CoupangEditPatch.rowCollection(c)&&!CoupangEditPatch.equivalent(before,now,c)&&!CoupangEditPatch.equivalent(now,wanted,c))throw new MarketplaceEditConflict(c.path(),CoupangEditPatch.displayValue(before,c),CoupangEditPatch.displayValue(now,c),CoupangEditPatch.displayValue(wanted,c));
            if(Set.of("common.productCode","common.origin","common.material","common.afterService","stockMode","productQuantity").contains(c.path()))throw invalid("이 공통 항목은 쿠팡의 옵션·상품고시에서 선택해 수정해 주세요.");
            if(Set.of("markets.COUPANG.overrides.description","markets.COUPANG.overrides.imageIds").contains(c.path()))throw invalid("기존 상품의 이미지·설명은 옵션별 이미지·설명 항목에서 변경해 주세요.");
            validateSelected(c,desired,latest);
        }
        if(changes.stream().anyMatch(c->c.path().startsWith("delivery.")||c.path().startsWith("markets.COUPANG.coupang.delivery")))requireRegionalPolicy(desired,desired.markets().get(Market.COUPANG));
        var p=build(actor,desired,desired.markets().get(Market.COUPANG),mapping,requested,raw,changes,inventoryValues);
        var visible=new ArrayList<Change>();for(var c:changes){var before=CoupangEditPatch.value(latest,c,json);var after=CoupangEditPatch.value(desired,c,json);if(!CoupangEditPatch.equivalent(before,after,c))diff(c.optionId()==null?c.path()+".":"옵션."+c.optionId()+"."+c.path()+".",CoupangEditPatch.displayValue(before,c),CoupangEditPatch.displayValue(after,c),visible);}
        return new Prepared(p.accountKey(),p.mapping(),p.steps(),List.copyOf(visible),p.preparedAt(),p.expectedSkus(),new EditIntent(latest,List.copyOf(changes)));
    }
    private void requireRegionalPolicy(Document d,MarketConfig m){CoupangDeliveryPolicy.verify(client,d,m);}
    private static Long inventoryNumber(JsonNode value,String field){var number=value.path(field);return number.isIntegralNumber()&&number.canConvertToLong()&&number.asLong()>=0?number.asLong():null;}
    private static boolean selected(List<MarketplaceEditing.Change> changes,String path,String optionId){return changes==null||changes.stream().anyMatch(c->(c.path().equals(path)||c.path().startsWith(path+"."))&&(c.optionId()==null||Objects.equals(c.optionId(),optionId)));}
    static boolean editableField(String prefix,String key){return prefix.endsWith(".settings")?SETTINGS.contains(key):prefix.endsWith(".registration")?REGISTRATION.contains(key):DELIVERY_INPUT.containsKey(key);}
    private static final Map<String,String> DELIVERY_INPUT=Map.ofEntries(Map.entry("deliveryMethod","method"),Map.entry("deliveryCompanyCode","carrier"),Map.entry("deliveryChargeType","chargeType"),Map.entry("deliveryCharge","charge"),Map.entry("freeShipOverAmount","freeOver"),Map.entry("returnCharge","returnCharge"),Map.entry("deliveryChargeOnReturn","initialReturnCharge"),Map.entry("remoteAreaDeliverable","remoteArea"),Map.entry("unionDeliveryType","bundle"),Map.entry("outboundShippingPlaceCode","outboundCode"),Map.entry("returnCenterCode","returnCode"),Map.entry("returnChargeName","returnName"),Map.entry("companyContactNumber","returnContact"),Map.entry("returnZipCode","returnZip"),Map.entry("returnAddress","returnAddress"),Map.entry("returnAddressDetail","returnAddressDetail"));
    static String deliveryInput(String wire){return DELIVERY_INPUT.getOrDefault(wire,"__none");}
    static String deliveryWire(String input){return DELIVERY_INPUT.entrySet().stream().filter(e->e.getValue().equals(input)).map(Map.Entry::getKey).findFirst().orElseThrow(()->invalid("배송 수정 항목을 확인해 주세요."));}
    private void validateSelected(MarketplaceEditing.Change c,Document desired,Document previous){
        String p=c.path();var value=CoupangEditPatch.value(desired,c,json);
        if(value.isString()){
            String text=value.asString();int count=text.codePointCount(0,text.length());
            if(Set.of("common.name","markets.COUPANG.overrides.name","markets.COUPANG.overrides.productName").contains(p)&&(text.isBlank()||count>100))throw invalid("상품명은 1~100자로 입력해 주세요.");
            if(p.equals("options.name")&&(text.isBlank()||count>150))throw invalid("옵션명은 1~150자로 입력해 주세요.");
            if(p.equals("options.price"))validateWireField("salePrice",text);
            if(p.equals("options.quantity"))validateWireField("maximumBuyCount",text);
            if(p.equals("common.taxType")&&!Set.of("TAX","FREE").contains(text)||p.equals("common.adultOnly")&&!Set.of("EVERYONE","ADULT_ONLY").contains(text))throw invalid("과세·성인 설정을 확인해 주세요.");
            if(p.startsWith("delivery.")&&!Set.of("delivery.jejuCharge","delivery.remoteCharge").contains(p))validateWireField(deliveryWire(p.substring(9)),text);
            for(String base:List.of("markets.COUPANG.coupang.settings","markets.COUPANG.coupang.delivery","markets.COUPANG.coupang.options.registration"))if(p.startsWith(base+".")){
                String key=p.substring(base.length()+1);validateWireField(key,text);
                if(base.endsWith(".registration")&&Set.of("salePrice","maximumBuyCount").contains(key))throw invalid("가격·재고는 옵션 가격·수량 항목에서 변경해 주세요.");
            }
        }
        if(p.endsWith(".settings")||p.endsWith(".delivery")||p.endsWith(".registration")){
            var before=CoupangEditPatch.value(previous,c,json);var old=new HashMap<String,String>();for(var row:before)old.put(scalar(row.path("name")),scalar(row.path("value")));var seen=new HashSet<String>();
            for(var row:value){String key=scalar(row.path("name")),text=scalar(row.path("value"));if(key==null||text==null||!seen.add(key))throw invalid("설정 항목과 값을 확인해 주세요.");
                if(Objects.equals(old.get(key),text))continue;
                if(key.startsWith("autoPricingInfoView."))throw invalid("조회 전용 자동 가격 설정은 변경할 수 없습니다.");
                if(p.endsWith(".registration")&&Set.of("salePrice","maximumBuyCount").contains(key))throw invalid("현재 가격·재고는 옵션의 가격·수량 항목에서 변경해 주세요.");
                validateWireField(key,text);
            }
            for(var key:old.keySet())if(!seen.contains(key))throw invalid("설정 항목 삭제는 지원하지 않습니다. 값을 직접 변경해 주세요.");
        }
        if(p.endsWith(".attributes"))for(var row:value){
            String name=scalar(row.path("name"));boolean unchanged=false;for(var old:CoupangEditPatch.value(previous,c,json))if(Objects.equals(name,scalar(old.path("name")))&&old.equals(row))unchanged=true;if(unchanged)continue;
            String text=scalar(row.path("value")),exposed=scalar(row.path("exposed"));if(blank(name)||name.codePointCount(0,name.length())>25||text==null||text.codePointCount(0,text.length())>30||exposed==null||!Set.of("EXPOSED","NONE").contains(exposed))throw invalid("쿠팡 속성 이름·값·구분을 확인해 주세요.");
        }
    }
    private void validateWireField(String key,String value){
        if(NUMBERS.contains(key)){var n=number(value);if(n.compareTo(BigInteger.valueOf(9_007_199_254_740_991L))>0)throw invalid("숫자 입력 범위를 확인해 주세요.");if(key.equals("maximumBuyCount")&&n.compareTo(BigInteger.valueOf(99999))>0)throw invalid("판매 수량은 99999 이하로 입력해 주세요.");if(key.equals("unitCount")&&n.signum()==0)throw invalid("판매 단위는 1 이상이어야 합니다.");}
        if(BOOLS.contains(key)&&!Set.of("true","false").contains(value))throw invalid("참·거짓 설정을 확인해 주세요.");
        var choices=Map.ofEntries(Map.entry("adultOnly",Set.of("EVERYONE","ADULT_ONLY")),Map.entry("taxType",Set.of("TAX","FREE")),Map.entry("parallelImported",Set.of("PARALLEL_IMPORTED","NOT_PARALLEL_IMPORTED")),Map.entry("overseasPurchased",Set.of("OVERSEAS_PURCHASED","NOT_OVERSEAS_PURCHASED")),Map.entry("deliveryMethod",Set.of("SEQUENCIAL","COLD_FRESH","MAKE_ORDER","AGENT_BUY","VENDOR_DIRECT")),Map.entry("deliveryChargeType",Set.of("FREE","NOT_FREE","CHARGE_RECEIVED","CONDITIONAL_FREE")),Map.entry("remoteAreaDeliverable",Set.of("Y","N")),Map.entry("unionDeliveryType",Set.of("UNION_DELIVERY","NOT_UNION_DELIVERY")));
        if(choices.containsKey(key)&&!choices.get(key).contains(value))throw invalid("쿠팡 설정의 선택값을 확인해 주세요.");
        if(key.equals("saleStartedAt")||key.equals("saleEndedAt"))try{if(java.time.LocalDateTime.parse(value).getYear()>2099)throw new IllegalArgumentException();}catch(RuntimeException bad){throw invalid("판매 일시는 2099년 이하의 유효한 날짜로 입력해 주세요.");}
        if(key.equals("searchTags")){var tags=Arrays.stream(value.split(",")).map(String::trim).filter(t->!t.isEmpty()).toList();if(tags.size()>20||tags.stream().anyMatch(t->t.codePointCount(0,t.length())>20))throw invalid("검색어는 각 20자 이하, 20개 이하로 입력해 주세요.");}
        if(key.equals("emptyBarcodeReason")&&value.codePointCount(0,value.length())>100||key.equals("offerDescription")&&value.codePointCount(0,value.length())>700)throw invalid("설정 내용의 길이를 줄여 주세요.");
    }
    private String requireRegistrationUser(){
        String id=client.registrationUserId();
        if(blank(id))throw invalid(CoupangProductClient.REGISTRATION_USER_REQUIRED);
        return id;
    }
    private Prepared build(Long actor,Document d,MarketConfig m,Mapping mapping,boolean requested){
        return build(actor,d,m,mapping,requested,mapping==null?json.createObjectNode():(ObjectNode)client.rawProduct(mapping.sellerProductId()),null,null);
    }
    private Prepared build(Long actor,Document d,MarketConfig m,Mapping mapping,boolean requested,ObjectNode baseline,List<MarketplaceEditing.Change> selectedChanges,Map<String,JsonNode> inventoryValues){
        if(mapping==null&&(blank(settings(d,m).get("brandId"))||blank(m.overrides()==null?d.common().brand():inherited(m.overrides().brand(),d.common().brand()))))throw invalid("브랜드를 검색해서 선택해 주세요.");
        if(mapping==null){
            var creationIssues=CoupangCreateValidation.issues(d,m);
            if(!creationIssues.isEmpty())throw invalid(creationIssues.getFirst().message());
        }
        if(mapping!=null&&(selected(selectedChanges,"common.brand",null)||selected(selectedChanges,"markets.COUPANG.overrides.brand",null))&&!Objects.equals(scalar(baseline.path("brand")),m.overrides()==null?d.common().brand():inherited(m.overrides().brand(),d.common().brand())))throw invalid("기존 쿠팡 브랜드는 변경할 수 없습니다.");
        ObjectNode desired=baseline.deepCopy();
        if(selected(selectedChanges,"common.name",null)||selected(selectedChanges,"markets.COUPANG.overrides.name",null))set(desired,"sellerProductName",name(d,m));
        if(selected(selectedChanges,"markets.COUPANG.overrides.productName",null))set(desired,"displayProductName",m.overrides()==null?d.common().name():inherited(m.overrides().productName(),d.common().name()));
        if(selected(selectedChanges,"common.productName",null))set(desired,"generalProductName",d.common().productName());
        if(selected(selectedChanges,"common.brand",null)||selected(selectedChanges,"markets.COUPANG.overrides.brand",null))set(desired,"brand",m.overrides()==null?d.common().brand():inherited(m.overrides().brand(),d.common().brand()));
        if(mapping==null){set(desired,"vendorId",client.vendorId());set(desired,"displayCategoryCode",m.categoryCode());}
        else if(!m.categoryCode().equals(scalar(baseline.path("displayCategoryCode"))))throw invalid("수정할 상품의 카테고리가 변경되었습니다. 다시 가져와 주세요.");
        for(var e:settings(d,m).entrySet()){
            if(!selected(selectedChanges,"markets.COUPANG.coupang.settings."+e.getKey(),null)&&!(e.getKey().equals("manufacture")&&selected(selectedChanges,"common.manufacturer",null)))continue;
            if(Set.of("requested","vendorUserId").contains(e.getKey()))continue; // Legacy draft identity is server-owned.
            if(!SETTINGS.contains(e.getKey()))throw invalid("쿠팡 설정 "+e.getKey()+"의 전송 규격 확인이 필요합니다.");
            if(mapping!=null&&"brandId".equals(e.getKey())){
                String old=scalar(baseline.path("brandId")),next=e.getValue();
                if(!Objects.equals(blank(old)?null:old,blank(next)?null:next))throw invalid("기존 상품의 브랜드 ID는 쿠팡 Wing에서 변경해 주세요.");
                continue; // Existing brand identifiers cannot be updated through OpenAPI; preserve the complete fresh value.
            }
            set(desired,e.getKey(),e.getValue());
        }
        if(mapping==null)set(desired,"vendorUserId",requireRegistrationUser());
        var sourceItems=baseline.path("items");var output=json.createArrayNode();
        var mappings=new ArrayList<OptionMapping>();var currentSteps=new ArrayList<Step>();var changes=new ArrayList<Change>();var skus=new ArrayList<String>();
        if(mapping!=null&&(!sourceItems.isArray()||sourceItems.size()!=d.options().size()||mapping.options().size()!=d.options().size()))throw invalid("원격 옵션 구성이 변경되었습니다. 다시 가져와 주세요.");
        var seenRemote=new HashSet<String>();
        for(var o:d.options()){
            var om=mapping==null?new OptionMapping(o.id(),null,null):mapping.options().stream().filter(x->x.optionId().equals(o.id())).findFirst().orElseThrow(()->invalid("옵션 연결을 확인해 주세요."));
            ObjectNode source=null;
            if(mapping!=null){
                for(var row:sourceItems)if(Objects.equals(om.sellerProductItemId(),scalar(row.path("sellerProductItemId"))))source=(ObjectNode)row;
                if(source==null||!seenRemote.add(om.sellerProductItemId())||om.vendorItemId()!=null&&!Objects.equals(om.vendorItemId(),optionalId(source.path("vendorItemId"))))throw invalid("원격 옵션 ID가 변경되었습니다. 다시 가져와 주세요.");
                if(om.vendorItemId()==null&&optionalId(source.path("vendorItemId"))!=null)om=new OptionMapping(om.optionId(),om.sellerProductItemId(),optionalId(source.path("vendorItemId")));
            }
            ObjectNode item=source==null?json.createObjectNode():source.deepCopy();var cfg=optionConfig(m,o);var over=override(m,o);
            if(selected(selectedChanges,"options.name",o.id())){
                String wantedName=over==null?o.name():inherited(over.name(),o.name());
                if(source!=null&&!Objects.equals(wantedName,scalar(source.path("itemName"))))throw invalid("기존 등록 옵션명은 직접 변경할 수 없습니다. 구매옵션 값을 수정해 주세요.");
                if(source==null)set(item,"itemName",wantedName);
            }
            var registration=registration(d,m,o);
            // A common/explicit option change wins over the preserved imported registration settings.
            String wantedPrice=over==null?o.price():inherited(over.price(),o.price());
            String wantedQuantity=d.stockMode()==StockMode.PRODUCT?d.productQuantity():over==null?o.quantity():inherited(over.quantity(),o.quantity());
            if(!blank(wantedPrice))registration.put("salePrice",wantedPrice);
            if(!blank(wantedQuantity))registration.put("maximumBuyCount",wantedQuantity);
            for(var e:registration.entrySet()){
                String key=e.getKey();
                if(selectedChanges!=null&&!selected(selectedChanges,"markets.COUPANG.coupang.options.registration."+key,o.id())&&!selected(selectedChanges,Map.of("externalVendorSku","options.sku","salePrice","options.price","maximumBuyCount","options.quantity","modelNo","common.model","taxType","common.taxType","adultOnly","common.adultOnly").getOrDefault(key,"__none"),key.equals("modelNo")||key.equals("taxType")||key.equals("adultOnly")?null:o.id()))continue;
                if(key.startsWith("autoPricingInfoView."))continue; // Read-only view is preserved in the complete remote baseline.
                if(!REGISTRATION.contains(key))throw invalid("쿠팡 옵션 설정 "+key+"의 전송 규격 확인이 필요합니다.");
                if(source!=null&&om.vendorItemId()!=null&&Set.of("salePrice","maximumBuyCount").contains(key))continue;
                if(source!=null&&om.vendorItemId()!=null&&key.equals("originalPrice")){
                    if(number(e.getValue()).mod(BigInteger.TEN).signum()!=0)throw invalid("정상가는 10원 단위로 입력해 주세요.");
                    if(!numericEqual(e.getValue(),source.path(key)))currentSteps.add(currentStep(Type.ORIGINAL_PRICE,o.id(),om.vendorItemId(),e.getValue(),source));
                    continue;
                }
                if(source!=null&&om.vendorItemId()!=null&&key.startsWith("autoPricingInfo.")){
                    if(!blank(e.getValue())&&!Objects.equals(e.getValue(),scalar(at(source,key))))throw invalid("승인된 옵션의 자동 가격 설정은 별도 변경 기능이 필요합니다.");
                    continue;
                }
                set(item,key,e.getValue());
            }
            if(source!=null&&cfg!=null){
                var original=original(m,om.sellerProductItemId());
                // Omitted read-only fields and unrepresented nested values stay in the fresh server baseline.
                var originalAttributes=original==null?CoupangPurchaseAttributes.read(source.path("attributes")):original.attributes();
                if(selected(selectedChanges,"markets.COUPANG.coupang.options.attributes",o.id())&&!Objects.equals(cfg.attributes(),originalAttributes)){
                    boolean purchaseChanged=CoupangPurchaseAttributes.changed(originalAttributes,cfg.attributes());
                    var categoryRules=purchaseChanged?client.rawCategory(m.categoryCode()):new cc.ataglace.molebutter.marketplace.api.CoupangEditor.CategoryRules(m.categoryCode(),null,List.of(),List.of(),List.of(),List.of(),List.of());
                    item.set("attributes",CoupangPurchaseAttributes.patch(json,source.path("attributes"),originalAttributes,cfg.attributes(),categoryRules));
                }

                if(selected(selectedChanges,"markets.COUPANG.coupang.options.notices",o.id())&&original!=null&&!Objects.equals(cfg.notices(),original.notices()))item.set("notices",mergeNotices(source.path("notices"),noticeRows(cfg),original.notices()));
                else if(original==null&&cfg.notices()!=null&&!cfg.notices().isEmpty())item.set("notices",mergeNotices(source.path("notices"),noticeRows(cfg),List.of()));
                if(selected(selectedChanges,"markets.COUPANG.coupang.options.certifications",o.id())&&original!=null&&!Objects.equals(cfg.certifications(),original.certifications())){
                    for(var cert:nullableList(cfg.certifications())){var old=original.certifications().stream().filter(c->Objects.equals(c.type(),cert.type())).findFirst().orElse(null);if(!Objects.equals(nullableList(cert.attachments()),old==null?List.of():old.attachments()))throw invalid("인증 첨부 수정은 전송 경로 확인이 필요합니다.");}
                    item.set("certifications",merge(source.path("certifications"),certificationCodeRows(cfg),"certificationType",original.certifications().stream().map(c->c.type()).toList()));
                }
            }else{
                item.set("attributes",attributeRows(cfg,o));item.set("notices",noticeRows(cfg));item.set("certifications",certificationRows(cfg));
            }
            var imageRows=selected(selectedChanges,"media.images",o.id())||selected(selectedChanges,"markets.COUPANG.overrides.imageIds",null)?images(actor,d,m,o,source):null;
            if(imageRows!=null)item.set("images",imageRows);
            if((selected(selectedChanges,"media.contents",o.id())||selected(selectedChanges,"markets.COUPANG.overrides.description",null))&&(source==null||!sameContents(m,om.sellerProductItemId(),d,m,o))){var next=contents(actor,d,m,o,source);item.set("contents",source==null?next:mergeContents(source.path("contents"),next));}
            if(source!=null&&om.vendorItemId()!=null&&(selected(selectedChanges,"options.price",o.id())||selected(selectedChanges,"options.quantity",o.id()))){
                String price=over==null?o.price():inherited(over.price(),o.price());String quantity=d.stockMode()==StockMode.PRODUCT?d.productQuantity():over==null?o.quantity():inherited(over.quantity(),o.quantity());
                var inventory=inventoryValues==null?client.rawInventory(om.vendorItemId()):inventoryValues.get(om.vendorItemId());
                if(selected(selectedChanges,"options.price",o.id())){if(number(price).mod(BigInteger.TEN).signum()!=0)throw invalid("현재 판매가는 10원 단위로 입력해 주세요.");if(!numericEqual(price,inventory.path("salePrice")))currentSteps.add(currentStep(Type.PRICE,o.id(),om.vendorItemId(),price,inventory));}
                if(selected(selectedChanges,"options.quantity",o.id())){if(number(quantity).compareTo(BigInteger.valueOf(99999))>0)throw invalid("현재 재고는 99999 이하로 입력해 주세요.");if(!numericEqual(quantity,inventory.path("amountInStock")))currentSteps.add(currentStep(Type.STOCK,o.id(),om.vendorItemId(),quantity,inventory));}
            }
            if(mapping==null)item.put("itemName",CoupangPurchaseAttributes.itemName(item.path("attributes")));
            mappings.add(om);output.add(item);String sku=scalar(item.path("externalVendorSku"));if(!blank(sku))skus.add(sku);
        }
        if(selectedChanges!=null){var ordered=json.createArrayNode();for(var remote:sourceItems){JsonNode matching=null;for(var row:output)if(remote.path("sellerProductItemId").equals(row.path("sellerProductItemId")))matching=row;if(matching==null)throw invalid("상품 옵션 연결을 확인해 주세요.");ordered.add(matching);}output=ordered;}
        desired.set("items",output);
        if(selected(selectedChanges,"markets.COUPANG.coupang.documents",null)&&mapping!=null&&m.coupang()!=null&&m.coupang().source()!=null&&!Objects.equals(m.coupang().documents(),m.coupang().source().documents()))desired.set("requiredDocuments",documentRows(m.coupang(),baseline.path("requiredDocuments")));
        if(mapping==null){
            CoupangPurchaseAttributes.create(output,client.rawCategory(m.categoryCode()));
            desired.put("requested",requested);for(var e:delivery(d,m).entrySet())set(desired,e.getKey(),e.getValue());
            if(m.coupang()!=null)desired.set("requiredDocuments",documentRows(m.coupang(),json.createArrayNode()));
            if(skus.isEmpty())throw invalid("응답 유실 시 등록 여부를 확인할 판매자 상품코드를 입력해 주세요.");
            // Keep the preview candidates for comparison; execution records a fresh snapshot before POST.
            var prior=createBaseline(List.of(skus.getFirst()));
            var step=new Step(UUID.randomUUID().toString(),Type.CREATE,null,"POST",CoupangProductClient.PATH,"",wire(desired),wire(prior),wire(desired));
            diff("",json.createObjectNode(),desired,changes);
            return new Prepared(client.accountKey(),new Mapping(client.accountKey(),null,mappings),List.of(step),List.copyOf(changes),client.now(),List.of(skus.getFirst()));
        }
        var steps=new ArrayList<Step>();
        // Default false prevents an information edit from silently requesting approval. It is only applied if an edit exists.
        boolean productChanged=!baseline.equals(desired)||selectedChanges==null&&requested&&!baseline.path("requested").asBoolean();
        if(productChanged){desired.put("requested",requested);diff("",baseline,desired,changes);steps.add(new Step(UUID.randomUUID().toString(),Type.PRODUCT,null,"PUT",CoupangProductClient.PATH,"",wire(desired),wire(baseline),wire(delta(baseline,desired))));}
        var beforeDelivery=json.createObjectNode();var afterDelivery=json.createObjectNode();set(afterDelivery,"sellerProductId",mapping.sellerProductId());
        for(var e:delivery(d,m).entrySet()){if(selectedChanges!=null&&!selected(selectedChanges,"markets.COUPANG.coupang.delivery."+e.getKey(),null)&&!selected(selectedChanges,"delivery."+deliveryInput(e.getKey()),null))continue;beforeDelivery.set(e.getKey(),baseline.path(e.getKey()));set(afterDelivery,e.getKey(),e.getValue());}
        ObjectNode comparison=afterDelivery.deepCopy();comparison.remove("sellerProductId");
        if(!beforeDelivery.equals(comparison)){
            if(Arrays.asList("임시저장중","승인대기중","SAVED","APPROVING").contains(scalar(baseline.path("statusName"))))throw invalid("임시저장·승인대기 상품의 배송·반품은 현재 변경할 수 없습니다.");
            diff("배송.",beforeDelivery,comparison,changes);steps.add(new Step(UUID.randomUUID().toString(),Type.DELIVERY,null,"PUT",CoupangProductClient.PATH+"/"+mapping.sellerProductId()+"/partial","",wire(afterDelivery),wire(beforeDelivery),wire(comparison)));
        }
        for(var step:currentSteps){var before=json.readTree(step.baselineJson());var after=json.readTree(step.expectedJson());diff("옵션."+step.optionId()+".",before,after,changes);steps.add(step);}
        steps.sort(Comparator.comparingInt(step->switch(step.type()){case DELIVERY->0;case ORIGINAL_PRICE->1;case PRICE->2;case STOCK->3;case PRODUCT->4;case CREATE->5;case APPROVAL->6;}));
        return new Prepared(client.accountKey(),new Mapping(client.accountKey(),mapping.sellerProductId(),List.copyOf(mappings)),List.copyOf(steps),List.copyOf(changes),client.now(),List.copyOf(skus));
    }

    public Result execute(Long actor,Prepared prepared,Step step,Mapping current){
        return execute(actor,prepared,step,current,s->{});
    }
    @Override public Result execute(Long actor,Prepared prepared,Step step,Mapping current,java.util.function.Consumer<Step> beforeDispatch){
        access.productActor(actor,true);Instant attempted=client.now();Mapping mapping=current==null?prepared.mapping():current;
        if(!validAccount(prepared,mapping))return result(State.FAILED,mapping,"ACCOUNT_CHANGED","쿠팡 계정이 변경되었습니다. 다시 준비해 주세요.",attempted);
        if(!prepared.steps().stream().anyMatch(s->s.id().equals(step.id())&&s.type()==step.type()&&Objects.equals(s.optionId(),step.optionId())))return result(State.FAILED,mapping,"INVALID_STEP","전송 단계를 확인해 주세요.",attempted);
        if(step.type()==Type.APPROVAL)return executeApproval(actor,prepared,step,mapping,attempted,beforeDispatch);
        if(step.type()==Type.CREATE&&mapping.sellerProductId()!=null)return result(State.FAILED,mapping,"ALREADY_CREATED","이미 연결된 상품입니다.",attempted);
        if(prepared.editIntent()!=null&&step.type()!=Type.CREATE)return executeSelected(actor,prepared,step,mapping,attempted,beforeDispatch);
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try{return client.writeSession(()->{
            access.productActor(actor,true);
            if(step.type()!=Type.CREATE&&(!Objects.equals(prepared.mapping().sellerProductId(),mapping.sellerProductId())||!Objects.equals(prepared.mapping().options(),mapping.options())))return result(State.FAILED,mapping,"MAPPING_CHANGED","상품 연결이 변경되었습니다. 다시 준비해 주세요.",attempted);
            if(step.type()!=Type.CREATE&&!baselineMatches(step,mapping))return result(State.FAILED,mapping,"BASELINE_CHANGED","쿠팡 상품이 변경되었습니다. 다시 준비해 주세요.",attempted);
            if(step.type()==Type.PRODUCT){
                var fresh=client.rawProduct(mapping.sellerProductId());var proposed=json.readTree(step.bodyJson());
                for(var next:proposed.path("items"))for(var old:fresh.path("items"))if(old.path("sellerProductItemId").equals(next.path("sellerProductItemId"))){
                    var before=CoupangPurchaseAttributes.read(old.path("attributes"));var after=CoupangPurchaseAttributes.read(next.path("attributes"));
                    if(CoupangPurchaseAttributes.changed(before,after))CoupangPurchaseAttributes.patch(json,old.path("attributes"),before,after,client.rawCategory(scalar(fresh.path("displayCategoryCode"))));
                }
            }
            Step actual=step;
            if(step.type()==Type.CREATE){
                if(prepared.editIntent()!=null){var d=CoupangEditPatch.apply(prepared.editIntent().observed(),prepared.editIntent().changes(),json);requireRegionalPolicy(d,d.markets().get(Market.COUPANG));}
                var createBody=json.readTree(step.bodyJson());
                if(inspection!=null&&prepared.editIntent()!=null){var input=CoupangEditPatch.apply(prepared.editIntent().observed(),prepared.editIntent().changes(),json);var config=input.markets().get(Market.COUPANG);var checks=new ArrayList<cc.ataglace.molebutter.media.api.ImageInspection.Input>();for(var option:input.options()){for(var image:DraftCoupangProjection.images(input,config,option))checks.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input("COUPANG",image.imageType(),option.id(),image.id(),image.url(),image.assetId(),false));String override=config.overrides()==null?null:config.overrides().description();if(override!=null)checks.addAll(cc.ataglace.molebutter.media.api.ImageInspection.descriptionInputs("COUPANG",override,""));else for(var content:MarketplaceDocuments.contentsFor(input,option.id()))checks.addAll(cc.ataglace.molebutter.media.api.ImageInspection.descriptionInputs("COUPANG",content.type().equals("IMAGE")?"<img src=\""+escape(content.value())+"\">":content.value(),""));}inspection.require(actor,checks);}
                else for(var item:createBody.path("items")){for(var image:item.path("images")){var a=r2.resolve(actor,image.path("vendorPath").asString(""));if(a.bytes()>3*1024*1024||a.width()!=a.height()||a.width()<500||a.width()>5000)throw invalid("쿠팡 이미지 규격을 확인해 주세요.");}for(var content:item.path("contents"))for(var part:content.path("contentDetails")){String value=part.path("content").asString("");r2.validateDescription(actor,part.path("detailType").asString("").equals("IMAGE")?"<img src=\""+escape(value)+"\">":value,"");}}
                CoupangPurchaseAttributes.create(createBody.path("items"),client.rawCategory(scalar(createBody.path("displayCategoryCode"))));
                try{actual=new Step(step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),step.bodyJson(),wire(createBaseline(prepared.expectedSkus())),step.expectedJson());}
                catch(MarketplaceFailure failure){return result(State.FAILED,mapping,failure.kind().name(),"등록 전 기존 상품을 조회하지 못해 전송하지 않았습니다. 다시 확인해 주세요.",attempted);}
            }
            access.productActor(actor,true);
            beforeDispatch.accept(actual);
            dispatched.set(true);
            var response=client.write(actual.method(),actual.path(),actual.query(),actual.bodyJson());
            if(response.status()!=200)return httpFailure(response.status(),response.body(),mapping,attempted);
            var accepted=parseOutcome(response.body(),step,mapping,attempted);
            if(accepted.state()!=State.ACCEPTED)return accepted;
            if(approvalFollows(prepared,step))return saved(prepared,step,accepted);
            try{return verifyKnown(prepared,step,accepted);}catch(RuntimeException verificationFailure){return accepted;}
        });}catch(MarketplaceFailure e){
            boolean definite=!dispatched.get()||Set.of(MarketplaceFailure.Kind.CONFIGURATION,MarketplaceFailure.Kind.BUSY,MarketplaceFailure.Kind.RATE_LIMIT).contains(e.kind());
            return result(definite?State.FAILED:State.UNKNOWN,mapping,e.kind().name(),definite?e.getMessage():"처리 결과를 확인하지 못했습니다. 등록 여부를 확인 중입니다.",attempted);
        }catch(InputValidationFailure e){return result(State.FAILED,mapping,"VALIDATION",e.getMessage(),attempted);}
    }
    private Result executeSelected(Long actor,Prepared prepared,Step step,Mapping mapping,Instant attempted,java.util.function.Consumer<Step> beforeDispatch){
        var attemptedRequest=new java.util.concurrent.atomic.AtomicReference<String>();
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try{return client.writeSession(()->{
            access.productActor(actor,true);
            if(!Objects.equals(prepared.mapping(),mapping))return result(State.FAILED,mapping,"MAPPING_CHANGED","상품 연결이 변경되었습니다. 다시 준비해 주세요.",attempted);
            var intent=prepared.editIntent();boolean requested=!approvalFollows(prepared,step)&&step.type()==Type.PRODUCT&&step.bodyJson()!=null&&json.readTree(step.bodyJson()).path("requested").asBoolean();var fresh=buildSelected(actor,intent.observed(),mapping,requested,intent.observed(),intent.changes());
            var next=fresh.steps().stream().filter(s->s.type()==step.type()&&Objects.equals(s.optionId(),step.optionId())).findFirst().orElse(null);
            if(next==null)return result(State.CONFIRMED,fresh.mapping(),"ALREADY_APPLIED","선택한 변경이 이미 반영되어 있습니다.",attempted);
            // Recompose a full product payload from the immediately preceding server read; untouched values are not taken from the old preview.
            var request=json.createObjectNode();request.put("method",next.method());request.put("path",next.path());request.put("query",next.query());if(next.bodyJson()!=null)request.set("body",json.readTree(next.bodyJson()));
            String requestJson=wire(request);access.productActor(actor,true);beforeDispatch.accept(next);attemptedRequest.set(requestJson);dispatched.set(true);
            var response=client.write(next.method(),next.path(),next.query(),next.bodyJson());
            var outcome=response.status()==200?parseOutcome(response.body(),next,fresh.mapping(),attempted):httpFailure(response.status(),response.body(),fresh.mapping(),attempted);
            if(approvalFollows(prepared,step))outcome=saved(prepared,step,outcome);
            else if(outcome.state()==State.ACCEPTED)try{outcome=verifyKnown(fresh,next,outcome);}catch(RuntimeException verificationFailure){}
            return withRequest(outcome,attemptedRequest.get());
        });}catch(InputValidationFailure conflict){return result(State.FAILED,mapping,"BASELINE_CHANGED",conflict.getMessage(),attempted);}
        catch(MarketplaceFailure failure){boolean definite=!dispatched.get()||Set.of(MarketplaceFailure.Kind.CONFIGURATION,MarketplaceFailure.Kind.BUSY,MarketplaceFailure.Kind.RATE_LIMIT).contains(failure.kind());return withRequest(result(definite?State.FAILED:State.UNKNOWN,mapping,failure.kind().name(),definite?failure.getMessage():"처리 결과를 확인하지 못했습니다.",attempted),attemptedRequest.get());}
    }
    private static Result withRequest(Result result,String request){return new Result(result.state(),result.mapping(),result.code(),result.message(),result.attemptedAt(),request);}
    private Result executeApproval(Long actor,Prepared prepared,Step step,Mapping mapping,Instant attempted,java.util.function.Consumer<Step> beforeDispatch){
        if(mapping.sellerProductId()==null)return result(State.FAILED,mapping,"MAPPING_CHANGED","저장된 등록상품 ID를 확인해 주세요.",attempted);
        var sent=new java.util.concurrent.atomic.AtomicBoolean();
        try{return client.writeSession(()->{
            String path=CoupangProductClient.PATH+"/"+mapping.sellerProductId()+"/approvals";
            var actual=new Step(step.id(),Type.APPROVAL,null,"PUT",path,"",null,step.baselineJson(),step.expectedJson());
            access.productActor(actor,true);beforeDispatch.accept(actual);sent.set(true);
            var response=client.write("PUT",path,"",null);
            if(response.status()!=200){
                if(response.status()==400&&approvalProcessing(response.body()))return result(State.FAILED,mapping,"APPROVAL_NOT_READY","쿠팡이 상품을 처리 중입니다. 10분 후 승인 요청만 재시도해 주세요.",attempted);
                return httpFailure(response.status(),response.body(),mapping,attempted);
            }
            var accepted=parseOutcome(response.body(),actual,mapping,attempted);
            if(accepted.state()!=State.ACCEPTED)return accepted;
            try{return verifyApproval(prepared,actual,accepted);}catch(MarketplaceFailure e){return result(State.ACCEPTED,mapping,"APPROVAL_PENDING","판매 승인 요청 접수 · 승인 결과 확인 필요",attempted);}
        });}catch(MarketplaceFailure e){return result(sent.get()?State.UNKNOWN:State.FAILED,mapping,e.kind().name(),sent.get()?"판매 승인 요청 결과를 확인하지 못했습니다. 재전송하지 말고 결과를 확인해 주세요.":"승인 요청을 보내지 못했습니다.",attempted);}
    }
    private boolean approvalProcessing(byte[] body){try{String message=json.readTree(body).path("message").asString("");return message.contains("상품 정보가 등록 또는 수정되고 있습니다");}catch(RuntimeException e){return false;}}
    private Result verifyApproval(Prepared p,Step approval,Result previous){
        var save=json.readValue(approval.expectedJson(),Step.class);var mapping=previous.mapping();
        if(save.type()==Type.CREATE){var expected=json.readTree(save.expectedJson()).deepCopy().asObject();expected.put("requested",true);return verifyKnown(p,new Step(save.id(),Type.CREATE,null,save.method(),save.path(),save.query(),save.bodyJson(),save.baselineJson(),wire(expected)),previous);}
        if(save.type()!=Type.PRODUCT)throw invalid("승인 요청의 저장 단계를 확인해 주세요.");
        var actual=client.rawProduct(mapping.sellerProductId());String status=Objects.toString(scalar(actual.path("statusName")),"");
        if(Set.of("승인반려","REJECTED").contains(status))return result(State.ACCEPTED,mapping,"APPROVAL_REJECTED","쿠팡 승인 반려 사유를 확인해 주세요.",previous.attemptedAt());
        if(!Set.of("승인완료","APPROVED").contains(status))return result(State.ACCEPTED,mapping,"APPROVAL_PENDING","판매 승인 결과를 기다리고 있습니다.",previous.attemptedAt());
        var expected=json.readTree(save.expectedJson()).deepCopy().asObject();expected.remove("requested");
        return result(subset(expected,actual)?State.CONFIRMED:State.ACCEPTED,mapping,subset(expected,actual)?"VERIFIED":"REFLECTION_PENDING",subset(expected,actual)?"상품 변경과 판매 승인을 확인했습니다.":"판매 승인은 완료되었으며 변경값 반영 확인이 필요합니다.",previous.attemptedAt());
    }
    public Result reconcile(Long actor,Prepared prepared,Step step,Result previous){
        access.productActor(actor,true);Mapping mapping=previous.mapping()==null?prepared.mapping():previous.mapping();
        if(!validAccount(prepared,mapping))return result(State.FAILED,mapping,"ACCOUNT_CHANGED","쿠팡 계정이 변경되었습니다. 연결 정보를 확인해 주세요.",previous.attemptedAt());
        if(step.type()==Type.CREATE&&mapping.sellerProductId()==null&&Duration.between(previous.attemptedAt(),client.now()).compareTo(Duration.ofMinutes(1))<0)return result(State.UNKNOWN,mapping,"WAIT_RECONCILE","등록 여부 확인을 기다리고 있습니다.",previous.attemptedAt());
        try{return client.writeSession(()->{
            if(step.type()==Type.APPROVAL)return verifyApproval(prepared,step,previous);
            if(step.type()==Type.CREATE&&mapping.sellerProductId()==null){
                var candidates=new LinkedHashSet<String>();for(String sku:prepared.expectedSkus())for(var row:client.rawSummary(sku))candidates.add(scalar(row.path("sellerProductId")));
                for(var id:json.readTree(step.baselineJson()).path("knownSellerProductIds"))candidates.remove(id.asString());
                // SKU/content equality cannot distinguish our lost POST from a concurrent or late-visible registration.
                // Only an ID returned by the registration response is authoritative for automatic mapping.
                return result(State.UNKNOWN,mapping,"UNRESOLVED",candidates.isEmpty()?"등록 여부를 확인하지 못했습니다. 다시 확인해 주세요.":"같은 상품코드의 후보가 있지만 이번 등록 결과인지 확인할 수 없습니다. 새로 등록하지 말고 쿠팡에서 확인해 주세요.",previous.attemptedAt());
            }
            if(step.type()==Type.CREATE)return verifyKnown(prepared,step,previous);
            if("NEEDS_CORRECTION".equals(previous.code()))return previous;
            if(expectedMatches(step,mapping))return result(State.CONFIRMED,mapping,"VERIFIED",previous.code().equals("NEEDS_CORRECTION")?previous.message():"쿠팡 반영을 확인했습니다.",previous.attemptedAt());
            return result(previous.state()==State.ACCEPTED?State.ACCEPTED:State.UNKNOWN,mapping,previous.code(),previous.state()==State.ACCEPTED?"쿠팡 반영을 기다리고 있습니다.":"쿠팡 처리 결과를 확인하지 못했습니다.",previous.attemptedAt());
        });}catch(MarketplaceFailure e){return result(State.UNKNOWN,mapping,e.kind().name(),"쿠팡 처리 결과를 확인하지 못했습니다.",previous.attemptedAt());}
    }
    private ObjectNode createBaseline(List<String> skus){
        var ids=new LinkedHashSet<String>();
        for(String sku:skus)for(var row:client.rawSummary(sku))ids.add(scalar(row.path("sellerProductId")));
        var baseline=json.createObjectNode();var rows=json.createArrayNode();ids.forEach(rows::add);
        baseline.set("knownSellerProductIds",rows);return baseline;
    }
    private boolean validAccount(Prepared p,Mapping m){return client.accountKey().equals(p.accountKey())&&m!=null&&client.accountKey().equals(m.accountKey());}
    private Result verifyKnown(Prepared prepared,Step step,Result accepted){
        Mapping mapping=accepted.mapping();
        if(step.type()==Type.CREATE){
            var actual=client.rawProduct(mapping.sellerProductId());var expected=json.readTree(step.expectedJson());
            if(!Objects.equals(mapping.sellerProductId(),optionalId(actual.path("sellerProductId")))
                    ||!expected.path("vendorId").equals(actual.path("vendorId")))
                return result(State.ACCEPTED,mapping,"MAPPING_CHANGED","등록 상품의 식별자를 확인하지 못했습니다.",accepted.attemptedAt());
            try{mapping=mappingFor(mapping,actual,expected);}
            catch(InputValidationFailure e){return result(State.ACCEPTED,mapping,"CREATE_OPTIONS_UNCONFIRMED","상품은 조회되지만 옵션 연결을 확인하지 못했습니다.",accepted.attemptedAt());}
            String status=Objects.toString(scalar(actual.path("statusName")),"");boolean approved=Set.of("승인완료","APPROVED").contains(status);
            if(Set.of("승인반려","REJECTED").contains(status))return result(State.ACCEPTED,mapping,"APPROVAL_REJECTED","쿠팡 심사에서 반려되었습니다. Wing에서 사유를 확인해 주세요.",accepted.attemptedAt());
            if(Set.of("심사중","승인대기중","부분승인완료","IN_REVIEW","PENDING_APPROVAL").contains(status))
                return result(State.ACCEPTED,mapping,"APPROVAL_PENDING","상품 등록을 확인했습니다. 판매 승인을 기다리고 있습니다.",accepted.attemptedAt());
            if(!approved&&!Set.of("임시저장","SAVED").contains(status))
                return result(State.ACCEPTED,mapping,"CREATE_STATUS_UNCONFIRMED","등록 상품의 현재 상태를 확인하지 못했습니다.",accepted.attemptedAt());
            if(expected.path("requested").asBoolean()&&!approved)
                return result(State.ACCEPTED,mapping,"APPROVAL_PENDING","상품 등록을 확인했습니다. 판매 승인을 기다리고 있습니다.",accepted.attemptedAt());
            if(!cc.ataglace.molebutter.marketplace.api.MarketplaceCreationConfirmation.completeOptions(mapping,
                    prepared.mapping().options().stream().map(OptionMapping::optionId).toList(),approved))
                return result(State.ACCEPTED,mapping,"CREATE_OPTIONS_UNCONFIRMED","상품은 조회되지만 옵션 연결을 확인하지 못했습니다.",accepted.attemptedAt());
            if("NEEDS_CORRECTION".equals(accepted.code())&&!approved)return result(State.ACCEPTED,mapping,accepted.code(),accepted.message(),accepted.attemptedAt());
            // Dates, CDN URLs and content can change after a successful registration.
            // Report those differences without keeping a verified creation locked forever.
            boolean reflected=createApplied(expected,actual);
            return result(State.CONFIRMED,mapping,reflected?"CREATED_CONFIRMED":"CREATED_VALUES_DIFFER",
                    reflected?"쿠팡 등록·옵션 연결을 확인했습니다.":"등록·옵션 연결을 확인했습니다. 현재 상품값이 등록 요청과 다릅니다. 상품 수정에서 최신값을 확인해 주세요.",accepted.attemptedAt());
        }
        if("NEEDS_CORRECTION".equals(accepted.code()))return accepted;
        return expectedMatches(step,mapping)?result(State.CONFIRMED,mapping,"VERIFIED","쿠팡 반영을 확인했습니다.",accepted.attemptedAt()):accepted;
    }
    private boolean baselineMatches(Step step,Mapping mapping){
        if(mapping.sellerProductId()==null)return false;
        JsonNode expected=json.readTree(step.baselineJson());
        if(step.type()==Type.PRODUCT)return expected.equals(client.rawProduct(mapping.sellerProductId()));
        if(step.type()==Type.DELIVERY)return subset(expected,client.rawProduct(mapping.sellerProductId()));
        var option=optionMapping(mapping,step.optionId());var product=client.rawProduct(mapping.sellerProductId());
        boolean owned=false;for(var item:product.path("items"))if(Objects.equals(option.sellerProductItemId(),optionalId(item.path("sellerProductItemId")))&&Objects.equals(option.vendorItemId(),optionalId(item.path("vendorItemId"))))owned=true;
        if(step.type()==Type.ORIGINAL_PRICE)return owned&&subset(expected,remoteOption(product,option));
        return owned&&subset(expected,client.rawInventory(option.vendorItemId()));
    }
    private boolean expectedMatches(Step step,Mapping mapping){
        if(mapping.sellerProductId()==null)return false;
        var expected=json.readTree(step.expectedJson());
        if(step.type()==Type.CREATE)return matchesCreate(expected,client.rawProduct(mapping.sellerProductId()));
        if(step.type()==Type.PRODUCT){
            var actual=client.rawProduct(mapping.sellerProductId());
            boolean requested=step.bodyJson()!=null&&json.readTree(step.bodyJson()).path("requested").asBoolean();
            if(requested&&!Arrays.asList("승인완료","APPROVED").contains(scalar(actual.path("statusName"))))return false;
            // requested is a command flag, not proof that approval has completed.
            if(expected.isObject())expected.asObject().remove("requested");
            return subset(expected,actual);
        }
        if(step.type()==Type.DELIVERY)return subset(expected,client.rawProduct(mapping.sellerProductId()));
        var option=optionMapping(mapping,step.optionId());
        if(step.type()==Type.ORIGINAL_PRICE)return subset(expected,remoteOption(client.rawProduct(mapping.sellerProductId()),option));
        return subset(expected,client.rawInventory(option.vendorItemId(),step.type()==Type.PRICE,step.type()==Type.STOCK));
    }
    private JsonNode remoteOption(JsonNode product,OptionMapping option){
        for(var item:product.path("items"))if(Objects.equals(option.sellerProductItemId(),optionalId(item.path("sellerProductItemId")))&&Objects.equals(option.vendorItemId(),optionalId(item.path("vendorItemId"))))return item;
        return json.createObjectNode();
    }
    Result parseOutcome(byte[] body,Step step,Mapping mapping,Instant attempted){return new CoupangWriteOutcome(json).parseOutcome(body,step,mapping,attempted);}
    Result httpFailure(int status,byte[] body,Mapping mapping,Instant now){return new CoupangWriteOutcome(json).httpFailure(status,body,mapping,now);}
    private Step currentStep(Type type,String optionId,String vendorItemId,String value,JsonNode inventory){
        var before=json.createObjectNode();var after=json.createObjectNode();String key=switch(type){case ORIGINAL_PRICE->"originalPrice";case PRICE->"salePrice";case STOCK->"amountInStock";default->throw new IllegalArgumentException("Not a current value step");};
        value=number(value).toString();before.set(key,inventory.path(key));after.set(key,json.readTree(value));
        String path="/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+vendorItemId+(switch(type){case ORIGINAL_PRICE->"/original-prices/";case PRICE->"/prices/";case STOCK->"/quantities/";default->throw new IllegalArgumentException("Not a current value step");})+value;
        return new Step(UUID.randomUUID().toString(),type,optionId,"PUT",path,type==Type.PRICE?"forceSalePriceUpdate=false":"",null,wire(before),wire(after));
    }
    private ArrayNode images(Long actor,Document d,MarketConfig m,Option o,ObjectNode source){
        var selected=DraftCoupangProjection.images(d,m,o).stream().sorted(Comparator.comparing((Image i)->!i.representative()).thenComparingInt(Image::order)).toList();
        if(source!=null){
            var sourceImages=new ArrayList<String>();for(var row:source.path("images"))sourceImages.add(CoupangProductClient.imageUrl(optionalText(row.path("cdnPath")),optionalText(row.path("vendorPath")))+"|"+scalar(row.path("imageType"))+"|"+scalar(row.path("imageOrder")));
            var desiredImages=selected.stream().map(i->i.url()+"|"+i.imageType()+"|"+i.order()).toList();
            if(sourceImages.equals(desiredImages))return null;

        }
        if(selected.stream().filter(Image::representative).count()!=1||selected.stream().filter(i->i.imageType().equals("DETAIL")).count()>9||selected.stream().filter(i->i.imageType().equals("USED_PRODUCT")).count()>4)throw invalid("옵션마다 대표 이미지 1개, 추가 이미지 9개 이하, 중고상태 이미지 4개 이하가 필요합니다.");
        var rows=json.createArrayNode();int order=0;
        for(var i:selected){
            String url;
            if(i.assetId()!=null){if(inspection!=null)inspection.require(actor,List.of(new cc.ataglace.molebutter.media.api.ImageInspection.Input("COUPANG",i.imageType(),o.id(),i.id(),i.url(),i.assetId(),false)));var a=assets.readEdited(actor,i.assetId()).metadata();var errors=cc.ataglace.molebutter.media.api.ImageRules.errors("COUPANG",i.imageType(),a.mimeType(),a.width(),a.height(),a.bytes());if(!errors.isEmpty())throw invalid(String.join(" ",errors));url=assets.submissionUrl(actor,i.assetId());var edited=json.createObjectNode();edited.put("imageOrder",order++);edited.put("imageType",i.imageType());edited.put("vendorPath",url);rows.add(edited);continue;}
            ObjectNode row=json.createObjectNode();
            if(source!=null)for(var old:source.path("images"))if(Objects.equals(i.url(),CoupangProductClient.imageUrl(optionalText(old.path("cdnPath")),optionalText(old.path("vendorPath")))))row=(ObjectNode)old.deepCopy();
            url=https(i.url());
            if(row.isEmpty()){
                var a=r2.resolve(actor,url);var errors=cc.ataglace.molebutter.media.api.ImageRules.errors("COUPANG",i.imageType(),a.mimeType(),a.width(),a.height(),a.bytes());if(!errors.isEmpty())throw invalid(String.join(" ",errors));
                url=a.url();if(url.length()>200)throw invalid("쿠팡 이미지 주소는 200자 이하로 선택해 주세요.");
            }
            row.put("imageOrder",order++);row.put("imageType",i.imageType());
            if(!row.hasNonNull("cdnPath")&&!row.hasNonNull("vendorPath"))row.put("vendorPath",https(url));
            rows.add(row);
        }
        return rows;
    }
    private ArrayNode contents(Long actor,Document d,MarketConfig m,Option o,ObjectNode source){
        var rows=json.createArrayNode();String override=m.overrides()==null?null:m.overrides().description();
        var selected=override!=null?List.of(new Content("override","HTML",override,o.id())):MarketplaceDocuments.contentsFor(d,o.id());
        if(selected.isEmpty())throw invalid("옵션 설명을 입력해 주세요.");
        String previous=previousDescription(source);
        for(var c:selected){boolean image="IMAGE".equals(c.type());if(inspection!=null)inspection.require(actor,cc.ataglace.molebutter.media.api.ImageInspection.descriptionInputs("COUPANG",image?"<img src=\""+escape(c.value())+"\">":c.value(),previous));String description=cc.ataglace.molebutter.media.api.EditedImages.description(actor,image?"<img src=\""+escape(c.value())+"\">":c.value(),previous,r2,assets);var row=json.createObjectNode();row.put("contentsType",image?"IMAGE":"HTML");var details=json.createArrayNode();var part=json.createObjectNode();part.put("detailType",image?"IMAGE":"TEXT");part.put("content",image?(c.assetId()!=null?assets.submissionUrl(actor,c.assetId()):https(c.value())):publishHtml(actor,d,description));details.add(part);row.set("contentDetails",details);rows.add(row);}return rows;
    }
    private String publishHtml(Long actor,Document d,String html){
        String result=html;
        if(result.contains("/api/marketplaces/assets/")||result.contains("blob:"))throw invalid("기존 로컬 설명 이미지를 R2 이미지로 다시 선택해 주세요.");
        var pattern=java.util.regex.Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*(?:[\\\"']([^\\\"']*)[\\\"']|([^\\s>]+))",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.DOTALL);
        var matcher=pattern.matcher(result);while(matcher.find())https(matcher.group(1)==null?matcher.group(2):matcher.group(1));
        return result;
    }
    private static String previousDescription(ObjectNode source){
        if(source==null)return "";var html=new StringBuilder();for(var content:source.path("contents"))for(var part:content.path("contentDetails")){
            String value=part.path("content").asString("");if(part.path("detailType").asString("").equals("IMAGE")&&!value.startsWith("https://")&&value.matches("(?:vendor_inventory|image)/[a-zA-Z0-9_./%-]+"))value="https://img1a.coupangcdn.com/image/"+value.replaceFirst("^image/","");if(part.path("detailType").asString("").equals("IMAGE"))html.append("<img src=\"").append(escape(value)).append("\">");else html.append(value);
        }return html.toString();
    }
    private boolean sameContents(MarketConfig market,String itemId,Document d,MarketConfig m,Option o){
        var original=original(market,itemId);if(original==null)return false;
        var expected=new ArrayList<String>();for(var c:original.contents()){
            String value=c.content();
            if("IMAGE".equals(c.detailType())){if(value!=null&&!MarketplaceDocuments.https(value)&&value.matches("(?:vendor_inventory|image)/[a-zA-Z0-9_./%-]+"))value="https://img1a.coupangcdn.com/image/"+value.replaceFirst("^image/","");if(value==null)continue;expected.add("IMAGE|"+value);}
            else expected.add("HTML|"+("HTML".equals(c.type())?value:"<pre>"+escape(value)+"</pre>"));
        }
        if(m.overrides()!=null&&m.overrides().description()!=null)return false;
        return expected.equals(MarketplaceDocuments.contentsFor(d,o.id()).stream().map(c->c.type()+"|"+c.value()).toList());
    }
    private cc.ataglace.molebutter.marketplace.api.CoupangEditor.EditOption original(MarketConfig m,String itemId){return m.coupang()==null||m.coupang().source()==null?null:m.coupang().source().options().stream().filter(i->Objects.equals(i.sellerProductItemId(),itemId)).findFirst().orElse(null);}
    private ArrayNode attributeRows(CoupangOption cfg,Option o){
        var rows=json.createArrayNode();if(cfg!=null)for(var a:nullableList(cfg.attributes())){var row=json.createObjectNode();row.put("attributeTypeName",a.name());row.put("attributeValueName",a.value());row.put("exposed",a.exposed());rows.add(row);}
        if(cfg==null)for(var a:o.attributes())if(!rows.valueStream().anyMatch(r->a.name().equals(scalar(r.path("attributeTypeName"))))){var row=json.createObjectNode();row.put("attributeTypeName",a.name());row.put("attributeValueName",a.value());row.put("exposed","EXPOSED");rows.add(row);}return rows;
    }
    private ArrayNode noticeRows(CoupangOption cfg){var rows=json.createArrayNode();if(cfg!=null)for(var n:nullableList(cfg.notices())){var row=json.createObjectNode();row.put("noticeCategoryName",n.category());row.put("noticeCategoryDetailName",n.name());row.put("content",n.content());rows.add(row);}return rows;}
    private ArrayNode certificationRows(CoupangOption cfg){var rows=json.createArrayNode();if(cfg!=null)for(var c:nullableList(cfg.certifications())){if(!nullableList(c.attachments()).isEmpty())throw invalid("인증 첨부의 전송 경로 확인이 필요합니다.");var row=json.createObjectNode();row.put("certificationType",c.type());row.put("certificationCode",c.code());rows.add(row);}return rows;}
    private ArrayNode certificationCodeRows(CoupangOption cfg){var rows=json.createArrayNode();for(var c:nullableList(cfg.certifications())){var row=json.createObjectNode();row.put("certificationType",c.type());row.put("certificationCode",c.code());rows.add(row);}return rows;}
    private ArrayNode documentRows(Coupang cfg,JsonNode current){
        var rows=json.createArrayNode();var names=new HashSet<String>();
        for(var doc:nullableList(cfg.documents())){names.add(doc.templateName());ObjectNode row=json.createObjectNode();for(var old:current)if(Objects.equals(doc.templateName(),scalar(old.path("templateName"))))row=(ObjectNode)old.deepCopy();row.put("templateName",doc.templateName());if(!blank(doc.path())){row.put("documentPath",doc.path());row.remove("vendorDocumentPath");}else if(!blank(doc.vendorPath())){row.put("vendorDocumentPath",https(doc.vendorPath()));row.remove("documentPath");}else throw invalid("구비 서류의 첨부 경로를 입력해 주세요.");rows.add(row);}
        var original=cfg.source()==null?List.<String>of():cfg.source().documents().stream().map(d->d.templateName()).toList();for(var old:current)if(!names.contains(scalar(old.path("templateName")))&&!original.contains(scalar(old.path("templateName"))))rows.add(old.deepCopy());return rows;
    }
    private ArrayNode mergeContents(JsonNode current,ArrayNode next){
        if(!current.isArray()||current.isEmpty())return next;
        if(current.size()!=next.size()){for(var row:current){if(hasUnknown(row,Set.of("contentsType","contentDetails")))throw invalid("기존 설명 부가 정보의 수정 보존 규격 확인이 필요합니다.");for(var part:row.path("contentDetails"))if(hasUnknown(part,Set.of("detailType","content")))throw invalid("기존 설명 세부 정보의 수정 보존 규격 확인이 필요합니다.");}return next;}
        var rows=json.createArrayNode();for(int n=0;n<next.size();n++){var old=current.get(n);var incoming=next.get(n);var row=(ObjectNode)old.deepCopy();row.set("contentsType",incoming.path("contentsType"));var details=json.createArrayNode();var oldParts=old.path("contentDetails");var newParts=incoming.path("contentDetails");
            if(oldParts.size()!=newParts.size()){for(var part:oldParts)if(hasUnknown(part,Set.of("detailType","content")))throw invalid("기존 설명 세부 정보의 수정 보존 규격 확인이 필요합니다.");details=(ArrayNode)newParts.deepCopy();}
            else for(int p=0;p<newParts.size();p++){var part=(ObjectNode)oldParts.get(p).deepCopy();part.set("detailType",newParts.get(p).path("detailType"));part.set("content",newParts.get(p).path("content"));details.add(part);}row.set("contentDetails",details);rows.add(row);
        }return rows;
    }
    private static boolean hasUnknown(JsonNode row,Set<String> names){for(var entry:row.properties())if(!names.contains(entry.getKey()))return true;return false;}
    private ArrayNode merge(JsonNode current,ArrayNode desired,String key,List<String> known){
        var result=json.createArrayNode();var names=new HashSet<String>();for(var row:desired){String name=scalar(row.path(key));names.add(name);ObjectNode merged=(ObjectNode)row.deepCopy();for(var old:current)if(Objects.equals(name,scalar(old.path(key)))){merged=(ObjectNode)old.deepCopy();for(var p:row.properties())merged.set(p.getKey(),p.getValue());}result.add(merged);}
        for(var old:current){String name=scalar(old.path(key));if(!names.contains(name)&&!known.contains(name))result.add(old.deepCopy());}return result;
    }
    private ArrayNode mergeNotices(JsonNode current,ArrayNode desired,List<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Notice> known){
        var result=json.createArrayNode();var keys=new HashSet<String>();var oldKeys=known.stream().map(n->n.category()+"|"+n.name()).toList();
        for(var row:desired){String key=noticeKey(row);keys.add(key);ObjectNode merged=(ObjectNode)row.deepCopy();for(var old:current)if(key.equals(noticeKey(old))){merged=(ObjectNode)old.deepCopy();for(var p:row.properties())merged.set(p.getKey(),p.getValue());}result.add(merged);}for(var old:current)if(!keys.contains(noticeKey(old))&&!oldKeys.contains(noticeKey(old)))result.add(old.deepCopy());return result;
    }
    private static String noticeKey(JsonNode row){return scalar(row.path("noticeCategoryName"))+"|"+scalar(row.path("noticeCategoryDetailName"));}
    private Mapping mappingFor(Mapping original,JsonNode actual,JsonNode expected){
        if(!actual.path("items").isArray()||actual.path("items").size()!=original.options().size()
                ||expected.path("items").size()!=original.options().size())throw invalid("등록 옵션 구성이 변경되었습니다.");
        var options=new ArrayList<OptionMapping>();
        for(int n=0;n<original.options().size();n++){
            JsonNode row=null;var link=original.options().get(n);var wanted=expected.path("items").get(n);
            for(var got:actual.path("items"))if(link.sellerProductItemId()!=null
                    ?Objects.equals(link.sellerProductItemId(),optionalId(got.path("sellerProductItemId")))
                    :wanted.path("externalVendorSku").equals(got.path("externalVendorSku"))&&wanted.path("itemName").equals(got.path("itemName"))){if(row!=null)throw invalid("등록 옵션 연결을 확인해 주세요.");row=got;}
            // Only use a unique seller code inside the product ID returned by the POST.
            // Preserve verified option IDs if display names or seller codes later change.
            if(row==null&&link.sellerProductItemId()==null)for(var got:actual.path("items"))if(wanted.path("externalVendorSku").equals(got.path("externalVendorSku"))){if(row!=null)throw invalid("등록 옵션 연결을 확인해 주세요.");row=got;}
            if(row==null||optionalId(row.path("sellerProductItemId"))==null)throw invalid("등록 옵션 연결을 확인해 주세요.");
            if(link.vendorItemId()!=null&&!Objects.equals(link.vendorItemId(),optionalId(row.path("vendorItemId"))))throw invalid("등록 옵션 번호가 변경되었습니다.");
            options.add(new OptionMapping(original.options().get(n).optionId(),optionalId(row.path("sellerProductItemId")),optionalId(row.path("vendorItemId"))));
        }
        var mapping=new Mapping(original.accountKey(),optionalId(actual.path("sellerProductId")),List.copyOf(options));
        if(!cc.ataglace.molebutter.marketplace.api.MarketplaceCreationConfirmation.completeOptions(mapping,original.options().stream().map(OptionMapping::optionId).toList(),false))throw invalid("등록 옵션 연결이 중복되거나 누락되었습니다.");
        return mapping;
    }
    private boolean matchesCreate(JsonNode expected,JsonNode actual){
        for(String key:List.of("sellerProductName","displayCategoryCode","vendorId"))if(!expected.path(key).equals(actual.path(key)))return false;
        var a=actual.path("items");var e=expected.path("items");if(!a.isArray()||a.size()!=e.size())return false;
        for(var wantedItem:e){
            JsonNode actualItem=null;for(var got:a)if(wantedItem.path("externalVendorSku").equals(got.path("externalVendorSku"))&&wantedItem.path("itemName").equals(got.path("itemName"))){if(actualItem!=null)return false;actualItem=got;}if(actualItem==null)return false;
        }
        return true;
    }
    private static boolean createApprovalApplied(JsonNode expected,JsonNode actual){
        return !expected.path("requested").asBoolean()||Arrays.asList("승인완료","APPROVED").contains(scalar(actual.path("statusName")));
    }
    private boolean createApplied(JsonNode expected,JsonNode actual){
        for(var p:expected.properties()){
            if(Set.of("requested","sellerProductId","productId").contains(p.getKey()))continue; // Approval state and generated IDs are reported independently.
            if("items".equals(p.getKey())){
                for(var wanted:p.getValue()){
                    JsonNode got=null;for(var candidate:actual.path("items"))if(wanted.path("externalVendorSku").equals(candidate.path("externalVendorSku"))&&wanted.path("itemName").equals(candidate.path("itemName")))got=candidate;
                    if(got==null)return false;
                    for(var field:wanted.properties()){
                        if("sellerProductItemId".equals(field.getKey())||"vendorItemId".equals(field.getKey()))continue;
                        if("images".equals(field.getKey())){if(!imagesApplied(field.getValue(),got.path("images")))return false;}
                        else if(Set.of("attributes","notices","certifications").contains(field.getKey())){if(!arrayMembersApplied(field.getValue(),got.path(field.getKey())))return false;}
                        else if(!subset(field.getValue(),got.path(field.getKey())))return false;
                    }
                }
            }else if("requiredDocuments".equals(p.getKey())){if(!arrayMembersApplied(p.getValue(),actual.path(p.getKey())))return false;}
            else if(!subset(p.getValue(),actual.path(p.getKey())))return false;
        }
        return true;
    }
    private static boolean arrayMembersApplied(JsonNode expected,JsonNode actual){if(expected.isArray()&&expected.isEmpty()&&(actual.isMissingNode()||actual.isNull()))return true;if(!actual.isArray())return false;for(var wanted:expected){boolean found=false;for(var got:actual)if(subset(wanted,got))found=true;if(!found)return false;}return true;}
    private static boolean imagesApplied(JsonNode expected,JsonNode actual){
        if(!actual.isArray()||actual.size()!=expected.size())return false;
        for(var wanted:expected){boolean found=false;for(var got:actual){
            boolean samePath=Objects.equals(optionalText(wanted.path("vendorPath")),optionalText(got.path("vendorPath")))&&wanted.path("vendorPath").isString()||Objects.equals(optionalText(wanted.path("cdnPath")),optionalText(got.path("cdnPath")))&&wanted.path("cdnPath").isString();
            if(samePath&&wanted.path("imageType").equals(got.path("imageType"))&&wanted.path("imageOrder").equals(got.path("imageOrder")))found=true;
        }if(!found)return false;}return true;
    }
    private JsonNode delta(JsonNode before,JsonNode after){
        if(before.isObject()&&after.isObject()){var result=json.createObjectNode();for(var p:after.properties())if(!before.path(p.getKey()).equals(p.getValue()))result.set(p.getKey(),delta(before.path(p.getKey()),p.getValue()));return result;}
        if(before.isArray()&&after.isArray()&&before.size()==after.size()){var result=json.createArrayNode();for(int n=0;n<after.size();n++){var row=delta(before.get(n),after.get(n));if(row.isObject()&&!after.get(n).path("sellerProductItemId").isMissingNode())row.asObject().set("sellerProductItemId",after.get(n).path("sellerProductItemId"));result.add(row);}return result;}
        return after.deepCopy();
    }
    private static boolean subset(JsonNode expected,JsonNode actual){
        if(expected.isObject()){for(var p:expected.properties())if(!subset(p.getValue(),actual.path(p.getKey())))return false;return true;}
        if(expected.isArray()){
            if(!actual.isArray()||expected.size()!=actual.size())return false;
            boolean keyed=!expected.isEmpty()&&expected.valueStream().allMatch(row->row.isObject()&&!row.path("sellerProductItemId").isMissingNode());
            for(int n=0;n<expected.size();n++){var wanted=expected.get(n);JsonNode got=actual.get(n);if(keyed){got=null;for(var row:actual)if(wanted.path("sellerProductItemId").equals(row.path("sellerProductItemId"))){if(got!=null)return false;got=row;}if(got==null)return false;}if(!subset(wanted,got))return false;}return true;
        }
        return expected.equals(actual);
    }
    private static OptionMapping optionMapping(Mapping m,String id){return m.options().stream().filter(x->x.optionId().equals(id)).findFirst().orElseThrow(()->invalid("옵션 연결을 확인해 주세요."));}
    private void set(ObjectNode target,String path,String value){
        if(value==null)return;String[] parts=path.split("\\.");ObjectNode row=target;for(int n=0;n<parts.length-1;n++){var child=row.path(parts[n]);if(!child.isMissingNode()&&!child.isObject())throw invalid("기존 "+path+" 설정의 보존 형식을 확인해 주세요.");if(!child.isObject()){child=json.createObjectNode();row.set(parts[n],child);}row=(ObjectNode)child;}
        String key=parts[parts.length-1];if(blank(value)&&row.path(key).isMissingNode())return;
        if(NUMBERS.contains(path)){if(blank(value))return;row.set(key,json.readTree(number(value).toString()));}else if(BOOLS.contains(path)){if(blank(value))return;if(!Set.of("true","false").contains(value))throw invalid(path+"의 참·거짓 값을 확인해 주세요.");row.put(key,Boolean.parseBoolean(value));}else if(path.equals("searchTags")){var tags=json.createArrayNode();for(var tag:value.split(","))if(!tag.isBlank())tags.add(tag.trim());row.set(key,tags);}else row.put(key,value);
    }
    private static JsonNode at(JsonNode row,String path){for(String part:path.split("\\."))row=row.path(part);return row;}
    private static BigInteger number(String v){if(v==null||!v.matches("[0-9]{1,20}"))throw invalid("쿠팡 숫자 입력을 확인해 주세요.");return new BigInteger(v);}
    private static boolean numericEqual(String value,JsonNode n){return n.isIntegralNumber()&&new BigInteger(value).equals(n.bigIntegerValue());}
    private static String scalar(JsonNode n){return n.isMissingNode()||n.isNull()?null:n.isString()?n.asString():n.isNumber()||n.isBoolean()?n.toString():null;}
    private static String optionalText(JsonNode n){return n.isString()?n.asString():null;}
    private static String optionalId(JsonNode n){String v=scalar(n);return v!=null&&v.matches("[0-9]{1,30}")?v:null;}
    private static boolean blank(String v){return v==null||v.isBlank();}
    private static String escape(String v){return v==null?"":v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String https(String value){try{var uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||value.length()>200||privateHost(uri.getHost()))throw new IllegalArgumentException();return uri.toASCIIString();}catch(RuntimeException e){throw invalid("쿠팡에 전달할 공개 HTTPS 이미지 경로를 확인해 주세요.");}}
    private static boolean privateHost(String host){
        String h=host.toLowerCase(Locale.ROOT).replaceFirst("\\.$","");if(h.equals("localhost")||h.endsWith(".localhost")||h.endsWith(".local")||h.endsWith(".internal")||!h.contains("."))return true;
        if(h.matches("[0-9.]+")){String[] parts=h.split("\\.");if(parts.length!=4)return true;int[] p=new int[4];for(int n=0;n<4;n++){p[n]=Integer.parseInt(parts[n]);if(p[n]<0||p[n]>255)return true;}return p[0]==0||p[0]==10||p[0]==127||p[0]==169&&p[1]==254||p[0]==172&&p[1]>=16&&p[1]<=31||p[0]==192&&p[1]==168||p[0]==100&&p[1]>=64&&p[1]<=127||p[0]>=224;}
        if(h.contains(":")){try{var address=java.net.InetAddress.getByName(h);return address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()||h.startsWith("[fc")||h.startsWith("[fd");}catch(java.net.UnknownHostException e){return true;}}
        return false;
    }
    private String wire(JsonNode n){String value=json.writeValueAsString(n);if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>5*1024*1024)throw invalid("쿠팡 전송 내용은 5MiB 이하로 줄여 주세요.");return value;}
    private void diff(String path,JsonNode before,JsonNode after,List<Change> changes){if(path.equals("vendorUserId."))return;if(before.equals(after))return;if(before.isObject()||after.isObject()){var keys=new LinkedHashSet<String>();if(before.isObject())before.properties().forEach(p->keys.add(p.getKey()));if(after.isObject())after.properties().forEach(p->keys.add(p.getKey()));for(var key:keys)diff(path+key+".",before.path(key),after.path(key),changes);return;}if(before.isArray()||after.isArray()){for(int n=0;n<Math.max(before.size(),after.size());n++){var a=before.isArray()&&n<before.size()?before.get(n):json.nullNode();var b=after.isArray()&&n<after.size()?after.get(n):json.nullNode();diff(path+"["+(n+1)+"].",a,b,changes);}return;}String a=summary(before),b=summary(after);if(before.isString()&&after.isString()&&Math.max(before.asString().length(),after.asString().length())>200){String x=before.asString(),y=after.asString();int at=0;while(at<Math.min(x.length(),y.length())&&x.charAt(at)==y.charAt(at))at++;int start=Math.max(0,at-70);a=snippet(x,start);b=snippet(y,start);}changes.add(new Change(path.replaceFirst("\\.$",""),a,b));}
    private static String snippet(String value,int start){start=Math.min(start,value.length());int end=Math.min(value.length(),start+200);return (start>0?"…":"")+value.substring(start,end)+(end<value.length()?"…":"");}
    private String summary(JsonNode n){if(n.isMissingNode()||n.isNull())return null;if(n.isArray()||n.isObject())return "정보 "+n.size()+"개";String v=scalar(n);return v!=null&&v.length()>200?v.substring(0,200)+"…":v;}
    private static Result result(State s,Mapping m,String c,String message,Instant at){return new Result(s,m,c,message,at);}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
