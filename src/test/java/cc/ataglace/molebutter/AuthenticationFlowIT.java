package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cc.ataglace.molebutter.config.AdminBootstrap;
import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.exception.BusinessException;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.EmailSender;
import cc.ataglace.molebutter.service.KeyValueStore;
import cc.ataglace.molebutter.service.auth.AuthTokenService;
import cc.ataglace.molebutter.service.auth.EmailVerificationPurpose;
import cc.ataglace.molebutter.service.auth.EmailVerificationService;
import cc.ataglace.molebutter.service.auth.JwtService;
import tools.jackson.databind.ObjectMapper;

/** scripts/test-integration.sh가 준비한 임시 MySQL/Redis에만 연결한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=classpath:bootstrap-admin-test.properties", "spring.datasource.username=test_app",
        "spring.datasource.password=isolated-test-app-password",
        "spring.flyway.user=test_migrator", "spring.flyway.password=isolated-test-migration-password",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.password=", "mail.provider=test",
        "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes", "auth.cookie.secure=false",
        "auth.signin.rate-limit-max-attempts=1000"
})
@ActiveProfiles("bootstrap-admin")
@Import(AuthenticationFlowIT.MailConfiguration.class)
class AuthenticationFlowIT {
    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        String url = System.getenv("MOLEBUTTER_TEST_DB_URL");
        if (url == null || !url.contains("/molebutter_test?")) {
            throw new IllegalStateException("Run with scripts/test-integration.sh (isolated test database required)");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.data.redis.port", () -> System.getenv("MOLEBUTTER_TEST_REDIS_PORT"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailConfiguration {
        @Bean CapturedMail capturedMail() { return new CapturedMail(); }
    }

    static class CapturedMail implements EmailSender {
        final Map<String, String> codes = new ConcurrentHashMap<>();
        final java.util.Set<String> failOnce = ConcurrentHashMap.newKeySet();
        @Override public void send(String to, String subject, String body) {
            if (failOnce.remove(to)) throw new IllegalStateException("Simulated provider failure");
            codes.put(to, body.substring(body.indexOf(": ") + 2, body.indexOf('\n')));
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CapturedMail mail;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired KeyValueStore store;
    @Autowired AuthTokenService tokens;
    @Autowired JwtService jwt;
    @Autowired EmailVerificationService verification;
    @Autowired AdminBootstrap bootstrap;

    @Test
    void signupApprovalSigninAndRoleRestrictions() throws Exception {
        String email = uniqueEmail();
        Browser staff = signup(email);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 403, "ACCOUNT_PENDING");
        Browser admin = admin();
        long userId = users.findByEmail(email).orElseThrow().getId();
        assertStatus(admin.post("/api/admin/users/" + userId + "/approve", Map.of("role", "VIEWER")), 200);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 200);
        assertStatus(staff.get("/api/auth/me"), 200);
        assertStatus(staff.get("/api/admin/users"), 403);
        assertStatus(admin.get("/api/admin/users"), 200);
        assertStatus(staff.post("/api/auth/signout", Map.of()), 200);
        assertStatus(staff.get("/api/auth/me"), 401);
    }

    @Test
    void csrfMissingAndForgedTokensAreRejectedIncludingSignin() throws Exception {
        Browser browser = new Browser();
        assertStatus(browser.send("POST", "/api/auth/signin", credentials("admin@example.com"), null), 403,
                "INVALID_CSRF_TOKEN");
        browser.csrf();
        assertStatus(browser.send("POST", "/api/auth/signin", credentials("admin@example.com"), "forged"), 403,
                "INVALID_CSRF_TOKEN");
        assertStatus(browser.post("/api/auth/signin", Map.of("email", "admin@example.com",
                "password", "IntegrationAdmin123!")), 200);
    }

    @Test
    void suspensionAndRoleChangeImmediatelyRevokeOldAccessAndRefresh() throws Exception {
        String email = uniqueEmail();
        Browser staff = approvedUser(email);
        Browser admin = admin();
        long id = users.findByEmail(email).orElseThrow().getId();
        assertStatus(admin.post("/api/admin/users/" + id + "/role", Map.of("role", "PRODUCT")), 200);
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 200);
        assertStatus(admin.post("/api/admin/users/" + id + "/suspend", Map.of()), 200);
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 403, "ACCOUNT_SUSPENDED");
    }

    @Test
    void reusedRefreshRevokesEntireFamilyIncludingNewAccess() throws Exception {
        Browser staff = approvedUser(uniqueEmail());
        String oldRefresh = staff.cookies.get("refresh_token");
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 200);
        String newAccess = staff.cookies.get("access_token");
        String newRefresh = staff.cookies.get("refresh_token");
        staff.cookies.put("refresh_token", oldRefresh);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        staff.cookies.put("access_token", newAccess);
        staff.cookies.put("refresh_token", newRefresh);
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
    }

    @Test
    void passwordResetIsSingleUseAndRevokesPreviouslyIssuedTokens() throws Exception {
        String email = uniqueEmail();
        Browser staff = approvedUser(email);
        Browser reset = new Browser();
        Map<String, String> resetBody = Map.of("email", email, "newPassword", "NewPassword456!");
        assertStatus(reset.post("/api/auth/password-reset", resetBody), 400, "EMAIL_NOT_VERIFIED");
        assertStatus(reset.post("/api/auth/password-reset/email-code", Map.of("email", email)), 200);
        assertStatus(reset.post("/api/auth/password-reset/verify-email", Map.of("email", email,
                "code", mail.codes.get(email))), 200);
        assertStatus(reset.post("/api/auth/password-reset", resetBody), 200);
        assertStatus(reset.post("/api/auth/password-reset", Map.of("email", email,
                "newPassword", "OtherPassword789!")), 400, "EMAIL_NOT_VERIFIED");
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        assertStatus(reset.post("/api/auth/signin", credentials(email)), 401);
        assertStatus(reset.post("/api/auth/signin", Map.of("email", email,
                "password", "NewPassword456!")), 200);
    }

    @Test
    void concurrentRefreshHasAtMostOneWinnerAndRevokesReusedFamily() throws Exception {
        String email = uniqueEmail();
        approvedUser(email);
        User user = users.findByEmail(email).orElseThrow();
        var bundle = tokens.issueOnSignin(user);
        List<Integer> result = concurrent(8, () -> {
            try { tokens.rotate(bundle.refreshToken()); return 1; }
            catch (BusinessException e) { return 0; }
        });
        assertThat(result.stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(1);
        assertThat(tokens.isFamilyRevoked(jwt.familyId(jwt.parse(bundle.refreshToken())))).isTrue();
    }

    @Test
    void concurrentEmailSendVerifyAndConsumptionHaveOneWinner() throws Exception {
        String email = uniqueEmail();
        assertThat(concurrent(8, () -> attempt(() -> verification.sendCode(email, EmailVerificationPurpose.SIGNUP))))
                .filteredOn(value -> value == 1).hasSize(1);
        String code = mail.codes.get(email);
        // Two valid requests both fit the attempt budget; only one may consume the code.
        assertThat(concurrent(2, () -> attempt(() -> verification.verifyCode(email, code, EmailVerificationPurpose.SIGNUP))))
                .filteredOn(value -> value == 1).hasSize(1);
        assertThat(concurrent(8, () -> attempt(() -> verification.consumeVerified(email, EmailVerificationPurpose.SIGNUP))))
                .filteredOn(value -> value == 1).hasSize(1);
    }

    @Test
    void failedEmailDoesNotLeaveAUsableCodeAndAllowsRetry() {
        String email = uniqueEmail();
        mail.failOnce.add(email);
        assertThatThrownBy(() -> verification.sendCode(email, EmailVerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class);
        assertThat(store.get("auth:email:code:SIGNUP:" + email)).isEmpty();
        assertThatCode(() -> verification.sendCode(email, EmailVerificationPurpose.SIGNUP)).doesNotThrowAnyException();
        assertThat(mail.codes).containsKey(email);
    }

    @Test
    void tooManyWrongCodesInvalidateEvenTheCorrectCode() {
        String email = uniqueEmail();
        verification.sendCode(email, EmailVerificationPurpose.PASSWORD_RESET);
        String correct = mail.codes.get(email);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> verification.verifyCode(email, "invalid", EmailVerificationPurpose.PASSWORD_RESET))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> verification.verifyCode(email, correct, EmailVerificationPurpose.PASSWORD_RESET))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verification.consumeVerified(email, EmailVerificationPurpose.PASSWORD_RESET))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void redisCountersExpireAndOneTimeTokensHaveOnlyOneConsumer() throws Exception {
        String key = "it:" + UUID.randomUUID();
        assertThat(concurrent(16, () -> (int) store.increment(key, Duration.ofMinutes(1))))
                .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, 16).boxed().toList());
        assertThat(store.getAndDelete(key)).contains("16");
        assertThat(store.getAndDelete(key)).isEmpty();
        assertThat(store.putIfAbsent(key, "value", Duration.ofMillis(100))).isTrue();
        Thread.sleep(150);
        assertThat(store.exists(key)).isFalse();
    }

    @Test
    void migrationsValidateAndRuntimeAccountCannotCreateTables() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE forbidden_ddl (id BIGINT)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void bootstrapNeverOverwritesExistingAdminCredentials() {
        User before = users.findByEmail("admin@example.com").orElseThrow();
        assertThat(before.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(before.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("IntegrationAdmin123!", before.getPasswordHash())).isTrue();
        bootstrap.run(null);
        assertThat(users.findByEmail("admin@example.com").orElseThrow().getPasswordHash())
                .isEqualTo(before.getPasswordHash());
        assertThat(users.findAllByRole(UserRole.ADMIN)).hasSize(1);
    }

    private int attempt(Runnable action) {
        try { action.run(); return 1; } catch (BusinessException e) { return 0; }
    }

    private List<Integer> concurrent(int count, IntSupplier action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) tasks.add(executor.submit(() -> { start.await(); return action.getAsInt(); }));
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (var task : tasks) results.add(task.get(20, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
    }

    private String uniqueEmail() { return "test-" + UUID.randomUUID() + "@example.com"; }
    private Map<String, String> credentials(String email) { return Map.of("email", email, "password", "Password123!"); }

    private Browser admin() throws Exception {
        Browser admin = new Browser();
        assertStatus(admin.post("/api/auth/signin", Map.of("email", "admin@example.com",
                "password", "IntegrationAdmin123!")), 200);
        return admin;
    }

    private Browser signup(String email) throws Exception {
        Browser browser = new Browser();
        assertStatus(browser.post("/api/auth/signup/email-code", Map.of("email", email)), 200);
        assertStatus(browser.post("/api/auth/signup/verify-email", Map.of("email", email,
                "code", mail.codes.get(email))), 200);
        assertStatus(browser.post("/api/auth/signup", Map.of("email", email, "password", "Password123!",
                "name", "테스트", "phoneNumber", "01012345678")), 200);
        return browser;
    }

    private Browser approvedUser(String email) throws Exception {
        Browser browser = signup(email);
        assertStatus(admin().post("/api/admin/users/" + users.findByEmail(email).orElseThrow().getId()
                + "/approve", Map.of("role", "VIEWER")), 200);
        assertStatus(browser.post("/api/auth/signin", credentials(email)), 200);
        return browser;
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).withFailMessage("HTTP %s: %s", response.statusCode(), response.body())
                .isEqualTo(expected);
    }

    private void assertStatus(HttpResponse<String> response, int expected, String code) {
        assertStatus(response, expected);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    class Browser {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final Map<String, String> cookies = new HashMap<>();
        String csrf;
        void csrf() throws Exception {
            var response = get("/api/auth/csrf");
            assertStatus(response, 200);
            csrf = json.readTree(response.body()).path("data").path("token").asText();
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> post(String path, Object body) throws Exception {
            if (csrf == null) csrf();
            var response = send("POST", path, body, csrf);
            if (response.statusCode() == 403 && json.readTree(response.body()).path("code").asText()
                    .equals("INVALID_CSRF_TOKEN")) {
                csrf();
                return send("POST", path, body, csrf);
            }
            return response;
        }
        HttpResponse<String> send(String method, String path, Object body, String csrfHeader) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            if (!cookies.isEmpty()) request.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            if (csrfHeader != null) request.header("X-XSRF-TOKEN", csrfHeader);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String value : response.headers().allValues("set-cookie")) {
                for (HttpCookie cookie : HttpCookie.parse(value)) {
                    if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                    else cookies.put(cookie.getName(), cookie.getValue());
                }
            }
            return response;
        }
    }
}
