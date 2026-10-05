package cc.ataglace.molebutter.identity.api;
import cc.ataglace.molebutter.identity.api.ClientInfo;
public interface UserAuthService {
    cc.ataglace.molebutter.identity.api.UserAccount signin(String email, String rawPassword, ClientInfo client);
}
