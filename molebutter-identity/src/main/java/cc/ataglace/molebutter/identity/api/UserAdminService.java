package cc.ataglace.molebutter.identity.api;
import java.util.List;
import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.identity.api.UserStatus;
import cc.ataglace.molebutter.identity.api.UserSummaryResponse;
import cc.ataglace.molebutter.identity.api.ClientInfo;
public interface UserAdminService {
    List<UserSummaryResponse> listUsers(UserStatus status);
    void approve(Long actorId, Long userId, UserRole role, ClientInfo client);
    void changeRole(Long actorId, Long userId, UserRole role, ClientInfo client);
    void suspend(Long actorId, Long userId, ClientInfo client);
    void unsuspend(Long actorId, Long userId, ClientInfo client);
    void unlock(Long actorId, Long userId, ClientInfo client);
}
