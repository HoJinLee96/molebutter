package cc.ataglace.molebutter.marketplacenaver.internal;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

final class NaverDeliveryPolicy {
    private static final String P="originProduct.deliveryInfo.";
    static void verify(NaverEditor.Input input,Function<Map<String,String>,JsonNode> read){
        var f=input.fields();String area=P+"deliveryFee.deliveryFeeByArea.";
        boolean hasFees=f.containsKey(area+"area2extraFee")||f.containsKey(area+"area3extraFee");
        if(!Boolean.TRUE.equals(f.get(P+"deliveryBundleGroupUsable"))&&Objects.toString(f.get(P+"deliveryBundleGroupId"),"").isBlank())return;
        if(Objects.toString(f.get(P+"deliveryBundleGroupId"),"").isBlank()&&!hasFees)return;
        String id=Objects.toString(f.get(P+"deliveryBundleGroupId"),"");if(!id.matches("[1-9][0-9]{0,18}"))throw new MarketplaceSettingsFailure("markets.NAVER.naver.editorInput.fields.originProduct.deliveryInfo.deliveryBundleGroupId","지역 추가 배송비를 적용할 기존 묶음배송 그룹을 조회·선택해 주세요.");
        var response=read.apply(Map.of("groupId",id));if(response==null)throw new MarketplaceSettingsFailure("markets.NAVER.naver.editorInput.fields.originProduct.deliveryInfo.deliveryBundleGroupId","묶음배송 그룹을 확인하지 못했습니다. 다시 조회해 주세요.");
        var group=response.has("deliveryBundleGroup")?response.path("deliveryBundleGroup"):response;
        if(!group.path("id").asString("").equals(id)||!group.path("usable").isBoolean()||!group.path("usable").asBoolean())throw new MarketplaceSettingsFailure("markets.NAVER.naver.editorInput.fields.originProduct.deliveryInfo.deliveryBundleGroupId","사용 가능한 묶음배송 그룹을 다시 선택해 주세요.");
        if(!hasFees)return;
        var fees=group.path("deliveryFeeByArea");String zone=fees.path("deliveryAreaType").asString("");if(!Set.of("AREA_2","AREA_3").contains(zone))throw new MarketplaceSettingsFailure("markets.NAVER.naver.editorInput.fields.originProduct.deliveryInfo.deliveryBundleGroupId","묶음배송 그룹의 지역별 배송비를 확인하지 못했습니다.");
        MarketplaceCommonSettings.equalPolicy("제주 추가 배송비",Objects.toString(f.get(area+"area2extraFee"),""),fees.path("area2extraFee").asString(""));
        MarketplaceCommonSettings.equalPolicy("제주 외 도서산간 추가 배송비",Objects.toString(f.get(area+"area3extraFee"),""),fees.path(zone.equals("AREA_2")?"area2extraFee":"area3extraFee").asString(""));
    }
}
