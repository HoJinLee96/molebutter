package cc.ataglace.molebutter.identity.api;

import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.identity.api.UserStatus;
import java.time.LocalDateTime;
/** Read-only account contract; no entity or repository crosses a business boundary. */
public interface UserAccount {
    Long getId(); String getEmail(); String getName(); String getPhoneNumber();
    UserRole getRole(); UserStatus getStatus(); long getAuthVersion();
    LocalDateTime getCreatedAt(); LocalDateTime getLastLoginAt();
    default boolean isSigninBlocked(){return getStatus()!=UserStatus.ACTIVE;}
    default boolean isPending(){return getStatus()==UserStatus.PENDING;}
    default boolean isLocked(){return getStatus()==UserStatus.LOCKED;}
    default boolean isSuspended(){return getStatus()==UserStatus.SUSPENDED;}
}
