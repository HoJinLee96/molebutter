package cc.ataglace.molebutter.dto.admin;

import java.time.LocalDateTime;

import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.audit.UserAuthAuditLog;

/** 인증 감사 로그의 한 행. */
public record AuthLogResponse(
        String id,
        UserRole userRole,
        String userId,
        String email,
        UserAuthEventType eventType,
        String eventTypeLabel,
        String ip,
        String userAgent,
        boolean success,
        String failureReason,
        LocalDateTime createdAt) {

    public static AuthLogResponse from(UserAuthAuditLog log) {
        return new AuthLogResponse(
                String.valueOf(log.getId()),
                log.getUserRole(),
                log.getUserId() == null ? null : String.valueOf(log.getUserId()),
                log.getEmail(),
                log.getEventType(),
                log.getEventType().getLabel(),
                log.getIp(),
                log.getUserAgent(),
                log.isSuccess(),
                log.getFailureReason(),
                log.getCreatedAt());
    }
}
