package cc.ataglace.molebutter.attendance.internal;
import cc.ataglace.molebutter.attendance.api.AttendanceQueryDtos.*;
import cc.ataglace.molebutter.attendance.internal.AttendanceTime;
import cc.ataglace.molebutter.identity.api.IdentityAccounts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.attendance.api.AttendanceStatus;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.attendance.internal.AttendanceQueryRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DefaultAttendanceQueryService implements cc.ataglace.molebutter.attendance.api.AttendanceQueryService {
    private final AttendanceQueryRepository queries;
    private final IdentityAccounts users;
    private final AttendanceTime time;
    private static final int PAGE_SIZE = 20, EXPORT_CHUNK = 500;

    public PageResponse<RecordRow> records(Long actor, String from, String to, String q, Long userId, AttendanceStatus status, int page) {
        authorize(actor); checkPage(page);
        var filter = recordFilter(from, to, q, userId, status);
        long count = queries.recordCount(filter);
        return new PageResponse<>(queries.records(filter, (long) page * PAGE_SIZE, PAGE_SIZE), page, pages(count), count);
    }
    public RecordRow detail(Long actor, long id) {
        authorize(actor);
        return queries.detail(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    public PageResponse<SummaryRow> summary(Long actor, String month, String q, Long userId, int page) {
        authorize(actor); checkPage(page);
        var filter = summaryFilter(month, q, userId);
        long count = queries.summaryCount(filter);
        return new PageResponse<>(queries.summaries(filter, (long) page * PAGE_SIZE, PAGE_SIZE), page, pages(count), count);
    }
    // 응답을 시작하기 전에 파일을 완성한다. 생성 실패는 JSON 오류로 전달하며 파일은 항상 정리한다.
    // 모든 청크는 이 메서드의 같은 REPEATABLE_READ 트랜잭션/커넥션을 사용한다.
    public Path recordsCsv(Long actor, String from, String to, String q, Long userId, AttendanceStatus status) throws IOException {
        authorize(actor);
        var filter = recordFilter(from, to, q, userId, status);
        return file(writer -> {
            csv(writer, "직원 ID", "이름", "이메일", "계정 상태", "근무일", "근태 상태", "출근", "퇴근", "확정 순근무시간", "확정 휴게시간", "휴게 구간");
            for (long offset = 0; ; offset += EXPORT_CHUNK) {
                var rows = queries.records(filter, offset, EXPORT_CHUNK);
                for (var r : rows) csv(writer, textId(r.employee().id()), safe(r.employee().name()), safe(r.employee().email()),
                        label(r.employee().status()), r.workDate(), label(r.status()), stamp(r.clockIn()), stamp(r.clockOut()),
                        duration(r.workedSeconds()), duration(r.breakSeconds()), r.breaks().stream()
                            .map(b -> stamp(b.startedAt()) + " ~ " + (b.endedAt() == null ? "미기록" : stamp(b.endedAt())))
                            .collect(Collectors.joining(" / ")));
                if (rows.size() < EXPORT_CHUNK) break;
            }
        });
    }
    public Path summaryCsv(Long actor, String month, String q, Long userId) throws IOException {
        authorize(actor);
        var filter = summaryFilter(month, q, userId);
        return file(writer -> {
            csv(writer, "집계 월", "직원 ID", "이름", "이메일", "계정 상태", "기록일 수", "퇴근 완료일 수", "확정 순근무시간", "확정 휴게시간", "근무 중", "휴게 중", "퇴근 누락", "미확정 건수");
            for (long offset = 0; ; offset += EXPORT_CHUNK) {
                var rows = queries.summaries(filter, offset, EXPORT_CHUNK);
                for (var r : rows) csv(writer, r.month(), textId(r.employee().id()), safe(r.employee().name()), safe(r.employee().email()),
                        label(r.employee().status()), r.recordedDays(), r.completedDays(), duration(r.workedSeconds()), duration(r.breakSeconds()),
                        r.workingCount(), r.onBreakCount(), r.missingCount(), r.unconfirmedCount());
                if (rows.size() < EXPORT_CHUNK) break;
            }
        });
    }
    private Map<String, Object> recordFilter(String from, String to, String q, Long userId, AttendanceStatus status) {
        YearMonth current = YearMonth.from(time.now());
        try {
            LocalDate start = from == null || from.isBlank() ? current.atDay(1) : LocalDate.parse(from);
            LocalDate end = to == null || to.isBlank() ? current.atEndOfMonth() : LocalDate.parse(to);
            if (start.getYear() < 1000 || end.getYear() > 9999 || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 366)
                throw new InputValidationFailure("조회 기간은 시작일부터 종료일까지 최대 366일입니다.");
            var p = common(q, userId); p.put("from", start); p.put("to", end); p.put("status", status == null ? null : status.name());
            return p;
        } catch (DateTimeParseException e) { throw new InputValidationFailure("날짜는 YYYY-MM-DD 형식으로 입력해 주세요."); }
    }
    private Map<String, Object> summaryFilter(String month, String q, Long userId) {
        try {
            YearMonth m = month == null || month.isBlank() ? YearMonth.from(time.now()) : YearMonth.parse(month);
            if (m.getYear() < 1000 || m.getYear() > 9998) throw new InputValidationFailure("조회 월이 올바르지 않습니다.");
            var p = common(q, userId); p.put("month", m.toString()); p.put("from", m.atDay(1)); p.put("to", m.atEndOfMonth());
            p.put("until", m.plusMonths(1).atDay(1).atStartOfDay()); return p;
        } catch (DateTimeParseException e) { throw new InputValidationFailure("월은 YYYY-MM 형식으로 입력해 주세요."); }
    }
    private Map<String, Object> common(String q, Long userId) {
        String query = q == null ? "" : q.trim();
        if (query.length() > 255 || (userId != null && userId <= 0)) throw new InputValidationFailure("직원 검색 조건이 올바르지 않습니다.");
        Map<String, Object> p = new HashMap<>(); p.put("q", query); p.put("userId", userId); return p;
    }
    private void authorize(Long actor) {
        var user = users.findById(actor).orElseThrow(() -> new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED));
        if (user.isSigninBlocked() || user.getRole() != UserRole.ADMIN) throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
    }
    private static void checkPage(int page) { if (page < 0) throw new InputValidationFailure("페이지는 0 이상이어야 합니다."); }
    private static int pages(long count) { return (int) Math.min(Integer.MAX_VALUE, (count + PAGE_SIZE - 1) / PAGE_SIZE); }
    @FunctionalInterface private interface Output { void write(Writer writer) throws IOException; }
    private static Path file(Output output) throws IOException {
        Path path = Files.createTempFile("molebutter-attendance-", ".csv");
        boolean complete = false;
        try {
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF'); output.write(writer);
            }
            complete = true; return path;
        } finally { if (!complete) Files.deleteIfExists(path); }
    }
    static void csv(Writer writer, Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write('"'); writer.write(Objects.toString(values[i], "").replace("\"", "\"\"")); writer.write('"');
        }
        writer.write("\r\n");
    }
    // CSV에는 타입이 없으므로 Excel이 긴 ID를 숫자로 반올림하지 않도록 텍스트 접두사를 넣는다.
    static String textId(String id) { return "\t" + id; }
    static String safe(String text) {
        if (text == null) return "";
        String leading = text.stripLeading();
        boolean control = text.chars().anyMatch(c -> c == '\t' || c == '\r' || c == '\n');
        return control || (!leading.isEmpty() && "=+-@＝＋－＠".indexOf(leading.charAt(0)) >= 0) ? "'" + text : text;
    }
    static String duration(Long seconds) {
        if (seconds == null) return "";
        return "%d:%02d:%02d".formatted(seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }
    private static String stamp(LocalDateTime time) { return time == null ? "" : time.toString().replace('T', ' '); }
    private static String label(String status) {
        return switch (status) {
            case "ACTIVE" -> "정상"; case "PENDING" -> "승인 대기"; case "LOCKED" -> "잠금"; case "SUSPENDED" -> "정지";
            case "COMPLETED" -> "퇴근 완료"; case "WORKING" -> "근무 중"; case "ON_BREAK" -> "휴게 중"; case "MISSING" -> "퇴근 누락";
            default -> status;
        };
    }
}
