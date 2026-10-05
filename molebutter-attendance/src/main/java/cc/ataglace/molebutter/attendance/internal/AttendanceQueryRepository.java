package cc.ataglace.molebutter.attendance.internal;
import cc.ataglace.molebutter.attendance.api.AttendanceQueryDtos.*;


import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import cc.ataglace.molebutter.attendance.api.AttendanceDtos.BreakTime;

import lombok.RequiredArgsConstructor;

/** 조회 전용 SQL. 휴게를 기록 단위로 먼저 합쳐 직원별 집계에서 근무시간이 늘어나지 않게 한다. */
@Repository
@RequiredArgsConstructor
public class AttendanceQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String CTE = """
        WITH filtered AS (
            SELECT a.* FROM attendance a WHERE a.work_date BETWEEN :from AND :to
        ), rests AS (
            SELECT b.attendance_id, SUM(TIMESTAMPDIFF(SECOND, b.started_at, b.ended_at)) AS seconds
            FROM attendance_break b JOIN filtered a ON a.id = b.attendance_id
            GROUP BY b.attendance_id
        ), timed AS (
            SELECT a.*, CASE WHEN a.status = 'COMPLETED' THEN COALESCE(r.seconds, 0) END AS rest_seconds,
                CASE WHEN a.status = 'COMPLETED' THEN
                    GREATEST(0, TIMESTAMPDIFF(SECOND, a.clock_in, a.clock_out) - COALESCE(r.seconds, 0))
                END AS work_seconds
            FROM filtered a LEFT JOIN rests r ON r.attendance_id = a.id
        )
        """;
    private static final String EMPLOYEE_FILTER = """
        AND (:userId IS NULL OR u.id = :userId)
        AND (:q = '' OR LOCATE(:q, u.name) > 0 OR LOCATE(:q, u.email) > 0)
        """;
    private static final String RECORD_FROM = """
        FROM timed a JOIN `user` u ON u.id = a.user_id
        WHERE (:status IS NULL OR a.status = :status)
        """ + EMPLOYEE_FILTER;
    private static final String SUMMARY_CTE = CTE + """
        , totals AS (
            SELECT user_id, COUNT(*) recorded_days, SUM(status = 'COMPLETED') completed_days,
                COALESCE(SUM(work_seconds), 0) work_seconds, COALESCE(SUM(rest_seconds), 0) rest_seconds,
                SUM(status = 'WORKING') working_count, SUM(status = 'ON_BREAK') on_break_count,
                SUM(status = 'MISSING') missing_count
            FROM timed GROUP BY user_id
        )
        """;
    private static final String SUMMARY_FROM = """
        FROM `user` u LEFT JOIN totals t ON t.user_id = u.id
        WHERE ((u.created_at < :until AND u.user_status <> 'PENDING') OR t.recorded_days > 0)
        """ + EMPLOYEE_FILTER;

    public long recordCount(Map<String, Object> params) {
        return jdbc.queryForObject(CTE + "SELECT COUNT(*) " + RECORD_FROM, params, Long.class);
    }
    public List<RecordRow> records(Map<String, Object> params, long offset, int limit) {
        var p = new HashMap<>(params); p.put("offset", offset); p.put("limit", limit);
        var rows = jdbc.query(CTE + """
            SELECT a.*, u.name, u.email, u.user_status
            """ + RECORD_FROM + " ORDER BY a.work_date DESC, a.id DESC LIMIT :limit OFFSET :offset", p,
            (rs, n) -> record(rs));
        return withBreaks(rows);
    }
    public Optional<RecordRow> detail(long id) {
        var rows = jdbc.query("""
            SELECT a.*, u.name, u.email, u.user_status,
                CASE WHEN a.status = 'COMPLETED' THEN COALESCE(r.seconds, 0) END rest_seconds,
                CASE WHEN a.status = 'COMPLETED' THEN GREATEST(0,
                    TIMESTAMPDIFF(SECOND, a.clock_in, a.clock_out) - COALESCE(r.seconds, 0)) END work_seconds
            FROM attendance a JOIN `user` u ON u.id = a.user_id LEFT JOIN (
                SELECT attendance_id, SUM(TIMESTAMPDIFF(SECOND, started_at, ended_at)) seconds
                FROM attendance_break WHERE attendance_id = :id GROUP BY attendance_id
            ) r ON r.attendance_id = a.id WHERE a.id = :id
            """, Map.of("id", id), (rs, n) -> record(rs));
        return withBreaks(rows).stream().findFirst();
    }
    private List<RecordRow> withBreaks(List<RecordRow> rows) {
        if (rows.isEmpty()) return rows;
        Map<String, List<BreakTime>> intervals = new HashMap<>();
        jdbc.query("""
            SELECT attendance_id, started_at, ended_at FROM attendance_break
            WHERE attendance_id IN (:ids) ORDER BY attendance_id, break_order
            """, Map.of("ids", rows.stream().map(r -> Long.valueOf(r.id())).toList()), rs -> {
                intervals.computeIfAbsent(rs.getString("attendance_id"), k -> new ArrayList<>())
                    .add(new BreakTime(time(rs, "started_at"), time(rs, "ended_at")));
            });
        return rows.stream().map(r -> new RecordRow(r.id(), r.employee(), r.workDate(), r.status(),
                r.clockIn(), r.clockOut(), r.workedSeconds(), r.breakSeconds(),
                List.copyOf(intervals.getOrDefault(r.id(), List.of())))).toList();
    }
    public long summaryCount(Map<String, Object> params) {
        return jdbc.queryForObject(SUMMARY_CTE + "SELECT COUNT(*) " + SUMMARY_FROM, params, Long.class);
    }
    public List<SummaryRow> summaries(Map<String, Object> params, long offset, int limit) {
        var p = new HashMap<>(params); p.put("offset", offset); p.put("limit", limit);
        return jdbc.query(SUMMARY_CTE + "SELECT u.id user_id, u.name, u.email, u.user_status, t.recorded_days, t.completed_days, "
                + "t.work_seconds, t.rest_seconds, t.working_count, t.on_break_count, t.missing_count " + SUMMARY_FROM
                + " ORDER BY u.name ASC, u.id ASC LIMIT :limit OFFSET :offset", p, (rs, n) -> {
            long working = rs.getLong("working_count"), resting = rs.getLong("on_break_count"), missing = rs.getLong("missing_count");
            return new SummaryRow(params.get("month").toString(), employee(rs), rs.getLong("recorded_days"),
                    rs.getLong("completed_days"), rs.getLong("work_seconds"), rs.getLong("rest_seconds"),
                    working, resting, missing, working + resting + missing);
        });
    }
    private static Employee employee(ResultSet rs) throws SQLException {
        return new Employee(rs.getString("user_id"), rs.getString("name"), rs.getString("email"), rs.getString("user_status"));
    }
    private static RecordRow record(ResultSet rs) throws SQLException {
        return new RecordRow(rs.getString("id"), employee(rs), rs.getDate("work_date").toLocalDate(),
                rs.getString("status"), time(rs, "clock_in"), time(rs, "clock_out"),
                rs.getObject("work_seconds", Long.class), rs.getObject("rest_seconds", Long.class), List.of());
    }
    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime();
    }
}
