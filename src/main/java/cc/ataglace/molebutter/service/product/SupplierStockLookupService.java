package cc.ataglace.molebutter.service.product;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.exception.*;
import cc.ataglace.molebutter.infra.product.*;
import lombok.RequiredArgsConstructor;

/** Durable manual queue sharing the existing worker lease. HTTP runs outside transactions. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class SupplierStockLookupService {
    private final ProductStore db;
    private final NaverSearchRecoveryService recovery;
    private final ProductChangeService changes;
    private final RecommendationLookupService recommendationLookups;
    private final ProductSupplierService suppliers;
    private final SupplierPreferenceService preferences;
    public record Work(long id,long actor,SupplierRefreshService.Work product,long supplier,Offer offer,SupplierResult previous) {}
    private OperationFailure stale(){return new OperationFailure("조회 기준이 변경되었습니다. 새로 조회해 주세요.");}
    private Map<String,Object> row(long id){var rows=db.jdbc.queryForList("SELECT * FROM supplier_stock_lookup WHERE id=?",id);if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);return rows.getFirst();}
    private long num(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    private void access(Long actor,long product,long supplier,long id){db.authorize(actor,false);var r=row(id);if(num(r,"product_id")!=product||num(r,"supplier_id")!=supplier)throw new BusinessException(ErrorCode.NOT_FOUND);}
    public StockLookupJob get(Long actor,long product,long supplier,long id){access(actor,product,supplier,id);return job(id);}
    private StockLookupJob job(long id){return db.jdbc.queryForObject("SELECT j.*,((SELECT stock_lookup_blocked_job FROM product_settings WHERE id=1)=j.id) blocking FROM supplier_stock_lookup j WHERE j.id=?",(r,n)->new StockLookupJob(r.getString("id"),r.getString("product_id"),r.getString("supplier_id"),r.getString("status"),r.getLong("revision"),r.getString("message"),ProductStore.date(r,"created_at"),ProductStore.date(r,"finished_at"),r.getBoolean("blocking")),id);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public StockLookupJob enqueue(Long actor,long product,long supplier,StockLookupInput input){
        db.authorize(actor,false);db.lock();
        if(recovery.gated())throw new OperationFailure("네이버 검색 대기·중단 상태입니다. 작업 화면에서 확인해 주세요.");
        var existing=db.jdbc.queryForList("SELECT id FROM supplier_stock_lookup WHERE product_id=? AND active_supplier=?",Long.class,product,supplier);if(!existing.isEmpty())return job(existing.getFirst());
        if(blockedJob()!=null)throw new OperationFailure("개별 재고 조회의 접속 제한을 먼저 해제해 주세요.");
        var products=db.jdbc.queryForList("SELECT revision,lookup_revision FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",product);if(products.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);var p=products.getFirst();ProductStore.revision(((Number)p.get("revision")).longValue(),input.revision());
        var comparison=suppliers.comparison(product);var listing=java.util.stream.Stream.concat(java.util.stream.Stream.concat(comparison.groups().stream(),comparison.recommendations().stream()).flatMap(g->g.listings().stream()),comparison.selected()==null?java.util.stream.Stream.empty():java.util.stream.Stream.of(comparison.selected())).filter(l->l.id().equals(Long.toString(supplier))).findFirst().orElseThrow(this::stale);
        ProductStore.revision(listing.revision(),input.supplierRevision());
        if(!listing.current()||listing.mall()!=Mall.NAVER_SMART_STORE||listing.result()==null||!NaverChannelPolicy.probeAllowed(listing.result().offer()))throw stale();
        var s=db.jdbc.queryForMap("SELECT observed_run_id FROM product_supplier WHERE id=? AND product_id=? AND merged_into IS NULL",supplier,product);if(s.get("observed_run_id")==null)throw stale();
        if(recommendationLookups.restricted(((Number)s.get("observed_run_id")).longValue(),listing.mall()))throw new OperationFailure("해당 작업에서 이 쇼핑몰의 접속 제한이 확인되었습니다. 새 최신화에서 다시 확인해 주세요.");
        long id=ProductStore.id();db.jdbc.update("INSERT INTO supplier_stock_lookup(id,product_id,supplier_id,actor_id,run_id,lookup_revision,assignment_revision,preference_revision,selection_snapshot,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?)",id,product,supplier,actor,s.get("observed_run_id"),p.get("lookup_revision"),listing.revision(),preferenceRevision(),db.encode(suppliers.selectionBasis(product)),db.time.now());changes.reproject(product);return job(id);
    }
    private long preferenceRevision(){return db.jdbc.queryForObject("SELECT preference_revision FROM product_settings WHERE id=1",Long.class);}
    private Long blockedJob(){return db.jdbc.queryForObject("SELECT stock_lookup_blocked_job FROM product_settings WHERE id=1",Long.class);}
    private boolean paused(){return db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run WHERE status IN ('PAUSED','BLOCKED','RETRY_WAIT')",Long.class)>0;}
    private boolean owns(String owner){return db.jdbc.queryForObject("SELECT COUNT(*) FROM product_settings WHERE id=1 AND worker_owner=? AND worker_until>?",Long.class,owner,db.time.now())==1;}
    private void release(String owner){db.jdbc.update("UPDATE product_settings SET worker_owner=NULL,worker_until=NULL WHERE id=1 AND worker_owner=?",owner);}
    private void terminal(long id,String state,String message){db.jdbc.update("UPDATE supplier_stock_lookup SET status=?,message=?,finished_at=?,revision=revision+1 WHERE id=?",state,message,db.time.now(),id);changes.reproject(num(row(id),"product_id"));}
    private boolean valid(Map<String,Object> j){
        try{db.authorize(num(j,"actor_id"),false);}catch(BusinessException ex){return false;}
        long product=num(j,"product_id"),supplier=num(j,"supplier_id");
        if(preferenceRevision()!=num(j,"preference_revision"))return false;
        var rows=db.jdbc.queryForList("SELECT p.lookup_revision,s.assignment_revision,s.observed_run_id,s.observation_revision,(SELECT MAX(run_id) FROM product_refresh_entry WHERE product_id=p.id) latest_run FROM catalog_product p JOIN product_supplier s ON s.product_id=p.id WHERE p.id=? AND s.id=? AND p.deleted_at IS NULL AND p.merged_into IS NULL AND s.merged_into IS NULL",product,supplier);
        if(rows.isEmpty())return false;var p=rows.getFirst();
        if(!Objects.equals(p.get("lookup_revision"),j.get("lookup_revision"))||!Objects.equals(p.get("assignment_revision"),j.get("assignment_revision"))||!Objects.equals(p.get("observed_run_id"),j.get("run_id"))||!Objects.equals(p.get("latest_run"),j.get("run_id"))||!Objects.equals(p.get("observation_revision"),j.get("lookup_revision")))return false;
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run WHERE id=? AND status='CANCELLED'",Long.class,j.get("run_id"))>0)return false;
        return Objects.equals(db.decode(j.get("selection_snapshot").toString(),SelectionBasis.class),suppliers.selectionBasis(product));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Work claim(String owner){
        db.lock();recovery.recoverAbandoned();if(recovery.gated()||blockedJob()!=null||paused())return null;
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_settings WHERE id=1 AND worker_owner IS NOT NULL AND worker_until>?",Long.class,db.time.now())>0)return null;
        db.jdbc.update("UPDATE supplier_stock_lookup SET status='PENDING',revision=revision+1 WHERE status='RUNNING'");
        for(long id:db.jdbc.queryForList("SELECT id FROM supplier_stock_lookup WHERE status='PENDING' ORDER BY id",Long.class)){
            var j=row(id);if(!valid(j)){terminal(id,"STALE","조회 기준이 변경되었습니다. 새로 요청해 주세요.");continue;}
            long product=num(j,"product_id");var result=db.decode(db.jdbc.queryForObject("SELECT observation FROM product_supplier WHERE id=?",String.class,j.get("supplier_id")),SupplierResult.class);
            if(result==null||!NaverChannelPolicy.probeAllowed(result.offer())){terminal(id,"STALE","판매글을 다시 확인해 주세요.");continue;}
            if(recommendationLookups.restricted(num(j,"run_id"),result.offer().mall())){terminal(id,"CANCELLED","해당 작업에서 쇼핑몰 접속 제한이 확인되어 개별 조회를 생략했습니다. 새 최신화에서 다시 확인해 주세요.");continue;}
            db.jdbc.update("UPDATE product_settings SET worker_owner=?,worker_until=? WHERE id=1",owner,db.time.now().plusMinutes(10));
            db.jdbc.update("UPDATE supplier_stock_lookup SET status='RUNNING',started_at=?,message=NULL,revision=revision+1 WHERE id=?",db.time.now(),id);
            var work=new SupplierRefreshService.Work(num(j,"run_id"),product,num(j,"lookup_revision"),"","","","",preferences.snapshot(),suppliers.manualAssignments(product),suppliers.selectionBasis(product));
            return new Work(id,num(j,"actor_id"),work,num(j,"supplier_id"),result.offer(),result);
        }return null;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void finish(String owner,Work w,SupplierResult result){
        db.lock();if(!owns(owner))return;var j=row(w.id());
        if(!"RUNNING".equals(j.get("status"))){release(owner);return;}
        if(!valid(j)){terminal(w.id(),"STALE","조회 기준이 변경되어 결과를 반영하지 않았습니다.");release(owner);return;}
        suppliers.recordStock(w.actor(),w.product(),w.supplier(),result);
        changes.manual(w.product(),w.supplier(),w.id());
        db.jdbc.update("UPDATE supplier_stock_lookup SET result=? WHERE id=?",db.encode(result),w.id());
        boolean verified=result.stockEvidence()!=null&&result.stockEvidence().directVerified();
        terminal(w.id(),"FAILED".equals(result.state())?"FAILED":verified?"SUCCEEDED":"UNCONFIRMED",result.message()!=null?result.message():verified?null:"매장·판매채널을 확인하지 못했습니다. 다시 최신화해 주세요.");release(owner);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void blocked(String owner,Work w,String message){
        db.lock();if(!owns(owner))return;
        // Even a cancelled in-flight request can reveal a real restriction; keep the global gate.
        db.jdbc.update("UPDATE product_settings SET stock_lookup_blocked_job=? WHERE id=1",w.id());
        var pending=row(w.id());
        if("RUNNING".equals(pending.get("status"))){
            if(valid(pending)&&SupplierLookupStatusPolicy.target(w.previous(),w.product().preferences(),w.product().manualStores(),w.product().selectionBasis()))
                db.jdbc.update("UPDATE catalog_product SET latest_status='PARTIAL',latest_result=JSON_SET(latest_result,'$.status','PARTIAL','$.message','개별 재고 조회 접속 제한 · 작업 확인 필요','$.statusPolicyVersion',?,'$.statusReasons',JSON_ARRAY('STOCK_LOOKUP_BLOCKED')),revision=revision+1 WHERE id=? AND lookup_revision=?",SupplierLookupStatusPolicy.VERSION,w.product().productId(),w.product().revision());
            terminal(w.id(),"BLOCKED",message);
        }
        release(owner);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public StockLookupJob cancel(Long actor,long product,long supplier,long id,Long revision){
        db.lock();access(actor,product,supplier,id);var j=job(id);ProductStore.revision(j.revision(),revision);
        if(Set.of("PENDING","RUNNING","BLOCKED").contains(j.status()))terminal(id,"CANCELLED","개별 조회를 취소했습니다.");return job(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public StockLookupJob resume(Long actor,long product,long supplier,long id,Long revision){
        db.lock();access(actor,product,supplier,id);var j=job(id);ProductStore.revision(j.revision(),revision);
        if(!Objects.equals(blockedJob(),id))throw new OperationFailure("재개할 접속 제한이 없습니다.");
        if(paused())throw new OperationFailure("최신화 작업의 중단·로그인 대기를 먼저 해제해 주세요.");
        db.jdbc.update("UPDATE product_settings SET stock_lookup_blocked_job=NULL WHERE id=1");
        if("BLOCKED".equals(j.status())){if(valid(row(id)))db.jdbc.update("UPDATE supplier_stock_lookup SET status='PENDING',message=NULL,finished_at=NULL,revision=revision+1 WHERE id=?",id);else terminal(id,"STALE","조회 기준이 변경되었습니다. 새로 요청해 주세요.");}changes.reproject(product);return job(id);
    }
}
