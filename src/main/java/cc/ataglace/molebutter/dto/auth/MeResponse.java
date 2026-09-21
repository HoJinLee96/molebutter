package cc.ataglace.molebutter.dto.auth;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;

/** 로그인한 본인 정보 응답. id는 JS number 정밀도 문제를 피해 문자열로 내린다. */
public record MeResponse(
        String id,
        String email,
        String name,
        UserRole role,
        UserStatus status) {

    public static MeResponse from(User user) {
        return new MeResponse(
                String.valueOf(user.getId()),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getStatus());
    }
}
