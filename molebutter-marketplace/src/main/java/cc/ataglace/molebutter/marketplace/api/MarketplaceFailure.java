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
    public MarketplaceFailure(Kind kind,String market){super("NAVER".equals(market)?naverMessage(kind):kind.message);this.kind=kind;}
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
