package cc.ataglace.molebutter.identity.api;
import cc.ataglace.molebutter.identity.api.UserAuthEventType;
import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.identity.api.ClientInfo;
public interface UserAuthAuditService {
    void register(UserRole userRole, Long userId, String email, UserAuthEventType eventType, ClientInfo client,
            boolean success, String failureReason);
}
