package cc.ataglace.molebutter.service.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.exception.BusinessException;

class AuthenticationPolicyTests {
    @Test
    void credentialVersionChangesEvenWithinTheSameSecond() {
        User user = activeUser();
        LocalDateTime now = LocalDateTime.now();
        user.changePassword("new-hash", now);
        user.changePassword("another-hash", now);
        assertThat(user.getAuthVersion()).isEqualTo(2);
        user.suspend();
        assertThat(user.getAuthVersion()).isEqualTo(3);
        user.unsuspend();
        assertThat(user.getAuthVersion()).isEqualTo(3);
        user.changeRole(UserRole.PRODUCT);
        assertThat(user.getAuthVersion()).isEqualTo(4);
    }

    @Test
    void pendingAndLockedAccountsCannotSignIn() {
        User pending = User.builder().role(UserRole.VIEWER).status(UserStatus.PENDING).build();
        assertThat(pending.isSigninBlocked()).isTrue();
        pending.approve(UserRole.VIEWER);
        assertThat(pending.isSigninBlocked()).isFalse();
        pending.signinFailure(1, LocalDateTime.now());
        assertThat(pending.isSigninBlocked()).isTrue();
        assertThat(pending.getAuthVersion()).isEqualTo(1);
    }

    @Test
    void passwordsRespectBcryptByteLimitAsWellAsCharacterLimit() {
        PasswordPolicyValidator validator = new PasswordPolicyValidator();
        assertThatCode(() -> validator.validate("ValidPassword123!")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate("a1" + "가".repeat(30)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate("onlyletters")).isInstanceOf(BusinessException.class);
    }

    private User activeUser() {
        return User.builder().email("test@example.com").name("Test").passwordHash("hash")
                .role(UserRole.VIEWER).status(UserStatus.ACTIVE).build();
    }
}
