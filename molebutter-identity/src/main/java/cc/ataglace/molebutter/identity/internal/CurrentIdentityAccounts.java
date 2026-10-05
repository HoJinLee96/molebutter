package cc.ataglace.molebutter.identity.internal;

import cc.ataglace.molebutter.identity.api.IdentityAccounts;
import cc.ataglace.molebutter.identity.api.UserAccount;
import cc.ataglace.molebutter.identity.internal.UserRepository;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Optional;
@Service @lombok.RequiredArgsConstructor
public class CurrentIdentityAccounts implements IdentityAccounts {
    private final UserRepository users;
    @Transactional(readOnly=true) public Optional<UserAccount> findById(Long id){return users.findById(id).map(this::snapshot);}
    @Transactional(propagation=Propagation.MANDATORY) public UserAccount lockUser(Long id){return snapshot(users.findLockedById(id).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND)));}
    private UserAccount snapshot(cc.ataglace.molebutter.identity.internal.User u){return new Account(u.getId(),u.getEmail(),u.getName(),u.getPhoneNumber(),u.getRole(),u.getStatus(),u.getAuthVersion(),u.getCreatedAt(),u.getLastLoginAt());}
    private record Account(Long id,String email,String name,String phoneNumber,cc.ataglace.molebutter.identity.api.UserRole role,cc.ataglace.molebutter.identity.api.UserStatus status,long authVersion,java.time.LocalDateTime createdAt,java.time.LocalDateTime lastLoginAt) implements UserAccount {
        public Long getId(){return id;} public String getEmail(){return email;} public String getName(){return name;} public String getPhoneNumber(){return phoneNumber;}
        public cc.ataglace.molebutter.identity.api.UserRole getRole(){return role;} public cc.ataglace.molebutter.identity.api.UserStatus getStatus(){return status;} public long getAuthVersion(){return authVersion;}
        public java.time.LocalDateTime getCreatedAt(){return createdAt;} public java.time.LocalDateTime getLastLoginAt(){return lastLoginAt;}
    }
}
