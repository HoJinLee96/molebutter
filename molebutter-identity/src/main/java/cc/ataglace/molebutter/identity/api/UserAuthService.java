package cc.ataglace.molebutter.identity.api;
public interface UserAuthService {
    cc.ataglace.molebutter.identity.api.UserAccount signin(String email, String rawPassword, ClientInfo client);
}
