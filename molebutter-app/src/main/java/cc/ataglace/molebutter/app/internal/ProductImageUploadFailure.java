package cc.ataglace.molebutter.app.internal;

/** Safe, localized errors before an upload is accepted; no provider exception details. */
final class ProductImageUploadFailure extends RuntimeException {
    enum Kind { INVALID_INPUT, NOT_FOUND, CONFLICT, DUPLICATE, BUSY, NOT_CONFIGURED, CHECK_FAILED }
    private final Kind kind;
    ProductImageUploadFailure(Kind kind, String message) { super(message); this.kind = kind; }
    Kind kind() { return kind; }
}
