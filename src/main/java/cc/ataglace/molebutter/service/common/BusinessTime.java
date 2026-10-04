package cc.ataglace.molebutter.service.common;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
/** Shared business clock. Authentication/signing instants stay in UTC; display/business dates use Seoul. */
@Component
public class BusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static LocalDateTime koreaNow() { return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MICROS); }
    public LocalDateTime now() { return koreaNow(); }
}
