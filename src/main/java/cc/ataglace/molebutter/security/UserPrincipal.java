package cc.ataglace.molebutter.security;

import java.security.Principal;

import cc.ataglace.molebutter.domain.UserRole;

public record UserPrincipal(Long userId, String email, UserRole userRole) implements Principal{

    @Override
    public String getName() {
        return email;
    }
}