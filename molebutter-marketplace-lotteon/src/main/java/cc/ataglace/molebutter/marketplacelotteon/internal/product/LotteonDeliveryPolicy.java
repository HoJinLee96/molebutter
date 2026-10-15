package cc.ataglace.molebutter.marketplacelotteon.internal.product;
import java.util.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

final class LotteonDeliveryPolicy {
    static void verify(Long actor,LotteonCatalog catalog,LotteonInput input){
        var requested=input.deliveryIntent();if(requested==null||requested.isEmpty())return;
        var fields=input.product().fields();String code=Objects.toString(fields.get("dvCstPolNo"),"");
        var rows=catalog.metadata(actor,"shipping-policies",Objects.toString(fields.get("lrtrNo"),""),0).stream().filter(r->r.id().equals(code)).toList();
        if(rows.size()!=1)throw new MarketplaceSettingsFailure("lotteon.product.fields.dvCstPolNo","공통 배송비를 비교할 사용 가능한 롯데ON 배송비 정책을 선택해 주세요.");
        var values=rows.getFirst().data();String type=requested.getOrDefault("chargeType","");String expected=Map.of("FREE","B","NOT_FREE","A","CONDITIONAL_FREE","C").get(type);
        if(!type.isBlank()&&(expected==null||!expected.equals(Objects.toString(values.get("dvCstDvsCd"),""))))throw new MarketplaceSettingsFailure("lotteon.product.fields.dvCstPolNo","배송비 유형이 공통값과 다릅니다. 맞는 정책을 선택하거나 선택한 정책 금액 사용을 선택해 주세요.");
        for(var e:Map.of("charge","dvCst","freeOver","cndlFreeStdAmt","returnCharge","rcst","jejuCharge","jejuAdtnDvCst","remoteCharge","inrmAdtnDvCst").entrySet()){
            if(e.getKey().equals("freeOver")&&!type.equals("CONDITIONAL_FREE")||e.getKey().equals("charge")&&type.equals("FREE"))continue;
            MarketplaceCommonSettings.equalPolicy(Map.of("charge","기본 배송비","freeOver","무료배송 기준 금액","returnCharge","회수 배송비","jejuCharge","제주 추가 배송비","remoteCharge","제주 외 도서산간 추가 배송비").get(e.getKey()),requested.get(e.getKey()),values.get(e.getValue()));
        }
        // The response has no separate initial outbound return amount. Do not invent its meaning.
        if(requested.containsKey("initialReturnCharge"))throw new MarketplaceSettingsFailure("lotteon.product.fields.dvCstPolNo","롯데ON 정책의 초도 반품 배송비를 별도 확인할 수 없습니다. 정책을 확인하고 선택한 정책 금액 사용을 선택해 주세요.");
    }
}
