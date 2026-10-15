package cc.ataglace.molebutter.marketplacenaver.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;

/** 요청 규격 검사만 담당하며 외부 조회·이미지 발행·실행 기록에 접근하지 않는다. */
final class NaverWriteValidation {
    private final ObjectMapper json;
    NaverWriteValidation(ObjectMapper json) { this.json = json; }
    private static InputValidationFailure invalid(String message) { return new InputValidationFailure(message); }
    private static boolean changedField(List<MarketplaceEditing.Change> changes,String path){return changes.stream().anyMatch(c->c.path().equals(NaverEditPatch.PREFIX+"fields."+path));}
    void validateInput(NaverEditor.Input input,boolean create){
        var body=json.createObjectNode();body.set("originProduct",json.createObjectNode());body.set("smartstoreChannelProduct",json.createObjectNode());
        input.fields().forEach((k,v)->NaverEditPatch.put(body,k,json.valueToTree(v)));
        var origin=body.path("originProduct").asObject();origin.put("detailContent",input.description());
        var images=json.createObjectNode();var optional=json.createArrayNode();for(var image:input.images()){var row=json.createObjectNode().put("url",image.url()==null?"server-edit":image.url());if(image.representative())images.set("representativeImage",row);else optional.add(row);}images.set("optionalImages",optional);origin.set("images",images);
        if("COMBINATION".equals(input.optionMode())){
            var info=json.createObjectNode();var names=json.createObjectNode();for(int n=0;n<input.optionNames().size();n++)names.put("optionGroupName"+(n+1),input.optionNames().get(n));info.set("optionCombinationGroupNames",names);info.put("useStockManagement",true);
            var rows=json.createArrayNode();long total=0;for(var option:input.options()){var row=json.createObjectNode();for(int n=0;n<option.values().size();n++)row.put("optionName"+(n+1),option.values().get(n));if(option.price()!=null)row.put("price",option.price());if(option.stockQuantity()!=null)row.put("stockQuantity",option.stockQuantity());row.put("usable",option.usable()==null||option.usable());row.put("sellerManagerCode",option.sellerManagerCode());rows.add(row);if(option.usable()==null||option.usable())total=Math.addExact(total,option.stockQuantity()==null?0:option.stockQuantity());}info.set("optionCombinations",rows);origin.put("stockQuantity",total);NaverEditPatch.put(body,"originProduct.detailAttribute.optionInfo",info);
        }
        validate(body,create,input);
    }
    void validate(ObjectNode body,boolean create,NaverEditor.Input input){
        validate(body,create,input,List.of());
    }
    void validate(ObjectNode body,boolean create,NaverEditor.Input input,List<MarketplaceEditing.Change> changes){
        var origin=body.path("originProduct");var detail=origin.path("detailAttribute");
        required(origin,"name");required(origin,"leafCategoryId");if(create)required(origin,"detailContent");
        String status=origin.path("statusType").asString("");
        if(create&&!status.equals("SALE"))throw invalid("신규 상품은 판매 중으로 등록됩니다. 노출을 중지하려면 채널 전시를 중지해 주세요.");
        if(!create&&!Set.of("SALE","SUSPENSION").contains(status))throw invalid(changedField(changes,"originProduct.statusType")?"상품 수정의 판매 상태는 판매 중 또는 판매 중지만 선택할 수 있습니다.":"현재 판매 상태("+status+")를 유지하는 상품 수정은 지원하지 않습니다. 스마트스토어센터에서 상태를 확인해 주세요. 저장 과정에서 판매 중으로 강제 변경하지 않습니다.");
        number(origin.path("salePrice"),0,999999990,"판매가");if(create||origin.has("stockQuantity"))number(origin.path("stockQuantity"),0,99999999,"재고");
        var images=origin.path("images");required(images.path("representativeImage"),"url");if(input.images().stream().filter(NaverEditor.Image::representative).count()!=1||images.path("optionalImages").size()>9)throw invalid("대표 이미지는 1개, 추가 이미지는 9개까지 등록할 수 있습니다.");
        var after=detail.path("afterServiceInfo");required(after,"afterServiceTelephoneNumber");required(after,"afterServiceGuideContent");
        var area=detail.path("originAreaInfo");required(area,"originAreaCode");if(area.path("originAreaCode").asString("").startsWith("02"))required(area,"importer");if(area.path("originAreaCode").asString("").startsWith("04"))required(area,"content");
        bool(detail.path("minorPurchasable"),"구매 연령");
        var channel=body.path("smartstoreChannelProduct");bool(channel.path("naverShoppingRegistration"),"네이버 쇼핑 등록 여부");if(!Set.of("ON","SUSPENSION").contains(channel.path("channelProductDisplayStatusType").asString("")))throw invalid("스마트스토어 전시 상태는 전시 중 또는 중지로 선택해 주세요.");
        if(create||detail.has("productInfoProvidedNotice")){var notice=detail.path("productInfoProvidedNotice");required(notice,"productInfoProvidedNoticeType");String key=NaverEditPatch.noticeKey(notice.path("productInfoProvidedNoticeType").asString(""));if(key==null||!notice.path(key).isObject())throw invalid("선택한 상품정보제공고시를 입력해 주세요.");for(String field:NaverEditPatch.NOTICE_REQUIRED.getOrDefault(key,Set.of())){if(Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents").contains(field))continue;var v=notice.path(key).path(field);if(v.isMissingNode()||v.isNull()||v.isString()&&v.asString().isBlank())throw invalid("필수 상품정보제공고시 항목을 입력해 주세요: "+field);}}
        for(var certification:detail.path("productCertificationInfos"))number(certification.path("certificationInfoId"),1,Long.MAX_VALUE,"인증 유형");
        for(var attribute:detail.path("productAttributes")){number(attribute.path("attributeValueSeq"),1,Long.MAX_VALUE,"검색 속성 값");if(attribute.has("attributeSeq"))number(attribute.path("attributeSeq"),1,Long.MAX_VALUE,"검색 속성");}
        for(String path:List.of("originProduct.detailAttribute.naverShoppingSearchInfo.brandId","originProduct.detailAttribute.naverShoppingSearchInfo.modelId","originProduct.deliveryInfo.claimDeliveryInfo.shippingAddressId","originProduct.deliveryInfo.claimDeliveryInfo.returnAddressId","originProduct.deliveryInfo.deliveryBundleGroupId","smartstoreChannelProduct.bbsSeq")){var v=NaverEditPatch.at(body,path);if(!v.isMissingNode()&&!v.isNull())number(v,1,Long.MAX_VALUE,path);}
        var quantity=detail.path("purchaseQuantityInfo");for(String key:List.of("minPurchaseQuantity","maxPurchaseQuantityPerOrder"))if(quantity.has(key))number(quantity.path(key),0,10000,"구매 수량");if(quantity.has("maxPurchaseQuantityPerId"))number(quantity.path("maxPurchaseQuantityPerId"),0,99999999,"1인 구매 수량");
        if(detail.has("taxType")&&!Set.of("TAX","DUTYFREE","SMALL").contains(detail.path("taxType").asString("")))throw invalid("부가세 유형을 확인해 주세요.");
        if(detail.path("seoInfo").path("pageTitle").asString("").length()>100||detail.path("seoInfo").path("metaDescription").asString("").length()>160)throw invalid("검색 제목은 100자, 설명은 160자 이하로 입력해 주세요.");
        if("COMBINATION".equals(input.optionMode())){
            if(input.optionNames().isEmpty()||input.optionNames().size()>3||input.optionNames().stream().anyMatch(String::isBlank)||new HashSet<>(input.optionNames()).size()!=input.optionNames().size()||input.options().isEmpty())throw invalid("구매 옵션명은 중복 없이 1~3개 입력하고 옵션을 추가해 주세요.");
            var combinations=new HashSet<List<String>>();long total=0;for(var o:input.options()){
                if(o.values().size()!=input.optionNames().size()||o.values().stream().anyMatch(String::isBlank)||!combinations.add(o.values()))throw invalid("구매 옵션 조합의 값이 비어 있거나 중복되었습니다.");
                if(o.price()==null||o.stockQuantity()==null)throw invalid("각 옵션의 추가 금액과 재고를 확인해 주세요.");
                number(json.valueToTree(o.price()),-999999990L,999999990L,"옵션 추가 금액");number(json.valueToTree(o.stockQuantity()),0,99999999,"옵션 재고");
                if(origin.path("salePrice").asLong()+o.price()<0||origin.path("salePrice").asLong()+o.price()>999999990)throw invalid("판매가와 옵션 추가 금액의 합계를 확인해 주세요.");
                if(o.usable()==null||o.usable())total=Math.addExact(total,o.stockQuantity());
            }if(total>99999999)throw invalid("판매 가능한 옵션의 전체 재고가 허용 범위를 초과했습니다.");
        }else if("NONE".equals(input.optionMode())&&(!input.options().isEmpty()||!input.optionNames().isEmpty()))throw invalid("옵션 없는 상품은 구매 옵션을 비워 주세요.");
        var delivery=origin.path("deliveryInfo");if(delivery.isObject()){
            if(!Set.of("DELIVERY","DIRECT").contains(delivery.path("deliveryType").asString("")))throw invalid("배송 방식을 확인해 주세요.");
            if(delivery.path("deliveryType").asString("").equals("DELIVERY"))required(delivery,"deliveryCompany");
            var fee=delivery.path("deliveryFee");if(!fee.isObject())throw invalid("배송비 설정을 입력해 주세요.");
            if((create||changes.stream().anyMatch(c->c.path().contains(".deliveryInfo.deliveryFee.")))&&fee.isObject()){if(fee.path("deliveryFeeType").asString("").equals("FREE"))fee.asObject().put("baseFee",0);if(!fee.path("deliveryFeeType").asString("").equals("CONDITIONAL_FREE"))fee.asObject().put("freeConditionalAmount",0);}
            if(fee.has("baseFee"))number(fee.path("baseFee"),0,100000,"기본 배송비");if(fee.has("freeConditionalAmount"))number(fee.path("freeConditionalAmount"),0,999999990,"무료배송 기준");
            var region=fee.path("deliveryFeeByArea");if(region.isObject()){String zone=region.path("deliveryAreaType").asString("");if(!Set.of("AREA_2","AREA_3").contains(zone))throw invalid("지역별 배송비 권역을 선택해 주세요.");number(region.path("area2extraFee"),0,200000,"제주 추가 배송비");if(zone.equals("AREA_3"))number(region.path("area3extraFee"),0,200000,"제주 외 도서산간 추가 배송비");}
            var claims=delivery.path("claimDeliveryInfo");number(claims.path("returnDeliveryFee"),0,1000000,"반품 배송비");number(claims.path("exchangeDeliveryFee"),0,1000000,"교환 배송비");
        }
        var discount=origin.path("customerBenefit").path("immediateDiscountPolicy").path("discountMethod");if(discount.isObject()){
            var value=discount.path("value");if(value.isMissingNode()||value.isNull()){origin.path("customerBenefit").asObject().remove("immediateDiscountPolicy");}
            else {decimal(value,BigDecimal.ONE,new BigDecimal("10000000"),"즉시 할인");String unit=discount.path("unitType").asString("");if(!Set.of("WON","PERCENT").contains(unit))throw invalid("할인 단위는 원 또는 퍼센트로 입력해 주세요.");if(unit.equals("PERCENT")&&value.asDouble()>100||unit.equals("WON")&&value.asDouble()>origin.path("salePrice").asDouble())throw invalid("할인 값이 판매 가격을 초과했습니다.");if(create||changedField(changes,"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.startDate"))time(discount.path("startDate"),10,0,"할인 시작일");if(create||changedField(changes,"originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.endDate"))time(discount.path("endDate"),10,9,"할인 종료일");}
        }
        if(create||changedField(changes,"originProduct.saleStartDate"))time(origin.path("saleStartDate"),60,0,"판매 시작일");if(create||changedField(changes,"originProduct.saleEndDate"))time(origin.path("saleEndDate"),60,59,"판매 종료일");
    }
    private static void required(JsonNode parent,String field){if(!parent.path(field).isString()||parent.path(field).asString().isBlank())throw invalid("필수 항목을 입력해 주세요: "+field);}
    private static void number(JsonNode value,long min,long max,String label){if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<min||value.asLong()>max)throw invalid(label+"는 "+min+"~"+max+" 범위의 정수로 입력해 주세요.");}
    private static void decimal(JsonNode value,BigDecimal min,BigDecimal max,String label){try{var d=new BigDecimal(value.asString());if(!value.isNumber()||d.compareTo(min)<0||d.compareTo(max)>0)throw new IllegalArgumentException();}catch(RuntimeException e){throw invalid(label+" 값을 확인해 주세요.");}}
    private static void bool(JsonNode value,String label){if(!value.isBoolean())throw invalid(label+"의 선택 값을 확인해 주세요.");}
    private static void time(JsonNode value,int interval,int offset,String label){if(value.isMissingNode()||value.isNull()||value.isString()&&value.asString().isBlank())return;try{var date=OffsetDateTime.parse(value.asString());if(date.getMinute()%interval!=offset||date.getSecond()!=0||date.getNano()!=0)throw new IllegalArgumentException();}catch(RuntimeException e){throw invalid(label+"의 시간·분과 시간대 형식을 확인해 주세요.");}}
}
