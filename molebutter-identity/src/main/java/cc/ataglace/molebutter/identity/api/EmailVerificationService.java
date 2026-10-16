package cc.ataglace.molebutter.identity.api;
public interface EmailVerificationService {
    void sendCode(String email, EmailVerificationPurpose purpose);
    void verifyCode(String email, String code, EmailVerificationPurpose purpose);
    void consumeVerified(String email, EmailVerificationPurpose purpose);
}
