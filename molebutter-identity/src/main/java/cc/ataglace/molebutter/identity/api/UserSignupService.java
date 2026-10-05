package cc.ataglace.molebutter.identity.api;
import cc.ataglace.molebutter.identity.api.SignupRequest;
import cc.ataglace.molebutter.identity.api.ClientInfo;
public interface UserSignupService {
    void sendSignupCode(String rawEmail, ClientInfo client);
    void verifySignupCode(String rawEmail, String code);
    void signup(SignupRequest request, ClientInfo client);
}
