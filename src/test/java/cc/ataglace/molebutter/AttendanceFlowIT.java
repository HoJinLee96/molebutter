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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;

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

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserRole;
import cc.ataglace.molebutter.domain.UserStatus;
import cc.ataglace.molebutter.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import cc.ataglace.molebutter.service.attendance.AttendanceTime;
import cc.ataglace.molebutter.service.attendance.AttendanceService;
import cc.ataglace.molebutter.dto.attendance.AttendanceDtos.*;
import tools.jackson.databind.JsonNode;

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
@Import({AuthenticationFlowIT.MailConfiguration.class, AttendanceFlowIT.ClockConfig.class})
class AttendanceFlowIT {
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

    @Autowired AttendanceService attendance;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TestTime time;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean @Primary TestTime attendanceTestTime() { return new TestTime(); }
    }
    static class TestTime extends AttendanceTime {
        volatile LocalDateTime value = LocalDateTime.parse("2026-02-10T09:00:00");
        @Override public LocalDateTime now() { return value; }
    }
    @BeforeEach void resetTime() { at("2026-02-10T09:00:00"); }
    void at(String value) { time.value = LocalDateTime.parse(value); }

    @Test
    void workBreaksAndCheckoutCloseTheOpenBreak() throws Exception {
        Browser b = staff(UserRole.VIEWER);
        JsonNode row = post(b, "/api/attendance/clock-in", Map.of(), 200);
        assertThat(row.path("id").isString()).isTrue();
        post(b, "/api/attendance/clock-in", Map.of(), 409);
        at("2026-02-10T12:00:00");
        row = action(b, "break-start", row, 200);
        at("2026-02-10T12:30:00");
        row = action(b, "break-end", row, 200);
        at("2026-02-10T17:30:00");
        row = action(b, "break-start", row, 200);
        at("2026-02-10T18:00:00");
        row = action(b, "clock-out", row, 200);
        assertThat(row.path("record").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(row.path("workedSeconds").asLong()).isEqualTo(8 * 3600);
        assertThat(row.path("breakSeconds").asLong()).isEqualTo(3600);
        assertThat(row.path("record").path("breaks").size()).isEqualTo(2);
        assertThat(row.path("record").path("breaks").get(1).path("endedAt").asText()).contains("18:00");
        action(b, "clock-out", row, 409);
        post(b, "/api/attendance/clock-in", Map.of(), 409);
        assertThat(data(b.get("/api/attendance?month=2026-02")).path("totalElements").asLong()).isEqualTo(1);
        assertThat(data(b.get("/api/attendance?month=2026-01")).path("totalElements").asLong()).isZero();
        assertStatus(b.get("/attendance"), 200);
    }

    @Test
    void overnightCheckoutBelongsToStartDateAndNextDayIsIndependent() throws Exception {
        Browser b = staff(UserRole.PRODUCT);
        at("2026-02-10T23:00:00");
        JsonNode row = post(b, "/api/attendance/clock-in", Map.of(), 200);
        at("2026-02-11T02:00:00");
        row = action(b, "clock-out", row, 200);
        assertThat(row.path("workDate").asText()).isEqualTo("2026-02-10");
        assertThat(row.path("workedSeconds").asLong()).isEqualTo(3 * 3600);
        post(b, "/api/attendance/clock-in", Map.of(), 200);
    }

    @Test
    void nextDayClockInMarksMissingWithoutInventingCheckoutOrBreakEnd() throws Exception {
        Browser b = staff(UserRole.VIEWER);
        JsonNode row = post(b, "/api/attendance/clock-in", Map.of(), 200);
        at("2026-02-10T12:00:00");
        row = action(b, "break-start", row, 200);
        at("2026-02-11T09:00:00");
        post(b, "/api/attendance/clock-in", Map.of(), 409);
        post(b, "/api/attendance/clock-in", Map.of("previousRecordId", row.path("id").asText(),
                "previousRevision", 0, "confirmMissing", true), 409);
        post(b, "/api/attendance/clock-in", Map.of("previousRecordId", row.path("id").asText(),
                "previousRevision", row.path("revision").asLong(), "confirmMissing", true), 200);
        JsonNode missing = data(b.get("/api/attendance/" + row.path("id").asText()));
        assertThat(missing.path("record").path("status").asText()).isEqualTo("MISSING");
        assertThat(missing.path("record").path("clockOut").isNull()).isTrue();
        assertThat(missing.path("record").path("breaks").get(0).path("endedAt").isNull()).isTrue();
        assertThat(missing.path("workedSeconds").isNull()).isTrue();
        action(b, "clock-out", missing, 409);
        Map<String, Object> request = correction(missing, "2026-02-10", "2026-02-10T09:00:00", "2026-02-10T18:00:00");
        request.put("breaks", List.of(Map.of("startedAt", "2026-02-10T12:00:00", "endedAt", "2026-02-10T12:30:00")));
        JsonNode c = post(b, "/api/attendance/corrections", request, 200);
        post(admin(), "/api/attendance-manage/corrections/" + c.path("id").asText() + "/approve", Map.of("revision", 0), 200);
        JsonNode fixed = data(b.get("/api/attendance/" + row.path("id").asText()));
        assertThat(fixed.path("workedSeconds").asLong()).isEqualTo(8 * 3600 + 1800);
    }

    @Test
    void correctionCancelRejectResubmitApproveAndImmutableHistory() throws Exception {
        Browser b = staff(UserRole.VIEWER), admin = admin();
        JsonNode row = completeDay(b);
        var request = correction(row, "2026-02-10", "2026-02-10T08:00:00", "2026-02-10T17:00:00");
        JsonNode c = post(b, "/api/attendance/corrections", request, 200);
        post(b, "/api/attendance/corrections", request, 409);
        post(b, "/api/attendance/corrections/" + c.path("id").asText() + "/cancel", Map.of("revision", 0), 200);
        c = post(b, "/api/attendance/corrections", request, 200);
        String review = "/api/attendance-manage/corrections/" + c.path("id").asText();
        post(admin, review + "/reject", Map.of("revision", 0, "comment", " "), 400);
        post(admin, review + "/reject", Map.of("revision", 0, "comment", "시간을 확인해 주세요"), 200);
        c = post(b, "/api/attendance/corrections", request, 200);
        review = "/api/attendance-manage/corrections/" + c.path("id").asText();
        JsonNode approved = post(admin, review + "/approve", Map.of("revision", 0), 200);
        assertThat(approved.path("before").path("clockIn").asText()).contains("09:00");
        assertThat(approved.path("after").path("clockIn").asText()).contains("08:00");
        assertThat(approved.path("reviewedBy").isString()).isTrue();
        post(admin, review + "/approve", Map.of("revision", 1), 409);
        post(b, "/api/attendance/corrections/" + c.path("id").asText() + "/cancel", Map.of("revision", 1), 409);
        row = data(b.get("/api/attendance/" + row.path("id").asText()));
        JsonNode second = post(b, "/api/attendance/corrections",
                correction(row, "2026-02-10", "2026-02-10T08:30:00", "2026-02-10T17:00:00"), 200);
        post(admin, "/api/attendance-manage/corrections/" + second.path("id").asText() + "/approve", Map.of("revision", 0), 200);
        JsonNode old = data(b.get("/api/attendance/corrections/" + c.path("id").asText()));
        assertThat(old.path("after").path("clockIn").asText()).contains("08:00");
        assertThat(data(b.get("/api/attendance/corrections?month=2026-02")).path("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void missingDateCanBeAddedAndAdminCanApproveOwnRequest() throws Exception {
        Browser admin = admin();
        at("2026-02-10T18:00:00");
        JsonNode c = post(admin, "/api/attendance/corrections",
                correction(null, "2026-01-30", "2026-01-30T09:00:00", "2026-01-30T18:00:00"), 200);
        assertThat(c.path("before").isNull()).isTrue();
        c = post(admin, "/api/attendance-manage/corrections/" + c.path("id").asText() + "/approve", Map.of("revision", 0), 200);
        assertThat(c.path("selfReviewed").asBoolean()).isTrue();
        assertThat(data(admin.get("/api/attendance?month=2026-01")).path("totalElements").asLong()).isEqualTo(1);
        assertStatus(admin.get("/attendance-manage"), 200);
    }

    @Test
    void staleOriginalOrNewRecordBlocksApproval() throws Exception {
        Browser b = staff(UserRole.VIEWER), admin = admin();
        JsonNode row = completeDay(b);
        JsonNode c = post(b, "/api/attendance/corrections",
                correction(row, "2026-02-10", "2026-02-10T08:00:00", "2026-02-10T17:00:00"), 200);
        jdbc.update("UPDATE attendance SET revision=revision+1 WHERE id=?", row.path("id").asText());
        post(admin, "/api/attendance-manage/corrections/" + c.path("id").asText() + "/approve", Map.of("revision", 0), 409);
        assertThat(data(b.get("/api/attendance/corrections/" + c.path("id").asText())).path("status").asText()).isEqualTo("PENDING");
        Browser other = staff(UserRole.VIEWER);
        JsonNode absent = post(other, "/api/attendance/corrections",
                correction(null, "2026-02-10", "2026-02-10T08:00:00", "2026-02-10T17:00:00"), 200);
        post(other, "/api/attendance/clock-in", Map.of(), 200);
        post(admin, "/api/attendance-manage/corrections/" + absent.path("id").asText() + "/approve", Map.of("revision", 0), 409);
    }

    @Test
    void rejectsFutureInvalidBreaksAndOverlappingAdjacentWork() throws Exception {
        Browser b = staff(UserRole.VIEWER);
        JsonNode row = completeDay(b);
        post(b, "/api/attendance/corrections", correction(row, "2026-02-10", "2026-02-10T09:00:00", "2026-02-11T10:00:00"), 400);
        post(b, "/api/attendance/corrections", correction(row, "2026-02-10", "2026-02-10T09:00:00", "2026-02-10T08:00:00"), 400);
        var request = correction(row, "2026-02-10", "2026-02-10T09:00:00", "2026-02-10T18:00:00");
        request.put("breaks", List.of(Map.of("startedAt", "2026-02-10T08:00:00", "endedAt", "2026-02-10T10:00:00")));
        post(b, "/api/attendance/corrections", request, 400);
        request.put("breaks", List.of(Map.of("startedAt", "2026-02-10T12:00:00", "endedAt", "2026-02-10T13:00:00"),
                Map.of("startedAt", "2026-02-10T12:30:00", "endedAt", "2026-02-10T14:00:00")));
        post(b, "/api/attendance/corrections", request, 400);
        at("2026-02-11T09:00:00");
        JsonNode next = post(b, "/api/attendance/clock-in", Map.of(), 200);
        at("2026-02-11T18:00:00");
        action(b, "clock-out", next, 200);
        post(b, "/api/attendance/corrections", correction(row, "2026-02-10", "2026-02-10T09:00:00", "2026-02-11T10:00:00"), 409);
        post(b, "/api/attendance/corrections", correction(null, "2026-02-09", "2026-02-09T09:00:00", "2026-02-10T10:00:00"), 409);
    }

    @Test
    void concurrentClockInBreakAndApprovalHaveOnlyOneWinner() throws Exception {
        User user = newUser(UserRole.VIEWER);
        List<Integer> statuses = concurrent(8, () -> {
            try { attendance.clockIn(user.getId(), new ClockInRequest(null, null, false)); return 200; }
            catch (IllegalStateException ex) { return 409; }
        });
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        RecordView row = attendance.current(user.getId()).active();
        at("2026-02-10T12:00:00");
        statuses = concurrent(8, () -> {
            try { attendance.action(user.getId(), "break-start", new ActionRequest(row.id(), row.revision())); return 200; }
            catch (IllegalStateException ex) { return 409; }
        });
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        at("2026-02-10T18:00:00");
        RecordView active = attendance.current(user.getId()).active();
        RecordView closed = attendance.action(user.getId(), "clock-out", new ActionRequest(active.id(), active.revision()));
        CorrectionRequest proposal = new CorrectionRequest(closed.workDate(), closed.id(), closed.revision(),
                LocalDateTime.parse("2026-02-10T08:00:00"), LocalDateTime.parse("2026-02-10T17:00:00"), List.of(), "정정");
        statuses = concurrent(8, () -> {
            try { attendance.requestCorrection(user.getId(), proposal); return 200; }
            catch (IllegalStateException ex) { return 409; }
        });
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        var c = attendance.myCorrections(user.getId(), "2026-02", 0).items().getFirst();
        Long adminId = users.findByEmail("admin@example.com").orElseThrow().getId();
        statuses = concurrent(8, () -> {
            try { attendance.review(adminId, Long.valueOf(c.id()), true, new ReviewRequest(c.revision(), null)); return 200; }
            catch (IllegalStateException ex) { return 409; }
        });
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(attendance.detail(user.getId(), Long.valueOf(closed.id())).revision()).isEqualTo(closed.revision() + 1);
    }

    @Test
    void rejectsOtherUsersAdminActionsMissingCsrfAndSuspendedUser() throws Exception {
        Browser owner = staff(UserRole.VIEWER), other = staff(UserRole.PRODUCT);
        JsonNode row = completeDay(owner);
        JsonNode c = post(owner, "/api/attendance/corrections", correction(row, "2026-02-10", "2026-02-10T08:00:00", "2026-02-10T17:00:00"), 200);
        assertStatus(other.get("/api/attendance/" + row.path("id").asText()), 404);
        action(other, "clock-out", row, 404);
        assertStatus(other.get("/api/attendance/corrections/" + c.path("id").asText()), 404);
        post(other, "/api/attendance/corrections/" + c.path("id").asText() + "/cancel", Map.of("revision", 0), 404);
        assertStatus(other.get("/api/attendance-manage/corrections"), 403);
        post(other, "/api/attendance-manage/corrections/" + c.path("id").asText() + "/approve", Map.of("revision", 0), 403);
        assertStatus(other.send("POST", "/api/attendance/clock-in", Map.of(), null), 403);
        assertStatus(new Browser().get("/api/attendance/current"), 401);
        Long ownerId = Long.valueOf(data(owner.get("/api/auth/me")).path("id").asText());
        post(admin(), "/api/admin/users/" + ownerId + "/suspend", Map.of(), 200);
        assertStatus(owner.get("/api/attendance/current"), 401);
        post(owner, "/api/attendance/clock-in", Map.of(), 401);
    }

    private JsonNode completeDay(Browser b) throws Exception {
        at("2026-02-10T09:00:00");
        JsonNode row = post(b, "/api/attendance/clock-in", Map.of(), 200);
        at("2026-02-10T18:00:00");
        return action(b, "clock-out", row, 200);
    }
    private Map<String, Object> correction(JsonNode row, String date, String in, String out) {
        Map<String, Object> map = new HashMap<>();
        map.put("workDate", date); map.put("clockIn", in); map.put("clockOut", out);
        map.put("reason", "기록 누락을 정정합니다."); map.put("breaks", List.of());
        if (row != null) { map.put("recordId", row.path("id").asText()); map.put("revision", row.path("revision").asLong()); }
        return map;
    }
    private JsonNode action(Browser b, String action, JsonNode row, int expected) throws Exception {
        return post(b, "/api/attendance/" + action, Map.of("recordId", row.path("id").asText(), "revision", row.path("revision").asLong()), expected);
    }
    private JsonNode post(Browser b, String path, Object body, int expected) throws Exception {
        var response = b.post(path, body); assertStatus(response, expected);
        return json.readTree(response.body()).path("data");
    }
    private JsonNode data(HttpResponse<String> response) {
        assertStatus(response, 200); return json.readTree(response.body()).path("data");
    }
    private User newUser(UserRole role) {
        return users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.com").name("근태 테스트")
                .passwordHash(encoder.encode("Attendance123!")).role(role).status(UserStatus.ACTIVE).build());
    }
    private Browser staff(UserRole role) throws Exception {
        User user = newUser(role); Browser b = new Browser();
        post(b, "/api/auth/signin", Map.of("email", user.getEmail(), "password", "Attendance123!"), 200); return b;
    }
    private Browser admin() throws Exception {
        Browser b = new Browser(); post(b, "/api/auth/signin", Map.of("email", "admin@example.com", "password", "IntegrationAdmin123!"), 200); return b;
    }
    private List<Integer> concurrent(int count, IntSupplier action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1); List<Future<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) tasks.add(executor.submit(() -> { start.await(); return action.getAsInt(); }));
            start.countDown(); List<Integer> result = new ArrayList<>();
            for (var task : tasks) result.add(task.get(20, TimeUnit.SECONDS)); return result;
        }
    }
    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).withFailMessage("HTTP %s: %s", response.statusCode(), response.body()).isEqualTo(expected);
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
