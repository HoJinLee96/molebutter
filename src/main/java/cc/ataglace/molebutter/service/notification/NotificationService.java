package cc.ataglace.molebutter.service.notification;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.domain.*;
import cc.ataglace.molebutter.exception.*;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final JdbcTemplate jdbc;
    public record Notice(String id,String type,String severity,String title,String message,LocalDateTime occurredAt,boolean accessible) {}
    public record Inbox(List<Notice> items,String nextCursor) {}
    public record Summary(long count,String latestId) {}
    public record Target(String url,String reason) {}
    private record Event(long id,String type,String severity,String title,String message,String target,Long targetId,LocalDateTime at) {}
    private LocalDateTime now(){return cc.ataglace.molebutter.service.common.BusinessTime.koreaNow();}
    private long id(){return cc.ataglace.molebutter.service.common.BusinessIds.next();}
    private UserRole actor(long user) {
        var rows=jdbc.queryForList("SELECT user_role,user_status FROM `user` WHERE id=?",user);
        if(rows.isEmpty()||!"ACTIVE".equals(rows.getFirst().get("user_status")))throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        return UserRole.valueOf(rows.getFirst().get("user_role").toString());
    }
    /** Call inside the business transaction: receipts become visible only when its result commits. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(String key,String type,String severity,String title,String message,String target,Long targetId,Collection<Long> recipients) {
        if(recipients.isEmpty())return;
        jdbc.update("INSERT INTO notification_event(id,event_key,event_type,severity,title,message,target_type,target_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_key=VALUES(event_key)",id(),key,type,severity,title,message,target,targetId,now());
        long event=jdbc.queryForObject("SELECT id FROM notification_event WHERE event_key=?",Long.class,key);
        for(Long user:new HashSet<>(recipients))if(user!=null)
            jdbc.update("INSERT INTO user_notification(user_id,event_id) SELECT id,? FROM `user` WHERE id=? ON DUPLICATE KEY UPDATE event_id=VALUES(event_id)",event,user);
    }
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
        publish("refresh:"+run+":"+r.get("notification_sequence"),"REFRESH_"+state,"BLOCKED".equals(state)?"ERROR":total>success+sold&&"COMPLETED".equals(state)?"WARNING":"INFO",title,message,"REFRESH",run,recipients);
    }
    private String shortMessage(String s){if(s==null)return "작업 상태를 확인해 주세요.";return s.length()>500?s.substring(0,500):s;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void correction(long correction,String state,long owner,Long reviewer,LocalDate date) {
        publish("correction:"+correction+":"+state,"CORRECTION_"+state,"REJECTED".equals(state)?"WARNING":"INFO","근태 정정 "+switch(state){case "PENDING"->"신청 완료";case "CANCELLED"->"취소";case "APPROVED"->"승인";default->"반려";},date+" 근무","CORRECTION",correction,reviewer==null?List.of(owner):List.of(owner,reviewer));
    }
    @Transactional(readOnly=true)
    public Inbox list(long user,String cursor,int size) {
        UserRole role=actor(user);if(size<1||size>50)throw new IllegalArgumentException("알림 조회 개수를 확인해 주세요.");
        long before=Long.MAX_VALUE;try{if(cursor!=null)before=Long.parseLong(cursor);if(before<=0)throw new NumberFormatException();}catch(NumberFormatException e){throw new IllegalArgumentException("알림 조회 위치를 확인해 주세요.");}
        var rows=jdbc.query("SELECT e.* FROM user_notification n JOIN notification_event e ON e.id=n.event_id WHERE n.user_id=? AND n.dismissed_at IS NULL AND e.id<? ORDER BY e.id DESC LIMIT ?",(r,n)->new Event(r.getLong("id"),r.getString("event_type"),r.getString("severity"),r.getString("title"),r.getString("message"),r.getString("target_type"),(Long)r.getObject("target_id"),r.getTimestamp("occurred_at").toLocalDateTime()),user,before,size+1);
        boolean more=rows.size()>size;var page=rows.subList(0,Math.min(size,rows.size()));
        return new Inbox(page.stream().map(e->{boolean access=allowed(role,user,e);return new Notice(Long.toString(e.id),e.type,e.severity,access?e.title:"접근 권한 없음",access?e.message:"",e.at,access);}).toList(),more?Long.toString(page.getLast().id):null);
    }
    @Transactional(readOnly=true)
    public Summary summary(long user) {
        actor(user);return jdbc.queryForObject("SELECT COUNT(*) count,MAX(event_id) latest FROM user_notification WHERE user_id=? AND dismissed_at IS NULL",(r,n)->new Summary(r.getLong("count"),r.getString("latest")),user);
    }
    private Event owned(long user,long id) {
        var rows=jdbc.query("SELECT e.* FROM notification_event e JOIN user_notification n ON n.event_id=e.id WHERE n.user_id=? AND e.id=?",(r,n)->new Event(r.getLong("id"),r.getString("event_type"),r.getString("severity"),r.getString("title"),r.getString("message"),r.getString("target_type"),(Long)r.getObject("target_id"),r.getTimestamp("occurred_at").toLocalDateTime()),user,id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    private boolean allowed(UserRole role,long user,Event e) {
        return switch(e.target) {
            case "PRODUCT","PRODUCTS","REFRESH","IMPORT","SETTINGS" -> role.getSections().contains(MenuSection.PRODUCTS);
            case "USERS" -> role==UserRole.ADMIN;
            case "CORRECTION" -> role==UserRole.ADMIN||jdbc.queryForObject("SELECT COUNT(*) FROM attendance_correction WHERE id=? AND user_id=?",Long.class,e.targetId,user)>0;
            case "ATTENDANCE","PROFILE" -> true;
            default -> false;
        };
    }
    @Transactional(readOnly=true)
    public Target target(long user,long id) {
        var role=actor(user);var e=owned(user,id);if(!allowed(role,user,e))return new Target(null,"접근 권한이 없습니다.");
        String url=switch(e.target) {
            case "PRODUCT" -> exists("SELECT COUNT(*) FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",e.targetId)?"/products?product="+e.targetId:null;
            case "REFRESH" -> exists("SELECT COUNT(*) FROM product_refresh_run WHERE id=?",e.targetId)?"/product-refresh?run="+e.targetId:null;
            case "CORRECTION" -> exists("SELECT COUNT(*) FROM attendance_correction WHERE id=?",e.targetId)?(exists("SELECT COUNT(*) FROM attendance_correction WHERE id=? AND user_id="+user,e.targetId)?"/attendance":"/attendance-manage")+"?correction="+e.targetId:null;
            case "IMPORT" -> "/products#import-panel";
            case "PRODUCTS" -> "/products";
            case "SETTINGS" -> "/settings";
            case "USERS" -> "/user-manage";
            case "ATTENDANCE" -> "/attendance";
            case "PROFILE" -> "/my-page";
            default -> null;
        };
        return new Target(url,url==null?"대상이 삭제되었거나 더 이상 확인할 수 없습니다.":null);
    }
    private boolean exists(String sql,Long id){return id!=null&&jdbc.queryForObject(sql,Long.class,id)>0;}
    @Transactional public void dismiss(long user,long id){actor(user);owned(user,id);jdbc.update("UPDATE user_notification SET dismissed_at=? WHERE user_id=? AND event_id=? AND dismissed_at IS NULL",now(),user,id);}
}
