package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.exception.InputValidationFailure;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.exception.OperationFailure;
import cc.ataglace.molebutter.infra.product.*;

/** All transitions share the product worker lock; no browser/network operation occurs here. */
@Service @RequiredArgsConstructor @Slf4j
@Transactional(isolation=Isolation.READ_COMMITTED)
public class NaverSearchRecoveryService {
    private final ProductStore db;
    private final cc.ataglace.molebutter.service.notification.NotificationService notifications;
    private boolean owns(String owner){return db.jdbc.queryForObject("SELECT COUNT(*) FROM product_settings WHERE id=1 AND worker_owner=? AND worker_until>?",Long.class,owner,db.time.now())==1;}
    private void release(String owner){db.jdbc.update("UPDATE product_settings SET worker_owner=NULL,worker_until=NULL WHERE id=1 AND worker_owner=?",owner);}
    private Map<String,Object> attempt(long id){return db.jdbc.queryForMap("SELECT * FROM product_search_attempt WHERE id=?",id);}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private LocalDateTime date(Object value){return value==null?null:value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
    public boolean gated(){return Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT search_manual_resume_required OR search_cooldown_until>? FROM product_settings WHERE id=1",Boolean.class,db.time.now()));}
    public Map<String,Object> gate(){
        var r=db.jdbc.queryForMap("SELECT search_gate_version version,search_cooldown_until untilAt,search_manual_resume_required manualResumeRequired,CAST(search_gate_run_id AS CHAR) runId,CAST(search_gate_attempt_id AS CHAR) attemptId FROM product_settings WHERE id=1");
        r.put("untilAt",date(r.get("untilAt")));return r;
    }
    public Long begin(String owner,SupplierRefreshService.Work w){
        db.lock();if(!owns(owner))return null;
        if(gated()){release(owner);return null;}
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id JOIN catalog_product p ON p.id=e.product_id WHERE e.run_id=? AND e.product_id=? AND e.status='CHECKING' AND r.status='RUNNING' AND p.lookup_revision=e.lookup_revision AND p.deleted_at IS NULL AND p.merged_into IS NULL",Long.class,w.runId(),w.productId())!=1){
            db.jdbc.update("UPDATE product_refresh_entry e JOIN catalog_product p ON p.id=e.product_id SET e.status='STALE' WHERE e.run_id=? AND e.product_id=? AND e.status='CHECKING' AND (p.lookup_revision<>e.lookup_revision OR p.deleted_at IS NOT NULL OR p.merged_into IS NOT NULL)",w.runId(),w.productId());release(owner);return null;
        }
        long id=ProductStore.id();int n=db.jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM product_search_attempt WHERE run_id=? AND product_id=?",Integer.class,w.runId(),w.productId());
        db.jdbc.update("INSERT INTO product_search_attempt(id,run_id,product_id,lookup_revision,attempt_no,owner_token,status,started_at) VALUES(?,?,?,?,?,?,'RUNNING',?)",id,w.runId(),w.productId(),w.revision(),n,owner,db.time.now());return id;
    }
    private boolean current(String owner,SupplierRefreshService.Work w,Map<String,Object> a){return owns(owner)&&owner.equals(a.get("owner_token"))&&"RUNNING".equals(a.get("status"))&&number(a,"run_id")==w.runId()&&number(a,"product_id")==w.productId()&&number(a,"lookup_revision")==w.revision();}
    public boolean success(String owner,SupplierRefreshService.Work w,long id){
        db.lock();var a=attempt(id);if(!current(owner,w,a))return false;
        boolean valid=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id JOIN catalog_product p ON p.id=e.product_id WHERE e.run_id=? AND e.product_id=? AND e.status='CHECKING' AND r.status IN ('RUNNING','PAUSED') AND p.lookup_revision=? AND p.deleted_at IS NULL AND p.merged_into IS NULL",Long.class,w.runId(),w.productId(),w.revision())==1;
        db.jdbc.update("UPDATE product_search_attempt SET status=?,finished_at=? WHERE id=?",valid?"SUCCEEDED":"CANCELLED",db.time.now(),id);
        searchInterval();
        if(!valid){db.jdbc.update("UPDATE product_refresh_entry SET status='STALE' WHERE run_id=? AND product_id=? AND status='CHECKING'",w.runId(),w.productId());release(owner);return false;}
        db.jdbc.update("UPDATE product_refresh_run SET search_retry_count=0,login_retry_count=0,block_reason=NULL,next_retry_at=NULL,search_failure_stage=NULL,search_failure_code=NULL,search_failure_signature=NULL WHERE id=?",w.runId());
        // Only an attempt that began after the gate event can clear an expired, automatic gate.
        db.jdbc.update("UPDATE product_settings SET search_cooldown_until=NULL,search_gate_run_id=NULL,search_gate_attempt_id=NULL,search_gate_version=search_gate_version+1 WHERE id=1 AND search_manual_resume_required=FALSE AND (search_cooldown_until IS NULL OR search_cooldown_until<=?) AND (search_gate_attempt_id IS NULL OR search_gate_attempt_id<?)",db.time.now(),id);
        return true;
    }
    public void searchInterval(){db.jdbc.update("UPDATE product_settings SET next_search_at=GREATEST(COALESCE(next_search_at,?),?) WHERE id=1",db.time.now().plusSeconds(30),db.time.now().plusSeconds(30));}
    public void failure(String owner,SupplierRefreshService.Work w,long id,Exception error){
        String code,stage;Integer http=null;Map<String,Object> diagnostic=Map.of();
        if(error instanceof NaverSearchFailure f){code=f.code().name();stage=f.stage();http=f.httpStatus();diagnostic=f.diagnostics();}
        else if(error instanceof NaverPriceSearch.SearchBlocked b){code=b.reason().name();stage="SEARCH";http=b.httpStatus();}
        else {code="INTERNAL_ERROR";stage="SEARCH";}
        db.lock();var a=attempt(id);if(!current(owner,w,a))return;
        var run=db.jdbc.queryForMap("SELECT status,search_retry_count,search_failure_signature FROM product_refresh_run WHERE id=?",w.runId());
        String state=run.get("status").toString();int count=((Number)run.get("search_retry_count")).intValue();
        boolean temporary=NaverSearchFailure.temporary(code),validation=NaverSearchFailure.validation(code);
        String signature=code+":"+stage+":"+diagnostic.getOrDefault("mismatches",List.of());
        boolean repeated=validation&&signature.equals(run.get("search_failure_signature"));
        if(temporary||validation)count++;
        int[] minutes={5,30,60,120};
        LocalDateTime next=(temporary||validation)&&!repeated&&count<=4?db.time.now().plusMinutes(validation?5:minutes[count-1]):null;
        String message=NaverSearchFailure.message(code)+(next==null?" · 자동 재개 중단, 수동 확인 필요":" · "+next.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" 재개 예정");
        boolean active=List.of("RUNNING","PAUSED").contains(state);
        boolean valid=db.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product p JOIN product_refresh_entry e ON e.product_id=p.id WHERE e.run_id=? AND e.product_id=? AND e.status='CHECKING' AND p.lookup_revision=? AND p.deleted_at IS NULL AND p.merged_into IS NULL",Long.class,w.runId(),w.productId(),w.revision())==1;
        db.jdbc.update("UPDATE product_search_attempt SET status=?,stage=?,reason_code=?,http_status=?,safe_diagnostics=?,finished_at=?,retry_at=? WHERE id=?",!active?"CANCELLED":!valid?"STALE":next==null?"BLOCKED":"RETRY_WAIT",stage,code,http,db.encode(diagnostic),db.time.now(),next,id);
        searchInterval();
        // A real failure still gates new work if the user cancelled the in-flight job.
        db.jdbc.update("UPDATE product_settings SET search_cooldown_until=?,search_manual_resume_required=?,search_gate_attempt_id=?,search_gate_run_id=?,search_gate_version=search_gate_version+1 WHERE id=1",next,next==null,id,w.runId());
        if(active&&valid){
            String target=state.equals("PAUSED")?"PAUSED":next==null?"BLOCKED":"RETRY_WAIT";
            db.jdbc.update("UPDATE product_refresh_run SET status=?,search_retry_count=?,login_retry_count=login_retry_count+?,block_reason=?,search_failure_code=?,search_failure_stage=?,search_failure_signature=IF(?, ?,search_failure_signature),next_retry_at=?,message=? WHERE id=?",target,count,NaverSearchFailure.login(code)?1:0,code,code,stage,validation,signature,next,message,w.runId());
            db.jdbc.update("UPDATE product_refresh_entry SET status='PENDING' WHERE run_id=? AND product_id=?",w.runId(),w.productId());
            db.jdbc.update("UPDATE catalog_product SET latest_status=?,latest_result=NULL WHERE id=? AND lookup_revision=?",next==null?"FAILED":"PENDING",w.productId(),w.revision());
            if(!target.equals("PAUSED"))notifications.refresh(w.runId(),target,message);
        }else if(active){db.jdbc.update("UPDATE product_refresh_entry SET status='STALE' WHERE run_id=? AND product_id=? AND status='CHECKING'",w.runId(),w.productId());}
        log.warn("[NAVER_SEARCH_ATTEMPT] attemptId={} runId={} productId={} stage={} code={} httpStatus={} retryCount={} retryAt={} exception={}",id,w.runId(),w.productId(),stage,code,http,count,next,error.getClass().getSimpleName());
        release(owner);
    }
    /** Before claiming any work, recover a persisted search left behind by a dead worker. */
    public void recoverAbandoned(){
        db.lock();if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_settings WHERE id=1 AND worker_owner IS NOT NULL AND worker_until>?",Long.class,db.time.now())>0)return;
        for(var a:db.jdbc.queryForList("SELECT * FROM product_search_attempt WHERE status='RUNNING' ORDER BY id")){
            long id=number(a,"id"),run=number(a,"run_id"),product=number(a,"product_id");var next=db.time.now().plusMinutes(5);
            db.jdbc.update("UPDATE product_search_attempt SET status='INTERRUPTED',stage='SEARCH',reason_code='INTERRUPTED',finished_at=?,retry_at=? WHERE id=? AND status='RUNNING'",db.time.now(),next,id);
            db.jdbc.update("UPDATE product_settings SET search_cooldown_until=GREATEST(COALESCE(search_cooldown_until,?),?),search_gate_run_id=?,search_gate_attempt_id=?,search_gate_version=search_gate_version+1,worker_owner=NULL,worker_until=NULL WHERE id=1",next,next,run,id);
            db.jdbc.update("UPDATE product_refresh_run SET status=IF(status='RUNNING','RETRY_WAIT',status),next_retry_at=?,block_reason='INTERRUPTED',search_failure_stage='SEARCH',search_failure_code='INTERRUPTED',message='이전 검색 실행 중단 · 5분 후 재개 예정' WHERE id=? AND status IN ('RUNNING','PAUSED')",next,run);
            db.jdbc.update("UPDATE product_refresh_entry SET status='PENDING' WHERE run_id=? AND product_id=? AND status='CHECKING'",run,product);
            db.jdbc.update("UPDATE catalog_product SET latest_status='PENDING',latest_result=NULL WHERE id=? AND lookup_revision=? AND latest_status='CHECKING'",product,number(a,"lookup_revision"));
        }
    }
    /** Called only from explicit, authorized resume commands, never by the polling worker. */
    public void resumeGate(long expectedVersion){
        db.lock();var g=gate();ProductStore.revision(number(g,"version"),expectedVersion);
        var until=date(g.get("untilAt"));if(until!=null&&until.isAfter(db.time.now()))throw new OperationFailure("예정 시각 이후에 재개할 수 있습니다.");
        db.jdbc.update("UPDATE product_settings SET search_cooldown_until=NULL,search_manual_resume_required=FALSE,search_gate_attempt_id=NULL,search_gate_run_id=NULL,search_gate_version=search_gate_version+1 WHERE id=1");
    }
    @Transactional(readOnly=true) public PageResponse<Map<String,Object>> history(Long actor,long run,int page,String code,Long product){
        db.authorize(actor,false);if(page<0)throw new InputValidationFailure("페이지를 확인해 주세요.");
        String filter=ProductStore.text(code,40,false);var args=new ArrayList<Object>();args.add(run);String where=" WHERE run_id=?";
        if(!filter.isBlank()){where+=" AND reason_code=?";args.add(filter);}if(product!=null){where+=" AND product_id=?";args.add(product);}
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_search_attempt"+where,Long.class,args.toArray());args.add((long)page*20);
        var rows=db.jdbc.queryForList("SELECT (SELECT product_code FROM catalog_product p WHERE p.id=product_search_attempt.product_id) productCode,CAST(id AS CHAR) id,CAST(product_id AS CHAR) productId,attempt_no attemptNo,status,stage,reason_code reasonCode,http_status httpStatus,safe_diagnostics diagnostics,started_at startedAt,finished_at finishedAt,retry_at retryAt FROM product_search_attempt"+where+" ORDER BY id DESC LIMIT 20 OFFSET ?",args.toArray());
        rows.forEach(r->{for(String key:List.of("startedAt","finishedAt","retryAt"))r.put(key,date(r.get(key)));r.put("message",r.get("reasonCode")==null?null:NaverSearchFailure.message(r.get("reasonCode").toString()));if(r.get("diagnostics")!=null)r.put("diagnostics",db.decode(r.get("diagnostics").toString(),Map.class));});
        return new PageResponse<>(rows,page,(int)((count+19)/20),count);
    }
}
