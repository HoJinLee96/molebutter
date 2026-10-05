package cc.ataglace.molebutter.procurement.internal;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @lombok.RequiredArgsConstructor
public class ProcurementNotifications {
    private final JdbcTemplate jdbc;
    private final cc.ataglace.molebutter.operations.api.NotificationService notifications;
    @Transactional(propagation=Propagation.MANDATORY)
    public void refresh(long run,String state,String reason) {
        jdbc.update("UPDATE product_refresh_run SET notification_sequence=notification_sequence+1 WHERE id=?",run);
        var r=jdbc.queryForMap("SELECT created_by,trigger_type,notification_sequence FROM product_refresh_run WHERE id=?",run);
        List<Long> recipients="SCHEDULED".equals(r.get("trigger_type"))
            ?jdbc.queryForList("SELECT id FROM `user` WHERE user_status='ACTIVE' AND user_role IN ('ADMIN','PRODUCT')",Long.class)
            :r.get("created_by")==null?List.of():List.of(((Number)r.get("created_by")).longValue());
        var counts=jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(status='SUCCESS'),0) success,COALESCE(SUM(status='SOLD_OUT'),0) sold_out FROM product_refresh_entry WHERE run_id=?",run);
        long total=((Number)counts.get("total")).longValue(),success=((Number)counts.get("success")).longValue(),sold=((Number)counts.get("sold_out")).longValue();
        String title="상품 최신화 "+switch(state){case "COMPLETED"->"완료";case "CANCELLED"->"취소";case "RETRY_WAIT"->"자동 재개 대기";default->"중단";};
        String message="COMPLETED".equals(state)?"성공 "+success+" / 품절 "+sold+" / 확인 필요 "+(total-success-sold):"CANCELLED".equals(state)?"최신화 작업을 취소했습니다.":shortMessage(reason);
        notifications.publish("refresh:"+run+":"+r.get("notification_sequence"),"REFRESH_"+state,"BLOCKED".equals(state)?"ERROR":total>success+sold&&"COMPLETED".equals(state)?"WARNING":"INFO",title,message,"REFRESH",run,recipients);
    }
    private String shortMessage(String s){if(s==null)return "작업 상태를 확인해 주세요.";return s.length()>500?s.substring(0,500):s;}
}
