package cc.ataglace.molebutter.storage.api;

/** Contains only a stable sanitized code/message, never the SDK exception or request credentials. */
public final class StorageException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT, TOO_LARGE, NOT_CONFIGURED, CONFLICT, NOT_FOUND,
        ACCESS_DENIED, UNAVAILABLE, WRITE_UNCERTAIN
    }

    private final Code code;

    public StorageException(Code code) {
        super(message(code));
        this.code = code;
    }

    public Code code() {
        return code;
    }

    private static String message(Code code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "Invalid object storage input.";
            case TOO_LARGE -> "Object exceeds the configured size limit.";
            case NOT_CONFIGURED -> "Object storage is not configured.";
            case CONFLICT -> "Object storage condition did not match the current object.";
            case NOT_FOUND -> "Object storage resource was not found.";
            case ACCESS_DENIED -> "Object storage access was denied.";
            case UNAVAILABLE -> "Object storage request could not be completed.";
            case WRITE_UNCERTAIN -> "Object storage write outcome is uncertain; read the current state before another write.";
        };
    }
}
