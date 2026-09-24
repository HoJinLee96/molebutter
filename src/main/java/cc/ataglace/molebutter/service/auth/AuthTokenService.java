package cc.ataglace.molebutter.service.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.exception.ErrorCode;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.KeyValueStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 토큰 라이프사이클: 발급 / refresh (절대 상한·reuse 탐지) / logout 폐기·blacklist.
 *
 * <p>
 * refresh는 jti 기준으로 서버 저장소에 둔다. 회전 시 절대 만료(exp)는 연장하지 않는다.
 * 폐기된 refresh가 재사용되면 해당 family 전체를 무효화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private static final String REFRESH_KEY = "auth:refresh:";
    private static final String FAMILY_REVOKED_KEY = "auth:reffam:";
    private static final String ACCESS_BLACKLIST_KEY = "auth:bl:";

    private final JwtService jwtService;
    private final KeyValueStore store;
    private final AuthProperties authProperties;
    private final UserRepository userRepository;

    public record TokenBundle(String accessToken, String refreshToken, User user) {
    }

    /** 로그인 성공 시 access/refresh 한 쌍을 발급하고 refresh jti를 저장한다. */
    public TokenBundle issueOnSignin(User user) {
        String familyId = jwtService.newId();
        Instant absoluteExpiry = Instant.now().plus(authProperties.jwt().refreshTtl());
        return issueTokenBundle(user, familyId, absoluteExpiry);
    }

    /**
     * refresh 토큰 회전. 검증 → reuse/family 확인 → 계정 상태/비밀번호 변경 확인 → 새 토큰쌍 발급.
     * 실패는 모두 {@link ErrorCode#INVALID_TOKEN}으로 일반화한다.
     */
    public TokenBundle rotate(String refreshToken) {
        Claims claims = parseRefreshOrThrow(refreshToken);
        String jti = claims.getId();
        String familyId = jwtService.familyId(claims);
        Long userId = jwtService.userId(claims);
        Instant absoluteExpiry = claims.getExpiration().toInstant();

        if (familyId == null || jti == null || userId == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        // family가 이미 무효화되었으면 거부.
        if (store.exists(FAMILY_REVOKED_KEY + familyId)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        // 저장소에 jti가 없으면 = 이미 회전됨/로그아웃됨/재사용. 탈취로 보고 family 전체 무효화.
        if (store.getAndDelete(REFRESH_KEY + jti).isEmpty()) {
            revokeFamily(familyId, absoluteExpiry);
            log.warn("[AUTH] refresh reuse 감지 → family 무효화 familyId={}", familyId);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isSigninBlocked()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        // 비밀번호 변경 이후 발급된 토큰만 유효(변경 전 발급 refresh는 거부).
        if (!hasCurrentVersion(claims, user)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 기존 jti는 위에서 원자적으로 소비했다. 같은 family·절대만료를 유지한다.
        return issueTokenBundle(user, familyId, absoluteExpiry);
    }

    /**
     * 로그아웃: access는 남은 TTL 동안 blacklist에 올리고, refresh는 저장 폐기 + family 무효화한다.
     * 위조·만료 토큰은 조용히 무시한다(로그아웃은 항상 성공해야 한다).
     */
    public void revokeOnLogout(String accessToken, String refreshToken) {
        blacklistAccess(accessToken);
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtService.parse(refreshToken);
            if (!jwtService.isType(claims, JwtService.TYPE_REFRESH)) {
                return;
            }
            if (claims.getId() != null) {
                store.delete(REFRESH_KEY + claims.getId());
            }
            String familyId = jwtService.familyId(claims);
            if (familyId != null && claims.getExpiration() != null) {
                revokeFamily(familyId, claims.getExpiration().toInstant());
            }
        } catch (JwtException e) {
            log.debug("[AUTH] 로그아웃 refresh 파싱 실패(무시): {}", e.getMessage());
        }
    }

    /** access token을 남은 TTL 동안 blacklist에 올린다. 위조·만료 토큰은 무시한다. */
    public void blacklistAccess(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtService.parse(accessToken);
            if (!jwtService.isType(claims, JwtService.TYPE_ACCESS) || claims.getId() == null) {
                return;
            }
            Duration ttl = jwtService.remainingTtl(claims);
            if (!ttl.isZero()) {
                store.put(ACCESS_BLACKLIST_KEY + claims.getId(), "revoked", ttl);
            }
        } catch (JwtException e) {
            log.debug("[AUTH] access blacklist 파싱 실패(무시): {}", e.getMessage());
        }
    }

    /** access jti가 로그아웃/비밀번호 변경으로 폐기된 상태인지(JwtAuthenticationFilter에서 사용). */
    public boolean isAccessBlacklisted(String jti) {
        return jti != null && store.exists(ACCESS_BLACKLIST_KEY + jti);
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private TokenBundle issueTokenBundle(User user, String familyId, Instant absoluteExpiry) {
        Duration remaining = remainingUntil(absoluteExpiry);
        if (remaining.isZero()) {
            // 절대 상한 도달: 더 이상 갱신하지 않는다(재로그인 필요).
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        String jti = jwtService.newId();
        String refresh = jwtService.createRefreshToken(user, familyId, jti, absoluteExpiry);
        store.put(REFRESH_KEY + jti, user.getId() + ":" + familyId, remaining);
        String access = jwtService.createAccessToken(user, familyId);
        if (isFamilyRevoked(familyId)) {
            store.delete(REFRESH_KEY + jti);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        return new TokenBundle(access, refresh, user);
    }

    private Claims parseRefreshOrThrow(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        try {
            Claims claims = jwtService.parse(refreshToken);
            if (!jwtService.isType(claims, JwtService.TYPE_REFRESH)) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }
            return claims;
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }

    private void revokeFamily(String familyId, Instant absoluteExpiry) {
        Duration ttl = remainingUntil(absoluteExpiry);
        if (!ttl.isZero()) {
            store.put(FAMILY_REVOKED_KEY + familyId, "revoked", ttl);
        }
    }

    public boolean hasCurrentVersion(Claims claims, User user) {
        Number version = claims.get("ver", Number.class);
        return version != null && version.longValue() == user.getAuthVersion();
    }

    public boolean isFamilyRevoked(String familyId) {
        return familyId == null || store.exists(FAMILY_REVOKED_KEY + familyId);
    }

    /**
     * 절대 만료 시각까지 남은 시간을 반환한다.
     */
    private Duration remainingUntil(Instant absoluteExpiry) {
        long millis = absoluteExpiry.toEpochMilli() - System.currentTimeMillis();
        return millis <= 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }
}
