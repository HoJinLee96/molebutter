package cc.ataglace.molebutter.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.auth.PasswordPolicyValidator;
import cc.ataglace.molebutter.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;

/** 최초 1회 bootstrap-admin 프로필과 환경 변수로 관리자 계정을 준비한다. */
@Component
@Profile("bootstrap-admin")
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {
    private static final String DISABLED_SEED = "{disabled-initial-admin}";
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicyValidator passwordPolicy;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 기존 관리자의 비밀번호/상태를 재시작으로 덮어쓰지 않는다.
        if (users.findAllByRole(UserRole.ADMIN).stream()
                .anyMatch(user -> !DISABLED_SEED.equals(user.getPasswordHash()))) return;

        String email = EmailNormalizer.normalize(environment.getRequiredProperty("BOOTSTRAP_ADMIN_EMAIL"));
        String password = environment.getRequiredProperty("BOOTSTRAP_ADMIN_PASSWORD");
        passwordPolicy.validate(password);
        if ("admin1234!".equals(password)) {
            throw new IllegalArgumentException("기존 공용 초기 비밀번호는 사용할 수 없습니다.");
        }
        User existing = users.findLockedByEmail(email).orElse(null);
        if (existing != null) {
            if (existing.getRole() != UserRole.ADMIN || !DISABLED_SEED.equals(existing.getPasswordHash())) {
                throw new IllegalStateException("초기 관리자 이메일이 기존 계정과 충돌합니다.");
            }
            existing.changePassword(encoder.encode(password), java.time.LocalDateTime.now());
            existing.unsuspend();
        } else {
            users.save(User.builder().email(email).name("관리자").passwordHash(encoder.encode(password))
                    .role(UserRole.ADMIN).status(UserStatus.ACTIVE).build());
        }
    }
}
