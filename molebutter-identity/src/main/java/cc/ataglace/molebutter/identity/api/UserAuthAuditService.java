package cc.ataglace.molebutter.identity.api;
public interface UserAuthAuditService {
    void register(UserRole userRole, Long userId, String email, UserAuthEventType eventType, ClientInfo client,
            boolean success, String failureReason);
}
