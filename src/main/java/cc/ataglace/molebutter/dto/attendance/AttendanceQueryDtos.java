package cc.ataglace.molebutter.dto.attendance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import cc.ataglace.molebutter.dto.attendance.AttendanceDtos.BreakTime;

public final class AttendanceQueryDtos {
    private AttendanceQueryDtos() {}
    public record Employee(String id, String name, String email, String status) {}
    public record RecordRow(String id, Employee employee, LocalDate workDate, String status,
            LocalDateTime clockIn, LocalDateTime clockOut, Long workedSeconds, Long breakSeconds,
            List<BreakTime> breaks) {}
    public record SummaryRow(String month, Employee employee, long recordedDays, long completedDays,
            long workedSeconds, long breakSeconds, long workingCount, long onBreakCount, long missingCount,
            long unconfirmedCount) {}
}
