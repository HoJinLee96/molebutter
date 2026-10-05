package cc.ataglace.molebutter.operations.internal;
import cc.ataglace.molebutter.operations.api.OperationContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.operations.internal.OperationAuditLog;
import cc.ataglace.molebutter.operations.internal.OperationAuditLogRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DefaultOperationAuditService implements cc.ataglace.molebutter.operations.api.OperationAuditService {

    private final OperationAuditLogRepository repository;
    private final TransactionTemplate newTxTemplate;

    public DefaultOperationAuditService(OperationAuditLogRepository repository,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.newTxTemplate = new TransactionTemplate(transactionManager);
        this.newTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void register(UserRole userRole, Long userId, String email, String eventType, String targetType,
            String targetId, String httpMethod, String requestUri, String ip, String userAgent,
            boolean success, String failureReason) {
        register(OperationContext.http(java.util.UUID.randomUUID().toString(),userId,userRole,email),eventType,targetType,targetId,
                httpMethod,requestUri,ip,userAgent,success,failureReason);
    }

    /** Committed background results use the same audit store without fabricating a signed-in account. */
    public void backgroundAfterCommit(String eventType,String targetType,String targetId,boolean success,String failureCode) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Background audit requires a business transaction");
        var context=OperationContext.background();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    register(context,eventType,targetType,targetId,null,null,null,null,success,failureCode);
                }
            });
    }

    public void registerBackground(String eventType,String targetType,String targetId,boolean success,String failureCode) {
        register(OperationContext.background(),eventType,targetType,targetId,null,null,null,null,success,failureCode);
    }

    public void register(OperationContext context,String eventType,String targetType,String targetId,
            String httpMethod,String requestUri,String ip,String userAgent,boolean success,String failureReason) {
        UserRole userRole=context.actorRole();Long userId=context.actorId();String email=context.actorEmail();
        try {
            newTxTemplate.executeWithoutResult(status -> repository.save(OperationAuditLog.builder()
                    .executionSource(context.source().name())
                    .operationId(context.operationId())
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
            log.error("작동 감사 저장 실패. eventType={}, userId={}, email={}, userRole={}, targetId={}, method={}, uri={}, success={}, failureReason={}, exceptionType={}",
                eventType, userId, email, userRole, targetId, httpMethod, requestUri, success, failureReason, e.getClass().getSimpleName());
        }
    }
}
