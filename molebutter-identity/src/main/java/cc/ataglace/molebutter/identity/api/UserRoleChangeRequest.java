package cc.ataglace.molebutter.identity.api;


import cc.ataglace.molebutter.identity.api.UserRole;
import jakarta.validation.constraints.NotNull;

public record UserRoleChangeRequest(
        @NotNull UserRole role) {

}
