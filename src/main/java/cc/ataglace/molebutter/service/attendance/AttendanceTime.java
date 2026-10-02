package cc.ataglace.molebutter.service.attendance;

import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** JVM/DB 기본 시간대와 관계없이 근태는 한국 시간으로 기록한다. 테스트에서 교체할 수 있다. */
@Component
public class AttendanceTime {
    public LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.MICROS);
    }
}
