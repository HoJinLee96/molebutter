package cc.ataglace.molebutter.marketplace.api;

public final class MarketplaceDraftFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, CONFLICT }
    private final Kind kind;
    public MarketplaceDraftFailure(Kind kind) {
        super(kind == Kind.NOT_FOUND ? "판매 상품 초안을 찾을 수 없습니다." : "다른 작업에서 변경되었습니다. 새로 조회해 주세요.");
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
