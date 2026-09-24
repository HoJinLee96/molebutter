package cc.ataglace.molebutter.security;

/** 요청의 민감도에 따른 인증 정보 확인 범위. 접근 권한은 SecurityConfig에서 별도로 검사한다. */
public enum RequestAuthPolicy {
    BASIC_READ,
    SENSITIVE_READ,
    WRITE;

    public boolean requiresCurrentUser() {
        return this != BASIC_READ;
    }
}
