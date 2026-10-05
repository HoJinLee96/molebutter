package cc.ataglace.molebutter.identity.api;
import cc.ataglace.molebutter.identity.api.EmailVerificationPurpose;
public interface EmailVerificationService {
    void sendCode(String email, EmailVerificationPurpose purpose);
    void verifyCode(String email, String code, EmailVerificationPurpose purpose);
    void consumeVerified(String email, EmailVerificationPurpose purpose);
}
