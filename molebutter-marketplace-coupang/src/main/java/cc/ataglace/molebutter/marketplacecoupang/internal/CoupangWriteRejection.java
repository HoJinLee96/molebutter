package cc.ataglace.molebutter.marketplacecoupang.internal;


import tools.jackson.databind.JsonNode;

/** Recognizes documented errors without copying upstream seller data into public results. */
final class CoupangWriteRejection {
    private CoupangWriteRejection() {}
    static String classify(JsonNode body) {
        if(body==null)return null;
        if(body.isObject())for(var entry:body.properties()){
            if(java.util.Set.of("message","errorMessage","details").contains(entry.getKey())&&entry.getValue().isString()){
                var code=classify(entry.getValue().asString());if(code!=null)return code;
            }
            if(entry.getValue().isObject()||entry.getValue().isArray()){var code=classify(entry.getValue());if(code!=null)return code;}
        }
        else if(body.isArray())for(var row:body){var code=classify(row);if(code!=null)return code;}
        return null;
    }
    private static String classify(String message){
        if(message.contains("최대 50%")&&message.contains("100%"))return "PRICE_CHANGE_RANGE";
        if(message.contains("자동생성옵션의 가격을 직접 수정할 수 없습니다"))return "AUTOMATIC_OPTION";
        if(message.contains("삭제된 상품은 변경이 불가능"))return "DELETED_OPTION";
        if(message.contains("가격은 최소 10원 단위"))return "PRICE_UNIT";
        if(message.contains("유효하지 않은 ID"))return "INVALID_OPTION_ID";
        if(message.contains("apMinSalePrice must be less than price"))return "AUTO_PRICE_MINIMUM";
        if(message.contains("apMinSalePrice and apActive must be provided together"))return "AUTO_PRICE_PAIR";
        return null;
    }
    static String message(String code){return switch(code){
        case "HTTP_411_REJECTED"->"쿠팡이 요청 본문 길이 헤더를 요구했습니다(HTTP 411). 빈 PUT 요청의 Content-Length: 0 전송 방식이 수정되었습니다. 실패·미실행 단계만 재시도해 주세요.";
        case "PRICE_CHANGE_RANGE"->"쿠팡 가격 변경 비율 제한입니다(최대 50% 인하·100% 인상). 변경값을 확인해 주세요. 강제 변경은 자동으로 요청하지 않습니다.";
        case "AUTOMATIC_OPTION"->"자동생성 옵션의 판매가는 직접 변경할 수 없습니다. 기준 판매자 옵션 또는 쿠팡 Wing에서 변경해 주세요.";
        case "DELETED_OPTION"->"삭제된 상품 옵션은 변경할 수 없습니다. 상품을 다시 조회해 주세요.";
        case "PRICE_UNIT"->"판매가는 10원 단위로 입력해 주세요.";
        case "INVALID_OPTION_ID"->"쿠팡이 옵션 ID를 유효하지 않다고 응답했습니다. 상품을 다시 조회해 주세요.";
        case "AUTO_PRICE_MINIMUM"->"자동 가격 조정의 최저 판매가는 변경 판매가보다 작아야 합니다.";
        case "AUTO_PRICE_PAIR"->"자동 가격 조정의 최저 판매가와 활성화 설정을 함께 전달해야 합니다.";
        default->code.matches("HTTP_[0-9]{3}_REJECTED")?"쿠팡이 요청을 거절했습니다(HTTP "+code.substring(5,8)+"). 알려진 오류 사유로 분류되지 않았습니다.":null;
    };}
}
