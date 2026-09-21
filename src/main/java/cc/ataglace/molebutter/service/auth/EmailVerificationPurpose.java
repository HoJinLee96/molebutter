package cc.ataglace.molebutter.service.auth;

/** 이메일 인증번호 용도. KeyValueStore 키에 포함되어 용도 간 코드/verified 혼용을 막는다. */
public enum EmailVerificationPurpose {
    SIGNUP,
    /** 비밀번호 재설정(find-password) — Ch 4에서 사용 예정. */
    PASSWORD_RESET,
}
