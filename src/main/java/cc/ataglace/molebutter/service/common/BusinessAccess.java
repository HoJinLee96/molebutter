package cc.ataglace.molebutter.service.common;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.exception.*;
import lombok.RequiredArgsConstructor;
/** Current-account checks shared by catalog, inventory and settings; never trust a client-supplied role. */
@Component @RequiredArgsConstructor
public class BusinessAccess {
    private final JdbcTemplate jdbc;
    public UserRole productActor(Long actor, boolean adminOnly) {
        var rows=jdbc.queryForList("SELECT user_role,user_status FROM `user` WHERE id=?",actor);
        if(rows.isEmpty() || !"ACTIVE".equals(rows.getFirst().get("user_status"))) throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        var role=UserRole.valueOf(rows.getFirst().get("user_role").toString());
        if(role!=UserRole.ADMIN && (adminOnly || role!=UserRole.PRODUCT)) throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        return role;
    }
}
