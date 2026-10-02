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

    @Autowired cc.ataglace.molebutter.service.attendance.AttendanceQueryService queries;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cc.ataglace.molebutter.repository.AttendanceQueryRepository queryRepository;

    @Test
    void adminSummaryIncludesEligibleZeroUsersAndKeepsNamesakesSeparate() throws Exception {
        String group = "집계-" + UUID.randomUUID();
        User a = queryUser(group, "ACTIVE", "2025-12-01"), locked = queryUser(group, "LOCKED", "2026-02-28"),
                suspended = queryUser(group, "SUSPENDED", "2025-12-01"), pending = queryUser(group, "PENDING", "2025-12-01"),
                future = queryUser(group, "ACTIVE", "2026-03-01");
        Long adminId = users.findByEmail("admin@example.com").orElseThrow().getId();
        var result = queries.summary(adminId, "2026-02", group, null, 0);
        assertThat(result.items()).hasSize(3).allSatisfy(r -> {
            assertThat(r.recordedDays()).isZero(); assertThat(r.workedSeconds()).isZero();
        });
        assertThat(result.items().stream().map(r -> Long.valueOf(r.employee().id())).toList())
                .containsExactlyElementsOf(java.util.stream.Stream.of(a, locked, suspended).map(User::getId).sorted().toList());
        assertThat(queries.summary(adminId, "2025-11", group, null, 0).items()).isEmpty();
        fixtureRecord(pending, "2026-02-01", "COMPLETED", "09:00:00", "18:00:00");
        fixtureRecord(future, "2026-02-02", "MISSING", "09:00:00", null);
        assertThat(queries.summary(adminId, "2026-02", group, null, 0).totalElements()).isEqualTo(5);
        var http = data(admin().get("/api/attendance-manage/summary?month=2026-02&userId=" + a.getId()));
        assertThat(http.path("items").get(0).path("employee").path("id").isString()).isTrue();
        assertThat(http.path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void summaryMatchesPersonalSecondsOvernightAndExcludesUnconfirmed() throws Exception {
        User u = queryUser("시간합산-" + UUID.randomUUID(), "ACTIVE", "2025-01-01");
        long id = fixtureRecord(u, "2026-02-28", "COMPLETED", "22:00:00.500000", null);
        jdbc.update("UPDATE attendance SET clock_out='2026-03-01 08:00:01.200000' WHERE id=?", id);
        jdbc.update("INSERT INTO attendance_break VALUES (?,0,'2026-02-28 23:00:00.800000','2026-02-28 23:10:01.100000'),(?,1,'2026-03-01 03:00:00','2026-03-01 03:20:00')", id, id);
        fixtureRecord(u, "2026-02-01", "WORKING", "09:00:00", null);
        fixtureRecord(u, "2026-02-02", "ON_BREAK", "09:00:00", null);
        fixtureRecord(u, "2026-02-03", "MISSING", "09:00:00", null);
        Long actor = users.findByEmail("admin@example.com").orElseThrow().getId();
        var summary = queries.summary(actor, "2026-02", "", u.getId(), 0).items().getFirst();
        assertThat(summary.recordedDays()).isEqualTo(4); assertThat(summary.completedDays()).isEqualTo(1);
        assertThat(summary.workedSeconds()).isEqualTo(34200); assertThat(summary.breakSeconds()).isEqualTo(1800);
        assertThat(summary.workingCount()).isEqualTo(1); assertThat(summary.onBreakCount()).isEqualTo(1);
        assertThat(summary.missingCount()).isEqualTo(1); assertThat(summary.unconfirmedCount()).isEqualTo(3);
        assertThat(queries.summary(actor, "2026-03", "", u.getId(), 0).items().getFirst().recordedDays()).isZero();
        assertThat(queries.detail(actor, id).workedSeconds()).isEqualTo(attendance.detail(u.getId(), id).workedSeconds());
        assertThat(queries.detail(actor, id).breaks()).hasSize(2);
        var filtered = data(admin().get("/api/attendance-manage/records?from=2026-02-01&to=2026-02-28&status=COMPLETED&userId=" + u.getId()));
        assertThat(filtered.path("items").size()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("breaks").size()).isEqualTo(2);
        assertThat(filtered.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(id));
        assertThat(queries.records(actor, "2026-02-01", "2026-02-28", u.getEmail(), null, null, 0).totalElements()).isEqualTo(4);
    }

    @Test
    void approvedCorrectionUpdatesSummaryOnlyAfterApproval() throws Exception {
        Browser b = staff(UserRole.VIEWER), admin = admin();
        JsonNode row = completeDay(b);
        String userId = data(b.get("/api/auth/me")).path("id").asText();
        String url = "/api/attendance-manage/summary?month=2026-02&userId=" + userId;
        JsonNode c = post(b, "/api/attendance/corrections", correction(row, "2026-02-10", "2026-02-10T08:00:00", "2026-02-10T18:00:00"), 200);
        assertThat(data(admin.get(url)).path("items").get(0).path("workedSeconds").asLong()).isEqualTo(32400);
        post(admin, "/api/attendance-manage/corrections/" + c.path("id").asText() + "/approve", Map.of("revision", 0), 200);
        assertThat(data(admin.get(url)).path("items").get(0).path("workedSeconds").asLong()).isEqualTo(36000);
    }

    @Test
    void csvExportsAllFilteredRowsWithSafeTextLongIdsAndLargeHours() throws Exception {
        User u = queryUser(" =SUM(1,2)\"한글\n다음", "ACTIVE", "2025-01-01");
        for (int day = 1; day <= 22; day++) fixtureRecord(u, "2026-01-%02d".formatted(day), "COMPLETED", "09:00:00", "18:00:00");
        long missing = fixtureRecord(u, "2026-01-23", "MISSING", "09:00:00", null);
        jdbc.update("INSERT INTO attendance_break VALUES (?,0,'2026-01-23 12:00:00',NULL)", missing);
        Browser admin = admin(); String suffix = "?from=2026-01-01&to=2026-01-31&userId=" + u.getId();
        JsonNode page = data(admin.get("/api/attendance-manage/records" + suffix));
        assertThat(page.path("items").size()).isEqualTo(20); assertThat(page.path("totalElements").asInt()).isEqualTo(23);
        JsonNode second = data(admin.get("/api/attendance-manage/records" + suffix + "&page=1"));
        assertThat(second.path("items").size()).isEqualTo(3);
        assertThat(second.path("items").get(2).path("workDate").asText()).isEqualTo("2026-01-01");
        var response = admin.get("/api/attendance-manage/records.csv" + suffix); assertStatus(response, 200);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/csv");
        assertThat(response.headers().firstValue("content-length").orElseThrow()).isEqualTo(Integer.toString(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertThat(response.body()).startsWith("\uFEFF\"직원 ID\"").contains("\"\t" + u.getId() + "\"", "\"' =SUM(1,2)\"\"한글\n다음\"", "미기록", "\"\",\"\",\"2026-01-23");
        assertThat(response.body().split("\r\n")).hasSize(24);
        response = admin.get("/api/attendance-manage/summary.csv?month=2026-01&userId=" + u.getId());
        assertStatus(response, 200);
        assertThat(response.body()).contains("\"198:00:00\"", "\"23\",\"22\"");
        var invalid = admin.get("/api/attendance-manage/records.csv?from=2025-01-01&to=2026-01-02");
        assertStatus(invalid, 400);
        assertThat(invalid.headers().firstValue("content-type").orElseThrow()).contains("application/json");
    }

    @Test
    void exportChunksShareSnapshotEvenWhenCorrectionIsApprovedBetweenChunks() throws Exception {
        String group = "스냅샷-" + UUID.randomUUID();
        User a = queryUser(group, "ACTIVE", "2024-01-01"), b = queryUser(group, "ACTIVE", "2024-01-01");
        long oldest = 0;
        for (int day = 0; day < 251; day++) {
            String date = java.time.LocalDate.of(2025, 1, 1).plusDays(day).toString();
            long id = fixtureRecord(a, date, "COMPLETED", "09:00:00", "18:00:00");
            if (day == 0) oldest = id;
            fixtureRecord(b, date, "COMPLETED", "09:00:00", "18:00:00");
        }
        Long actor = users.findByEmail("admin@example.com").orElseThrow().getId();
        var request = attendance.requestCorrection(a.getId(), new CorrectionRequest(java.time.LocalDate.of(2025, 1, 1), Long.toString(oldest), 0L,
                LocalDateTime.parse("2025-01-01T08:00:00"), LocalDateTime.parse("2025-01-01T18:00:00"), List.of(), "동시 승인"));
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            if ((long) invocation.getArgument(1) == 500L && changed.compareAndSet(false, true)) {
                try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> attendance.review(actor, Long.valueOf(request.id()), true, new ReviewRequest(0L, null))).get(10, TimeUnit.SECONDS);
                }
            }
            return invocation.callRealMethod();
        }).when(queryRepository).records(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        java.nio.file.Path file = null;
        try {
            file = queries.recordsCsv(actor, "2025-01-01", "2025-12-31", group, null, null);
            String csv = java.nio.file.Files.readString(file);
            assertThat(changed).isTrue(); assertThat(csv.split("\r\n")).hasSize(503);
            assertThat(csv).doesNotContain("\"10:00:00\"").contains("\"9:00:00\"");
        } finally {
            org.mockito.Mockito.reset(queryRepository);
            if (file != null) java.nio.file.Files.deleteIfExists(file);
        }
        assertThat(queries.detail(actor, oldest).workedSeconds()).isEqualTo(36000);
    }

    @Test
    void summaryCsvContainsEveryEmployeeBeyondFirstPage() throws Exception {
        String group = "월별전체-" + UUID.randomUUID();
        for (int i = 0; i < 21; i++) queryUser(group + "-%02d".formatted(i), "ACTIVE", "2025-01-01");
        Long actor = users.findByEmail("admin@example.com").orElseThrow().getId();
        var first = queries.summary(actor, "2026-02", group, null, 0);
        var second = queries.summary(actor, "2026-02", group, null, 1);
        assertThat(first.items()).hasSize(20); assertThat(first.totalElements()).isEqualTo(21);
        assertThat(second.items()).hasSize(1); assertThat(second.items().getFirst().employee().name()).endsWith("-20");
        var file = queries.summaryCsv(actor, "2026-02", group, null);
        try {
            String csv = java.nio.file.Files.readString(file);
            assertThat(csv.split("\r\n")).hasSize(22);
            assertThat(csv).contains(group + "-00", group + "-20");
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }

    @Test
    void queryEndpointsRequireCurrentAdminAndValidateFilters() throws Exception {
        Browser admin = admin(), staff = staff(UserRole.VIEWER), anonymous = new Browser();
        String[] urls = {"/records", "/records/1", "/summary", "/records.csv", "/summary.csv"};
        for (String url : urls) {
            assertStatus(staff.get("/api/attendance-manage" + url), 403);
            assertStatus(anonymous.get("/api/attendance-manage" + url), 401);
        }
        for (String filter : List.of("from=2026-02-31", "from=2026-02-10&to=2026-02-01", "from=2025-01-01&to=2026-01-02", "page=-1", "userId=-1", "status=BAD"))
            assertStatus(admin.get("/api/attendance-manage/records?" + filter), 400);
        assertStatus(admin.get("/api/attendance-manage/summary?month=2026-13"), 400);
        assertStatus(admin.get("/api/attendance-manage/records/1"), 404);
        Long actor = users.findByEmail("admin@example.com").orElseThrow().getId();
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?", actor);
        try { for (String url : urls) assertStatus(admin.get("/api/attendance-manage" + url), 401); }
        finally { jdbc.update("UPDATE `user` SET user_status='ACTIVE' WHERE id=?", actor); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='attendance' AND index_name='ix_attendance_work_date_id'", Integer.class)).isEqualTo(2);
    }

    private User queryUser(String name, String status, String created) {
        User u = newUser(UserRole.VIEWER);
        jdbc.update("UPDATE `user` SET name=?, user_status=?, created_at=? WHERE id=?", name, status, created + " 00:00:00", u.getId());
        return u;
    }
    private long fixtureRecord(User user, String date, String status, String start, String end) {
        long id = com.github.f4b6a3.tsid.TsidCreator.getTsid().toLong();
        jdbc.update("INSERT INTO attendance(id,user_id,work_date,clock_in,clock_out,status,revision,created_at) VALUES (?,?,?,?,?,?,0,NOW(6))",
                id, user.getId(), date, date + " " + start, end == null ? null : date + " " + end, status);
        return id;
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
