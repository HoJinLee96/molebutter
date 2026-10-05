package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.ProductStore;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
@Service @lombok.RequiredArgsConstructor
public class DefaultProcurementScheduleService implements cc.ataglace.molebutter.procurement.api.ProcurementScheduleService {
    private final ProductStore db;
    public ScheduleSettings schedule(Long actor) {
        db.authorize(actor, false);
        return db.schedule(false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleSettings updateSchedule(Long actor, ScheduleSettings s) {
        db.authorize(actor, true);
        var current = db.schedule(true);
        ProductStore.revision(current.revision(), s.revision());
        try {
            if (s.scheduleTime() == null || !s.scheduleTime().matches("[0-2][0-9]:[0-5][0-9]"))
                throw new IllegalArgumentException();
            LocalTime.parse(s.scheduleTime());
        } catch (Exception e) {
            throw new InputValidationFailure("예약 시각을 확인해 주세요.");
        }
        db.jdbc.update("UPDATE procurement_settings SET schedule_enabled=?,schedule_time=?,revision=revision+1 WHERE id=1",
                s.scheduleEnabled(), s.scheduleTime());
        return db.schedule(false);
    }
}
