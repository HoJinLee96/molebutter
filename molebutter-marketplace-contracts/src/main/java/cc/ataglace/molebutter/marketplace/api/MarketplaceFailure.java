package cc.ataglace.molebutter.marketplace.api;
/** Only fixed messages are exposed. External errors and credentials never become exception messages. */
public final class MarketplaceFailure extends RuntimeException {
    public enum Kind {
        CANCELLED("쿠팡 조회가 취소되었습니다."),
        CONFIGURATION("쿠팡 연결 설정이 필요합니다."),
        AUTHENTICATION("쿠팡 인증에 실패했습니다. 키·서버 시각·서명을 확인해 주세요."),
        PERMISSION("쿠팡 접근이 거절되었습니다. 허용 IP와 API 권한을 확인해 주세요."),
        RATE_LIMIT("쿠팡 호출 제한입니다. 잠시 후 다시 조회해 주세요."),
        BUSY("쿠팡 조회 중입니다. 잠시 후 다시 조회해 주세요."),
        TIMEOUT("쿠팡 응답 시간이 초과되었습니다."),
        UPSTREAM("쿠팡 서버 오류입니다. 잠시 후 다시 조회해 주세요."),
        NETWORK("쿠팡에 연결할 수 없습니다."),
        RESPONSE("쿠팡 응답 형식을 확인할 수 없습니다."),
        ORDER_UNAVAILABLE("취소·반품으로 발주서를 조회할 수 없습니다. 클레임 정보를 확인해 주세요."),
        ORDER_INVALID("쿠팡 주문번호가 유효하지 않습니다. 주문번호를 확인해 주세요."),
        ORDER_ACCOUNT_MISMATCH("다른 판매자의 주문입니다. 연결 계정을 확인해 주세요."),
        ORDER_REJECTED("쿠팡이 주문 조회를 거절했습니다. 거절 사유를 확인하지 못했습니다."),
        REJECTED("쿠팡이 상품 조회 요청을 거절했습니다.");
        final String message; Kind(String message){this.message=message;}
    }
    private final Kind kind;
    public MarketplaceFailure(Kind kind){super(kind.message);this.kind=kind;}
    public MarketplaceFailure(Kind kind,String market){super("NAVER".equals(market)?naverMessage(kind):"ELEVENST".equals(market)?elevenstMessage(kind):"LOTTEON".equals(market)?kind.message.replace("쿠팡","롯데ON"):kind.message);this.kind=kind;}
    public MarketplaceFailure(Kind kind,String market,String externalCode){
        super("NAVER".equals(market)&&kind==Kind.PERMISSION?naverPermissionMessage(externalCode):"NAVER".equals(market)?naverMessage(kind):"ELEVENST".equals(market)?elevenstMessage(kind):"LOTTEON".equals(market)?kind.message.replace("쿠팡","롯데ON"):kind.message);this.kind=kind;
    }
    // Never expose an upstream message or an arbitrary upstream code.
    private static String naverPermissionMessage(String code){
        if(code==null)return naverMessage(Kind.PERMISSION);
        String reason=switch(code){
            case "GW.IP_NOT_ALLOWED" -> "호출이 허용되지 않은 서버 IP입니다. 커머스 API 애플리케이션의 허용 IP를 확인해 주세요.";
            case "GW.AUTHN" -> "요청한 API 접근이 거절되었습니다. 커머스 API 애플리케이션의 API 그룹 권한과 연동 계정을 확인해 주세요.";
            case "ROLE_NOT_FOUND" -> "요청한 API의 권한이 없습니다. 커머스 API 애플리케이션의 API 그룹 권한을 확인해 주세요.";
            case "PROVISION_NOT_FOUND" -> "약관 동의가 필요합니다. 스마트스토어 연동 약관을 확인해 주세요.";
            case "INVALID_CHANNEL_STATUS", "INVALID_STORE_STATUS", "INVALID_REPRESENT_STATUS", "INVALID_MEMBER_STATUS", "INVALID_INTERLOCK_STATUS" -> "판매자·스토어 또는 연동 상태로 접근이 거절되었습니다. 연동 계정 상태를 확인해 주세요.";
            case "RESOURCE_NOT_AVAILABLE" -> "연결 계정에서 접근할 수 없는 자원입니다. 판매자 연결 설정을 확인해 주세요.";
            default -> null;
        };
        return reason==null?naverMessage(Kind.PERMISSION):"스마트스토어 "+reason+" ("+code+")";
    }
    private static String elevenstMessage(Kind kind){return switch(kind){case AUTHENTICATION->"11번가 인증에 실패했습니다. OpenAPI 키와 SellerAPI 승인 상태를 확인해 주세요.";case PERMISSION->"11번가 접근이 거절되었습니다. 허용 IP와 SellerAPI 사용 승인을 확인해 주세요.";default->kind.message.replace("쿠팡","11번가");};}
    private static String naverMessage(Kind kind){
        return switch(kind){
            case CONFIGURATION -> "스마트스토어 커머스 API 연결 설정이 필요합니다.";
            case AUTHENTICATION -> "스마트스토어 인증에 실패했습니다. 애플리케이션 설정과 서버 시각을 확인해 주세요.";
            case PERMISSION -> "스마트스토어 접근이 거절되었습니다. 허용 IP와 API 권한을 확인해 주세요.";
            default -> kind.message.replace("쿠팡","스마트스토어");
        };
    }
    public Kind kind(){return kind;}
}
