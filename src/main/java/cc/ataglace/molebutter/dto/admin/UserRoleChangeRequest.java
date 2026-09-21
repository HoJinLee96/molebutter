package cc.ataglace.molebutter.dto.admin;

import cc.ataglace.molebutter.domain.UserRole;
import jakarta.validation.constraints.NotNull;

public record UserRoleChangeRequest(
        @NotNull UserRole role) {

}
