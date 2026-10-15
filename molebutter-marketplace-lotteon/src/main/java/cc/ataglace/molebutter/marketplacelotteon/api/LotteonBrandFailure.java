package cc.ataglace.molebutter.marketplacelotteon.api;

/** Fixed safe messages only. External payloads and credentials are never exposed. */
public final class LotteonBrandFailure extends RuntimeException {
    public enum Kind {
        CONFIGURATION("롯데ON 브랜드 검색 연결 설정이 필요합니다."),
        AUTHENTICATION("롯데ON 인증에 실패했습니다. 인증키와 유효기간을 확인해 주세요."),
        PERMISSION("롯데ON 접근이 거절되었습니다. 허용 IP와 API 권한을 확인해 주세요."),
        RATE_LIMIT("롯데ON 호출 제한입니다. 잠시 후 다시 검색해 주세요."),
        BUSY("롯데ON 브랜드 검색 중입니다. 잠시 후 다시 검색해 주세요."),
        CANCELLED("롯데ON 브랜드 검색이 취소되었습니다."),
        TIMEOUT("롯데ON 브랜드 검색 응답 시간이 초과되었습니다."),
        NETWORK("롯데ON에 연결할 수 없습니다."),
        UPSTREAM("롯데ON 서버 오류입니다. 잠시 후 다시 검색해 주세요."),
        REJECTED("롯데ON이 브랜드 검색 요청을 거절했습니다."),
        RESPONSE("롯데ON 브랜드 검색 응답 형식을 확인할 수 없습니다.");
        private final String message;
        Kind(String message){this.message=message;}
    }
    private final Kind kind;
    public LotteonBrandFailure(Kind kind){super(kind.message);this.kind=kind;}
    public Kind kind(){return kind;}
}
