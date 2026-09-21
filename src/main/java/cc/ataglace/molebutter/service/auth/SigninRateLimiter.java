package cc.ataglace.molebutter.service.auth;

import java.time.Duration;

import org.springframework.stereotype.Component;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.service.KeyValueStore;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 시도 rate limit. 두 카운터를 함께 검사한다.
 * <ul>
 * <li>IP 단독 — 여러 계정을 훑는 스프레이 방어. 임계값 {@code auth.signin.rate-limit-max-attempts}</li>
 * <li>email+IP — 존재하지 않는 계정에는 잠금(LOCKED)이 없으므로 여기서 무차별 대입을 막는다.
 * 임계값은 계정 잠금과 같은 {@code auth.signin.max-failed-attempts}, 로그인 성공 시 리셋된다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SigninRateLimiter {

    private static final String IP_KEY = "auth:rl:ip:";
    private static final String ACCOUNT_IP_KEY = "auth:rl:acct:";

    private final KeyValueStore store;
    private final AuthProperties authProperties;

    /** 이번 로그인 시도를 허용할지. 카운터 증가를 겸한다(허용 여부와 무관하게 시도는 집계). */
    public boolean tryAcquire(String email, String ip) {
        if (ip == null || ip.isBlank()) {
            // IP를 알 수 없으면 키를 만들 수 없다. 차단.
            return false;
        }
        AuthProperties.Signin cfg = authProperties.signin();
        Duration window = cfg.rateLimitWindow();
        boolean allowed = store.increment(IP_KEY + ip, window) <= cfg.rateLimitMaxAttempts();
        if (email != null && !email.isBlank()) {
            allowed &= store.increment(ACCOUNT_IP_KEY + email + ":" + ip, window) <= cfg.maxFailedAttempts();
        }
        return allowed;
    }

    /** 로그인 성공 시 email+IP 윈도우를 리셋한다(정상 사용자가 잦은 재로그인으로 차단되지 않도록). */
    public void resetAccountWindow(String email, String ip) {
        if (email == null || email.isBlank() || ip == null || ip.isBlank()) {
            return;
        }
        store.delete(ACCOUNT_IP_KEY + email + ":" + ip);
    }
}
