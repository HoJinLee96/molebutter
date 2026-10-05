package cc.ataglace.molebutter.attendance.internal;
import cc.ataglace.molebutter.attendance.internal.Attendance;
import cc.ataglace.molebutter.attendance.api.CorrectionStatus;

import java.time.*;
import cc.ataglace.molebutter.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "attendance_correction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceCorrection extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "work_date", nullable = false) private LocalDate workDate;
    @Column(name = "attendance_id") private Long attendanceId;
    @Column(name = "base_revision") private Long baseRevision;
    @Column(nullable = false) private long revision;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CorrectionStatus status;
    @Column(nullable = false, length = 1000) private String reason;
    @Column(name = "original_snapshot", columnDefinition = "LONGTEXT") private String originalSnapshot;
    @Column(name = "proposed_snapshot", nullable = false, columnDefinition = "LONGTEXT") private String proposedSnapshot;
    @Column(name = "reviewed_by") private Long reviewedBy;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    @Column(name = "review_comment", length = 1000) private String reviewComment;

    public AttendanceCorrection(Long userId, LocalDate date, Attendance original, String before, String after, String reason) {
        this.userId = userId;
        workDate = date;
        attendanceId = original == null ? null : original.getId();
        baseRevision = original == null ? null : original.getRevision();
        originalSnapshot = before;
        proposedSnapshot = after;
        this.reason = reason;
        status = CorrectionStatus.PENDING;
    }

    public void cancel() { status = CorrectionStatus.CANCELLED; revision++; }

    public void review(boolean approved, Long reviewer, LocalDateTime now, String comment) {
        status = approved ? CorrectionStatus.APPROVED : CorrectionStatus.REJECTED;
        reviewedBy = reviewer;
        reviewedAt = now;
        reviewComment = comment;
        revision++;
    }
}
