package cc.ataglace.molebutter.marketplacecoupang.internal;


import java.math.BigInteger;
import java.util.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplacecoupang.internal.DraftCoupangProjection.*;

/** Creation-only checks shared by input validation and the final wire preparation boundary. */
final class CoupangCreateValidation {
    private CoupangCreateValidation() {}
    static List<Issue> issues(Document document,MarketConfig market){
        var issues=new ArrayList<Issue>();
        var combinations=new HashSet<Map<String,String>>();
        for(int index=0;index<document.options().size();index++){
            var option=document.options().get(index);var config=optionConfig(market,option);
            String path="markets.COUPANG.coupang.options."+index;
            // Match attributeRows: configured attributes take precedence and common attributes
            // not already configured are sent as exposed purchase attributes.
            var attributes=new LinkedHashMap<String,String>();var configured=new HashSet<String>();
            if(config!=null)for(var attribute:nullableList(config.attributes())){
                configured.add(attribute.name());
                if(!"NONE".equals(attribute.exposed()))attributes.put(attribute.name(),attribute.value());
            }
            for(var attribute:option.attributes())if(configured.add(attribute.name()))attributes.put(attribute.name(),attribute.value());
            if(!attributes.isEmpty()&&!combinations.add(Map.copyOf(attributes)))
                issues.add(new Issue("COUPANG",path+".attributes","구매 옵션 조합이 중복되었습니다. 옵션별 색상·사이즈 등 구매 속성을 다르게 입력해 주세요."));
            var registration=registration(document,market,option);String minimum=registration.get("autoPricingInfo.minSalePrice");
            var override=override(market,option);String price=override==null?option.price():inherited(override.price(),option.price());
            if(minimum!=null&&!minimum.isBlank()&&(!minimum.matches("[0-9]{1,16}")||price==null||!price.matches("[0-9]{1,16}")||new BigInteger(minimum).compareTo(new BigInteger(price))>=0))
                issues.add(new Issue("COUPANG",path+".registration.autoPricingInfo.minSalePrice","자동 가격 최소 판매가는 판매가보다 작은 0 이상의 정수로 입력해 주세요."));
        }
        return List.copyOf(issues);
    }
}
