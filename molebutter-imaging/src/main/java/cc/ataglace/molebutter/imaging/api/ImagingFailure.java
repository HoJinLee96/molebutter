package cc.ataglace.molebutter.imaging.api;

/** Imaging business error; HTTP mapping belongs to the app module. */
public class ImagingFailure extends RuntimeException {
    public enum Kind { INVALID_INPUT, NOT_FOUND, BUSY, UPSTREAM, INTERNAL }
    private final Kind kind;
    public ImagingFailure(Kind kind, String message) { super(message); this.kind = kind; }
    public ImagingFailure(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
