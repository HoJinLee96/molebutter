package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.marketplace.api.MarketplaceOrderGateway;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrders.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrderCollections.*;

/** Contains only explicitly whitelisted observations, never raw responses or shipping PII. */
@Repository
class MarketplaceOrderStore {
    record StoredJob(long id,Long actor,String account,String status,Request request,String startedAt,String finishedAt) {}
    record Checkpoint(long id,long jobId,String stream,LocalDate from,LocalDate to,String cursor,String status,String message) {}
    private final JdbcTemplate db; private final ObjectMapper json;
    MarketplaceOrderStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    long saveSnapshot(String account,MarketplaceOrderGateway.Snapshot snapshot,long jobId) {
        validateSnapshot(snapshot);
        db.update("INSERT INTO marketplace_order(id,market,account_key,order_id,collected_at) VALUES(?,'COUPANG',?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE collected_at=CURRENT_TIMESTAMP(6)",BusinessIds.next(),account,snapshot.orderId());
        long parent=db.queryForObject("SELECT id FROM marketplace_order WHERE market='COUPANG' AND account_key=? AND order_id=? FOR UPDATE",Long.class,account,snapshot.orderId());
        db.update("UPDATE marketplace_order SET detail_reason=NULL WHERE id=?",parent);
        db.update("DELETE FROM marketplace_order_item WHERE parent_id=?",parent);
        String now=Instant.now().toString();
        for(var row:snapshot.items()) {
            var safe=withClaims(row,now,List.of());
            db.update("INSERT INTO marketplace_order_item(id,parent_id,shipment_box_id,sequence_no,vendor_item_id,status,product_name,ordered_at,paid_at,snapshot_json) VALUES(?,?,?,?,?,?,?,?,?,?)",BusinessIds.next(),parent,row.shipmentBoxId(),row.sequenceNo(),row.vendorItemId(),row.status(),safeString(row.productName()),kstTimestamp(row.orderedAt()),kstTimestamp(row.paidAt()),json.writeValueAsString(safe));
        }
        if(jobId>0)db.update("INSERT INTO marketplace_order_job_order(job_id,parent_id,snapshot_observed,observed_item_count) VALUES(?,?,TRUE,?) ON DUPLICATE KEY UPDATE snapshot_observed=TRUE,observed_item_count=VALUES(observed_item_count)",jobId,parent,snapshot.items().size());
        return parent;
    }
    static void validateSnapshot(MarketplaceOrderGateway.Snapshot s) {
        if(s==null||!s.fullDetailValid()||s.orderId()==null||s.items()==null||s.items().isEmpty())throw new InputValidationFailure("완전한 주문 상세가 필요합니다.");
        var identities=new HashSet<List<String>>();
        for(var row:s.items())if(!s.orderId().equals(row.orderId())||!"COUPANG".equals(row.market())||row.shipmentBoxId()==null||row.sequenceNo()==null||row.vendorItemId()==null||!identities.add(List.of(row.shipmentBoxId(),row.sequenceNo(),row.vendorItemId())))throw new InputValidationFailure("주문 식별자를 확인해 주세요.");
    }
    void saveClaim(String account,MarketplaceOrderGateway.ClaimObservation observation) {saveClaim(account,observation,0);}
    void saveClaim(String account,MarketplaceOrderGateway.ClaimObservation observation,long jobId) {
        var c=observation.summary();Timestamp incoming=kstTimestamp(c.updatedAt());
        if(observation.orderId()!=null&&!observation.orderId().isBlank()) {
            db.update("INSERT INTO marketplace_order(id,market,account_key,order_id,collected_at) VALUES(?,'COUPANG',?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE collected_at=CURRENT_TIMESTAMP(6)",BusinessIds.next(),account,observation.orderId());
            if(jobId>0)db.update("INSERT IGNORE INTO marketplace_order_job_order(job_id,parent_id) SELECT ?,id FROM marketplace_order WHERE market='COUPANG' AND account_key=? AND order_id=?",jobId,account,observation.orderId());
        }
        var prior=db.query("SELECT updated_at,JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.status')) FROM marketplace_order_claim WHERE market='COUPANG' AND account_key=? AND claim_type=? AND claim_id=? FOR UPDATE",(r,n)->new ClaimVersion(r.getTimestamp(1),r.getString(2)),account,c.type(),c.id());
        if(!acceptClaim(prior,incoming,c.status())) {
            if(incoming==null||prior.stream().anyMatch(v->incoming.equals(v.updatedAt())&&!Objects.equals(v.status(),c.status())))db.update("UPDATE marketplace_order_claim SET latest_verified=FALSE WHERE account_key=? AND claim_type=? AND claim_id=?",account,c.type(),c.id());
            linkClaims(jobId,account,c.type(),c.id());return;
        }
        if("WITHDRAWN".equals(c.status())) {
            db.update("UPDATE marketplace_order SET detail_reason=NULL WHERE account_key=? AND order_id=? AND detail_reason='ORDER_UNAVAILABLE'",account,observation.orderId());
            int changed=db.update("UPDATE marketplace_order_claim SET snapshot_json=JSON_SET(snapshot_json,'$.status','WITHDRAWN','$.updatedAt',?),updated_at=?,latest_verified=? WHERE market='COUPANG' AND account_key=? AND claim_type='RETURN' AND claim_id=?",c.updatedAt(),incoming,incoming!=null,account,c.id());
            if(changed>0){linkClaims(jobId,account,c.type(),c.id());return;}
        }
        db.update("INSERT INTO marketplace_order_claim(id,market,account_key,claim_type,claim_id,order_id,shipment_box_id,vendor_item_id,created_at,updated_at,latest_verified,snapshot_json) VALUES(?,'COUPANG',?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),created_at=COALESCE(created_at,VALUES(created_at)),updated_at=VALUES(updated_at),latest_verified=VALUES(latest_verified),snapshot_json=VALUES(snapshot_json)",BusinessIds.next(),account,c.type(),c.id(),safeString(observation.orderId()),safeString(observation.shipmentBoxId()),safeString(observation.vendorItemId()),observation.createdAt(),incoming,incoming!=null,json.writeValueAsString(c));
        linkClaims(jobId,account,c.type(),c.id());
        if(unavailable(account,observation.orderId()))for(long pending:db.queryForList("SELECT DISTINCT d.job_id FROM marketplace_order_failed_detail d JOIN marketplace_order_job j ON j.id=d.job_id WHERE j.account_key=? AND d.order_id=? AND d.error_code='ORDER_UNAVAILABLE'",Long.class,account,observation.orderId()))classifyDetail(pending,account,observation.orderId(),"ORDER_UNAVAILABLE");
    }
    record ClaimVersion(Timestamp updatedAt,String status) {}
    static boolean acceptClaim(List<ClaimVersion> prior,Timestamp incoming,String status){
        if(prior.isEmpty())return true;
        var latest=prior.stream().map(ClaimVersion::updatedAt).filter(Objects::nonNull).max(Timestamp::compareTo).orElse(null);
        if(incoming==null)return prior.stream().noneMatch(c->c.updatedAt()!=null||!Objects.equals(c.status(),status));
        if(prior.stream().anyMatch(c->incoming.equals(c.updatedAt())&&!Objects.equals(c.status(),status)))return false;
        if(latest!=null&&(incoming.before(latest)||incoming.equals(latest)&&!"WITHDRAWN".equals(status)&&prior.stream().anyMatch(c->"WITHDRAWN".equals(c.status()))))return false;
        if(latest==null&&!"WITHDRAWN".equals(status)&&prior.stream().anyMatch(c->"WITHDRAWN".equals(c.status())))return false;
        return true;
    }
    private void linkClaims(long job,String account,String type,String claim){if(job>0)db.update("INSERT IGNORE INTO marketplace_order_job_claim(job_id,claim_id) SELECT ?,id FROM marketplace_order_claim WHERE market='COUPANG' AND account_key=? AND claim_type=? AND claim_id=?",job,account,type,claim);}
    OrderPage orders(String account,Search search,int page,int size) {
        var args=new ArrayList<Object>();var where=new StringBuilder(" WHERE o.market='COUPANG' AND o.account_key=?");args.add(account);
        if(search!=null) {
            if(search.market()!=null&&!search.market().isBlank()){where.append(" AND o.market=?");args.add(search.market());}
            if(search.status()!=null&&!search.status().isBlank()){where.append(" AND i.status=?");args.add(search.status());}
            if(search.query()!=null&&!search.query().isBlank()){where.append(" AND (o.order_id LIKE ? ESCAPE '!' OR i.product_name LIKE ? ESCAPE '!' OR JSON_UNQUOTE(JSON_EXTRACT(i.snapshot_json,'$.sellerProductCode')) LIKE ? ESCAPE '!' OR i.vendor_item_id LIKE ? ESCAPE '!')");String q="%"+search.query().replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(q);args.add(q);args.add(q);args.add(q);}
            String dateColumn="PAID".equals(search.dateBasis())?"i.paid_at":"i.ordered_at";
            if("CLAIM".equals(search.dateBasis())) {
                where.append(" AND EXISTS (SELECT 1 FROM marketplace_order_claim c WHERE c.market=o.market AND c.account_key=o.account_key AND c.order_id=o.order_id");
                if(search.dateFrom()!=null&&!search.dateFrom().isBlank()){where.append(" AND c.updated_at>=?");args.add(search.dateFrom());}
                if(search.dateTo()!=null&&!search.dateTo().isBlank()){where.append(" AND c.updated_at<?");args.add(LocalDate.parse(search.dateTo()).plusDays(1).toString());}
                if(search.claimType()!=null&&!search.claimType().isBlank()){where.append(" AND c.claim_type=?");args.add(search.claimType());}
                where.append(")");
            } else {
                if(search.dateFrom()!=null&&!search.dateFrom().isBlank()){where.append(" AND "+dateColumn+">=?");args.add(search.dateFrom());}
                if(search.dateTo()!=null&&!search.dateTo().isBlank()){where.append(" AND "+dateColumn+"<?");args.add(LocalDate.parse(search.dateTo()).plusDays(1).toString());}
            }
            if(!"CLAIM".equals(search.dateBasis())&&search.claimType()!=null&&!search.claimType().isBlank()){where.append(" AND EXISTS (SELECT 1 FROM marketplace_order_claim c WHERE c.market=o.market AND c.account_key=o.account_key AND c.order_id=o.order_id AND c.claim_type=?)");args.add(search.claimType());}
        }
        String from=" FROM marketplace_order o JOIN (SELECT parent_id,shipment_box_id,sequence_no,vendor_item_id,snapshot_json,status,product_name,ordered_at,paid_at,id FROM marketplace_order_item UNION ALL SELECT p.id,'','','',NULL,'CLAIM_ONLY','클레임 정보',NULL,NULL,p.id FROM marketplace_order p WHERE NOT EXISTS (SELECT 1 FROM marketplace_order_item old WHERE old.parent_id=p.id)) i ON i.parent_id=o.id"+where;
        long total=db.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((long)page*size);
        var items=db.query("SELECT o.order_id,o.collected_at,i.snapshot_json,o.detail_reason"+from+" ORDER BY i.ordered_at DESC,o.order_id DESC,i.id LIMIT ? OFFSET ?",(r,n)->r.getString(3)==null?new OrderRow("COUPANG",r.getString(1),"","","",null,null,"상품 정보 미확인",null,r.getString(4)==null?"CLAIM_ONLY":r.getString(4),null,null,null,null,null,null,null,instant(r.getTimestamp(2)),null,List.of()):json.readValue(r.getString(3),OrderRow.class),pageArgs.toArray());
        long productTotal=db.queryForObject("SELECT COUNT(*)"+from+" AND i.snapshot_json IS NOT NULL",Long.class,args.toArray());
        return new OrderPage(items.stream().map(row->withClaims(row,row.collectedAt(),claims(account,row))).toList(),page,size,total,productTotal,total-productTotal);
    }
    OrderRow enrich(String account,OrderRow row){return withClaims(row,row.collectedAt(),claims(account,row));}
    private List<ClaimSummary> claims(String account,OrderRow row) {
        return db.query("SELECT shipment_box_id,vendor_item_id,snapshot_json,(SELECT COUNT(*) FROM marketplace_order_item i JOIN marketplace_order o ON o.id=i.parent_id WHERE o.market='COUPANG' AND o.account_key=marketplace_order_claim.account_key AND o.order_id=marketplace_order_claim.order_id AND i.shipment_box_id=marketplace_order_claim.shipment_box_id AND i.vendor_item_id=marketplace_order_claim.vendor_item_id),latest_verified FROM marketplace_order_claim WHERE market='COUPANG' AND account_key=? AND order_id=? AND ((shipment_box_id=? AND vendor_item_id=?) OR NOT EXISTS (SELECT 1 FROM marketplace_order_item i JOIN marketplace_order o ON o.id=i.parent_id WHERE o.market='COUPANG' AND o.account_key=marketplace_order_claim.account_key AND o.order_id=marketplace_order_claim.order_id AND i.shipment_box_id=marketplace_order_claim.shipment_box_id AND i.vendor_item_id=marketplace_order_claim.vendor_item_id))",(r,n)->{
            var c=json.readValue(r.getString(3),ClaimSummary.class);boolean linked=r.getLong(4)==1&&row.shipmentBoxId().equals(r.getString(1))&&row.vendorItemId().equals(r.getString(2));
            return new ClaimSummary(c.type(),c.id(),c.status(),c.quantity(),c.updatedAt(),linked,r.getBoolean(5),c.productName(),c.optionName(),c.sellerProductId(),c.vendorItemId(),c.shipmentBoxId(),c.purchaseQuantity(),c.createdAt());
        },account,row.orderId(),row.shipmentBoxId(),row.vendorItemId());
    }
    List<String> openOrderIds(String account) {
        return db.queryForList("SELECT DISTINCT o.order_id FROM marketplace_order o JOIN marketplace_order_item i ON i.parent_id=o.id WHERE o.market='COUPANG' AND o.account_key=? AND o.detail_reason IS NULL AND i.status NOT IN ('FINAL_DELIVERY','CANCELLED') ORDER BY o.order_id",String.class,account);
    }
    record ClaimRefresh(String type,String id,String orderId,String createdAt) {}
    List<ClaimRefresh> openClaims(String account){return db.query("SELECT DISTINCT claim_type,claim_id,order_id,created_at FROM marketplace_order_claim WHERE account_key=? AND claim_type IN ('RETURN','CANCEL','EXCHANGE') AND (latest_verified=FALSE OR JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.status')) NOT IN ('RETURNS_COMPLETED','RETURN_COMPLETED','SUCCESS','CANCEL','CANCELLED','WITHDRAWN'))",(r,n)->new ClaimRefresh(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),account);}
    List<StoredJob> jobs(Long actor,String account){return db.queryForList("SELECT id FROM marketplace_order_job WHERE created_by=? AND account_key=? ORDER BY id DESC LIMIT 20",Long.class,actor,account).stream().map(id->find(id,false)).toList();}
    boolean renew(long job,String owner){int lock=db.update("UPDATE marketplace_order_account_lock SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE active_job_id=? AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP(6)",job,owner);if(lock!=1)return false;return db.update("UPDATE marketplace_order_job SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE id=? AND status='RUNNING' AND lease_owner=?",job,owner)==1;}

    boolean withdrawnReceipt(String account,String id){return db.queryForObject("SELECT COUNT(*) FROM marketplace_order_claim WHERE account_key=? AND claim_type='RETURN' AND claim_id=? AND latest_verified=TRUE AND JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.status'))='WITHDRAWN'",Long.class,account,id)>0;}
    boolean expectedSeen(long checkpoint){return db.queryForObject("SELECT expected_seen FROM marketplace_order_checkpoint WHERE id=?",Boolean.class,checkpoint);}
    void markExpectedSeen(long checkpoint){db.update("UPDATE marketplace_order_checkpoint SET expected_seen=TRUE WHERE id=?",checkpoint);}
    boolean cursorSeen(long checkpoint,String cursor){return db.queryForObject("SELECT COUNT(*) FROM marketplace_order_cursor WHERE checkpoint_id=? AND cursor_hash=?",Long.class,checkpoint,DefaultMarketplaceSubmissions.account(cursor))>0;}
    long cursorCount(long checkpoint){return db.queryForObject("SELECT COUNT(*) FROM marketplace_order_cursor WHERE checkpoint_id=?",Long.class,checkpoint);}
    void rememberCursor(long checkpoint,String cursor){db.update("INSERT IGNORE INTO marketplace_order_cursor(checkpoint_id,cursor_hash) VALUES(?,?)",checkpoint,DefaultMarketplaceSubmissions.account(cursor==null?"":cursor));}
    StoredJob find(long id,boolean lock){var rows=db.query("SELECT id,created_by,account_key,status,request_json,started_at,finished_at FROM marketplace_order_job WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new StoredJob(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),json.readValue(r.getString(5),Request.class),instant(r.getTimestamp(6)),instant(r.getTimestamp(7))),id);if(rows.isEmpty())throw new InputValidationFailure("수집 이력을 찾을 수 없습니다.");return rows.getFirst();}
    Long requestJob(Long actor,String requestId){var ids=db.queryForList("SELECT id FROM marketplace_order_job WHERE created_by=? AND request_id=?",Long.class,actor,requestId);return ids.isEmpty()?null:ids.getFirst();}
    long insertJob(Long actor,String account,Request request){long id=BusinessIds.next();db.update("INSERT INTO marketplace_order_job(id,created_by,account_key,request_id,status,request_json,started_at) VALUES(?,?,?,?,'QUEUED',?,CURRENT_TIMESTAMP(6))",id,actor,account,request.requestId(),json.writeValueAsString(request));return id;}
    void addCheckpoint(long job,String stream,LocalDate from,LocalDate to){db.update("INSERT INTO marketplace_order_checkpoint(id,job_id,stream,date_from,date_to,status) VALUES(?,?,?,?,?,'PENDING')",BusinessIds.next(),job,stream,from,to);}
    List<Checkpoint> checkpoints(long job){return db.query("SELECT id,job_id,stream,date_from,date_to,cursor_value,status,message FROM marketplace_order_checkpoint WHERE job_id=? ORDER BY id",(r,n)->new Checkpoint(r.getLong(1),r.getLong(2),r.getString(3),r.getDate(4).toLocalDate(),r.getDate(5).toLocalDate(),r.getString(6),r.getString(7),r.getString(8)),job);}
    void checkpoint(long id,String cursor,String status,String message){db.update("UPDATE marketplace_order_checkpoint SET cursor_value=?,status=?,message=? WHERE id=?",cursor,status,message,id);if("DONE".equals(status))db.update("UPDATE marketplace_order_checkpoint SET error_stage=NULL,error_code=NULL WHERE id=?",id);}
    void status(long job,String status){db.update("UPDATE marketplace_order_account_lock SET active_job_id=NULL,lease_owner=NULL,lease_until=NULL WHERE active_job_id=?",job);db.update("UPDATE marketplace_order_job SET current_stage=? WHERE id=?",Set.of("QUEUED","RUNNING").contains(status)?status:"DONE",job);db.update("UPDATE marketplace_order_job SET status=?,finished_at="+(Set.of("QUEUED","RUNNING").contains(status)?"NULL":"CURRENT_TIMESTAMP(6)")+",lease_owner=NULL,lease_until=NULL WHERE id=?",status,job);}
    void retry(long job){db.update("UPDATE marketplace_order_failed_detail SET status='PENDING' WHERE job_id=? AND status='FAILED'",job);db.update("UPDATE marketplace_order_job SET error_stage=NULL,error_code=NULL,error_message=NULL WHERE id=?",job);db.update("DELETE c FROM marketplace_order_cursor c JOIN marketplace_order_checkpoint p ON p.id=c.checkpoint_id WHERE p.job_id=? AND p.status='PARTIAL' AND p.expected_seen=FALSE AND p.stream LIKE 'CLAIM%'",job);db.update("UPDATE marketplace_order_checkpoint SET cursor_value=NULL WHERE job_id=? AND status='PARTIAL' AND expected_seen=FALSE AND stream LIKE 'CLAIM%'",job);db.update("UPDATE marketplace_order_checkpoint SET status='PENDING',message=NULL WHERE job_id=? AND status IN ('FAILED','PARTIAL')",job);status(job,"QUEUED");}
    boolean observed(long job,String account,String orderId){return db.queryForObject("SELECT COUNT(*) FROM marketplace_order_job_order j JOIN marketplace_order o ON o.id=j.parent_id WHERE j.job_id=? AND j.snapshot_observed=TRUE AND o.account_key=? AND o.order_id=? AND EXISTS (SELECT 1 FROM marketplace_order_item i WHERE i.parent_id=o.id)",Long.class,job,account,orderId)>0;}
    long count(long job){return db.queryForObject("SELECT COUNT(*) FROM marketplace_order_job_order WHERE job_id=?",Long.class,job);}
    boolean isOwned(long job,String owner){return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM marketplace_order_job j JOIN marketplace_order_account_lock a ON a.account_key=j.account_key AND a.active_job_id=j.id WHERE j.id=? AND j.status='RUNNING' AND j.lease_owner=? AND j.lease_until>CURRENT_TIMESTAMP(6) AND a.lease_owner=j.lease_owner AND a.lease_until>CURRENT_TIMESTAMP(6)",Boolean.class,job,owner));}
    void lockAccount(String account){db.update("INSERT IGNORE INTO marketplace_order_account_lock(account_key) VALUES(?)",account);db.queryForList("SELECT account_key FROM marketplace_order_account_lock WHERE account_key=? FOR UPDATE",account);}
    Long claimJob(String owner,String account){
        lockAccount(account);
        var lease=db.queryForList("SELECT active_job_id,lease_until>CURRENT_TIMESTAMP(6) AND EXISTS (SELECT 1 FROM marketplace_order_job j WHERE j.id=marketplace_order_account_lock.active_job_id AND j.status='RUNNING' AND j.lease_until>CURRENT_TIMESTAMP(6)) AS valid_lease FROM marketplace_order_account_lock WHERE account_key=?",account).getFirst();
        Object valid=lease.get("valid_lease");if(lease.get("active_job_id")!=null&&(Boolean.TRUE.equals(valid)||valid instanceof Number n&&n.intValue()!=0))return null;
        if(lease.get("active_job_id")!=null)db.update("UPDATE marketplace_order_job SET status='INTERRUPTED',current_stage='INTERRUPTED',finished_at=CURRENT_TIMESTAMP(6),lease_owner=NULL,lease_until=NULL WHERE id=? AND status='RUNNING'",lease.get("active_job_id"));
        db.update("UPDATE marketplace_order_job SET status='INTERRUPTED',current_stage='INTERRUPTED',finished_at=CURRENT_TIMESTAMP(6),lease_owner=NULL,lease_until=NULL WHERE account_key=? AND status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6)",account);
        var ids=db.queryForList("SELECT id FROM marketplace_order_job WHERE status='QUEUED' AND account_key=? ORDER BY id LIMIT 1 FOR UPDATE",Long.class,account);
        if(ids.isEmpty()){db.update("UPDATE marketplace_order_account_lock SET active_job_id=NULL,lease_owner=NULL,lease_until=NULL WHERE account_key=?",account);return null;}
        long id=ids.getFirst();db.update("UPDATE marketplace_order_job SET status='RUNNING',lease_owner=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE id=?",owner,id);
        db.update("UPDATE marketplace_order_account_lock SET active_job_id=?,lease_owner=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE WHERE account_key=?",id,owner,account);return id;
    }
    String collectedAt(String account,String orderId){var rows=db.query("SELECT collected_at FROM marketplace_order WHERE market='COUPANG' AND account_key=? AND order_id=?",(r,n)->instant(r.getTimestamp(1)),account,orderId);return rows.isEmpty()?null:rows.getFirst();}
    record FailedDetail(long id,long jobId,String orderId,String source,LocalDate from,LocalDate to,String status,String code,String message) {}
    List<FailedDetail> details(long job){return db.query("SELECT id,job_id,order_id,source,date_from,date_to,status,error_code,message FROM marketplace_order_failed_detail WHERE job_id=? ORDER BY id",(r,n)->new FailedDetail(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getDate(5).toLocalDate(),r.getDate(6).toLocalDate(),r.getString(7),r.getString(8),r.getString(9)),job);}
    void failedDetail(long job,String orderId,Checkpoint checkpoint,String code,String message){var saved=find(job,false);db.update("INSERT IGNORE INTO marketplace_order(id,market,account_key,order_id,collected_at) VALUES(?,'COUPANG',?,?,CURRENT_TIMESTAMP(6))",BusinessIds.next(),saved.account(),orderId);db.update("INSERT IGNORE INTO marketplace_order_job_order(job_id,parent_id) SELECT ?,id FROM marketplace_order WHERE market='COUPANG' AND account_key=? AND order_id=?",job,saved.account(),orderId);db.update("INSERT INTO marketplace_order_failed_detail(id,job_id,order_id,source,date_from,date_to,status,error_code,message) VALUES(?,?,?,?,?,?,'FAILED',?,?) ON DUPLICATE KEY UPDATE status='FAILED',error_code=VALUES(error_code),message=VALUES(message)",BusinessIds.next(),job,orderId,source(checkpoint.stream()),checkpoint.from(),checkpoint.to(),code,message);classifyDetail(job,saved.account(),orderId,code);}
    void detailDone(long job,String orderId){db.update("UPDATE marketplace_order_failed_detail SET status='DONE',error_code=NULL,message=NULL WHERE job_id=? AND order_id=?",job,orderId);}
    void detailFailed(long id,String code,String message){db.update("UPDATE marketplace_order_failed_detail SET status='FAILED',error_code=?,message=? WHERE id=?",code,message,id);var rows=db.query("SELECT d.job_id,j.account_key,d.order_id FROM marketplace_order_failed_detail d JOIN marketplace_order_job j ON j.id=d.job_id WHERE d.id=?",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},id);if(!rows.isEmpty()){var row=rows.getFirst();classifyDetail(Long.parseLong(row[0]),row[1],row[2],code);}}
    static boolean terminalDetail(String code){return Set.of("ORDER_UNAVAILABLE","ORDER_INVALID","ORDER_ACCOUNT_MISMATCH","ORDER_REJECTED").contains(code);}
    void classifyDetail(long job,String account,String order,String code){
        db.update("UPDATE marketplace_order SET detail_reason=? WHERE account_key=? AND order_id=?",code,account,order);
        boolean supported="ORDER_UNAVAILABLE".equals(code)&&db.queryForObject("SELECT COUNT(*) FROM marketplace_order_claim WHERE account_key=? AND order_id=? AND claim_type IN ('RETURN','CANCEL') AND JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.status'))<>'WITHDRAWN'",Long.class,account,order)>0;
        if(terminalDetail(code))db.update("UPDATE marketplace_order_failed_detail SET status=? WHERE job_id=? AND order_id=?",supported?"SKIPPED":"BLOCKED",job,order);
    }
    void markUnavailable(String account,String order){db.update("UPDATE marketplace_order SET detail_reason='ORDER_UNAVAILABLE' WHERE account_key=? AND order_id=?",account,order);}
    boolean unavailable(String account,String order){return "ORDER_UNAVAILABLE".equals(detailReason(account,order));}
    String detailReason(String account,String order){var reasons=db.queryForList("SELECT detail_reason FROM marketplace_order WHERE account_key=? AND order_id=?",String.class,account,order);return reasons.isEmpty()?null:reasons.getFirst();}
    Detail storedDetail(String account,String order,String reason){
        var rows=db.query("SELECT i.snapshot_json FROM marketplace_order_item i JOIN marketplace_order o ON o.id=i.parent_id WHERE o.account_key=? AND o.order_id=? ORDER BY i.id",(r,n)->json.readValue(r.getString(1),OrderRow.class),account,order);
        if(rows.isEmpty())rows=List.of(new OrderRow("COUPANG",order,"","","",null,null,"클레임 정보",null,reason,null,null,null,null,null,null,null,collectedAt(account,order),null,List.of()));
        return new Detail(order,List.of(),rows.stream().map(row->enrich(account,row)).toList(),null,collectedAt(account,order),reason);
    }
    void stage(long job,String owner,String stage){db.update("UPDATE marketplace_order_job SET current_stage=? WHERE id=? AND status='RUNNING' AND lease_owner=?",stage,job,owner);}
    String stage(long job){return db.queryForObject("SELECT current_stage FROM marketplace_order_job WHERE id=?",String.class,job);}
    void jobError(long job,String stage,String code,String message){db.update("UPDATE marketplace_order_job SET error_stage=?,error_code=?,error_message=? WHERE id=?",stage,code,message,job);}
    void checkpointError(long id,String stage,String code){db.update("UPDATE marketplace_order_checkpoint SET error_stage=?,error_code=? WHERE id=?",stage,code,id);}
    void pageCompleted(long id){db.update("UPDATE marketplace_order_checkpoint SET pages=pages+1,error_stage=NULL,error_code=NULL WHERE id=?",id);}
    List<CheckpointProgress> checkpointProgress(long job){return db.query("SELECT stream,date_from,date_to,status,pages,error_stage,error_code,message FROM marketplace_order_checkpoint WHERE job_id=? ORDER BY id",(r,n)->{String source=source(r.getString(1)),from=r.getDate(2).toString(),to=r.getDate(3).toString();Failure failure=r.getString(7)==null?null:new Failure(r.getString(6),r.getString(7),source,from,to,null,r.getString(8));return new CheckpointProgress(source,from,to,r.getString(4),r.getLong(5),failure);},job);}
    Progress progress(long job){
        long orders=count(job);
        long items=db.queryForObject("SELECT COALESCE(SUM(observed_item_count),0) FROM marketplace_order_job_order WHERE job_id=? AND snapshot_observed=TRUE",Long.class,job);
        long claims=db.queryForObject("SELECT COUNT(DISTINCT c.claim_type,c.claim_id) FROM marketplace_order_job_claim j JOIN marketplace_order_claim c ON c.id=j.claim_id WHERE j.job_id=?",Long.class,job);
        long unknown=db.queryForObject("SELECT COUNT(*) FROM marketplace_order_job_order jo JOIN marketplace_order o ON o.id=jo.parent_id WHERE jo.job_id=? AND jo.snapshot_observed=FALSE AND COALESCE(o.detail_reason,'')<>'ORDER_UNAVAILABLE'",Long.class,job);
        var checkpoints=checkpointProgress(job);
        boolean reconstructed=Boolean.TRUE.equals(db.queryForObject("SELECT progress_reconstructed FROM marketplace_order_job WHERE id=?",Boolean.class,job));
        return new Progress(orders,items,claims,unknown,checkpoints.stream().mapToLong(CheckpointProgress::pages).sum(),checkpoints.stream().filter(c->"DONE".equals(c.status())).count(),checkpoints.size(),details(job).stream().filter(d->!Set.of("DONE","SKIPPED").contains(d.status())).count(),reconstructed);
    }
    List<Failure> failures(StoredJob job){var result=new ArrayList<Failure>();for(var c:checkpointProgress(job.id()))if(c.error()!=null)result.add(c.error());for(var d:details(job.id()))if(!Set.of("DONE","SKIPPED").contains(d.status()))result.add(new Failure("ORDER_DETAIL",d.code(),d.source(),d.from().toString(),d.to().toString(),d.orderId(),d.message()));var errors=db.queryForList("SELECT error_stage,error_code,error_message FROM marketplace_order_job WHERE id=?",job.id());if(!errors.isEmpty()&&errors.getFirst().get("error_code")!=null){var e=errors.getFirst();result.add(new Failure((String)e.get("error_stage"),(String)e.get("error_code"),"JOB",job.request().dateFrom(),job.request().dateTo(),null,(String)e.get("error_message")));}return result.stream().limit(100).toList();}
    static String source(String stream){int colon=stream.indexOf(':');return colon<0?stream:stream.substring(0,colon);}

    static OrderRow withClaims(OrderRow r,String collected,List<ClaimSummary> claims){return new OrderRow(r.market(),r.orderId(),r.shipmentBoxId(),r.sequenceNo(),r.vendorItemId(),r.sellerProductId(),r.sellerProductCode(),r.productName(),r.optionName(),r.status(),r.quantity(),r.cancelQuantity(),r.holdQuantity(),r.unitPrice(),r.orderPrice(),r.orderedAt(),r.paidAt(),collected,r.statusUpdatedAt(),claims);}
    static Timestamp kstTimestamp(String value){if(value==null||value.isBlank())return null;try{return Timestamp.valueOf(OffsetDateTime.parse(value.replace(' ','T')).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime());}catch(DateTimeException invalid){try{return Timestamp.valueOf(LocalDateTime.parse(value.replace(' ','T')));}catch(DateTimeException unknown){return null;}}}
    private static String safeString(String s){return s==null?"":s;}
    private static String instant(Timestamp t){return t==null?null:t.toInstant().toString();}
}
