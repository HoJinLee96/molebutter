package cc.ataglace.molebutter.service.product;
import java.time.*;
import org.springframework.stereotype.Component;
@Component public class ProductTime {
    public LocalDateTime now() {return cc.ataglace.molebutter.service.common.BusinessTime.koreaNow();}
}
