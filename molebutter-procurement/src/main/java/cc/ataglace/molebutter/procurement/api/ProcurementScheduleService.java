package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import org.springframework.transaction.annotation.*;


public interface ProcurementScheduleService {
    ScheduleSettings schedule(Long actor);
    ScheduleSettings updateSchedule(Long actor, ScheduleSettings s);
}
