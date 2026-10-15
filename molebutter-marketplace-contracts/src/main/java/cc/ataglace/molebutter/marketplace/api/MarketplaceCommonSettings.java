package cc.ataglace.molebutter.marketplace.api;

import java.time.LocalDateTime;
import java.util.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Common intent is not a provider code or a shared seller-policy mutation. */
public final class MarketplaceCommonSettings {
    private MarketplaceCommonSettings() {}
    private static final Map<String,String> ELEVEN=Map.of("CJGLS","00034","HYUNDAI","00012","HANJIN","00011","KGB","00002","EPOST","00007","KDEXP","00026");
    private static final Map<String,String> LOTTE=Map.of("CJGLS","0002","HYUNDAI","0001","HANJIN","0006","KGB","0005","EPOST","0004","KDEXP","0024");
    public static String carrier(String market,String value){
        if(MarketplaceDocuments.blank(value))return "";
        var codes=switch(market){case "ELEVENST"->ELEVEN;case "LOTTEON"->LOTTE;default->null;};
        return codes==null?value:codes.getOrDefault(value,"");
    }
    public static String service(Common c){
        return java.util.stream.Stream.of(c.afterServiceManager(),c.afterServiceTelephone(),c.afterService()).filter(v->!MarketplaceDocuments.blank(v)).reduce((a,b)->a+" / "+b).orElse("");
    }
    public static String serviceContact(Common c){return java.util.stream.Stream.of(c.afterServiceManager(),c.afterServiceTelephone()).filter(v->!MarketplaceDocuments.blank(v)).reduce((a,b)->a+" / "+b).orElse(c.afterService());}
    public static Map<String,String> partnerFields(String market,Document d){
        var c=d.common();var v=d.delivery();var f=new LinkedHashMap<String,String>();boolean lotte=market.equals("LOTTEON");
        put(f,lotte?"tdfDvsCd":"suplDtyfrPrdClfCd",Set.of("FREE","DUTYFREE").contains(Objects.toString(c.taxType(),""))?"02":"TAX".equals(c.taxType())?"01":"");
        put(f,lotte?"asContents":"asDetail",service(c));put(f,lotte?"hdcCd":"dlvEtprsCd",carrier(market,v.carrier()));
        put(f,lotte?"cmbnDvPsbYn":"bndlDlvCnYn",MarketplaceDocuments.blank(v.bundle())?"":v.bundle().equals("UNION_DELIVERY")?"Y":"N");
        if(lotte){put(f,"dvMnsCd",Map.of("SEQUENCIAL","DPCL","VENDOR_DIRECT","DGNN_DV").getOrDefault(Objects.toString(v.method(),""),""));put(f,"sndBgtNday",v.shippingDays());put(f,"slStrtDttm",compact(c.saleStartedAt()));put(f,"slEndDttm",compact(c.saleEndedAt()));}
        else{
            put(f,"dlvCnAreaCd",MarketplaceDocuments.blank(v.remoteArea())?"":v.remoteArea().equals("Y")?"01":"02");put(f,"dlvWyCd",Map.of("SEQUENCIAL","01","VENDOR_DIRECT","03").getOrDefault(Objects.toString(v.method(),""),""));
            put(f,"jejuDlvCst",v.jejuCharge());put(f,"islandDlvCst",v.remoteCharge());put(f,"rtngdDlvCst",v.returnCharge());
            var type=Objects.toString(v.chargeType(),"");put(f,"dlvCstInstBasiCd",Map.of("FREE","01","NOT_FREE","02","CONDITIONAL_FREE","03","CHARGE_RECEIVED","02").getOrDefault(type,""));
            if(!type.isBlank()){put(f,"dlvCstPayTypCd",type.equals("CHARGE_RECEIVED")?"02":"03");put(f,"dlvCst1",type.equals("FREE")?"0":v.charge());put(f,"PrdFrDlvBasiAmt",type.equals("CONDITIONAL_FREE")?v.freeOver():"0");}
            // Keep unsupported precision visible; the provider validator rejects it rather than truncating.
            put(f,"aplBgnDy",elevenDate(c.saleStartedAt(),false));put(f,"aplEndDy",elevenDate(c.saleEndedAt(),true));
            if(!MarketplaceDocuments.blank(c.saleStartedAt())||!MarketplaceDocuments.blank(c.saleEndedAt())){put(f,"selTermUseYn","Y");put(f,"selPrdClfCd","0:100");}
        }
        return f;
    }
    private static String elevenDate(String value,boolean end){if(MarketplaceDocuments.blank(value))return "";LocalDateTime time;try{time=LocalDateTime.parse(value);}catch(RuntimeException e){return value;}if(!end&&time.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)||end&&time.getHour()==23&&time.getMinute()==59&&(time.getSecond()==0||time.getSecond()==59)&&time.getNano()==0)return time.toLocalDate().toString().replace('-','/');return value;}
    private static String compact(String value){if(MarketplaceDocuments.blank(value))return "";try{return LocalDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss"));}catch(RuntimeException e){return value;}}
    private static void put(Map<String,String> f,String key,String value){if(!MarketplaceDocuments.blank(value))f.put(key,value);}
    public static List<Issue> unapplied(Document d,String market){var out=new ArrayList<Issue>();var v=d.delivery();
        if(market.equals("NAVER")){if(!MarketplaceDocuments.blank(v.shippingDays()))out.add(new Issue(market,"delivery.shippingDays","공통 출고 소요일은 스마트스토어 일반배송에 직접 대응하지 않습니다."));if(!MarketplaceDocuments.blank(v.remoteArea()))out.add(new Issue(market,"delivery.remoteArea","스마트스토어 배송 가능 지역은 해당 마켓의 배송 설정에서 확인해 주세요."));}
        if(Set.of("NAVER","ELEVENST").contains(market)&&!MarketplaceDocuments.blank(v.initialReturnCharge()))out.add(new Issue(market,"delivery.initialReturnCharge","초도 배송비는 별도 금액으로 자동 적용하지 않습니다. 해당 마켓의 무료배송 반품 부과 방식과 교환 배송비를 확인해 주세요."));
        if(market.equals("ELEVENST")&&!MarketplaceDocuments.blank(v.shippingDays()))out.add(new Issue(market,"delivery.shippingDays","공통 소요일을 11번가 발송마감 템플릿 번호로 변환하지 않습니다. 기존 템플릿을 선택해 주세요."));
        if(market.equals("LOTTEON")&&!MarketplaceDocuments.blank(v.remoteArea()))out.add(new Issue(market,"delivery.remoteArea","롯데ON 배송 가능 지역은 조회한 배송 지역 그룹에서 선택해 주세요."));return List.copyOf(out);
    }
    public static void validate(Document d){
        var c=d.common();var delivery=d.delivery();
        if(!MarketplaceDocuments.blank(c.taxType())&&!Set.of("TAX","FREE","DUTYFREE").contains(c.taxType()))throw invalid("공통 과세 정보는 과세 또는 면세를 선택해 주세요. 영세 등 다른 유형은 해당 마켓에서 설정해 주세요.");
        for(var entry:Map.of("출고 소요일",Objects.toString(delivery.shippingDays(),""),"제주 추가 배송비",Objects.toString(delivery.jejuCharge(),""),"도서산간 추가 배송비",Objects.toString(delivery.remoteCharge(),"")).entrySet()){
            if(!entry.getValue().isBlank()&&(!entry.getValue().matches("[0-9]{1,9}")||Long.parseLong(entry.getValue())>999999999L))throw invalid(entry.getKey()+"는 0 이상의 정수로 입력해 주세요.");
        }
        if(!MarketplaceDocuments.blank(c.saleStartedAt())||!MarketplaceDocuments.blank(c.saleEndedAt())){
            try{var start=LocalDateTime.parse(c.saleStartedAt());var end=LocalDateTime.parse(c.saleEndedAt());if(start.getYear()<2000||end.getYear()>2099||!start.isBefore(end))throw new IllegalArgumentException();}
            catch(RuntimeException e){throw invalid("판매 시작·종료 일시를 확인해 주세요. 종료는 시작 이후, 2099년 이하여야 합니다.");}
        }
        if(!MarketplaceDocuments.blank(c.afterServiceTelephone())&&!c.afterServiceTelephone().matches("[+0-9() -]{3,40}"))throw invalid("A/S 연락처 형식을 확인해 주세요.");
    }
    public static void equalPolicy(String label,String requested,Object actual){
        if(MarketplaceDocuments.blank(requested))return;
        String value=Objects.toString(actual,"");java.math.BigDecimal amount;
        try{if(value.length()>60)throw new IllegalArgumentException();amount=new java.math.BigDecimal(value);}catch(RuntimeException e){throw invalid(label+"가 선택한 배송 정책과 다릅니다. 적용값 "+requested+"원 / 정책값 미확인. 정책을 다시 조회해 주세요.");}
        try{if(!requested.matches("[0-9]+")||new java.math.BigDecimal(requested).compareTo(amount)!=0)throw new IllegalArgumentException();}catch(RuntimeException e){throw invalid(label+"가 선택한 배송 정책과 다릅니다. 적용값 "+requested+"원 / 정책값 "+amount+"원. 공통값 또는 해당 마켓의 정책을 다시 선택해 주세요.");}
    }
    private static InputValidationFailure invalid(String message){return new MarketplaceSettingsFailure("common",message);}
}
