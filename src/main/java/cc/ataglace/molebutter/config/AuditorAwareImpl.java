package cc.ataglace.molebutter.config;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JPA 감사(createdBy/updatedBy)의 주체를 제공한다. {@link JpaAuditingConfig}에서 빈으로 등록한다
 * (@DataJpaTest가 JpaAuditingConfig를 import할 때도 같은 빈이 따라오도록 @Component 대신 @Bean으로 둔다).
 *
 * <p>중요(계획 §11.2): 인증 컨텍스트가 없는 경로(앱 기동 중 seed, 내부 스케줄러/배치)에서도
 * 절대 예외를 던지지 않는다. Authentication이 없거나 익명이면 "SYSTEM"을 반환한다.
 */
public class AuditorAwareImpl implements AuditorAware<String> {

    private static final String SYSTEM_ACTOR = "SYSTEM";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.of(SYSTEM_ACTOR);
        }
        String name = authentication.getName();
        return Optional.of(name == null || name.isBlank() ? SYSTEM_ACTOR : name);
    }
}