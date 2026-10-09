package cc.ataglace.molebutter.marketplace.api;

public final class MarketplaceSubmissionFailure extends RuntimeException {
    public enum Kind {
        NOT_FOUND("실행 기록을 찾을 수 없습니다."),
        CONFLICT("초안 또는 실행 상태가 변경되었습니다. 다시 확인해 주세요."),
        EXPIRED("미리보기 유효 시간이 지났습니다. 다시 확인해 주세요."),
        INVALID("실행할 수 없는 입력입니다. 미리보기의 오류를 확인해 주세요.");
        final String message; Kind(String message){this.message=message;}
    }
    private final Kind kind;
    public MarketplaceSubmissionFailure(Kind kind){super(kind.message);this.kind=kind;}
    public Kind kind(){return kind;}
}
