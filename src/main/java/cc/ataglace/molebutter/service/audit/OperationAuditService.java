package cc.ataglace.molebutter.service.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.audit.OperationAuditLog;
import cc.ataglace.molebutter.repository.OperationAuditLogRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OperationAuditService {

    private final OperationAuditLogRepository repository;
    private final TransactionTemplate newTxTemplate;

    public OperationAuditService(OperationAuditLogRepository repository,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.newTxTemplate = new TransactionTemplate(transactionManager);
        this.newTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void register(UserRole userRole, Long userId, String email, String eventType, String targetType,
            String targetId, String httpMethod, String requestUri, String ip, String userAgent,
            boolean success, String failureReason) {
        try {
            newTxTemplate.executeWithoutResult(status -> repository.save(OperationAuditLog.builder()
                    .userRole(userRole)
                    .userId(userId)
                    .email(email)
                    .eventType(eventType)
                    .targetType(targetType)
                    .targetId(targetId)
                    .httpMethod(httpMethod)
                    .requestUri(requestUri)
                    .ip(ip)
                    .userAgent(userAgent)
                    .success(success)
                    .failureReason(failureReason)
                    .build()));
        } catch (Exception e) {
            log.error("작동 감사 저장 실패. eventType={}, userId={}, email={}, userRole={}, targetId={}, method={}, uri={}, success={}, failureReason={}",
                eventType, userId, email, userRole, targetId, httpMethod, requestUri, success, failureReason, e);
        }
    }
}