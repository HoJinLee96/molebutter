package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.ErrorCode;

/** HTTP transport mapping is owned by the app; business modules expose error codes only. */
final class HttpErrorStatus {
    private HttpErrorStatus() {}
    static int of(ErrorCode code) {
        return switch (code) {
            case INTERNAL_SERVER_ERROR -> 500;
            case INVALID_INPUT_VALUE, INVALID_TYPE_VALUE, PASSWORD_MISMATCH, PASSWORD_SAME_AS_OLD, INVALID_PASSWORD_FORMAT, INVALID_PHONE_NUMBER, INVALID_EMAIL, EMAIL_NOT_VERIFIED, INVALID_EMAIL_CODE -> 400;
            case NOT_FOUND, USER_NOT_FOUND -> 404;
            case METHOD_NOT_ALLOWED -> 405;
            case HANDLE_ACCESS_DENIED, INVALID_CSRF_TOKEN, ACCOUNT_LOCKED, ACCOUNT_SUSPENDED, ACCOUNT_PENDING -> 403;
            case OPTIMISTIC_LOCKING_FAILURE, DUPLICATE_EMAIL -> 409;
            case SIGNIN_FAILED, UNAUTHORIZED, INVALID_TOKEN -> 401;
            case AUTH_RATE_LIMITED, EMAIL_CODE_COOLDOWN -> 429;
            case EMAIL_SEND_FAILED -> 503;
        };
    }
}
