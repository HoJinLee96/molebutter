package cc.ataglace.molebutter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import cc.ataglace.molebutter.config.properties.AuthProperties;
import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.auth.AuthCookieService;
import cc.ataglace.molebutter.service.auth.AuthTokenService;
import cc.ataglace.molebutter.service.auth.JwtService;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class RequestAuthenticationTests {
    private static final String SECRET = "request-policy-test-secret-with-at-least-32-bytes";
    private final UserRepository users = mock(UserRepository.class);
    private final AuthTokenService tokens = mock(AuthTokenService.class);
    private final RequestAuthPolicyResolver policies = new RequestAuthPolicyResolver();
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        AuthProperties properties = new AuthProperties(null,
                new AuthProperties.Jwt(SECRET, Duration.ofMinutes(15), Duration.ofDays(14), "policy-tests"),
                new AuthProperties.Cookie("access", "refresh", "/api/auth", false), null);
        JwtService jwt = new JwtService(properties);
        ReflectionTestUtils.invokeMethod(jwt, "init");
        filter = new JwtAuthenticationFilter(jwt, tokens, new AuthCookieService(properties), users, policies);
        when(tokens.hasCurrentVersion(any(), any())).thenCallRealMethod();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/version-history,BASIC_READ", "HEAD,/version-history,BASIC_READ",
            "GET,/version-history/private,SENSITIVE_READ", "GET,/version-history/,SENSITIVE_READ",
            "GET,/api/auth/me,SENSITIVE_READ", "HEAD,/api/admin/users,SENSITIVE_READ",
            "GET,/my-page,SENSITIVE_READ", "GET,/api/auth-logs,SENSITIVE_READ",
            "GET,/new-feature,SENSITIVE_READ", "POST,/version-history,WRITE",
            "PUT,/version-history,WRITE", "PATCH,/version-history,WRITE", "DELETE,/version-history,WRITE",
            "OPTIONS,/version-history,WRITE"
    })
    void methodAndExactServerPathChoosePolicy(String method, String path, RequestAuthPolicy expected) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Auth-Policy", "BASIC_READ");
        request.setParameter("authPolicy", "BASIC_READ");
        assertThat(policies.resolve(request)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void basicReadUsesSignedIdentityWithoutDatabaseLookup(String method) throws Exception {
        Authentication authentication = authenticate(method, "/version-history", token().compact());
        assertThat(authentication.getPrincipal()).isEqualTo(new UserPrincipal(42L, "token@example.com", UserRole.ADMIN));
        assertThat(authentication.getAuthorities()).extracting("authority").contains("ROLE_ADMIN", "PERM_VERSION_HISTORY");
        verifyNoInteractions(users);
        verify(tokens).isAccessBlacklisted("access-id");
        verify(tokens).isFamilyRevoked("family-id");
        verify(tokens, never()).hasCurrentVersion(any(), any());
    }

    @Test
    void contextPathDoesNotChangeClassification() {
        var request = new MockHttpServletRequest("GET", "/app/version-history");
        request.setContextPath("/app");
        assertThat(policies.resolve(request)).isEqualTo(RequestAuthPolicy.BASIC_READ);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auth/me", "HEAD,/api/auth/me", "GET,/api/admin/users", "GET,/unknown",
            "POST,/version-history", "PUT,/version-history", "PATCH,/version-history", "DELETE,/version-history"})
    void sensitiveReadsAndWritesUseCurrentDatabaseIdentity(String method, String path) throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(activeUser()));
        Authentication authentication = authenticate(method, path, token().compact());
        assertThat(authentication.getPrincipal()).isEqualTo(new UserPrincipal(42L, "current@example.com", UserRole.VIEWER));
        assertThat(authentication.getAuthorities()).extracting("authority").contains("ROLE_VIEWER").doesNotContain("ROLE_ADMIN");
        verify(users).findById(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "suspended", "locked", "pending", "changed-version"})
    void strictRequestsRejectUnavailableOrChangedAccounts(String state) throws Exception {
        User user = activeUser();
        switch (state) {
            case "suspended" -> user.suspend();
            case "locked" -> user.signinFailure(1, java.time.LocalDateTime.now());
            case "pending" -> user = User.builder().role(UserRole.VIEWER).status(UserStatus.PENDING).build();
            case "changed-version" -> user.changeRole(UserRole.PRODUCT);
            default -> { }
        }
        when(users.findById(42L)).thenReturn("missing".equals(state) ? Optional.empty() : Optional.of(user));
        assertThat(authenticate("GET", "/api/auth/me", token().compact())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "family"})
    void basicReadStillRejectsRedisRevocations(String revoked) throws Exception {
        if ("access".equals(revoked)) when(tokens.isAccessBlacklisted("access-id")).thenReturn(true);
        else when(tokens.isFamilyRevoked("family-id")).thenReturn(true);
        assertThat(authenticate("GET", "/version-history", token().compact())).isNull();
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "signature", "issuer", "refresh", "unknown-role", "missing-role",
            "missing-email", "missing-version", "missing-id", "missing-expiry", "invalid-subject"})
    void basicReadRejectsInvalidTokensAndIncompleteIdentity(String invalid) throws Exception {
        JwtBuilder builder = token();
        switch (invalid) {
            case "expired" -> builder.expiration(Date.from(Instant.now().minusSeconds(60)));
            case "signature" -> builder.signWith(Keys.hmacShaKeyFor((SECRET + "different").getBytes(StandardCharsets.UTF_8)));
            case "issuer" -> builder.issuer("other-issuer");
            case "refresh" -> builder.claim("typ", "refresh");
            case "unknown-role" -> builder.claim("role", "UNKNOWN");
            case "missing-role" -> builder.claim("role", null);
            case "missing-email" -> builder.claim("email", null);
            case "missing-version" -> builder.claim("ver", null);
            case "missing-id" -> builder.id(null);
            case "missing-expiry" -> builder.expiration(null);
            case "invalid-subject" -> builder.subject("not-an-id");
            default -> throw new AssertionError(invalid);
        }
        assertThat(authenticate("GET", "/version-history", builder.compact())).isNull();
        verifyNoInteractions(users);
    }

    private JwtBuilder token() {
        return Jwts.builder().issuer("policy-tests").subject("42").id("access-id")
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .claim("typ", "access").claim("fam", "family-id").claim("ver", 0)
                .claim("email", "token@example.com").claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)));
    }

    private User activeUser() {
        return User.builder().email("current@example.com").role(UserRole.VIEWER).status(UserStatus.ACTIVE).build();
    }

    private Authentication authenticate(String method, String path, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Auth-Policy", "BASIC_READ");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> res.setContentType("application/json"));
        assertThat(response.getContentType()).isEqualTo("application/json");
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
