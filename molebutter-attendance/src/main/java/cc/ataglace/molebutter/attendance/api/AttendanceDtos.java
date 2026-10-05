package cc.ataglace.molebutter.attendance.api;


import java.time.*;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import cc.ataglace.molebutter.attendance.api.CorrectionStatus;
import cc.ataglace.molebutter.attendance.api.AttendanceStatus;

public final class AttendanceDtos {
    private AttendanceDtos() {}
    public record BreakTime(@NotNull LocalDateTime startedAt, @NotNull LocalDateTime endedAt) {}
    public record Snapshot(LocalDateTime clockIn, LocalDateTime clockOut, AttendanceStatus status, List<BreakTime> breaks) {
    }
    public record RecordView(String id, LocalDate workDate, long revision, Snapshot record, Long workedSeconds, Long breakSeconds) {}
    public record CurrentView(LocalDateTime serverNow, RecordView active, RecordView today) {}
    public record ClockInRequest(String previousRecordId, @PositiveOrZero Long previousRevision, Boolean confirmMissing) {}
    public record ActionRequest(@NotBlank String recordId, @NotNull @PositiveOrZero Long revision) {}
    public record CorrectionRequest(@NotNull LocalDate workDate, String recordId, @PositiveOrZero Long revision,
            @NotNull LocalDateTime clockIn, @NotNull LocalDateTime clockOut,
            @NotNull @Size(max = 100) List<@NotNull @Valid BreakTime> breaks,
            @NotBlank @Size(max = 1000) String reason) {}
    public record ReviewRequest(@NotNull @PositiveOrZero Long revision, @Size(max = 1000) String comment) {}
    public record CorrectionView(String id, String userId, String userName, LocalDate workDate, long revision,
            CorrectionStatus status, String reason, Snapshot before, Snapshot after, LocalDateTime requestedAt,
            String reviewedBy, String reviewerName, LocalDateTime reviewedAt, String reviewComment, boolean selfReviewed) {}
}
