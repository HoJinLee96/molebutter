package cc.ataglace.molebutter.identity.api;
import java.util.Optional;
public interface IdentityAccounts {
    Optional<UserAccount> findById(Long id);
    UserAccount lockUser(Long id);
}
