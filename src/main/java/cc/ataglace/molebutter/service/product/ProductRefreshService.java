package cc.ataglace.molebutter.service.product;

import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class ProductRefreshService {
    private final ProductStore db;
    private final NaverSearchRecoveryService recovery;
    private final ProductChangeService changes;
    private final RecommendationLookupService recommendationLookups;
    private final cc.ataglace.molebutter.service.notification.NotificationService notifications;
    private final ProductService products;
    private final SupplierPreferenceService preferences;
    private final ProductSupplierService suppliers;
    public record Work(long runId,long productId,long revision,String query,String productCode,String codeType,String brandKey,Preferences preferences,Map<String,String> manualStores,SelectionBasis selectionBasis) {
        public Work(long run,long product,long revision,String query,String code,String type,String brand,Preferences preferences,Map<String,String> manual){this(run,product,revision,query,code,type,brand,preferences,manual,null);}
        public Work(long run,long product,long revision,String query,String code,String type,String brand){this(run,product,revision,query,code,type,brand,null,Map.of());}
    }
    /** 폴링에는 옵션 목록·조회 이력을 읽지 않는다. 현재 상품 기준의 작업 상태만 반환한다. */
    @Transactional(readOnly=true) public RefreshStatus status(Long actor,long id) {
        db.authorize(actor,false);
        var rows=db.jdbc.query("""
            SELECT p.latest_status,p.lookup_revision,i.lookup_revision entry_revision,i.status entry_status,
                   CAST(r.id AS CHAR) run_id,r.status run_status,r.message,r.block_reason,r.login_retry_count,r.next_retry_at,r.search_retry_count,r.search_failure_stage,r.search_failure_code,
                   CASE WHEN r.status='RUNNING' AND i.status='PENDING' AND NOT EXISTS (
                     SELECT 1 FROM product_refresh_search c WHERE c.run_id=r.id AND c.query_hash=SHA2(i.query,256) AND c.checked_at>=?)
                     THEN (SELECT next_search_at FROM product_settings WHERE id=1 AND next_search_at>?) END next_search_at
            FROM catalog_product p
            LEFT JOIN product_refresh_entry i ON i.product_id=p.id AND i.run_id=(
                SELECT MAX(e.run_id) FROM product_refresh_entry e WHERE e.product_id=p.id)
            LEFT JOIN product_refresh_run r ON r.id=i.run_id
            WHERE p.id=? AND p.deleted_at IS NULL AND p.merged_into IS NULL
            """,(r,n)-> {
                String run=r.getString("run_id"),state=r.getString("latest_status"),message=r.getString("message");
                if(run!=null) {
                    if(r.getLong("lookup_revision")!=r.getLong("entry_revision")) {state="NOT_CHECKED";message="조회 기준이 변경되었습니다. 다시 조회해 주세요.";}
                    else state=ProductStatusPolicy.display(state,r.getString("block_reason"));
                }
                return new RefreshStatus(Long.toString(id),state,run,r.getString("run_status"),message,r.getString("block_reason"),r.getInt("login_retry_count"),date(r.getObject("next_retry_at")),date(r.getObject("next_search_at")),r.getInt("search_retry_count"),r.getString("search_failure_stage"),r.getString("search_failure_code"),recovery.gate(),r.getLong("lookup_revision")==r.getLong("entry_revision")&&List.of("PENDING","CHECKING").contains(Objects.toString(r.getString("entry_status"),""))&&RUN_STATUSES.subList(0,4).contains(Objects.toString(r.getString("run_status"),"")));
            },db.time.now().minusHours(24),db.time.now(),id);
        return rows.stream().findFirst().orElseThrow(()->new cc.ataglace.molebutter.exception.BusinessException(cc.ataglace.molebutter.exception.ErrorCode.NOT_FOUND));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public String start(Long actor,RefreshInput input) {
        db.authorize(actor,false);db.lock();
        if(input==null||input.scope()==null||!List.of("ALL_MANAGED","SELECTED").contains(input.scope()))throw new IllegalArgumentException("실행 범위를 지정해 주세요.");
        var ids=ProductStore.ids(input.ids());
        if(input.scope().equals("SELECTED")&&ids.isEmpty()||input.scope().equals("ALL_MANAGED")&&!ids.isEmpty())throw new IllegalArgumentException("실행 범위와 선택 항목을 확인해 주세요.");
        return create(actor,ids,"MANUAL");
    }
    private String create(Long actor,List<Long> selected,String trigger) {
        db.lock();if(db.jdbc.queryForObject("SELECT stock_lookup_blocked_job FROM product_settings WHERE id=1",Long.class)!=null)throw new cc.ataglace.molebutter.exception.OperationFailure("개별 재고 조회의 접속 제한을 먼저 해제해 주세요.");var snapshot=preferences.snapshot();if(snapshot.rules().isEmpty())throw new cc.ataglace.molebutter.exception.OperationFailure("공통 설정에서 선호 매입처를 먼저 등록해 주세요.");
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run WHERE status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')",Long.class)>0)throw new cc.ataglace.molebutter.exception.OperationFailure("기존 최신화 작업을 완료하거나 취소한 뒤 실행해 주세요.");
        List<Long> ids=selected.isEmpty()?db.jdbc.queryForList("SELECT id FROM catalog_product WHERE managed=TRUE AND merged_into IS NULL AND deleted_at IS NULL ORDER BY id",Long.class):selected;
        if(ids.isEmpty()||ids.size()>5000)throw new IllegalArgumentException("최신화할 상품을 1~5,000개 선택해 주세요.");
        var selectedProducts=ids.stream().map(products::product).toList();
        long run=ProductStore.id();db.jdbc.update("INSERT INTO product_refresh_run(id,status,trigger_type,created_by,created_at,preference_snapshot) VALUES(?,'RUNNING',?,?,?,?)",run,trigger,actor,db.time.now(),db.encode(snapshot));
        for(var p:selectedProducts) {
            changes.initialize(Long.parseLong(p.id()));
            boolean eligible=!p.searchQuery().isBlank()&&!p.productCode().isBlank();
            db.jdbc.update("INSERT INTO product_refresh_entry(run_id,product_id,lookup_revision,query,product_code,code_type,brand_key,status,selection_snapshot) VALUES(?,?,?,?,?,?,?,?,?)",run,p.id(),p.lookupRevision(),p.searchQuery(),p.productCode(),p.codeType(),p.brandKey(),eligible?"PENDING":"UNCONFIRMED",db.encode(suppliers.selectionBasis(Long.parseLong(p.id()))));
            db.jdbc.update("UPDATE catalog_product SET latest_status=?,latest_result=NULL,latest_at=? WHERE id=?",eligible?"PENDING":"UNCONFIRMED",db.time.now(),p.id());
            changes.reproject(Long.parseLong(p.id()));
        }
        return Long.toString(run);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void schedule() {
        var s=db.schedule(true);if(db.jdbc.queryForObject("SELECT stock_lookup_blocked_job FROM product_settings WHERE id=1",Long.class)!=null)return;if(preferences.snapshot().rules().isEmpty())return;var now=db.time.now();if(!s.scheduleEnabled()||now.toLocalTime().isBefore(LocalTime.parse(s.scheduleTime())))return;
        var last=db.jdbc.queryForObject("SELECT last_schedule_date FROM product_settings WHERE id=1",java.sql.Date.class);
        if(last!=null&&!last.toLocalDate().isBefore(now.toLocalDate()))return;
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run WHERE status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')",Long.class)>0)return;
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE managed=TRUE AND merged_into IS NULL AND deleted_at IS NULL",Long.class)==0)return;
        create(null,List.of(),"SCHEDULED");db.jdbc.update("UPDATE product_settings SET last_schedule_date=? WHERE id=1",now.toLocalDate());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Work claim(String worker) {
        db.lock();recovery.recoverAbandoned();if(recovery.gated())return null;if(db.jdbc.queryForObject("SELECT stock_lookup_blocked_job FROM product_settings WHERE id=1",Long.class)!=null)return null;var now=db.time.now();var lock=db.jdbc.queryForMap("SELECT worker_owner,worker_until FROM product_settings WHERE id=1");Object until=lock.get("worker_until");
        LocalDateTime expires=until instanceof java.sql.Timestamp t?t.toLocalDateTime():until instanceof LocalDateTime t?t:null;
        if(expires!=null&&expires.isAfter(now)&&lock.get("worker_owner")!=null)return null;
        db.jdbc.update("UPDATE product_refresh_run SET status='RUNNING',next_retry_at=NULL,message=NULL WHERE status='RETRY_WAIT' AND next_retry_at<=?",now);
        db.jdbc.update("UPDATE product_settings SET worker_owner=?,worker_until=? WHERE id=1",worker,now.plusMinutes(10));
        db.jdbc.update("UPDATE product_refresh_entry i JOIN product_refresh_run r ON r.id=i.run_id SET i.status='PENDING' WHERE i.status='CHECKING' AND r.status='RUNNING'");
        var runs=db.jdbc.queryForList("SELECT id FROM product_refresh_run WHERE status='RUNNING' ORDER BY id LIMIT 1",Long.class);
        if(runs.isEmpty()){release(worker);return null;}long run=runs.getFirst();
        var snapshot=db.decode(db.jdbc.queryForObject("SELECT preference_snapshot FROM product_refresh_run WHERE id=?",String.class,run),Preferences.class);
        if(snapshot==null||snapshot.rules().isEmpty()||db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry WHERE run_id=? AND (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(selection_snapshot,'$.lookupPolicy')),'')<>'SEARCH_QUERY' OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(selection_snapshot,'$.naverPolicy')),'')<>'WINDOW_ONLY' OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(selection_snapshot,'$.stockPolicy')),'')<>'STORE_REPRESENTATIVE')",Long.class,run)>0){db.jdbc.update("UPDATE product_refresh_run SET status='BLOCKED',message='이전 방식의 작업입니다. 취소 후 다시 실행해 주세요.' WHERE id=?",run);notifications.refresh(run,"BLOCKED","이전 방식의 작업입니다. 취소 후 다시 실행해 주세요.");release(worker);return null;}
        var items=db.jdbc.query("SELECT * FROM product_refresh_entry WHERE run_id=? AND status='PENDING' ORDER BY product_id LIMIT 1",(r,n)->new Work(run,r.getLong("product_id"),r.getLong("lookup_revision"),r.getString("query"),r.getString("product_code"),r.getString("code_type"),r.getString("brand_key"),snapshot,suppliers.manualAssignments(r.getLong("product_id")),db.decode(r.getString("selection_snapshot"),SelectionBasis.class)),run);
        if(items.isEmpty()){db.jdbc.update("UPDATE product_refresh_run SET status='COMPLETED',finished_at=? WHERE id=?",now,run);notifications.refresh(run,"COMPLETED",null);release(worker);return null;}
        Work w=items.getFirst();
        LocalDateTime nextSearch=date(db.jdbc.queryForObject("SELECT next_search_at FROM product_settings WHERE id=1",Object.class));
        if(nextSearch!=null&&nextSearch.isAfter(now)&&cached(w)==null){release(worker);return null;}
        db.jdbc.update("UPDATE product_refresh_entry SET status='CHECKING' WHERE run_id=? AND product_id=?",run,w.productId());
        db.jdbc.update("UPDATE catalog_product SET latest_status='CHECKING',latest_result=NULL,latest_at=? WHERE id=? AND lookup_revision=?",now,w.productId(),w.revision());return w;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void finish(String worker,Work w,RefreshResult result) {
        db.lock();if(!owns(worker))return;
        if("CANCELLED".equals(db.jdbc.queryForObject("SELECT status FROM product_refresh_run WHERE id=?",String.class,w.runId()))){release(worker);return;}
        if(!"CHECKING".equals(db.jdbc.queryForObject("SELECT status FROM product_refresh_entry WHERE run_id=? AND product_id=?",String.class,w.runId(),w.productId())))return;
        var p=products.product(w.productId());
        if(p.lookupRevision()!=w.revision())result=new RefreshResult("STALE",null,null,null,List.of(),db.time.now(),"조회 기준이 변경되었습니다. 다시 최신화해 주세요.");
        db.jdbc.update("UPDATE product_refresh_entry SET status=?,result=?,checked_at=? WHERE run_id=? AND product_id=?",result.status(),db.encode(result),result.checkedAt(),w.runId(),w.productId());
        db.jdbc.update("INSERT INTO product_lookup_history(id,product_id,run_id,legacy,created_at,payload) VALUES(?,?,?,FALSE,?,?)",ProductStore.id(),w.productId(),w.runId(),result.checkedAt(),db.encode(result));
        if(p.lookupRevision()==w.revision()) {
            db.jdbc.update("UPDATE catalog_product SET latest_status=?,latest_result=?,latest_at=? WHERE id=?",result.status(),db.encode(result),result.checkedAt(),w.productId());
            if(result.suppliers().stream().anyMatch(s->s.accepted()&&s.options().stream().anyMatch(o->List.of("AVAILABLE","SOLD_OUT").contains(o.state()))))db.jdbc.update("UPDATE catalog_product SET last_good_result=? WHERE id=?",db.encode(result),w.productId());
            suppliers.record(w,result);
            changes.automatic(w,result);
        }
        release(worker);
    }
    public Long searchStarted(String owner,Work work){return recovery.begin(owner,work);}
    public boolean searchSucceeded(String owner,Work work,long attempt){return recovery.success(owner,work,attempt);}
    public void searchFailed(String owner,Work work,long attempt,Exception error){recovery.failure(owner,work,attempt,error);}
    public PageResponse<Map<String,Object>> searchAttempts(Long actor,long run,int page,String code,Long product){return recovery.history(actor,run,page,code,product);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public void resumeSearchGate(Long actor,long version){
        db.authorize(actor,false);db.lock();var g=recovery.gate();ProductStore.revision(((Number)g.get("version")).longValue(),version);
        var run=g.get("runId");
        if(run!=null&&db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run WHERE id=? AND status IN ('PAUSED','BLOCKED')",Long.class,run)>0){control(actor,Long.parseLong(run.toString()),"resume");}
        else recovery.resumeGate(version);
    }
    /** Legacy internal entry point; actual browser attempts use searchSucceeded with their attempt ID. */
    @Transactional(isolation=Isolation.READ_COMMITTED) public boolean searchFinished(String worker,Work w,boolean success) {
        if(!owns(worker))return false;
        recovery.searchInterval();
        if(success)db.jdbc.update("UPDATE product_refresh_run SET login_retry_count=0,block_reason=NULL,next_retry_at=NULL WHERE id=? AND status IN ('RUNNING','PAUSED')",w.runId());
        if("CANCELLED".equals(db.jdbc.queryForObject("SELECT status FROM product_refresh_run WHERE id=?",String.class,w.runId()))){release(worker);return false;}
        return true;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void blocked(String worker,Work w,String message){blocked(worker,w,"ACCESS_RESTRICTED",message);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public void blocked(String worker,Work w,String reason,String message) {
        if(!owns(worker))return;
        var run=db.jdbc.queryForMap("SELECT status,login_retry_count FROM product_refresh_run WHERE id=?",w.runId());
        String state=run.get("status").toString();
        if(!List.of("RUNNING","PAUSED").contains(state)){release(worker);return;}
        int count=((Number)run.get("login_retry_count")).intValue();LocalDateTime next=null;
        boolean login="LOGIN_REQUIRED".equals(reason);
        if(login){count++;int[] minutes={5,30,60,120};if(count<=minutes.length)next=db.time.now().plusMinutes(minutes[count-1]);
            message=next==null?"네이버 로그인 반복 · 자동 재개 중단":"네이버 로그인 감지 · "+next.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" 재개 예정";}
        String target="PAUSED".equals(state)?"PAUSED":next!=null?"RETRY_WAIT":"BLOCKED";
        if("PAUSED".equals(target))message="네이버 검색 중단 · 자동 재개 중단됨";
        db.jdbc.update("UPDATE product_refresh_run SET status=?,block_reason=?,login_retry_count=?,next_retry_at=?,message=? WHERE id=?",target,reason,count,next,message,w.runId());
        if(!"PAUSED".equals(target))notifications.refresh(w.runId(),target,message);
        db.jdbc.update("UPDATE product_refresh_entry SET status='PENDING' WHERE run_id=? AND product_id=?",w.runId(),w.productId());
        db.jdbc.update("UPDATE catalog_product SET latest_status=?,latest_result=NULL WHERE id=? AND lookup_revision=?",next!=null?"PENDING":"SUPPLIER_ACCESS_RESTRICTED".equals(reason)?"PARTIAL":"FAILED",w.productId(),w.revision());release(worker);
    }
    private static LocalDateTime date(Object value){return value instanceof java.sql.Timestamp t?t.toLocalDateTime():value instanceof LocalDateTime t?t:null;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public boolean heartbeat(String worker,Work work){if(!owns(worker))return false;if("CANCELLED".equals(db.jdbc.queryForObject("SELECT status FROM product_refresh_run WHERE id=?",String.class,work.runId()))){release(worker);return false;}db.jdbc.update("UPDATE product_settings SET worker_until=? WHERE id=1",db.time.now().plusMinutes(10));return true;}
    private boolean owns(String worker){return worker.equals(db.jdbc.queryForObject("SELECT worker_owner FROM product_settings WHERE id=1 FOR UPDATE",String.class));}
    private void release(String worker){db.jdbc.update("UPDATE product_settings SET worker_owner=NULL,worker_until=NULL WHERE id=1 AND worker_owner=?",worker);}
    @Transactional(readOnly=true) public SearchResult cached(Work w) {
        var rows=db.jdbc.queryForList("SELECT result FROM product_refresh_search WHERE run_id=? AND query_hash=? AND checked_at>=?",String.class,w.runId(),hash(w.query()),db.time.now().minusHours(24));
        return rows.isEmpty()?null:db.decode(rows.getFirst(),SearchResult.class);
    }
    @Transactional public void cache(Work w,SearchResult r){db.jdbc.update("INSERT INTO product_refresh_search(run_id,query_hash,result,checked_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE result=VALUES(result),checked_at=VALUES(checked_at)",w.runId(),hash(w.query()),db.encode(r),db.time.now());}
    @Transactional(isolation=Isolation.READ_COMMITTED) public void control(Long actor,long run,String action) {
        db.authorize(actor,false);db.lock();String state=db.jdbc.queryForObject("SELECT status FROM product_refresh_run WHERE id=?",String.class,run);
        LocalDateTime next=date(db.jdbc.queryForObject("SELECT next_retry_at FROM product_refresh_run WHERE id=?",Object.class,run));
        if(action.equals("pause")&&List.of("RUNNING","RETRY_WAIT").contains(state))db.jdbc.update("UPDATE product_refresh_run SET status='PAUSED',message='자동 재개 중단됨' WHERE id=?",run);
        else if(action.equals("resume")&&List.of("PAUSED","BLOCKED").contains(state)) {
            var gate=recovery.gate();var cooldown=(LocalDateTime)gate.get("untilAt");
            if(cooldown!=null&&cooldown.isAfter(db.time.now()))throw new cc.ataglace.molebutter.exception.OperationFailure("예정 시각 이후에 재개할 수 있습니다.");
            if(next!=null&&next.isAfter(db.time.now()))throw new cc.ataglace.molebutter.exception.OperationFailure("예정 시각 이후에 재개할 수 있습니다.");
            if(!Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT preference_snapshot IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product_refresh_entry e WHERE e.run_id=product_refresh_run.id AND (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.selection_snapshot,'$.lookupPolicy')),'')<>'SEARCH_QUERY' OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.selection_snapshot,'$.naverPolicy')),'')<>'WINDOW_ONLY' OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.selection_snapshot,'$.stockPolicy')),'')<>'STORE_REPRESENTATIVE')) FROM product_refresh_run WHERE id=?",Boolean.class,run)))throw new cc.ataglace.molebutter.exception.OperationFailure("이전 방식의 작업입니다. 취소 후 다시 실행해 주세요.");
            boolean finalStop=Boolean.TRUE.equals(gate.get("manualResumeRequired"));
            recovery.resumeGate(((Number)gate.get("version")).longValue());
            db.jdbc.update("UPDATE product_refresh_run SET status='RUNNING',message=NULL,next_retry_at=NULL,search_retry_count=IF(?,0,search_retry_count),search_failure_signature=IF(?,NULL,search_failure_signature),login_retry_count=IF(login_retry_count>=5 OR ?,0,login_retry_count) WHERE id=?",finalStop,finalStop,finalStop,run);
        }
        else if(action.equals("cancel")&&List.of("RUNNING","PAUSED","BLOCKED","RETRY_WAIT").contains(state)) {
            db.jdbc.update("UPDATE product_refresh_run SET status='CANCELLED',next_retry_at=NULL,finished_at=? WHERE id=?",db.time.now(),run);
            db.jdbc.update("UPDATE supplier_stock_lookup SET status='CANCELLED',revision=revision+1,finished_at=?,message='기준 최신화 작업이 취소되었습니다.' WHERE run_id=? AND status IN ('PENDING','RUNNING','BLOCKED')",db.time.now(),run);
            db.jdbc.update("UPDATE catalog_product p JOIN product_refresh_entry i ON i.product_id=p.id SET p.latest_status='CANCELLED',p.latest_result=NULL WHERE i.run_id=? AND i.status IN ('PENDING','CHECKING') AND p.lookup_revision=i.lookup_revision",run);
            db.jdbc.update("UPDATE product_refresh_entry SET status='CANCELLED' WHERE run_id=? AND status IN ('PENDING','CHECKING')",run);
            notifications.refresh(run,"CANCELLED",null);
        }else throw new cc.ataglace.molebutter.exception.OperationFailure("작업 상태가 변경되었습니다. 새로 조회해 주세요.");
        for(long product:db.jdbc.queryForList("SELECT e.product_id FROM product_refresh_entry e JOIN catalog_product p ON p.id=e.product_id WHERE e.run_id=? AND p.deleted_at IS NULL AND p.merged_into IS NULL",Long.class,run))changes.reproject(product);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public String retry(Long actor,long run) {
        db.authorize(actor,false);db.lock();var ids=db.jdbc.queryForList("SELECT i.product_id FROM product_refresh_entry i JOIN catalog_product p ON p.id=i.product_id WHERE i.run_id=? AND "+RETRYABLE,Long.class,run);
        if(ids.isEmpty())throw new IllegalArgumentException("재조회할 항목이 없습니다.");return create(actor,ids,"RETRY");
    }
    private static final List<String> RUN_STATUSES=List.of("RUNNING","PAUSED","RETRY_WAIT","BLOCKED","COMPLETED","CANCELLED");
    /** 실패 재조회(retry) 대상과 같은 조건. 진행 중 작업에서 아직 조회하지 않은 항목(PENDING·CHECKING)은 실패로 보지 않는다. */
    private static final String RETRYABLE="i.status NOT IN ('SUCCESS','SOLD_OUT','NO_MATCH','PENDING','CHECKING') AND p.deleted_at IS NULL AND p.merged_into IS NULL";
    /**
     * 최신화 작업 이력. 폴링마다 전체 작업×항목을 집계하지 않도록 현재 페이지 id를 먼저 고른 뒤 그 작업만 집계한다.
     * 진행 중 작업·선택 작업·재고 조회 차단 작업은 필터와 무관하게 함께 돌려준다(목록에 없어도 제어·표시할 수 있도록).
     */
    @Transactional(readOnly=true) public RefreshRunPage runs(Long actor,RefreshRunQuery q) {
        db.authorize(actor,false);
        if(q.page()<0||!List.of(20,50,100).contains(q.size()))throw new IllegalArgumentException("페이지와 표시 개수(20·50·100)를 확인해 주세요.");
        String status=ProductStore.text(q.status(),30,false);
        if(!status.isEmpty()&&!RUN_STATUSES.contains(status))throw new IllegalArgumentException("작업 상태를 확인해 주세요.");
        LocalDate from=day(q.from()),to=day(q.to());
        if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
        // created_at은 한국 시간 DATETIME이다. 종료일은 다음 날 0시 전까지 포함한다.
        if(from!=null){where.append(" AND r.created_at>=?");args.add(from.atStartOfDay());}
        if(to!=null){where.append(" AND r.created_at<?");args.add(to.plusDays(1).atStartOfDay());}
        if(!status.isEmpty()){where.append(" AND r.status=?");args.add(status);}
        if(q.failed())where.append(" AND EXISTS (SELECT 1 FROM product_refresh_entry i JOIN catalog_product p ON p.id=i.product_id WHERE i.run_id=r.id AND ").append(RETRYABLE).append(')');
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run r"+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(q.size());pageArgs.add((long)q.page()*q.size());
        var ids=db.jdbc.queryForList("SELECT r.id FROM product_refresh_run r"+where+" ORDER BY r.id DESC LIMIT ? OFFSET ?",Long.class,pageArgs.toArray());
        var items=ids.isEmpty()?List.<Map<String,Object>>of():runRows(" WHERE r.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
        var active=runRows(" WHERE r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')");
        var selected=q.run()==null?null:runRows(" WHERE r.id=?",q.run()).stream().findFirst().orElse(null);
        var blocks=db.jdbc.queryForList("SELECT CAST(j.id AS CHAR) id,CAST(j.product_id AS CHAR) productId,CAST(j.supplier_id AS CHAR) supplierId,j.revision,j.message FROM supplier_stock_lookup j JOIN product_settings s ON s.stock_lookup_blocked_job=j.id WHERE s.id=1");
        return new RefreshRunPage(items,q.page(),(int)((count+q.size()-1)/q.size()),count,active,selected,blocks.isEmpty()?null:blocks.getFirst(),recovery.gate());
    }
    private static LocalDate day(String value){
        String s=ProductStore.text(value,10,false);if(s.isEmpty())return null;
        try{return LocalDate.parse(s);}catch(DateTimeException e){throw new IllegalArgumentException("날짜는 YYYY-MM-DD 형식으로 입력해 주세요.");}
    }
    private List<Map<String,Object>> runRows(String where,Object... args) {
        var params=new ArrayList<Object>(List.of(db.time.now().minusHours(24),db.time.now()));params.addAll(List.of(args));
        var rows=db.jdbc.queryForList("""
            SELECT CAST(r.id AS CHAR) id,r.status,r.trigger_type triggerType,r.created_at createdAt,r.finished_at finishedAt,r.message,r.block_reason blockReason,r.login_retry_count loginRetryCount,r.next_retry_at nextRetryAt,r.search_retry_count searchRetryCount,r.search_failure_stage searchFailureStage,r.search_failure_code searchFailureCode,
            CASE WHEN r.status='RUNNING' AND EXISTS (SELECT 1 FROM product_refresh_entry e WHERE e.run_id=r.id AND e.status='PENDING' AND NOT EXISTS (SELECT 1 FROM product_refresh_search c WHERE c.run_id=r.id AND c.query_hash=SHA2(e.query,256) AND c.checked_at>=?))
              AND NOT EXISTS (SELECT 1 FROM product_refresh_entry e WHERE e.run_id=r.id AND e.status='CHECKING')
              THEN (SELECT next_search_at FROM product_settings WHERE id=1 AND next_search_at>?) END nextSearchAt,
            COUNT(i.product_id) total,COALESCE(SUM(i.status NOT IN ('PENDING','CHECKING')),0) done,
            COALESCE(SUM(i.status='SUCCESS'),0) success,COALESCE(SUM(i.status='PARTIAL'),0) partial,COALESCE(SUM(i.status='SOLD_OUT'),0) soldOut,
            COALESCE(SUM(i.status='FAILED' AND p.deleted_at IS NULL AND p.merged_into IS NULL),0) failedCount,COALESCE(SUM(i.status='NO_MATCH' AND p.deleted_at IS NULL AND p.merged_into IS NULL),0) noMatchCount,
            COALESCE(SUM(i.status='PARTIAL' AND p.deleted_at IS NULL AND p.merged_into IS NULL),0) partialRetryCount,
            COALESCE(SUM(i.status NOT IN ('SUCCESS','SOLD_OUT','NO_MATCH','PENDING','CHECKING','FAILED','PARTIAL') AND p.deleted_at IS NULL AND p.merged_into IS NULL),0) otherRetryCount,
            (SELECT COUNT(*) FROM recommendation_lookup_diagnostic d WHERE d.run_id=r.id AND d.kind='FAILED') recommendationFailed,
            (SELECT COUNT(*) FROM recommendation_lookup_diagnostic d WHERE d.run_id=r.id AND d.kind='SKIPPED') recommendationSkipped,
            COALESCE(SUM(%s),0) retryable
            FROM product_refresh_run r LEFT JOIN product_refresh_entry i ON i.run_id=r.id LEFT JOIN catalog_product p ON p.id=i.product_id
            """.formatted(RETRYABLE)+where+" GROUP BY r.id ORDER BY r.id DESC",params.toArray());
        // Match the LocalDateTime wire format of refresh-status; JDBC Timestamp may serialize with a UTC offset.
        rows.forEach(row->{row.put("nextRetryAt",date(row.get("nextRetryAt")));row.put("nextSearchAt",date(row.get("nextSearchAt")));});
        return rows;
    }
    @Transactional(readOnly=true) public PageResponse<Map<String,Object>> items(Long actor,long run,int page) {
        db.authorize(actor,false);if(page<0)throw new IllegalArgumentException("페이지를 확인해 주세요.");
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry WHERE run_id=?",Long.class,run);
        var rows=db.jdbc.queryForList("SELECT CAST(p.id AS CHAR) productId,COALESCE(b.name,'') brand,p.product_code productCode,p.comparison_code comparisonCode,(p.deleted_at IS NOT NULL OR p.merged_into IS NOT NULL) deleted,i.status,i.result,i.checked_at checkedAt FROM product_refresh_entry i JOIN catalog_product p ON p.id=i.product_id LEFT JOIN product_brand b ON b.id=p.brand_id WHERE i.run_id=? ORDER BY p.id LIMIT 20 OFFSET ?",run,(long)page*20);
        var selections=suppliers.selected(rows.stream().map(r->r.get("productId").toString()).toList());
        rows.forEach(r->{var diagnostics=recommendationLookups.list(run,Long.parseLong(r.get("productId").toString()));r.put("recommendationDiagnostics",diagnostics);r.put("recommendationFailed",diagnostics.stream().filter(d->"FAILED".equals(d.kind())).count());r.put("recommendationSkipped",diagnostics.stream().filter(d->"SKIPPED".equals(d.kind())).count());r.put("selectedSupplier",selections.get(r.get("productId").toString()));if(r.get("result")!=null)r.put("result",db.decode(r.get("result").toString(),RefreshResult.class));});return new PageResponse<>(rows,page,(int)((count+19)/20),count);
    }
    private static String hash(String q){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(q.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
