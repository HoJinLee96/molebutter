package cc.ataglace.molebutter.marketplace.api;

public final class MarketplaceEditingFailure extends RuntimeException {
    public enum Kind {
        NOT_FOUND("편집 세션을 찾을 수 없습니다."),
        EXPIRED("편집 시간이 지났습니다. 최신값을 다시 조회해 주세요."),
        CONFLICT("공통 상품 또는 연결이 변경되었습니다. 다시 조회해 주세요.");
        final String message; Kind(String message) { this.message = message; }
    }
    private final Kind kind;
    public MarketplaceEditingFailure(Kind kind) { super(kind.message); this.kind=kind; }
    public Kind kind() { return kind; }
}
