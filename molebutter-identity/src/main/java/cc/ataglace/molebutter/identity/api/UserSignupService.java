package cc.ataglace.molebutter.identity.api;
public interface UserSignupService {
    void sendSignupCode(String rawEmail, ClientInfo client);
    void verifySignupCode(String rawEmail, String code);
    void signup(SignupRequest request, ClientInfo client);
}
