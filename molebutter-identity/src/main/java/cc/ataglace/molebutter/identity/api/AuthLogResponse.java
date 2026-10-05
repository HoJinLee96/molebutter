package cc.ataglace.molebutter.identity.api;


import java.time.LocalDateTime;

import cc.ataglace.molebutter.identity.api.UserAuthEventType;
import cc.ataglace.molebutter.identity.api.UserRole;

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
