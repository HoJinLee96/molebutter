package cc.ataglace.molebutter.identity.api;


import java.security.Principal;

import cc.ataglace.molebutter.identity.api.UserRole;

public record UserPrincipal(Long userId, String email, UserRole userRole) implements Principal{

    @Override
    public String getName() {
        return email;
    }
}
