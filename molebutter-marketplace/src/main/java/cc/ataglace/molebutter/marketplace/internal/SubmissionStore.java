package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissions.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissionFailure.Kind.*;

/** Call mutating methods inside the caller's short database transaction. No remote I/O here. */
@Repository
class SubmissionStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImageAssets assets;
    SubmissionStore(JdbcTemplate db,ObjectMapper json){this(db,json,null);}
    @org.springframework.beans.factory.annotation.Autowired
    SubmissionStore(JdbcTemplate db,ObjectMapper json,ImageAssets assets){this.db=db;this.json=json;this.assets=assets;}
    record Listing(long version,String state,String reason,Instant checkedAt,MarketplaceDrafts.Document observed) {}
    Listing listing(long draft,String market,String account,boolean lock){
        var rows=db.query("SELECT listing_version,state,reason,checked_at,observed_json FROM marketplace_listing_state WHERE draft_id=? AND market=? AND account_key=?"+(lock?" FOR UPDATE":""),(r,n)->new Listing(r.getLong(1),r.getString(2),r.getString(3),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant(),r.getString(5)==null?null:json.readValue(r.getString(5),MarketplaceDrafts.Document.class)),draft,market,account);
        return rows.isEmpty()?new Listing(1,"ACTIVE",null,null,null):rows.getFirst();
    }
    long listingVersion(long draft,String market,String account){return listing(draft,market,account,false).version();}
    boolean recreating(long draft,String market,String account){return "RECREATE_READY".equals(listing(draft,market,account,false).state());}
    void observedListing(long draft,String market,String account,long version,String state,String reason,MarketplaceDrafts.Document observed){
        db.update("INSERT IGNORE INTO marketplace_listing_state(draft_id,market,account_key,listing_version,state) VALUES(?,?,?,1,'ACTIVE')",draft,market,account);
        db.update("UPDATE marketplace_listing_state SET state=?,reason=?,checked_at=CURRENT_TIMESTAMP(6),observed_json=COALESCE(?,observed_json) WHERE draft_id=? AND market=? AND account_key=? AND listing_version=?",state,reason,observed==null?null:json.writeValueAsString(observed),draft,market,account,version);
    }
    Set<String> recreationAssetIds(long draft){var ids=new HashSet<String>();for(String value:db.queryForList("SELECT observed_json FROM marketplace_listing_state WHERE draft_id=? AND state='RECREATE_READY' AND observed_json IS NOT NULL",String.class,draft))ids.addAll(MarketplaceDocuments.assetIds(json.readValue(value,MarketplaceDrafts.Document.class)));return ids;}
    record RecreateHistory(long draft,String market,String hash) {}
    RecreateHistory recreateRequest(Long actor,String request){var ids=db.query("SELECT draft_id,market,request_hash FROM marketplace_listing_history WHERE created_by=? AND request_id=?",(r,n)->new RecreateHistory(r.getLong(1),r.getString(2),r.getString(3)),actor,request);return ids.isEmpty()?null:ids.getFirst();}
    void archiveListing(Long actor,long draft,String market,EditingStore.Binding binding,String request,String hash,MarketplaceDrafts.Document document,MarketplaceWriteGateway.EditIntent intent){
        long history=BusinessIds.next();
        db.update("INSERT INTO marketplace_listing_history(id,draft_id,market,account_key,listing_version,mapping_json,document_json,intent_json,request_id,request_hash,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",history,draft,market,binding.account(),binding.listingVersion(),json.writeValueAsString(binding.mapping()),json.writeValueAsString(document),intent==null?null:json.writeValueAsString(intent),request,hash,actor);
        if(assets!=null)assets.pinListingHistory(actor,history,Long.toString(draft),MarketplaceDocuments.assetIds(document));
        db.update("DELETE FROM marketplace_listing_mapping WHERE draft_id=? AND market=? AND account_key=?",draft,market,binding.account());
        db.update("UPDATE marketplace_listing_state SET listing_version=listing_version+1,state='RECREATE_READY',reason=NULL,checked_at=CURRENT_TIMESTAMP(6),observed_json=? WHERE draft_id=? AND market=? AND account_key=? AND listing_version=?",json.writeValueAsString(document),draft,market,binding.account(),binding.listingVersion());
    }
    boolean unsupportedSnapshotVersion(long executionId){
        String snapshot=db.queryForObject("SELECT p.prepared_json FROM marketplace_execution e JOIN marketplace_submission_preview p ON p.id=e.preview_id WHERE e.id=?",String.class,executionId);
        var prepared=read(snapshot,MarketplaceWriteGateway.Prepared.class);return prepared==null||prepared.schemaVersion()!=1;
    }
    int preparedSize(MarketplaceWriteGateway.Prepared prepared){return json.writeValueAsBytes(prepared).length;}
    record StoredPreview(long id,long draftId,long revision,Long actor,Instant expiresAt,
                         MarketplaceDrafts.Document document,MarketplaceWriteGateway.Prepared prepared,Preview preview,String sessionId) {
        StoredPreview(long id,long draftId,long revision,Long actor,Instant expiresAt,MarketplaceDrafts.Document document,MarketplaceWriteGateway.Prepared prepared,Preview preview){this(id,draftId,revision,actor,expiresAt,document,prepared,preview,null);}
    }
    record Job(long executionId,long draftId,long revision,Long actor,String leaseOwner,
               MarketplaceWriteGateway.Prepared prepared,MarketplaceWriteGateway.Step step,String action,
               MarketplaceWriteGateway.Mapping mapping,MarketplaceWriteGateway.Result previous,long attemptId) {}
    void insertPreview(long id,Long actor,MarketplaceDrafts.Document document,boolean requested,Instant expiresAt){
        db.update("INSERT INTO marketplace_submission_preview(id,draft_id,draft_revision,created_by,requested,document_json,created_at,expires_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
            id,Long.parseLong(document.id()),document.revision(),actor,requested,json.writeValueAsString(document),Timestamp.from(expiresAt));
    }
    void attachSession(long previewId,String sessionId){db.update("UPDATE marketplace_submission_preview SET edit_session_id=? WHERE id=?",Long.parseLong(sessionId),previewId);}
    void completePreview(long id,MarketplaceWriteGateway.Prepared prepared,Preview preview){
        db.update("UPDATE marketplace_submission_preview SET prepared_json=?,preview_json=?,expires_at=? WHERE id=?",
            prepared==null?null:json.writeValueAsString(prepared),json.writeValueAsString(preview),Timestamp.from(Instant.parse(preview.expiresAt())),id);
    }
    StoredPreview preview(long id,boolean lock){
        var rows=db.query("SELECT draft_id,draft_revision,created_by,expires_at,document_json,prepared_json,preview_json,edit_session_id FROM marketplace_submission_preview WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new StoredPreview(id,r.getLong(1),r.getLong(2),r.getLong(3),r.getTimestamp(4).toInstant(),json.readValue(r.getString(5),MarketplaceDrafts.Document.class),read(r.getString(6),MarketplaceWriteGateway.Prepared.class),read(r.getString(7),Preview.class),r.getString(8)),id);
        if(rows.isEmpty())throw new MarketplaceSubmissionFailure(NOT_FOUND);return rows.getFirst();
    }
    void pinAssets(Long actor,long previewId,String draftId,Set<String> ids){assets.pinReferences(actor,previewId,draftId,ids);}
    String importAccount(long draftId){return db.queryForObject("SELECT import_account FROM marketplace_draft WHERE id=?",String.class,draftId);}
    String importMarket(long draftId){return db.queryForObject("SELECT import_market FROM marketplace_draft WHERE id=?",String.class,draftId);}
    Set<String> linkedMarkets(long draftId){return new LinkedHashSet<>(db.queryForList("SELECT DISTINCT market FROM marketplace_listing_mapping WHERE draft_id=? ORDER BY market",String.class,draftId));}
    Long mappedDraft(String account,String product){return mappedDraft("COUPANG",account,product);}
    Long mappedDraft(String market,String account,String product){var rows=db.queryForList("SELECT draft_id FROM marketplace_listing_mapping WHERE market=? AND account_key=? AND external_product_id=?",Long.class,market,account,product);return rows.isEmpty()?null:rows.getFirst();}
    MarketplaceWriteGateway.Mapping mapping(long draftId,String account){
        return mapping(draftId,account,false);
    }
    MarketplaceWriteGateway.Mapping mapping(long draftId,String account,boolean lock){
        return mapping(draftId,"COUPANG",account,lock);
    }
    MarketplaceWriteGateway.Mapping mapping(long draftId,String market,String account,boolean lock){
        var rows=db.queryForList("SELECT mapping_json FROM marketplace_listing_mapping WHERE draft_id=? AND market=? AND account_key=?"+(lock?" FOR UPDATE":""),String.class,draftId,market,account);
        return rows.isEmpty()?null:json.readValue(rows.getFirst(),MarketplaceWriteGateway.Mapping.class);
    }
    record TransmittedIntent(long execution,MarketplaceWriteGateway.EditIntent intent) {}
    MarketplaceWriteGateway.EditIntent lastIntent(long draft,String market,EditingStore.Binding binding){var value=transmittedIntent(draft,market,binding);return value==null?null:value.intent();}
    TransmittedIntent transmittedIntent(long draft,String market,EditingStore.Binding binding){
        for(var row:db.queryForList("SELECT e.id,p.prepared_json FROM marketplace_execution e JOIN marketplace_submission_preview p ON p.id=e.preview_id JOIN marketplace_execution_target t ON t.execution_id=e.id WHERE e.draft_id=? AND t.market=? AND t.account_key=? AND EXISTS(SELECT 1 FROM marketplace_execution_step s WHERE s.execution_id=e.id AND s.status IN ('ACCEPTED','SUCCEEDED')) AND p.prepared_json IS NOT NULL ORDER BY e.created_at DESC LIMIT 30",draft,market,binding.account())){
            var p=read((String)row.get("prepared_json"),MarketplaceWriteGateway.Prepared.class);if(p==null||p.editIntent()==null||p.schemaVersion()!=1||!Objects.equals(p.listingVersion(),binding.listingVersion())||!market.equals(p.market()))continue;
            if(p.mapping()!=null&&DefaultMarketplaceEditing.sameMapping(p.mapping(),binding.mapping())||registrationBasis(p,binding)&&registeredIntent(draft,market,binding,p.editIntent()))return new TransmittedIntent(((Number)row.get("id")).longValue(),p.editIntent());
        }return null;
    }
    boolean applied(long draft,String market,String account,long revision){
        return !db.queryForList("SELECT draft_id FROM marketplace_listing_mapping WHERE draft_id=? AND market=? AND account_key=? AND applied_revision>=?",Long.class,draft,market,account,revision).isEmpty();
    }
    /** A common-only save may advance the pending revision after CREATE acquired its external ID. */
    boolean registeredIntent(long draft,String market,EditingStore.Binding binding,MarketplaceWriteGateway.EditIntent intent){
        if(binding.mapping()==null)return false;
        var snapshots=db.queryForList("SELECT p.prepared_json FROM marketplace_execution e JOIN marketplace_execution_target t ON t.execution_id=e.id JOIN marketplace_submission_preview p ON p.id=e.preview_id WHERE e.draft_id=? AND t.market=? AND t.account_key=? AND t.mode='CREATE' AND t.external_product_id=? AND EXISTS(SELECT 1 FROM marketplace_execution_step s WHERE s.execution_id=e.id AND s.step_type='CREATE' AND s.status IN ('ACCEPTED','SUCCEEDED'))",String.class,draft,market,binding.account(),binding.mapping().sellerProductId());
        for(var snapshot:snapshots){var prepared=read(snapshot,MarketplaceWriteGateway.Prepared.class);if(registrationBasis(prepared,binding)&&sameRegisteredIntent(prepared.editIntent(),intent,market))return true;}
        return false;
    }
    /** CREATE may carry stable option UUIDs before the remote product/option IDs exist. */
    boolean registrationBasis(MarketplaceWriteGateway.Prepared prepared,EditingStore.Binding binding){
        if(prepared==null||prepared.schemaVersion()!=1||binding.mapping()==null||!Objects.equals(prepared.accountKey(),binding.account())||!Objects.equals(prepared.listingVersion(),binding.listingVersion()))return false;
        var placeholder=prepared.mapping();if(placeholder==null)return true;
        if(placeholder.sellerProductId()!=null||placeholder.channelProductId()!=null||!Objects.equals(placeholder.accountKey(),binding.account())||placeholder.options()==null)return false;
        var ids=new HashSet<String>();for(var option:placeholder.options())if(option.optionId()==null||!ids.add(option.optionId())||option.sellerProductItemId()!=null||option.vendorItemId()!=null)return false;
        return binding.mapping().options()!=null&&ids.equals(binding.mapping().options().stream().map(MarketplaceWriteGateway.OptionMapping::optionId).collect(java.util.stream.Collectors.toSet()));
    }
    boolean sameRegisteredIntent(MarketplaceWriteGateway.EditIntent sent,MarketplaceWriteGateway.EditIntent saved,String market){
        if(sent==null||saved==null||!sent.changes().equals(saved.changes()))return false;
        var a=json.valueToTree(sent.observed()).asObject();var b=json.valueToTree(saved.observed()).asObject();var selected=a.path("markets").path(market).equals(b.path("markets").path(market));
        // Selection and unrelated provider settings are not part of this provider's CREATE body.
        for(var field:List.of("revision","selectedMarkets","markets")){a.remove(field);b.remove(field);}return selected&&a.equals(b);
    }
    Long byKey(String key){var rows=db.queryForList("SELECT id FROM marketplace_execution WHERE idempotency_key=?",Long.class,key);return rows.isEmpty()?null:rows.getFirst();}
    Long byPreview(long previewId){var rows=db.queryForList("SELECT id FROM marketplace_execution WHERE preview_id=?",Long.class,previewId);return rows.isEmpty()?null:rows.getFirst();}
    boolean active(long draftId,String account,long excluding){
        // A current read is essential: an earlier idempotency SELECT may have opened an old RR snapshot.
        return !db.queryForList("SELECT DISTINCT e.id FROM marketplace_execution e JOIN marketplace_execution_target t ON t.execution_id=e.id JOIN marketplace_execution_step s ON s.execution_id=e.id WHERE e.draft_id=? AND t.account_key=? AND e.id<>? AND s.status IN ('QUEUED','RUNNING','UNKNOWN','ACCEPTED') FOR UPDATE",Long.class,draftId,account,excluding).isEmpty();
    }
    long createExecution(Long actor,StoredPreview preview,String key){
        long id=BusinessIds.next();var p=preview.prepared();String product=p.mapping()==null?null:p.mapping().sellerProductId();
        db.update("INSERT INTO marketplace_execution(id,preview_id,draft_id,draft_revision,idempotency_key,created_by,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'QUEUED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id,preview.id(),preview.draftId(),preview.revision(),key,actor);
        db.update("INSERT INTO marketplace_execution_target(execution_id,market,account_key,mode,status,external_product_id) VALUES(?,?,?,?,'QUEUED',?)",id,p.market(),p.accountKey(),product==null?"CREATE":"UPDATE",product);
        int order=0;for(var step:p.steps())db.update("INSERT INTO marketplace_execution_step(execution_id,step_id,sequence_no,step_type,option_id,status,action,updated_at) VALUES(?,?,?,?,?,'QUEUED','WRITE',CURRENT_TIMESTAMP(6))",id,step.id(),order++,step.type().name(),step.optionId());
        if(p.mapping()!=null&&p.mapping().sellerProductId()!=null)saveMapping(preview.draftId(),preview.revision(),p.market(),p.mapping(),false);
        return id;
    }
    Execution execution(long id,boolean lock){
        var rows=db.query("SELECT draft_id,draft_revision,status,created_at,updated_at,revised_at FROM marketplace_execution WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Execution(Long.toString(id),r.getString(1),r.getLong(2),Status.valueOf(r.getString(3)),r.getTimestamp(4).toInstant().toString(),r.getTimestamp(5).toInstant().toString(),targets(id,lock),r.getTimestamp(6)!=null),id);
        if(rows.isEmpty())throw new MarketplaceSubmissionFailure(NOT_FOUND);return rows.getFirst();
    }
    private List<Target> targets(long id,boolean lock){return db.query("SELECT market,mode,status,external_product_id FROM marketplace_execution_target WHERE execution_id=? ORDER BY market"+(lock?" FOR UPDATE":""),
        (r,n)->new Target(r.getString(1),r.getString(2),Status.valueOf(r.getString(3)),r.getString(4),steps(id,lock)),id);}
    private List<Step> steps(long id,boolean lock){
        var previewId=db.queryForObject("SELECT preview_id FROM marketplace_execution WHERE id=?",Long.class,id);
        var prepared=preview(previewId,false).prepared();var names=new HashMap<String,String>();
        if(prepared!=null&&prepared.editIntent()!=null)for(var option:prepared.editIntent().observed().options())names.put(option.id(),option.name());
        return db.query("SELECT step_id,step_type,option_id,status,attempts,result_code,result_message,updated_at FROM marketplace_execution_step WHERE execution_id=? ORDER BY sequence_no"+(lock?" FOR UPDATE":""),
        (r,n)->new Step(r.getString(1),StepType.valueOf(r.getString(2)),r.getString(3),label(r.getString(2))+(names.get(r.getString(3))==null?"":" · "+names.get(r.getString(3))),Status.valueOf(r.getString(4)),r.getInt(5),r.getString(6),"HTTP_411_REJECTED".equals(r.getString(6))?knownLegacyReason(r.getString(6)):r.getString(7),r.getTimestamp(8).toInstant().toString()),id);
    }
    PageResponse<Execution> list(long draftId,int page,int size){
        String scope="(draft_id=? OR draft_id IN (SELECT previous_draft_id FROM marketplace_editor_history_link WHERE draft_id=?))";
        long count=db.queryForObject("SELECT COUNT(*) FROM marketplace_execution WHERE "+scope,Long.class,draftId,draftId);
        var ids=db.queryForList("SELECT id FROM marketplace_execution WHERE "+scope+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",Long.class,draftId,draftId,size,(long)page*size);
        return new PageResponse<>(ids.stream().map(i->execution(i,false)).toList(),page,(int)Math.ceil((double)count/size),count);
    }
    void queueRetry(long id){
        db.update("UPDATE marketplace_execution_step SET status='QUEUED',action='WRITE',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='FAILED'",id);aggregate(id);
    }
    void revise(long id,Long actor){
        // Preserve all attempted outcomes. Only never-started writes are retired.
        db.update("UPDATE marketplace_execution_step SET status='FAILED',result_code='NOT_EXECUTED',result_message='입력 수정으로 이전 요청을 종료했습니다. 실행하지 않은 단계입니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='QUEUED' AND action='WRITE' AND attempts=0",id);
        db.update("UPDATE marketplace_execution SET revised_at=CURRENT_TIMESTAMP(6),revised_by=? WHERE id=? AND revised_at IS NULL",actor,id);aggregate(id);
    }
    boolean pendingRevisionWork(long id){
        return !db.queryForList("SELECT step_id FROM marketplace_execution_step WHERE execution_id=? AND status='QUEUED' AND (action<>'WRITE' OR attempts<>0) FOR UPDATE",String.class,id).isEmpty();
    }
    String executionAccount(long id){return db.queryForObject("SELECT account_key FROM marketplace_execution_target WHERE execution_id=?",String.class,id);}
    void queueReconcile(long id){
        db.update("UPDATE marketplace_execution_step SET status='QUEUED',action='RECONCILE',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status IN ('UNKNOWN','ACCEPTED')",id);aggregate(id);
    }
    Job claim(String owner){
        // Serialize execution claims per account across processes; never resume an ambiguous WRITE.
        var candidates=db.queryForList("SELECT DISTINCT e.id FROM marketplace_execution e JOIN marketplace_execution_step s ON s.execution_id=e.id WHERE s.status='QUEUED' AND (s.action='RECONCILE' OR NOT EXISTS(SELECT 1 FROM marketplace_execution_step u WHERE u.execution_id=e.id AND u.status IN ('UNKNOWN','ACCEPTED','FAILED'))) ORDER BY e.id LIMIT 20",Long.class);
        for(long executionId:candidates){
            var account=db.queryForObject("SELECT account_key FROM marketplace_execution_target WHERE execution_id=?",String.class,executionId);
            db.update("INSERT IGNORE INTO marketplace_submission_account(account_key) VALUES(?)",account);
            var leases=db.queryForList("SELECT lease_owner,lease_until FROM marketplace_submission_account WHERE account_key=? FOR UPDATE",account);
            var lease=leases.getFirst();var until=(Timestamp)lease.get("lease_until");
            if(until!=null&&until.toInstant().isAfter(Instant.now()))continue;
            var header=db.queryForList("SELECT preview_id,draft_id,draft_revision,created_by FROM marketplace_execution WHERE id=?",executionId).getFirst();
            // Inspect/lock earlier ambiguity before the candidate itself; API mutations lock draft before execution.
            var ambiguousOthers=db.queryForList("SELECT DISTINCT e.id FROM marketplace_execution e JOIN marketplace_execution_target t ON t.execution_id=e.id JOIN marketplace_execution_step u ON u.execution_id=e.id WHERE e.draft_id=? AND t.account_key=? AND e.id<>? AND u.status IN ('UNKNOWN','ACCEPTED') FOR UPDATE",Long.class,((Number)header.get("draft_id")).longValue(),account,executionId);
            var latest=execution(executionId,true);
            boolean unresolved=latest.targets().stream().flatMap(t->t.steps().stream()).anyMatch(s->s.status()==Status.UNKNOWN||s.status()==Status.ACCEPTED);
            var rows=db.queryForList("SELECT step_id,action,result_json FROM marketplace_execution_step WHERE execution_id=? AND status='QUEUED' ORDER BY CASE WHEN action='RECONCILE' THEN 0 ELSE 1 END,sequence_no LIMIT 1 FOR UPDATE",executionId);
            if(rows.isEmpty())continue;
            var row=rows.getFirst();String stepId=row.get("step_id").toString(),action=row.get("action").toString();
            if(action.equals("WRITE")&&(unresolved||!ambiguousOthers.isEmpty()||latest.targets().stream().flatMap(t->t.steps().stream()).anyMatch(s->s.status()==Status.FAILED)))continue;
            var p=preview(((Number)header.get("preview_id")).longValue(),false).prepared();
            if(p.listingVersion()!=listingVersion(((Number)header.get("draft_id")).longValue(),p.market(),p.accountKey())){
                db.update("UPDATE marketplace_execution_step SET status='FAILED',result_code='MAPPING_CHANGED',result_message='이전 연결 세대의 요청은 실행할 수 없습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='QUEUED'",executionId);aggregate(executionId);continue;
            }
            var step=p.steps().stream().filter(s->s.id().equals(stepId)).findFirst().orElseThrow();
            if(step.type()==MarketplaceWriteGateway.Type.APPROVAL&&action.equals("WRITE")){
                // Bind verification to the actual saved request, including any rebased payload.
                var parent=json.readValue(step.expectedJson(),MarketplaceWriteGateway.Step.class);
                if(!p.steps().stream().anyMatch(s->s.id().equals(parent.id())&&(s.type()==MarketplaceWriteGateway.Type.CREATE||s.type()==MarketplaceWriteGateway.Type.PRODUCT)))throw new MarketplaceSubmissionFailure(CONFLICT);
                var requests=db.queryForList("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=? AND step_id=? AND action='WRITE' AND request_json IS NOT NULL ORDER BY started_at DESC,id DESC LIMIT 1",String.class,executionId,parent.id());
                if(!requests.isEmpty()){
                    var actual=json.readValue(requests.getFirst(),MarketplaceWriteGateway.Step.class);
                    if(actual.type()!=parent.type()||!Objects.equals(actual.optionId(),parent.optionId()))throw new MarketplaceSubmissionFailure(CONFLICT);
                    step=new MarketplaceWriteGateway.Step(step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),step.bodyJson(),step.baselineJson(),json.writeValueAsString(actual));
                }
            }
            if(action.equals("RECONCILE")){
                var requests=db.queryForList("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=? AND step_id=? AND action='WRITE' AND request_json IS NOT NULL ORDER BY started_at DESC,id DESC LIMIT 1",String.class,executionId,stepId);
                if(!requests.isEmpty()){
                    var actual=json.readValue(requests.getFirst(),MarketplaceWriteGateway.Step.class);
                    if(actual.type()!=step.type()||!Objects.equals(actual.optionId(),step.optionId()))throw new MarketplaceSubmissionFailure(CONFLICT);
                    step=new MarketplaceWriteGateway.Step(stepId,actual.type(),actual.optionId(),actual.method(),actual.path(),actual.query(),actual.bodyJson(),actual.baselineJson(),actual.expectedJson());
                }
            }
            var mapping=mapping(((Number)header.get("draft_id")).longValue(),p.market(),account,true);if(mapping==null)mapping=p.mapping();
            long attemptId=BusinessIds.next();
            db.update("UPDATE marketplace_submission_account SET lease_owner=?,lease_until=? WHERE account_key=?",owner,Timestamp.from(Instant.now().plusSeconds(300)),account);
            db.update("UPDATE marketplace_execution_step SET status='RUNNING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=?",executionId,stepId);
            db.update("INSERT INTO marketplace_execution_attempt(id,execution_id,step_id,action,status,started_at) VALUES(?,?,?,?,'RUNNING',CURRENT_TIMESTAMP(6))",attemptId,executionId,stepId,action);aggregate(executionId);
            return new Job(executionId,((Number)header.get("draft_id")).longValue(),((Number)header.get("draft_revision")).longValue(),((Number)header.get("created_by")).longValue(),owner,p,step,action,mapping,read((String)row.get("result_json"),MarketplaceWriteGateway.Result.class),attemptId);
        }
        return null;
    }
    boolean owns(Job job){return db.queryForObject("SELECT COUNT(*) FROM marketplace_submission_account WHERE account_key=? AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP(6)",Long.class,job.prepared().accountKey(),job.leaseOwner())==1;}
    void recordRequest(Job job,MarketplaceWriteGateway.Step actual){
        db.queryForList("SELECT account_key FROM marketplace_submission_account WHERE account_key=? FOR UPDATE",String.class,job.prepared().accountKey());
        if(!owns(job))throw new MarketplaceSubmissionFailure(CONFLICT);
        // Server-only exact request snapshot; never returned through execution DTOs.
        db.update("UPDATE marketplace_execution_attempt SET request_json=? WHERE id=? AND status='RUNNING'",json.writeValueAsString(actual),job.attemptId());
    }
    void heartbeat(Job job){db.update("UPDATE marketplace_submission_account SET lease_until=? WHERE account_key=? AND lease_owner=?",Timestamp.from(Instant.now().plusSeconds(300)),job.prepared().accountKey(),job.leaseOwner());}
    void finish(Job job,MarketplaceWriteGateway.Result result){
        db.queryForList("SELECT account_key FROM marketplace_submission_account WHERE account_key=? FOR UPDATE",String.class,job.prepared().accountKey());
        if(!owns(job))return;
        db.queryForList("SELECT id FROM marketplace_draft WHERE id=? FOR UPDATE",Long.class,job.draftId());execution(job.executionId(),true);
        if(job.prepared().listingVersion()!=listingVersion(job.draftId(),job.prepared().market(),job.prepared().accountKey())){
            db.update("UPDATE marketplace_execution_step SET status='FAILED',result_code='MAPPING_CHANGED',result_message='이전 연결 세대의 결과입니다. 현재 연결은 변경하지 않습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=? AND status='RUNNING'",job.executionId(),job.step().id());
            db.update("UPDATE marketplace_execution_attempt SET status='FAILED',result_code='MAPPING_CHANGED',completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",job.attemptId());aggregate(job.executionId());
            db.update("UPDATE marketplace_submission_account SET lease_owner=NULL,lease_until=NULL WHERE account_key=? AND lease_owner=?",job.prepared().accountKey(),job.leaseOwner());return;
        }
        if(job.action().equals("RECONCILE") && result.state()==MarketplaceWriteGateway.State.FAILED
                && Set.of("ACCESS_DENIED","ACCOUNT_CHANGED","UNSUPPORTED_SNAPSHOT_VERSION").contains(publicCode(result.code()))){
            // A failed check is not evidence that the previously dispatched write failed.
            var previous=job.previous();
            var retained=previous!=null && (previous.state()==MarketplaceWriteGateway.State.UNKNOWN || previous.state()==MarketplaceWriteGateway.State.ACCEPTED)
                    ? previous : new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.UNKNOWN,job.mapping(),"INTERRUPTED",null,Instant.now());
            String retainedState=retained.state()==MarketplaceWriteGateway.State.ACCEPTED?"ACCEPTED":"UNKNOWN";
            db.update("UPDATE marketplace_execution_step SET status=?,result_json=?,result_code=?,result_message=?,updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=? AND status='RUNNING'",retainedState,json.writeValueAsString(retained),publicCode(retained.code()),"기존 전송 결과는 미확인 상태로 유지됩니다. 최근 결과 확인 실패: "+publicCode(result.code()),job.executionId(),job.step().id());
            db.update("UPDATE marketplace_execution_attempt SET status='FAILED',result_code=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",publicCode(result.code()),job.attemptId());
            aggregate(job.executionId());
            db.update("UPDATE marketplace_submission_account SET lease_owner=NULL,lease_until=NULL WHERE account_key=? AND lease_owner=?",job.prepared().accountKey(),job.leaseOwner());
            return;
        }
        String state=switch(result.state()){case CONFIRMED->"SUCCEEDED";case ACCEPTED->"ACCEPTED";case FAILED->"FAILED";case UNKNOWN->"UNKNOWN";};
        String code=publicCode(result.code()),message=publicMessage(state,code,job.prepared().market());
        db.update("UPDATE marketplace_execution_step SET status=?,result_json=?,result_code=?,result_message=?,updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=? AND status='RUNNING'",state,json.writeValueAsString(result),code,message,job.executionId(),job.step().id());
        db.update("UPDATE marketplace_execution_attempt SET status=?,result_code=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",state,code,job.attemptId());
        if(result.mapping()!=null&&result.mapping().sellerProductId()!=null){
            saveMapping(job.draftId(),job.revision(),job.prepared().market(),result.mapping(),false);
            db.update("UPDATE marketplace_execution_target SET external_product_id=? WHERE execution_id=? AND market=?",result.mapping().sellerProductId(),job.executionId(),job.prepared().market());
        }
        if(result.state()==MarketplaceWriteGateway.State.FAILED&&Set.of("BASELINE_CHANGED","ACCOUNT_CHANGED","ACCESS_DENIED","MAPPING_CHANGED","ALREADY_CREATED","ALREADY_REGISTERED","LEGACY_INTENT","UNSUPPORTED_SNAPSHOT_VERSION").contains(code)){
            // This intent cannot safely be replayed. Release only never-started writes for a fresh preview.
            db.update("UPDATE marketplace_execution_step SET status='FAILED',result_code='NOT_EXECUTED',result_message='앞 단계 충돌로 실행하지 않았습니다. 새 변경 확인이 필요합니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='QUEUED' AND action='WRITE'",job.executionId());
        }
        aggregate(job.executionId());
        if(execution(job.executionId(),false).status()==Status.SUCCEEDED&&result.mapping()!=null)saveMapping(job.draftId(),job.revision(),job.prepared().market(),result.mapping(),true);
        db.update("UPDATE marketplace_submission_account SET lease_owner=NULL,lease_until=NULL WHERE account_key=? AND lease_owner=?",job.prepared().accountKey(),job.leaseOwner());
    }
    void saveMapping(long draftId,long revision,MarketplaceWriteGateway.Mapping mapping,boolean applied){
        saveMapping(draftId,revision,"COUPANG",mapping,applied);
    }
    void saveMapping(long draftId,long revision,String market,MarketplaceWriteGateway.Mapping mapping,boolean applied){
        if(mapping.sellerProductId()==null)return;
        var owners=db.queryForList("SELECT draft_id FROM marketplace_listing_mapping WHERE market=? AND account_key=? AND external_product_id=? FOR UPDATE",Long.class,market,mapping.accountKey(),mapping.sellerProductId());
        if(!owners.isEmpty()&&owners.getFirst()!=draftId)throw new MarketplaceSubmissionFailure(CONFLICT);
        var previous=mapping(draftId,market,mapping.accountKey(),true);if(previous!=null&&(!previous.sellerProductId().equals(mapping.sellerProductId())||previous.channelProductId()!=null&&!Objects.equals(previous.channelProductId(),mapping.channelProductId())))throw new MarketplaceSubmissionFailure(CONFLICT);
        db.update("INSERT INTO marketplace_listing_mapping(draft_id,market,account_key,external_product_id,mapping_json,applied_revision,updated_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE mapping_json=VALUES(mapping_json),applied_revision=IF(VALUES(applied_revision) IS NULL,applied_revision,GREATEST(COALESCE(applied_revision,-1),VALUES(applied_revision))),updated_at=CURRENT_TIMESTAMP(6)",draftId,market,mapping.accountKey(),mapping.sellerProductId(),json.writeValueAsString(mapping),applied?revision:null);
        db.update("UPDATE marketplace_listing_state SET state='ACTIVE',reason=NULL WHERE draft_id=? AND market=? AND account_key=?",draftId,market,mapping.accountKey());
    }
    void moveMapping(long oldDraft,long newDraft,String market,String account){
        db.update("DELETE FROM marketplace_listing_mapping WHERE draft_id=? AND market=? AND account_key=?",oldDraft,market,account);
        db.update("INSERT INTO marketplace_editor_history_link(draft_id,previous_draft_id) VALUES(?,?)",newDraft,oldDraft);
    }
    boolean archived(long draft){return !db.queryForList("SELECT draft_id FROM marketplace_editor_history_link WHERE previous_draft_id=?",Long.class,draft).isEmpty();}
    void aggregate(long id){
        var statuses=db.queryForList("SELECT status FROM marketplace_execution_step WHERE execution_id=?",String.class,id).stream().map(Status::valueOf).toList();Status state=aggregateStatus(statuses);
        db.update("UPDATE marketplace_execution SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",state.name(),id);
        db.update("UPDATE marketplace_execution_target SET status=? WHERE execution_id=?",state.name(),id);
    }
    static Status aggregateStatus(List<Status> states){
        if(states.contains(Status.RUNNING))return Status.RUNNING;
        if(states.contains(Status.UNKNOWN))return Status.UNKNOWN;
        if(states.contains(Status.ACCEPTED))return states.contains(Status.FAILED)?Status.PARTIAL:Status.ACCEPTED;
        if(states.contains(Status.FAILED))return states.stream().anyMatch(s->s==Status.SUCCEEDED||s==Status.ACCEPTED)?Status.PARTIAL:Status.FAILED;
        if(states.contains(Status.QUEUED))return Status.QUEUED;
        return Status.SUCCEEDED;
    }
    void recoverExpired(){
        var ids=db.queryForList("SELECT DISTINCT s.execution_id FROM marketplace_execution_step s JOIN marketplace_execution_target t ON t.execution_id=s.execution_id LEFT JOIN marketplace_submission_account a ON a.account_key=t.account_key WHERE s.status='RUNNING' AND (a.lease_until IS NULL OR a.lease_until<CURRENT_TIMESTAMP(6))",Long.class);
        for(long id:ids){
            String account=db.queryForObject("SELECT account_key FROM marketplace_execution_target WHERE execution_id=?",String.class,id);
            var leases=db.queryForList("SELECT lease_until FROM marketplace_submission_account WHERE account_key=? FOR UPDATE",account);
            if(!leases.isEmpty()){var until=(Timestamp)leases.getFirst().get("lease_until");if(until!=null&&until.toInstant().isAfter(Instant.now()))continue;}
            execution(id,true);
            db.update("UPDATE marketplace_execution_step SET status='UNKNOWN',result_code='INTERRUPTED',result_message='실행 중단으로 결과를 확인할 수 없습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='RUNNING'",id);
            db.update("UPDATE marketplace_execution_attempt SET status='UNKNOWN',result_code='INTERRUPTED',completed_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='RUNNING'",id);aggregate(id);
        }
    }
    List<Long> expiredUnexecutedPreviews(){
        return db.queryForList("SELECT p.id FROM marketplace_submission_preview p WHERE p.expires_at<CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR AND NOT EXISTS(SELECT 1 FROM marketplace_execution e WHERE e.preview_id=p.id) AND EXISTS(SELECT 1 FROM marketplace_execution_asset r WHERE r.submission_id=p.id) ORDER BY p.id LIMIT 20",Long.class);
    }
    void releaseExpiredPreviewPins(long previewId){
        var preview=preview(previewId,true);
        if(preview.expiresAt().plusSeconds(86400).isAfter(Instant.now())||byPreview(previewId)!=null)return;
        assets.releaseReferences(previewId);
    }
    static String label(String type){return switch(type){case "CREATE"->"상품 등록";case "PRODUCT"->"상품 정보 변경";case "DELIVERY"->"배송·반품 변경";case "ORIGINAL_PRICE"->"정상가 변경";case "PRICE"->"현재 가격 변경";case "STOCK"->"현재 재고 변경";case "APPROVAL"->"판매 승인 요청";default->"반영 확인";};}
    static String publicCode(String code){return code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"RESPONSE";}
    private static String knownLegacyReason(String code){return switch(code){
        case "HTTP_411_REJECTED"->"쿠팡이 요청 본문 길이 헤더를 요구했습니다(HTTP 411). 빈 PUT 요청의 Content-Length: 0 전송 방식이 수정되었습니다. 실패·미실행 단계만 재시도해 주세요.";
        case "PRICE_CHANGE_RANGE"->"쿠팡 가격 변경 비율 제한입니다(최대 50% 인하·100% 인상). 변경값을 확인해 주세요. 강제 변경은 자동으로 요청하지 않습니다.";
        case "AUTOMATIC_OPTION"->"자동생성 옵션의 판매가는 직접 변경할 수 없습니다. 기준 판매자 옵션 또는 쿠팡 Wing에서 변경해 주세요.";
        case "DELETED_OPTION"->"삭제된 상품 옵션은 변경할 수 없습니다. 상품을 다시 조회해 주세요.";
        case "PRICE_UNIT"->"판매가는 10원 단위로 입력해 주세요.";
        case "INVALID_OPTION_ID"->"쿠팡이 옵션 ID를 유효하지 않다고 응답했습니다. 상품을 다시 조회해 주세요.";
        case "AUTO_PRICE_MINIMUM"->"자동 가격 조정의 최저 판매가는 변경 판매가보다 작아야 합니다.";
        case "AUTO_PRICE_PAIR"->"자동 가격 조정의 최저 판매가와 활성화 설정을 함께 전달해야 합니다.";
        default->code.matches("HTTP_[0-9]{3}_REJECTED")?"쿠팡이 요청을 거절했습니다(HTTP "+code.substring(5,8)+"). 알려진 오류 사유로 분류되지 않았습니다.":null;
    };}

    static String publicMessage(String status,String code){
        if(status.equals("SUCCEEDED"))return "반영 확인 완료";
        if(code.equals("NEEDS_CORRECTION"))return "저장 완료 · 옵션 속성 보완 필요";
        if(code.equals("WAIT_RECONCILE"))return "등록 결과를 확인할 때까지 대기해 주세요.";
        if(status.equals("ACCEPTED"))return "접수 완료 · 반영 확인 필요";
        if(status.equals("UNKNOWN"))return "결과를 확인할 수 없습니다. 다시 등록하지 말고 결과를 확인해 주세요.";
        var reason=knownLegacyReason(code);if(reason!=null)return reason;
        return switch(code){case "ACCESS_DENIED"->"실행 권한이 변경되었습니다.";case "BASELINE_CHANGED"->"쿠팡 상품이 변경되었습니다. 다시 확인해 주세요.";case "LEGACY_INTENT"->"최신 조회와 변경 항목을 다시 확인해 주세요.";case "ACCOUNT_CHANGED"->"쿠팡 연결 계정이 변경되었습니다.";case "RATE_LIMIT"->"쿠팡 호출 제한입니다. 잠시 후 다시 시도해 주세요.";case "AUTHENTICATION","PERMISSION","CONFIGURATION"->"쿠팡 연결 설정·권한을 확인해 주세요.";default->"요청이 반영되지 않았습니다. 입력과 실행 결과를 확인해 주세요.";};
    }
    static String publicMessage(String status,String code,String market){
        if(status.equals("SUCCEEDED")&&code.equals("SAVED_FOR_APPROVAL"))return "쿠팡 저장 완료 · 다음 단계에서 판매 승인을 요청합니다.";
        if(code.equals("APPROVAL_NOT_READY"))return "쿠팡 저장 완료 · 상품 처리 중이므로 10분 후 승인 요청 단계만 재시도해 주세요.";
        if(status.equals("SUCCEEDED")&&code.equals("CREATED_VALUES_DIFFER"))return "등록·옵션 연결 확인 완료 · 현재 상품값이 등록 요청과 다릅니다. 상품 수정에서 최신값을 확인해 주세요.";
        if(status.equals("SUCCEEDED")&&code.equals("CREATED_CONFIRMED"))return "등록·옵션 연결 확인 완료 · 상품 수정에서 최신값을 확인해 주세요.";
        if(status.equals("ACCEPTED")&&Set.of("CREATE_OPTIONS_UNCONFIRMED","OPTIONS_UNCONFIRMED").contains(code))return "등록 상품은 조회되지만 옵션 연결 확인이 필요합니다. 같은 상품을 다시 등록하지 마세요.";
        if(status.equals("ACCEPTED")&&Set.of("CREATE_STATUS_UNCONFIRMED","STATUS_UNCONFIRMED").contains(code))return "등록 상품은 조회되지만 현재 판매·승인 상태를 확인하지 못했습니다.";
        if(status.equals("ACCEPTED")&&code.equals("APPROVAL_PENDING"))return "등록 상품 확인 완료 · 판매 승인 대기 중입니다.";
        if(status.equals("ACCEPTED")&&code.equals("APPROVAL_REJECTED"))return "등록 상품 확인 완료 · 승인 반려 사유를 판매자센터에서 확인해 주세요.";
        if(status.equals("ACCEPTED")&&code.equals("EDIT_UNSUPPORTED"))return "등록 상품은 조회되지만 현재 상품 유형의 수정 반영을 자동 확인할 수 없습니다. 판매자센터에서 확인해 주세요.";
        if(status.equals("ACCEPTED")&&code.equals("VERIFY_UNAVAILABLE"))return "결과 조회에 실패했습니다. 같은 요청을 다시 보내지 말고 잠시 후 결과를 확인해 주세요.";
        if(status.equals("FAILED")&&code.startsWith("PREFLIGHT_"))return "전송 전 조회에 실패하여 상품 요청을 보내지 않았습니다. 잠시 후 다시 확인해 주세요.";
        if("ELEVENST".equals(market)&&status.equals("ACCEPTED")&&code.equals("IMAGE_REVIEW_REQUIRED"))return "11번가 조회 이미지 주소가 달라 자동 비교할 수 없습니다. WebP 변환 여부와 이미지 반영을 판매자센터에서 확인해 주세요. 재전송하지 마세요.";
        if("COUPANG".equals(market)&&status.equals("UNKNOWN")&&code.equals("UNRESOLVED"))return "이번 요청의 등록상품 ID를 확인할 수 없습니다. 새로 등록하지 말고 쿠팡 Wing에서 등록 여부를 확인해 주세요.";
        if("LOTTEON".equals(market))return status.equals("SUCCEEDED")?"롯데ON 상품·단품 연결과 변경 반영 확인 완료":status.equals("ACCEPTED")?(code.equals("APPROVAL_PENDING")?"롯데ON 승인 대기 중":code.equals("APPROVAL_REJECTED")?"롯데ON 반려 · 판매자센터에서 사유를 확인해 주세요.":code.equals("OPTIONS_UNCONFIRMED")?"상품 확인 완료 · 단품 연결 확인 필요":"롯데ON 접수 완료 · 반영 확인 필요"):status.equals("UNKNOWN")?"롯데ON 결과 미확인 · 재전송하지 말고 결과를 확인해 주세요.":"롯데ON 요청을 진행하지 못했습니다. 최신 상품과 입력값을 확인해 주세요.";
        if("ELEVENST".equals(market))return status.equals("SUCCEEDED")?(code.equals("CREATED_CONFIRMED")?"등록·옵션 연결 확인 완료 · 상품 수정에서 최신값을 확인해 주세요.":"변경값 반영 확인 완료"):status.equals("ACCEPTED")?(code.equals("APPROVAL_PENDING")?"11번가 승인 결과 확인 필요":code.equals("OPTIONS_UNCONFIRMED")?"등록 상품은 확인했으나 옵션 연결 확인이 필요합니다.":"11번가 접수 완료 · 반영 확인 필요"):status.equals("UNKNOWN")?"11번가 결과 미확인 · 같은 요청을 다시 보내지 말고 결과를 확인해 주세요.":"11번가 요청을 진행하지 못했습니다. 최신 상품과 입력값을 확인해 주세요.";
        if(!"NAVER".equals(market))return publicMessage(status,code);
        if(status.equals("SUCCEEDED")&&code.equals("CREATED_VALUES_DIFFER"))return "등록·연결 확인 완료 · 현재 상품값이 등록 요청과 다릅니다. 상품 수정에서 최신값을 확인해 주세요.";
        if(status.equals("ACCEPTED")&&code.equals("CREATE_OPTIONS_UNCONFIRMED"))return "등록 상품은 조회되지만 옵션 연결을 확인하지 못했습니다. 상품의 옵션 구성을 확인해 주세요.";
        if(status.equals("ACCEPTED")&&code.equals("CREATE_STATUS_UNCONFIRMED"))return "등록 상품은 조회되지만 현재 상태를 확인하지 못했습니다. 상품 상태를 확인해 주세요.";
        if(status.equals("SUCCEEDED"))return "반영 확인 완료";
        if(status.equals("ACCEPTED"))return "스마트스토어 접수 완료 · 반영 확인 필요";
        if(status.equals("UNKNOWN"))return "결과를 확인할 수 없습니다. 같은 요청을 다시 보내지 말고 결과를 확인해 주세요.";
        return switch(code){
            case "ACCESS_DENIED"->"실행 권한이 변경되었습니다.";
            case "BASELINE_CHANGED"->"스마트스토어 상품이 변경되었습니다. 다시 확인해 주세요.";
            case "LEGACY_INTENT"->"최신 조회와 변경 항목을 다시 확인해 주세요.";
            case "ACCOUNT_CHANGED"->"스마트스토어 연결 계정이 변경되었습니다.";
            case "RATE_LIMIT","HTTP_429","HTTP_429_REJECTED"->"스마트스토어 호출 제한입니다. 잠시 후 다시 시도해 주세요.";
            case "AUTHENTICATION","PERMISSION","CONFIGURATION","HTTP_401","HTTP_403","HTTP_401_REJECTED","HTTP_403_REJECTED"->"스마트스토어 연결 설정·권한을 확인해 주세요.";
            case "ALREADY_CREATED","ALREADY_REGISTERED"->"이미 등록된 상품입니다. 결과를 확인한 뒤 상품 수정 화면을 사용해 주세요.";
            case "MAPPING_CHANGED"->"스마트스토어 상품·옵션 연결이 변경되었습니다. 상품을 다시 조회해 주세요.";
            case "REJECTED","HTTP_400","HTTP_404","HTTP_400_REJECTED","HTTP_404_REJECTED"->"스마트스토어가 요청을 거절했습니다. 필수값·카테고리·판매 상태를 확인해 주세요.";
            default->"스마트스토어 요청이 반영되지 않았습니다. 입력과 실행 결과를 확인해 주세요.";
        };
    }
    private <T>T read(String value,Class<T> type){return value==null?null:json.readValue(value,type);}
}
