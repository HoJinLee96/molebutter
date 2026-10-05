package cc.ataglace.molebutter.procurement.internal;


import java.util.*;

/** Safe structured diagnostics; never stores a response body, query, URL or exception message. */
public final class NaverSearchFailure extends IllegalStateException {
    public enum Code { API_LOGIN_REDIRECT, SEARCH_NO_REQUEST, SEARCH_RESPONSE_TIMEOUT, SEARCH_NETWORK_ERROR,
        RESPONSE_MISMATCH, RESPONSE_SCHEMA_CHANGED, UI_ELEMENT_MISSING, BROWSER_UNAVAILABLE, INTERNAL_ERROR,
        TAB_CLEANUP_FAILED, INTERRUPTED }
    private final Code code;
    private final String stage;
    private final Map<String,Object> diagnostics;
    private final Integer httpStatus;
    public NaverSearchFailure(Code code,String stage,Map<String,Object> diagnostics){this(code,stage,diagnostics,null);}
    public NaverSearchFailure(Code code,String stage,Map<String,Object> diagnostics,Integer status){
        super(message(code.name()));this.code=code;this.stage=stage;this.diagnostics=Map.copyOf(diagnostics);this.httpStatus=status;
    }
    public Code code(){return code;} public String stage(){return stage;}
    public Map<String,Object> diagnostics(){return diagnostics;} public Integer httpStatus(){return httpStatus;}
    public static boolean login(String code){return Set.of("LOGIN_REQUIRED","API_LOGIN_REDIRECT").contains(code);}
    public static boolean temporary(String code){return login(code)||Set.of("SEARCH_NO_REQUEST","SEARCH_RESPONSE_TIMEOUT","SEARCH_NETWORK_ERROR","INTERRUPTED").contains(code);}
    public static boolean validation(String code){return Set.of("RESPONSE_MISMATCH","RESPONSE_SCHEMA_CHANGED","UI_ELEMENT_MISSING").contains(code);}
    public static String message(String code){return switch(code){
        case "LOGIN_REQUIRED","API_LOGIN_REDIRECT" -> "네이버 로그인 요구";
        case "SEARCH_NO_REQUEST","SEARCH_RESPONSE_TIMEOUT" -> "네이버 검색 응답 지연";
        case "SEARCH_NETWORK_ERROR" -> "네이버 검색 통신 오류";
        case "RESPONSE_MISMATCH" -> "네이버 검색 응답 검증 불일치";
        case "RESPONSE_SCHEMA_CHANGED" -> "네이버 검색 응답 형식 확인 필요";
        case "UI_ELEMENT_MISSING" -> "네이버 검색 화면 확인 필요";
        case "TAB_CLEANUP_FAILED" -> "검색 탭 정리 실패";
        case "BROWSER_UNAVAILABLE" -> "검색 전용 Chrome 확인 필요";
        case "INTERRUPTED" -> "이전 검색 실행 중단";
        case "AUTHENTICATED_SESSION" -> "검색 전용 Chrome 로그인 상태 확인 필요";
        case "ACCESS_RESTRICTED" -> "네이버 검색 접속 제한·보안 확인";
        default -> "네이버 검색 처리 오류";
    };}
}
