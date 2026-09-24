package cc.ataglace.molebutter.service.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT 문자열의 발급·검증만 담당한다(서명·issuer·만료·타입 검사). 토큰의 저장·회전·폐기 정책은 호출 쪽의 몫.
 */
@Slf4j
@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_FAMILY = "fam";

    private final AuthProperties authProperties;
    private SecretKey signingKey;

    public JwtService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostConstruct
    void init() {
        byte[] secretBytes = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "auth.jwt.secret은 UTF-8 기준 32바이트(256비트) 이상이어야 합니다. 현재 " + secretBytes.length + "바이트");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    /** 토큰 식별자(jti·familyId)용 랜덤 UUID를 생성한다. */
    public String newId() {
        return UUID.randomUUID().toString();
    }

    /** access token 발급. */
    public String createAccessToken(User user, String familyId) {
        Instant now = Instant.now();
        Instant exp = now.plus(authProperties.jwt().accessTtl());
        return Jwts.builder()
                .issuer(authProperties.jwt().issuer())
                .subject(String.valueOf(user.getId()))
                .id(newId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim("ver", user.getAuthVersion())
                .claim(CLAIM_FAMILY, familyId)
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .signWith(signingKey)
                .compact();
    }

    /**
     * refresh token 발급. exp는 절대 만료(absoluteExpiry)로 고정한다. 회전 시 같은 familyId와 absoluteExpiry를 넘겨
     * 만료를 연장하지 않는다.
     */
    public String createRefreshToken(User user, String familyId, String jti, Instant absoluteExpiry) {
        return Jwts.builder()
                .issuer(authProperties.jwt().issuer())
                .subject(String.valueOf(user.getId()))
                .id(jti)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(absoluteExpiry))
                .claim("ver", user.getAuthVersion())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .claim(CLAIM_FAMILY, familyId)
                .signWith(signingKey)
                .compact();
    }

    /** 토큰을 검증(서명·issuer·만료)하고 Claims를 반환한다. 실패 시 {@link JwtException}. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(authProperties.jwt().issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 토큰의 type claim이 기대값(access/refresh)인지 확인한다. */
    public boolean isType(Claims claims, String expectedType) {
        return expectedType.equals(claims.get(CLAIM_TYPE, String.class));
    }

    /** claim에서 이메일(로그인 식별자)을 꺼낸다. */
    public String email(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }

    /** claim에서 사용자 역할명을 꺼낸다. */
    public String role(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    /** refresh claim에서 familyId(세션 계보)를 꺼낸다. */
    public String familyId(Claims claims) {
        return claims.get(CLAIM_FAMILY, String.class);
    }

    /** subject에서 userId를 꺼낸다. 숫자가 아니면 null. */
    public Long userId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 토큰 만료까지 남은 시간(0 이상). 이미 만료면 Duration.ZERO. */
    public Duration remainingTtl(Claims claims) {
        if (claims.getExpiration() == null) {
            return Duration.ZERO;
        }
        long millis = claims.getExpiration().getTime() - System.currentTimeMillis();
        return millis <= 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }
}
