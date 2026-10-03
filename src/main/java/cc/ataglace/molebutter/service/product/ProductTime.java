package cc.ataglace.molebutter.service.product;
import java.time.*;
import org.springframework.stereotype.Component;
@Component public class ProductTime {
    public LocalDateTime now() {return LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
}
