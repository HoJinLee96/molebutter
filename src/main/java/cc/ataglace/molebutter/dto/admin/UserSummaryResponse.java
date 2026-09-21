package cc.ataglace.molebutter.dto.admin;

import java.time.LocalDateTime;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;

/** 직원 관리 목록의 한 행. id는 JS number 정밀도 문제를 피해 문자열로 내린다. */
public record UserSummaryResponse(
        String id,
        String email,
        String name,
        String phoneNumber,
        UserRole role,
        String roleLabel,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                String.valueOf(user.getId()),
                user.getEmail(),
                user.getName(),
                user.getPhoneNumber(),
                user.getRole(),
                user.getRole().getLabel(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getLastLoginAt());
    }
}
