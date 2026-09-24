package cc.ataglace.molebutter.service.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.infra.web.ClientInfo;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.UserMutationService;
import cc.ataglace.molebutter.service.audit.UserAuthAuditService;
import cc.ataglace.molebutter.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthService {

    // 사용자를 찾지 못했을 때도 BCrypt 비교를 수행해 타이밍으로 계정 존재를 노출하지 않는다.
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final UserMutationService userMutationService;
    private final PasswordEncoder passwordEncoder;
    private final SigninRateLimiter rateLimiter;
    private final UserAuthAuditService authAuditService;
    private final AuthProperties authProperties;

    /**
     * 로그인 인증을 수행한다. 성공하면 갱신된 {@link User}를 반환하고, 실패하면 {@link BusinessException}을
     * 던진다.
     */
    public User signin(String email, String rawPassword, ClientInfo client) {
        LocalDateTime now = LocalDateTime.now();
        String normalizedEmail = EmailNormalizer.normalize(email);
        String ip = client == null ? null : client.ip();

        // 1) rate limit. 초과하면 비밀번호 검증 전에 차단한다(무차별 대입/잠금 DoS 완화). 키는 email+IP, IP 단독.
        if (!rateLimiter.tryAcquire(normalizedEmail, ip)) {
            authAuditService.register(null, null, normalizedEmail, UserAuthEventType.RATE_LIMIT_EXCEEDED, client,
                    false, "rate limit exceeded");
            throw new BusinessException(ErrorCode.AUTH_RATE_LIMITED);
        }

        Optional<User> found = userRepository.findByEmail(normalizedEmail);

        // 2) 사용자가 없는 경우
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword == null ? "" : rawPassword, DUMMY_HASH);
            authAuditService.register(null, null, normalizedEmail, UserAuthEventType.SIGNIN, client, false,
                    "USER_NOT_FOUND");
            throw new BusinessException(ErrorCode.SIGNIN_FAILED);
        }

        User user = found.get();
        Long userId = user.getId();

        // 3) 비밀번호 검증.
        boolean matches = passwordEncoder.matches(rawPassword == null ? "" : rawPassword, user.getPasswordHash());
        if (!matches) {
            authAuditService.register(user.getRole(), userId, normalizedEmail, UserAuthEventType.SIGNIN, client,
                    false,
                    "UNMATCH_PASSWORD");
            boolean nowLock = userMutationService.registerSigninFailure(userId,
                    authProperties.signin().maxFailedAttempts(), now);
            if (nowLock) {
                authAuditService.register(user.getRole(), userId, normalizedEmail, UserAuthEventType.SIGNIN, client,
                        false, "NOW_LOCK");
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BusinessException(ErrorCode.SIGNIN_FAILED);
        }

        // 4) 정지 계정
        if (user.isSuspended()) {
            authAuditService.register(user.getRole(), userId, normalizedEmail, UserAuthEventType.SIGNIN,
                    client, false, "ACCOUNT_SUSPENDED");
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        // 5) 일시 잠금 계정
        if (user.isLocked()) {
            authAuditService.register(user.getRole(), userId, normalizedEmail, UserAuthEventType.SIGNIN, client, false,
                    "ACCOUNT_LOCKED");
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        // 6) 승인 대기
        if (user.isPending()) {
            authAuditService.register(user.getRole(), userId, normalizedEmail, UserAuthEventType.SIGNIN, client, false,
                    "ACCOUNT_PENDING");
            throw new BusinessException(ErrorCode.ACCOUNT_PENDING);
        }

        // 7) 성공: 실패 카운터/잠금 초기화 + last_login_at 갱신을 커밋하고, rate limit 윈도우를 리셋한 뒤 감사 로그를 남긴다.
        User updated = userMutationService.registerSigninSuccess(userId, user.getAuthVersion(), user.getPasswordHash(), now);
        rateLimiter.resetAccountWindow(normalizedEmail, ip);
        authAuditService.register(updated.getRole(), updated.getId(), updated.getEmail(), UserAuthEventType.SIGNIN,
                client, true, null);
        return updated;
    }
}
