package cc.ataglace.molebutter.domain.attendance;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceBreak {
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public AttendanceBreak(LocalDateTime start, LocalDateTime end) {
        this.startedAt = start;
        this.endedAt = end;
    }

    void end(LocalDateTime now) { endedAt = now; }
}
