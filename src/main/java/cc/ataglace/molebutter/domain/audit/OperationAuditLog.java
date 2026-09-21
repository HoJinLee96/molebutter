package cc.ataglace.molebutter.domain.audit;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.github.f4b6a3.tsid.TsidCreator;

import cc.ataglace.molebutter.domain.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Entity
@Table(name = "operation_audit_log", indexes = {
        @Index(name = "ix_operation_audit_log_created_at", columnList = "created_at"),
        @Index(name = "ix_operation_audit_log_user_created", columnList = "user_id, created_at"),
        @Index(name = "ix_operation_audit_log_event_created", columnList = "event_type, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class OperationAuditLog {

    // 감사 저장은 절대 실패하면 안 되므로, 모든 문자열 컬럼은 아래 상수를 @Column length와 truncate 양쪽에 공유한다.
    private static final int EMAIL_MAX = 255;
    private static final int EVENT_TYPE_MAX = 50;
    private static final int TARGET_TYPE_MAX = 100;
    private static final int TARGET_ID_MAX = 500;
    private static final int HTTP_METHOD_MAX = 10;
    private static final int URI_MAX = 500;
    private static final int IP_MAX = 100;
    private static final int USER_AGENT_MAX = 500;
    private static final int FAILURE_REASON_MAX = 500;

    @Id
    @Column(name = "id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 20)
    private UserRole userRole;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "email", nullable = false, length = EMAIL_MAX)
    private String email;

    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_MAX)
    private String eventType;

    @Column(name = "target_type", length = TARGET_TYPE_MAX)
    private String targetType;

    @Column(name = "target_id", length = TARGET_ID_MAX)
    private String targetId;

    @Column(name = "http_method", length = HTTP_METHOD_MAX)
    private String httpMethod;

    @Column(name = "request_uri", length = URI_MAX)
    private String requestUri;

    @Column(name = "ip", length = IP_MAX)
    private String ip;

    @Column(name = "user_agent", length = USER_AGENT_MAX)
    private String userAgent;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
    private String failureReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Builder
    private OperationAuditLog(UserRole userRole, Long userId, String email, String eventType,
            String targetType, String targetId, String httpMethod, String requestUri,
            String ip, String userAgent, boolean success, String failureReason) {
        this.userRole = userRole;
        this.userId = userId;
        this.email = truncate(email, EMAIL_MAX);
        this.eventType = truncate(eventType, EVENT_TYPE_MAX);
        this.targetType = truncate(targetType, TARGET_TYPE_MAX);
        this.targetId = truncate(targetId, TARGET_ID_MAX);
        this.httpMethod = truncate(httpMethod, HTTP_METHOD_MAX);
        this.requestUri = truncate(requestUri, URI_MAX);
        this.ip = truncate(ip, IP_MAX);
        this.userAgent = truncate(userAgent, USER_AGENT_MAX);
        this.success = success;
        this.failureReason = truncate(failureReason, FAILURE_REASON_MAX);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = TsidCreator.getTsid().toLong();
        }
    }

}
