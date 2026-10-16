package cc.ataglace.molebutter.identity.api;


import jakarta.validation.constraints.NotNull;

public record UserRoleChangeRequest(
        @NotNull UserRole role) {

}
