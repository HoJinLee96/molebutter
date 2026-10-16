package cc.ataglace.molebutter.identity.api;


import java.time.LocalDateTime;


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

}
