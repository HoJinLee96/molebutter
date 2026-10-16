package cc.ataglace.molebutter.coupang.api;

public final class CoupangEditingFailure extends RuntimeException {
    public enum Kind {
        NOT_FOUND("편집 세션을 찾을 수 없습니다."),
        EXPIRED("편집 시간이 지났습니다. 최신값을 다시 조회해 주세요."),
        CONFLICT("작업본 버전 또는 쿠팡 상품·옵션 식별자가 변경되었습니다. 입력을 유지한 채 다시 조회해 주세요.");
        final String message; Kind(String message) { this.message = message; }
    }
    private final Kind kind;
    public CoupangEditingFailure(Kind kind) { super(kind.message); this.kind=kind; }
    public Kind kind() { return kind; }
}
