package cc.ataglace.molebutter.service.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.audit.UserAuthAuditLog;
import cc.ataglace.molebutter.infra.web.ClientInfo;
import cc.ataglace.molebutter.repository.UserAuthAuditLogRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserAuthAuditService {

    private final UserAuthAuditLogRepository repository;
    private final TransactionTemplate newTxTemplate;

    public UserAuthAuditService(UserAuthAuditLogRepository repository,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.newTxTemplate = new TransactionTemplate(transactionManager);
        this.newTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void register(UserRole userRole, Long userId, String email, UserAuthEventType eventType, ClientInfo client,
            boolean success, String failureReason) {
        try {
            newTxTemplate.executeWithoutResult(status -> repository.save(UserAuthAuditLog.builder()
                    .userRole(userRole)
                    .userId(userId)
                    .email(email)
                    .eventType(eventType)
                    .ip(client.ip())
                    .userAgent(client.userAgent())
                    .success(success)
                    .failureReason(failureReason)
                    .build()));
        } catch (Exception e) {
            log.error(
                    "인증 감사 저장 실패. userRole={}, userId={}, email={}, eventType={}, ip={}, userAgent={}, success={}, failureReason={}",
                    userRole, userId, email, eventType, client.ip(), client.userAgent(), success, failureReason, e);
        }
    }
}
