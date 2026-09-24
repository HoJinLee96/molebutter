package cc.ataglace.molebutter.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),

    // Common
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "Invalid Input Value"),
    INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "Invalid Type Value"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed"),
    HANDLE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is Denied"),
    OPTIMISTIC_LOCKING_FAILURE(HttpStatus.CONFLICT, "Optimistic Locking Failure"),

    // Auth
    INVALID_CSRF_TOKEN(HttpStatus.FORBIDDEN, "보안 토큰이 만료되었습니다. 다시 시도하세요."),
    SIGNIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "인증통해 복구 하세요."),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정입니다. 관리자에게 문의하세요."),
    ACCOUNT_PENDING(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다. 관리자에게 문의하세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
    PASSWORD_SAME_AS_OLD(HttpStatus.BAD_REQUEST, "새 비밀번호가 기존 비밀번호와 같습니다."),
    INVALID_PASSWORD_FORMAT(HttpStatus.BAD_REQUEST, "비밀번호는 8~64자이며 영문자와 숫자를 포함해야 합니다."),
    INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "휴대전화 번호 형식이 올바르지 않습니다."),
    INVALID_EMAIL(HttpStatus.BAD_REQUEST, "이메일 형식이 올바르지 않습니다."),

    // Signup / Email verification
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증이 완료되지 않았습니다."),
    INVALID_EMAIL_CODE(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않거나 만료되었습니다."),
    EMAIL_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "인증번호를 다시 요청하려면 잠시 후 시도하세요."),
    EMAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "인증번호 발송에 실패했습니다. 잠시 후 다시 시도하세요."),

    // Token
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.")
    ;

    private final HttpStatus status;
    private final String message;
}
