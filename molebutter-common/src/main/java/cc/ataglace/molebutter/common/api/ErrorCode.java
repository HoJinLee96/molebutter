package cc.ataglace.molebutter.common.api;




import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INTERNAL_SERVER_ERROR("Internal server error"),

    // Common
    INVALID_INPUT_VALUE("Invalid Input Value"),
    INVALID_TYPE_VALUE("Invalid Type Value"),
    NOT_FOUND("요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED("Method Not Allowed"),
    HANDLE_ACCESS_DENIED("Access is Denied"),
    OPTIMISTIC_LOCKING_FAILURE("Optimistic Locking Failure"),

    // Auth
    INVALID_CSRF_TOKEN("보안 토큰이 만료되었습니다. 다시 시도하세요."),
    SIGNIN_FAILED("아이디 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED("인증통해 복구 하세요."),
    ACCOUNT_SUSPENDED("정지된 계정입니다. 관리자에게 문의하세요."),
    ACCOUNT_PENDING("승인 대기 중인 계정입니다. 관리자에게 문의하세요."),
    UNAUTHORIZED("인증이 필요합니다."),
    USER_NOT_FOUND("일치하는 계정을 찾을 수 없습니다."),
    AUTH_RATE_LIMITED("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요."),
    PASSWORD_MISMATCH("현재 비밀번호가 올바르지 않습니다."),
    PASSWORD_SAME_AS_OLD("새 비밀번호가 기존 비밀번호와 같습니다."),
    INVALID_PASSWORD_FORMAT("비밀번호는 8~64자이며 영문자와 숫자를 포함해야 합니다."),
    INVALID_PHONE_NUMBER("휴대전화 번호 형식이 올바르지 않습니다."),
    INVALID_EMAIL("이메일 형식이 올바르지 않습니다."),

    // Signup / Email verification
    DUPLICATE_EMAIL("이미 가입된 이메일입니다."),
    EMAIL_NOT_VERIFIED("이메일 인증이 완료되지 않았습니다."),
    INVALID_EMAIL_CODE("인증번호가 올바르지 않거나 만료되었습니다."),
    EMAIL_CODE_COOLDOWN("인증번호를 다시 요청하려면 잠시 후 시도하세요."),
    EMAIL_SEND_FAILED("인증번호 발송에 실패했습니다. 잠시 후 다시 시도하세요."),

    // Token
    INVALID_TOKEN("유효하지 않은 토큰입니다.")
    ;

    private final String message;
}
