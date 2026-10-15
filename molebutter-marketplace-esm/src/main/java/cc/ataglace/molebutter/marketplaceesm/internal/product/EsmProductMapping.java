package cc.ataglace.molebutter.marketplaceesm.internal.product;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;

/** The misspelled addtional fields and upper/lower site names are contractual. */
final class EsmProductMapping {
    private final ObjectMapper json;
    EsmProductMapping(ObjectMapper json){this.json=json;}
    static final Map<String,String> COMMON=Map.of("name","itemBasicInfo.goodsName.kor","code","itemAddtionalInfo.managedCode","model","itemBasicInfo.catalog.modelName");
    static PartnerEditor.Schema schema(){
        var fields=new ArrayList<PartnerEditor.Field>();String[][] specs={
            {"itemBasicInfo.goodsName.kor","상품명","상품 정보","text","true"},
            {"itemBasicInfo.category.esm.catCode","ESM 카테고리","상품 정보","text","true"},
            {"category.GMARKET","G마켓 카테고리","상품 정보","text","false"},
            {"category.AUCTION","옥션 카테고리","상품 정보","text","false"},
            {"itemBasicInfo.catalog.brandNo","브랜드 코드","상품 정보","number","false"},
            {"itemBasicInfo.catalog.modelName","모델명","상품 정보","text","false"},
            {"itemBasicInfo.catalog.barCode","바코드","상품 정보","text","false"},
            {"itemAddtionalInfo.managedCode","판매자 상품코드","상품 정보","text","false"},
            {"itemAddtionalInfo.shipping.companyNo","택배사","배송·반품","number","true"},
            {"itemAddtionalInfo.shipping.policy.placeNo","출고지","배송·반품","number","true"},
            {"itemAddtionalInfo.shipping.policy.bundle.deliveryTmplId","묶음 배송비 정책","배송·반품","number","true"},
            {"itemAddtionalInfo.shipping.returnAndExchange.addrNo","반품·교환지","배송·반품","number","true"},
            {"itemAddtionalInfo.shipping.returnAndExchange.fee","반품·교환비","배송·반품","number","true"},
            {"itemAddtionalInfo.shipping.dispatchPolicyNo.gmkt","G마켓 발송 정책","배송·반품","number","false"},
            {"itemAddtionalInfo.shipping.dispatchPolicyNo.iac","옥션 발송 정책","배송·반품","number","false"},
            {"itemAddtionalInfo.origin.goodsType","원산지 구분","상품주요정보","number","true"},
            {"itemAddtionalInfo.origin.type","원산지 지역 구분","상품주요정보","number","true"},
            {"itemAddtionalInfo.origin.code","원산지 코드","상품주요정보","text","false"}};
        for(var a:specs)fields.add(new PartnerEditor.Field(a[0],a[1],a[2],a[3],Boolean.parseBoolean(a[4]),List.of()));
        for(String key:List.of("itemAddtionalInfo.isVatFree","itemAddtionalInfo.isAdultProduct","itemAddtionalInfo.origin.isMultipleOrigin","addtionalInfo.siteDiscount.gmkt","addtionalInfo.siteDiscount.iac"))fields.add(new PartnerEditor.Field(key,switch(key){case "itemAddtionalInfo.isVatFree"->"면세 상품";case "itemAddtionalInfo.isAdultProduct"->"성인 상품";case "itemAddtionalInfo.origin.isMultipleOrigin"->"복수 원산지";case "addtionalInfo.siteDiscount.gmkt"->"G마켓 지원 할인";default->"옥션 지원 할인";},"상품주요정보","select",true,List.of("true","false")));
        return new PartnerEditor.Schema(List.copyOf(fields),500,15,"G마켓·옥션의 공유 상품정보는 함께 변경됩니다. 신규 등록은 판매 상태로 노출될 수 있습니다.");
    }
    private JsonNode get(JsonNode node,String path){for(String key:path.split("\\."))node=node.path(key);return node;}
    private void put(ObjectNode root,String path,JsonNode value){String[] keys=path.split("\\.");ObjectNode node=root;for(int n=0;n<keys.length-1;n++){var next=node.path(keys[n]);if(!next.isObject()){next=json.createObjectNode();node.set(keys[n],next);}node=(ObjectNode)next;}node.set(keys[keys.length-1],value);}
    PartnerEditor.Observation read(JsonNode source,String product,String site){
        if(!source.path("itemBasicInfo").isObject()||!source.path("itemAddtionalInfo").isObject())throw invalid("ESM 상품 응답 형식을 확인할 수 없습니다.");
        var fields=new LinkedHashMap<String,Object>();for(var spec:schema().fields()){var value=get(source,spec.key());if(value.isValueNode()&&!value.isNull())fields.put(spec.key(),value.asString());}
        for(var category:get(source,"itemBasicInfo.category.site")){String market=category.path("siteType").asInt()==2?"GMARKET":"AUCTION";fields.put("category."+market,category.path("catCode").asString(""));}
        String priceKey="GMARKET".equals(site)?"Gmkt":"Iac";int type=get(source,"itemAddtionalInfo.recommendedOpts.type").asInt(-1);
        var options=new ArrayList<PartnerEditor.Option>();
        if(type==0)options.add(new PartnerEditor.Option(id("ESM:"+product+":base"),"기본 상품",get(source,"itemAddtionalInfo.managedCode").asString(""),get(source,"itemAddtionalInfo.price."+priceKey).asString(""),get(source,"itemAddtionalInfo.stock."+priceKey).asString(""),List.of(),true));
        var images=new ArrayList<PartnerEditor.Image>();for(int n=0;n<15;n++){String key=n==0?"basicImgURL":"addtionalImg"+n+"URL";String url=get(source,"itemAddtionalInfo.images."+key).asString("");if(!url.isBlank())images.add(new PartnerEditor.Image(id("ESM:"+product+":image:"+n),null,url,n==0,n));}
        var notices=new ArrayList<MarketplaceDrafts.Attribute>();for(var n:get(source,"itemAddtionalInfo.officialNotice.details"))notices.add(new MarketplaceDrafts.Attribute(n.path("officialNoticeItemelementCode").asString(""),n.path("isExtraMark").asBoolean(false)?"상품 상세설명 참조":n.path("value").asString("")));
        var input=normalize(new PartnerEditor.Input(fields,options,images,get(source,"itemAddtionalInfo.descriptions.kor.html").asString(""),get(source,"itemAddtionalInfo.officialNotice.officialNoticeNo").asString(""),notices,List.of()),schema());
        var readonly=new ArrayList<String>();if(!source.path("isEditableGoodsName").asBoolean(true))readonly.add("fields.itemBasicInfo.goodsName.kor");if(type!=0)readonly.add("options");
        return new PartnerEditor.Observation(input,product,Map.of(),List.of(),readonly,type==0?schema().warning():"옵션이 있는 ESM 상품은 현재 보존 모드입니다. 옵션 전문 매핑 확인 전 전송할 수 없습니다.");
    }
    ObjectNode write(PartnerEditor.Input value,JsonNode source,Set<String> sites,java.util.function.Function<PartnerEditor.Image,String> imageUrl){
        var input=normalize(value,schema());validate(input,schema());if(source!=null&&(get(source,"itemAddtionalInfo.shipping.type").asInt(-1)!=1||get(source,"itemAddtionalInfo.shipping.policy.feeType").asInt(-1)!=1))throw invalid("배송 방식 또는 개별 배송비 상품은 현재 보존 모드입니다.");if(sites.isEmpty()||!Set.of("GMARKET","AUCTION").containsAll(sites))throw invalid("ESM 전송 사이트를 확인해 주세요.");
        if(source!=null&&get(source,"itemAddtionalInfo.recommendedOpts.type").asInt(-1)!=0)throw invalid("ESM 옵션 상품의 전문 매핑을 확인한 후 저장할 수 있습니다.");
        if(input.description().contains("/api/marketplaces/assets/")||input.description().contains("blob:"))throw invalid("설명 이미지의 공개 주소 변환을 완료해 주세요.");
        if(java.util.regex.Pattern.compile("(?is)<\\s*(script|iframe)\\b|max-width\\s*:").matcher(input.description()).find())throw invalid("ESM 설명에는 스크립트, 아이프레임 또는 최대 너비 스타일을 사용할 수 없습니다.");
        if(input.options().size()!=1||!input.options().getFirst().attributes().isEmpty())throw invalid("ESM 옵션 코드 매핑을 먼저 선택해 주세요.");
        if(!input.certifications().isEmpty())throw invalid("ESM 인증 유형별 매핑 확인이 필요합니다.");
        if(source!=null&&!source.path("isEditableGoodsName").asBoolean(true)&&!get(source,"itemBasicInfo.goodsName.kor").asString("").equals(Objects.toString(input.fields().get("itemBasicInfo.goodsName.kor"),"")))throw invalid("이 ESM 상품은 상품명을 수정할 수 없습니다.");
        String name=Objects.toString(input.fields().get("itemBasicInfo.goodsName.kor"),"");if(name.getBytes(StandardCharsets.UTF_8).length>100)throw invalid("ESM 상품명은 100바이트 이내로 입력해 주세요.");
        ObjectNode root=source==null?json.createObjectNode():((ObjectNode)source).deepCopy();root.remove(List.of("goodsNo","siteGoodsNo","isEditableGoodsName"));
        for(var spec:schema().fields()){if(spec.key().startsWith("category."))continue;Object valueAt=input.fields().get(spec.key());if(valueAt==null)continue;String text=valueAt.toString();if(text.isBlank()&&"number".equals(spec.type()))continue;JsonNode wire=switch(spec.type()){case "number"->json.valueToTree(number(text,0,9999999999L,spec.label()));case "select"->json.valueToTree(Boolean.parseBoolean(text));default->json.valueToTree(text);};put(root,spec.key(),wire);}
        var category=json.createArrayNode();for(String market:new TreeSet<>(sites)){String code=Objects.toString(input.fields().get("category."+market),"");if(code.isBlank())throw invalid("전송 사이트의 카테고리를 선택해 주세요.");category.add(json.valueToTree(Map.of("siteType","GMARKET".equals(market)?2:1,"catCode",code)));}put(root,"itemBasicInfo.category.site",category);
        var option=input.options().getFirst();if(Boolean.FALSE.equals(option.usable()))throw invalid("ESM 판매 상태는 전용 상태 요청으로 변경해야 합니다.");long price=number(option.price(),10,999999990,"ESM 판매가");if(price%10!=0)throw invalid("ESM 판매가는 10원 단위로 입력해 주세요.");long quantity=number(option.quantity(),1,99999,"ESM 재고");
        for(String market:sites){String suffix="GMARKET".equals(market)?"Gmkt":"Iac";put(root,"itemAddtionalInfo.price."+suffix,json.valueToTree(price));put(root,"itemAddtionalInfo.stock."+suffix,json.valueToTree(quantity));put(root,"itemAddtionalInfo.sellingPeriod."+suffix,json.valueToTree(source==null?-1:0));String policy="itemAddtionalInfo.shipping.dispatchPolicyNo."+("GMARKET".equals(market)?"gmkt":"iac");if(Objects.toString(input.fields().get(policy),"").isBlank())throw invalid("전송 사이트의 발송 정책을 선택해 주세요.");}
        put(root,"itemAddtionalInfo.recommendedOpts.type",json.valueToTree(0));put(root,"itemAddtionalInfo.shipping.type",json.valueToTree(1));put(root,"itemAddtionalInfo.shipping.policy.feeType",json.valueToTree(1));
        put(root,"itemAddtionalInfo.officialNotice",json.valueToTree(Map.of("officialNoticeNo",input.noticeType(),"details",input.notices().stream().map(a->Map.of("officialNoticeItemelementCode",a.name(),"value",a.value(),"isExtraMark",false)).toList())));
        ObjectNode images=json.createObjectNode();int n=0;var urls=new HashSet<String>();for(var image:input.images().stream().sorted(Comparator.comparing((PartnerEditor.Image i)->!i.representative()).thenComparingInt(PartnerEditor.Image::order)).toList()){String url=imageUrl.apply(image);if(!MarketplaceDocuments.https(url))throw invalid("공개 이미지 주소를 확인해 주세요.");if(sites.contains("AUCTION")&&!urls.add(url))throw invalid("옥션은 중복 이미지 주소를 사용할 수 없습니다.");images.put(n==0?"basicImgURL":"addtionalImg"+n+"URL",url);n++;}put(root,"itemAddtionalInfo.images",images);
        put(root,"itemAddtionalInfo.descriptions.kor",json.valueToTree(Map.of("type",2,"html",input.description())));if(source==null)root.remove("isSell");return root;
    }
}
