package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;

/** 쿠팡 쓰기 응답을 판정한다. 접수와 반영 완료를 구분하며 조회나 재전송은 수행하지 않는다. */
final class CoupangWriteOutcome {
    private final ObjectMapper json;
    CoupangWriteOutcome(ObjectMapper json) { this.json = json; }
    Result parseOutcome(byte[] body,Step step,Mapping mapping,Instant attempted){
        JsonNode root;
        try{root=json.readTree(body);}catch(RuntimeException e){return result(State.UNKNOWN,mapping,"INVALID_RESPONSE","쿠팡 처리 결과를 확인하지 못했습니다.",attempted);}
        if(root==null||!root.isObject())return result(State.UNKNOWN,mapping,"INVALID_RESPONSE","쿠팡 처리 결과를 확인하지 못했습니다.",attempted);
        var response=root;
        if("200".equals(scalar(root.path("code")))&&root.path("data").isObject())response=root.path("data");
        String code=scalar(response.path("code"));
        if("ERROR".equals(code)){
            var reason=CoupangWriteRejection.classify(root);
            return result(State.FAILED,mapping,reason==null?"HTTP_200_REJECTED":reason,reason==null?CoupangWriteRejection.message("HTTP_200_REJECTED"):CoupangWriteRejection.message(reason),attempted);
        }
        if(!"SUCCESS".equals(code))return result(State.UNKNOWN,mapping,"INVALID_RESPONSE","쿠팡 처리 결과를 확인하지 못했습니다.",attempted);
        if(step.type()==Type.CREATE){
            String id=optionalId(response.path("data"));if(id==null)return result(State.UNKNOWN,mapping,"INVALID_RESPONSE","쿠팡 등록 결과의 상품 ID를 확인하지 못했습니다.",attempted);
            mapping=new Mapping(mapping.accountKey(),id,mapping.options());
        }
        if(step.type()==Type.APPROVAL&&!Objects.equals(mapping.sellerProductId(),optionalId(response.path("data"))))return result(State.UNKNOWN,mapping,"INVALID_RESPONSE","승인 요청의 등록상품 ID를 확인하지 못했습니다.",attempted);
        boolean warning=hasWarnings(root);
        // Raw messages can contain contacts or other seller data; only fixed classification crosses the API.
        return result(State.ACCEPTED,mapping,warning?"NEEDS_CORRECTION":"SUCCESS",warning?"상품은 저장되었으나 옵션 속성 보완이 필요합니다.":"쿠팡이 요청을 접수했습니다.",attempted);
    }
    private static boolean hasWarnings(JsonNode n){
        if(n.isObject())for(var entry:n.properties()){
            var value=entry.getValue();if("errorItems".equals(entry.getKey())&&value.isArray()&&!value.isEmpty())return true;
            if("details".equals(entry.getKey())&&value.isString()&&!value.asString().isBlank()&&!Set.of("[]","{}").contains(value.asString().trim()))return true;
            if(hasWarnings(value))return true;
        }else if(n.isArray())for(var value:n)if(hasWarnings(value))return true;
        return false;
    }
    Result httpFailure(int status,byte[] body,Mapping mapping,Instant now){
        String reason=null;
        if(status>=400&&status<500&&!Set.of(401,403,429).contains(status))try{reason=CoupangWriteRejection.classify(json.readTree(body));}catch(RuntimeException ignored){}
        String code=reason!=null?reason:switch(status){case 401->"AUTHENTICATION";case 403->"PERMISSION";case 429->"RATE_LIMIT";default->status>=500?"UPSTREAM":"HTTP_"+status+"_REJECTED";};
        String message=switch(status){case 401->"쿠팡 인증에 실패했습니다.";case 403->"쿠팡 접근이 거절되었습니다. 허용 IP와 권한을 확인해 주세요.";case 429->"쿠팡 호출 제한입니다. 잠시 후 다시 시도해 주세요.";default->status>=500?"쿠팡 처리 결과를 확인하지 못했습니다.":"쿠팡이 전송을 거절했습니다. 입력 규격을 확인해 주세요.";};
        return result(status>=500||status<400?State.UNKNOWN:State.FAILED,mapping,code,CoupangWriteRejection.message(code)==null?message:CoupangWriteRejection.message(code),now);
    }
    private static String scalar(JsonNode n){return n.isMissingNode()||n.isNull()?null:n.isString()?n.asString():n.isNumber()||n.isBoolean()?n.toString():null;}
    private static String optionalId(JsonNode n){String v=scalar(n);return v!=null&&v.matches("[0-9]{1,30}")?v:null;}
    private static Result result(State s,Mapping m,String c,String message,Instant at){return new Result(s,m,c,message,at);}
}
