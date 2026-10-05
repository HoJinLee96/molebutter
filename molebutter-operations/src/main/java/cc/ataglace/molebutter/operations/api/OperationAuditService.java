package cc.ataglace.molebutter.operations.api;
import cc.ataglace.molebutter.operations.api.OperationContext;
import cc.ataglace.molebutter.identity.api.UserRole;
public interface OperationAuditService {
    void register(UserRole userRole, Long userId, String email, String eventType, String targetType,
            String targetId, String httpMethod, String requestUri, String ip, String userAgent,
            boolean success, String failureReason);
    void backgroundAfterCommit(String eventType,String targetType,String targetId,boolean success,String failureCode);
    void registerBackground(String eventType,String targetType,String targetId,boolean success,String failureCode);
    void register(OperationContext context,String eventType,String targetType,String targetId,
            String httpMethod,String requestUri,String ip,String userAgent,boolean success,String failureReason);
}
