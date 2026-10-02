package cc.ataglace.molebutter.domain.attendance;

import java.time.*;
import java.util.*;
import cc.ataglace.molebutter.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "attendance", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "work_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attendance extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "work_date", nullable = false) private LocalDate workDate;
    @Column(name = "clock_in", nullable = false) private LocalDateTime clockIn;
    @Column(name = "clock_out") private LocalDateTime clockOut;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AttendanceStatus status;
    @Column(nullable = false) private long revision;
    @ElementCollection
    @CollectionTable(name = "attendance_break", joinColumns = @JoinColumn(name = "attendance_id"))
    @OrderColumn(name = "break_order")
    private List<AttendanceBreak> breaks = new ArrayList<>();

    public Attendance(Long userId, LocalDateTime now) {
        this.userId = userId;
        workDate = now.toLocalDate();
        clockIn = now;
        status = AttendanceStatus.WORKING;
    }

    public boolean isActive() { return status == AttendanceStatus.WORKING || status == AttendanceStatus.ON_BREAK; }

    public void startBreak(LocalDateTime now) {
        breaks.add(new AttendanceBreak(now, null));
        status = AttendanceStatus.ON_BREAK;
        revision++;
    }

    public void endBreak(LocalDateTime now) {
        breaks.getLast().end(now);
        status = AttendanceStatus.WORKING;
        revision++;
    }

    public void clockOut(LocalDateTime now) {
        if (status == AttendanceStatus.ON_BREAK) breaks.getLast().end(now);
        clockOut = now;
        status = AttendanceStatus.COMPLETED;
        revision++;
    }

    /** 누락된 시각은 추측하지 않는다. 열려 있던 휴게도 정정 전까지 미완료로 남긴다. */
    public void markMissing() { status = AttendanceStatus.MISSING; revision++; }

    public void correct(LocalDateTime in, LocalDateTime out, List<AttendanceBreak> replacement) {
        clockIn = in;
        clockOut = out;
        breaks.clear();
        breaks.addAll(replacement);
        status = AttendanceStatus.COMPLETED;
        revision++;
    }
}
