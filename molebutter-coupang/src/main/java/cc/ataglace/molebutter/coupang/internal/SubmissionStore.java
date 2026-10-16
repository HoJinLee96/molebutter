package cc.ataglace.molebutter.coupang.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangSubmissions.*;
import static cc.ataglace.molebutter.coupang.api.CoupangSubmissionFailure.Kind.*;

/**
 * Call mutating methods inside the caller's short database transaction. No
 * remote I/O here.
 */
@Repository
class SubmissionStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImageAssets assets;
    @org.springframework.beans.factory.annotation.Autowired
    SubmissionStore(JdbcTemplate db, ObjectMapper json, ImageAssets assets) {
        this.db = db;
        this.json = json;
        this.assets = assets;
    }
    // Existing generations are historical safeguards; native copies cannot be
    // rebound and start at 1.
    long listingVersion(long workingCopy, String account) {
        var rows = db.queryForList(
                "SELECT listing_version FROM coupang_listing_state WHERE working_copy_id=? AND account_key=?",
                Long.class, workingCopy, account);
        return rows.isEmpty() ? 1L : rows.getFirst();
    }
    boolean unsupportedSnapshotVersion(long executionId) {
        return unsupportedSnapshotVersion(executionId, false);
    }
    boolean unsupportedSnapshotVersion(long executionId, boolean writing) {
        String snapshot = db.queryForObject(
                "SELECT p.prepared_json FROM coupang_execution e JOIN coupang_submission_preview p ON p.id=e.preview_id WHERE e.id=?",
                String.class, executionId);
        var prepared = read(snapshot, CoupangWriteGateway.Prepared.class);
        return prepared == null || !prepared.supported() || writing && prepared.schemaVersion() < 4;
    }
    int preparedSize(CoupangWriteGateway.Prepared prepared) {
        return json.writeValueAsBytes(prepared).length;
    }
    record StoredPreview(long id, long workingCopyId, long revision, Long actor, Instant expiresAt,
            CoupangWorkingCopies.Document document, CoupangWriteGateway.Prepared prepared, Preview preview,
            String sessionId) {
        StoredPreview(long id, long workingCopyId, long revision, Long actor, Instant expiresAt,
                CoupangWorkingCopies.Document document, CoupangWriteGateway.Prepared prepared, Preview preview) {
            this(id, workingCopyId, revision, actor, expiresAt, document, prepared, preview, null);
        }
    }
    record Job(long executionId, long workingCopyId, long revision, Long actor, String leaseOwner,
            CoupangWriteGateway.Prepared prepared, CoupangWriteGateway.Step step, String action,
            CoupangWriteGateway.Mapping mapping, CoupangWriteGateway.Result previous, long attemptId) {
    }
    void insertPreview(long id, Long actor, WorkingCopyStore.Copy copy, boolean requested, Instant expiresAt) {
        db.update(
                "INSERT INTO coupang_submission_preview(id,working_copy_id,working_revision,created_by,requested,document_json,created_at,expires_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
                id, Long.parseLong(copy.id()), copy.revision(), actor, requested,
                json.writeValueAsString(copy.document()), Timestamp.from(expiresAt));
    }
    void attachSession(long previewId, String sessionId) {
        db.update("UPDATE coupang_submission_preview SET edit_session_id=? WHERE id=?", Long.parseLong(sessionId),
                previewId);
    }
    void completePreview(long id, CoupangWriteGateway.Prepared prepared, Preview preview) {
        db.update("UPDATE coupang_submission_preview SET prepared_json=?,preview_json=?,expires_at=? WHERE id=?",
                prepared == null ? null : json.writeValueAsString(prepared), json.writeValueAsString(preview),
                Timestamp.from(Instant.parse(preview.expiresAt())), id);
    }
    StoredPreview preview(long id, boolean lock) {
        var rows = db.query(
                "SELECT working_copy_id,working_revision,created_by,expires_at,document_json,prepared_json,preview_json,edit_session_id FROM coupang_submission_preview WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new StoredPreview(id, r.getLong(1), r.getLong(2), r.getLong(3), r.getTimestamp(4).toInstant(),
                        CoupangSnapshots.read(json, r.getString(5), CoupangWorkingCopies.Document.class),
                        read(r.getString(6), CoupangWriteGateway.Prepared.class), read(r.getString(7), Preview.class),
                        r.getString(8)),
                id);
        if (rows.isEmpty())
            throw new CoupangSubmissionFailure(NOT_FOUND);
        return rows.getFirst();
    }
    void pinAssets(Long actor, long previewId, String workingCopyId, Set<String> ids) {
        assets.pinReferences(actor, previewId, workingCopyId, ids);
    }
    CoupangWriteGateway.Mapping mapping(long workingCopyId, String account) {
        return mapping(workingCopyId, account, false);
    }
    CoupangWriteGateway.Mapping mapping(long workingCopyId, String account, boolean lock) {
        var rows = db.queryForList("SELECT mapping_json FROM coupang_product_working_copy WHERE id=? AND account_key=?"
                + (lock ? " FOR UPDATE" : ""), String.class, workingCopyId, account);
        return rows.isEmpty() || rows.getFirst() == null
                ? null
                : CoupangSnapshots.read(json, rows.getFirst(), CoupangWriteGateway.Mapping.class);
    }
    Long byKey(String key) {
        var rows = db.queryForList("SELECT id FROM coupang_execution WHERE idempotency_key=?", Long.class, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    Long byPreview(long previewId) {
        var rows = db.queryForList("SELECT id FROM coupang_execution WHERE preview_id=?", Long.class, previewId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    boolean active(long workingCopyId, String account, long excluding) {
        // A current read is essential: an earlier idempotency SELECT may have opened an
        // old RR snapshot.
        return !db.queryForList(
                "SELECT DISTINCT e.id FROM coupang_execution e JOIN coupang_execution_target t ON t.execution_id=e.id JOIN coupang_execution_step s ON s.execution_id=e.id WHERE e.working_copy_id=? AND t.account_key=? AND e.id<>? AND s.status IN ('QUEUED','RUNNING','UNKNOWN','ACCEPTED') FOR UPDATE",
                Long.class, workingCopyId, account, excluding).isEmpty();
    }
    long createExecution(Long actor, StoredPreview preview, String key) {
        long id = BusinessIds.next();
        var p = preview.prepared();
        String product = p.mapping() == null ? null : p.mapping().sellerProductId();
        db.update(
                "INSERT INTO coupang_execution(id,preview_id,working_copy_id,working_revision,idempotency_key,created_by,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'QUEUED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                id, preview.id(), preview.workingCopyId(), preview.revision(), key, actor);
        db.update(
                "INSERT INTO coupang_execution_target(execution_id,account_key,mode,status,external_product_id) VALUES(?,?,?,'QUEUED',?)",
                id, p.accountKey(), product == null ? "CREATE" : "UPDATE", product);
        int order = 0;
        for (var step : p.steps())
            db.update(
                    "INSERT INTO coupang_execution_step(execution_id,step_id,sequence_no,step_type,option_id,status,action,updated_at) VALUES(?,?,?,?,?,'QUEUED','WRITE',CURRENT_TIMESTAMP(6))",
                    id, step.id(), order++, step.type().name(), step.optionId());
        if (p.mapping() != null && p.mapping().sellerProductId() != null)
            saveMapping(preview.workingCopyId(), preview.revision(), p.mapping(), false);
        return id;
    }
    Execution execution(long id, boolean lock) {
        var rows = db.query(
                "SELECT working_copy_id,working_revision,status,created_at,updated_at,revised_at FROM coupang_execution WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Execution(Long.toString(id), r.getString(1), r.getLong(2), Status.valueOf(r.getString(3)),
                        r.getTimestamp(4).toInstant().toString(), r.getTimestamp(5).toInstant().toString(),
                        target(id, lock), r.getTimestamp(6) != null),
                id);
        if (rows.isEmpty())
            throw new CoupangSubmissionFailure(NOT_FOUND);
        return rows.getFirst();
    }
    private Target target(long id, boolean lock) {
        var rows = db.query(
                "SELECT mode,status,external_product_id FROM coupang_execution_target WHERE execution_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Target(r.getString(1), Status.valueOf(r.getString(2)), r.getString(3), steps(id, lock)),
                id);
        if (rows.size() != 1)
            throw new CoupangSubmissionFailure(CONFLICT);
        return rows.getFirst();
    }
    private List<Step> steps(long id, boolean lock) {
        var previewId = db.queryForObject("SELECT preview_id FROM coupang_execution WHERE id=?", Long.class, id);
        var prepared = preview(previewId, false).prepared();
        var names = new HashMap<String, String>();
        if (prepared != null && prepared.editIntent() != null)
            for (var option : prepared.editIntent().observed().options())
                names.put(option.id(), option.itemName());
        return db.query(
                "SELECT step_id,step_type,option_id,status,attempts,result_code,result_message,updated_at FROM coupang_execution_step WHERE execution_id=? ORDER BY sequence_no"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Step(r.getString(1), StepType.valueOf(r.getString(2)), r.getString(3),
                        label(r.getString(2))
                                + (names.get(r.getString(3)) == null ? "" : " · " + names.get(r.getString(3))),
                        Status.valueOf(r.getString(4)), r.getInt(5), r.getString(6),
                        "HTTP_411_REJECTED".equals(r.getString(6))
                                ? CoupangWriteRejection.message(r.getString(6))
                                : r.getString(7),
                        r.getTimestamp(8).toInstant().toString()),
                id);
    }
    PageResponse<Execution> list(long workingCopyId, int page, int size) {
        String scope = "(working_copy_id=? OR working_copy_id IN (SELECT previous_working_copy_id FROM legacy_coupang_editor_history_link WHERE working_copy_id=?))";
        long count = db.queryForObject("SELECT COUNT(*) FROM coupang_execution WHERE " + scope, Long.class,
                workingCopyId, workingCopyId);
        var ids = db.queryForList(
                "SELECT id FROM coupang_execution WHERE " + scope
                        + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                Long.class, workingCopyId, workingCopyId, size, (long) page * size);
        return new PageResponse<>(ids.stream().map(i -> execution(i, false)).toList(), page,
                (int) Math.ceil((double) count / size), count);
    }
    void queueRetry(long id) {
        db.update(
                "UPDATE coupang_execution_step SET status='QUEUED',action='WRITE',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='FAILED'",
                id);
        aggregate(id);
    }
    void revise(long id, Long actor) {
        // Preserve all attempted outcomes. Only never-started writes are retired.
        db.update(
                "UPDATE coupang_execution_step SET status='FAILED',result_code='NOT_EXECUTED',result_message='입력 수정으로 이전 요청을 종료했습니다. 실행하지 않은 단계입니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='QUEUED' AND action='WRITE' AND attempts=0",
                id);
        db.update(
                "UPDATE coupang_execution SET revised_at=CURRENT_TIMESTAMP(6),revised_by=? WHERE id=? AND revised_at IS NULL",
                actor, id);
        aggregate(id);
    }
    boolean pendingRevisionWork(long id) {
        return !db.queryForList(
                "SELECT step_id FROM coupang_execution_step WHERE execution_id=? AND status='QUEUED' AND (action<>'WRITE' OR attempts<>0) FOR UPDATE",
                String.class, id).isEmpty();
    }
    String executionAccount(long id) {
        return db.queryForObject("SELECT account_key FROM coupang_execution_target WHERE execution_id=?", String.class,
                id);
    }
    void queueReconcile(long id) {
        db.update(
                "UPDATE coupang_execution_step SET status='QUEUED',action='RECONCILE',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status IN ('UNKNOWN','ACCEPTED')",
                id);
        aggregate(id);
    }
    Job claim(String owner) {
        // Serialize execution claims per account across processes; never resume an
        // ambiguous WRITE.
        var candidates = db.queryForList(
                "SELECT DISTINCT e.id FROM coupang_execution e JOIN coupang_execution_step s ON s.execution_id=e.id WHERE s.status='QUEUED' AND (s.action='RECONCILE' OR NOT EXISTS(SELECT 1 FROM coupang_execution_step u WHERE u.execution_id=e.id AND u.status IN ('UNKNOWN','ACCEPTED','FAILED'))) ORDER BY e.id LIMIT 20",
                Long.class);
        for (long executionId : candidates) {
            var account = db.queryForObject("SELECT account_key FROM coupang_execution_target WHERE execution_id=?",
                    String.class, executionId);
            db.update("INSERT IGNORE INTO coupang_submission_account(account_key) VALUES(?)", account);
            var leases = db.queryForList(
                    "SELECT lease_owner,lease_until FROM coupang_submission_account WHERE account_key=? FOR UPDATE",
                    account);
            var lease = leases.getFirst();
            var until = (Timestamp) lease.get("lease_until");
            if (until != null && until.toInstant().isAfter(Instant.now()))
                continue;
            var header = db.queryForList(
                    "SELECT preview_id,working_copy_id,working_revision,created_by FROM coupang_execution WHERE id=?",
                    executionId).getFirst();
            // Inspect/lock earlier ambiguity before the candidate itself; API mutations
            // lock draft before execution.
            var ambiguousOthers = db.queryForList(
                    "SELECT DISTINCT e.id FROM coupang_execution e JOIN coupang_execution_target t ON t.execution_id=e.id JOIN coupang_execution_step u ON u.execution_id=e.id WHERE e.working_copy_id=? AND t.account_key=? AND e.id<>? AND u.status IN ('UNKNOWN','ACCEPTED') FOR UPDATE",
                    Long.class, ((Number) header.get("working_copy_id")).longValue(), account, executionId);
            var latest = execution(executionId, true);
            boolean unresolved = latest.target().steps().stream()
                    .anyMatch(s -> s.status() == Status.UNKNOWN || s.status() == Status.ACCEPTED);
            var rows = db.queryForList(
                    "SELECT step_id,action,result_json FROM coupang_execution_step WHERE execution_id=? AND status='QUEUED' ORDER BY CASE WHEN action='RECONCILE' THEN 0 ELSE 1 END,sequence_no LIMIT 1 FOR UPDATE",
                    executionId);
            if (rows.isEmpty())
                continue;
            var row = rows.getFirst();
            String stepId = row.get("step_id").toString(), action = row.get("action").toString();
            if (action.equals("WRITE") && (unresolved || !ambiguousOthers.isEmpty()
                    || latest.target().steps().stream().anyMatch(s -> s.status() == Status.FAILED)))
                continue;
            var p = preview(((Number) header.get("preview_id")).longValue(), false).prepared();
            if (p.listingVersion() != listingVersion(((Number) header.get("working_copy_id")).longValue(),
                    p.accountKey())) {
                db.update(
                        "UPDATE coupang_execution_step SET status='FAILED',result_code='MAPPING_CHANGED',result_message='이전 연결 세대의 요청은 실행할 수 없습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='QUEUED'",
                        executionId);
                aggregate(executionId);
                continue;
            }
            var step = p.steps().stream().filter(s -> s.id().equals(stepId)).findFirst().orElseThrow();
            if (step.type() == CoupangWriteGateway.Type.APPROVAL && action.equals("WRITE")) {
                // Bind verification to the actual saved request, including any rebased payload.
                var parent = CoupangSnapshots.read(json, step.expectedJson(), CoupangWriteGateway.Step.class);
                if (!p.steps().stream()
                        .anyMatch(s -> s.id().equals(parent.id()) && (s.type() == CoupangWriteGateway.Type.CREATE
                                || s.type() == CoupangWriteGateway.Type.PRODUCT)))
                    throw new CoupangSubmissionFailure(CONFLICT);
                var requests = db.queryForList(
                        "SELECT request_json FROM coupang_execution_attempt WHERE execution_id=? AND step_id=? AND action='WRITE' AND request_json IS NOT NULL ORDER BY started_at DESC,id DESC LIMIT 1",
                        String.class, executionId, parent.id());
                if (!requests.isEmpty()) {
                    var actual = CoupangSnapshots.read(json, requests.getFirst(), CoupangWriteGateway.Step.class);
                    if (actual.type() != parent.type() || !Objects.equals(actual.optionId(), parent.optionId()))
                        throw new CoupangSubmissionFailure(CONFLICT);
                    step = new CoupangWriteGateway.Step(step.id(), step.type(), step.optionId(), step.method(),
                            step.path(), step.query(), step.bodyJson(), step.baselineJson(),
                            json.writeValueAsString(actual));
                }
            }
            if (action.equals("RECONCILE")) {
                var requests = db.queryForList(
                        "SELECT request_json FROM coupang_execution_attempt WHERE execution_id=? AND step_id=? AND action='WRITE' AND request_json IS NOT NULL ORDER BY started_at DESC,id DESC LIMIT 1",
                        String.class, executionId, stepId);
                if (!requests.isEmpty()) {
                    var actual = CoupangSnapshots.read(json, requests.getFirst(), CoupangWriteGateway.Step.class);
                    if (actual.type() != step.type() || !Objects.equals(actual.optionId(), step.optionId()))
                        throw new CoupangSubmissionFailure(CONFLICT);
                    step = new CoupangWriteGateway.Step(stepId, actual.type(), actual.optionId(), actual.method(),
                            actual.path(), actual.query(), actual.bodyJson(), actual.baselineJson(),
                            actual.expectedJson());
                }
            }
            var mapping = mapping(((Number) header.get("working_copy_id")).longValue(), account, true);
            if (mapping == null)
                mapping = p.mapping();
            long attemptId = BusinessIds.next();
            db.update("UPDATE coupang_submission_account SET lease_owner=?,lease_until=? WHERE account_key=?", owner,
                    Timestamp.from(Instant.now().plusSeconds(300)), account);
            db.update(
                    "UPDATE coupang_execution_step SET status='RUNNING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=?",
                    executionId, stepId);
            db.update(
                    "INSERT INTO coupang_execution_attempt(id,execution_id,step_id,action,status,started_at) VALUES(?,?,?,?,'RUNNING',CURRENT_TIMESTAMP(6))",
                    attemptId, executionId, stepId, action);
            aggregate(executionId);
            return new Job(executionId, ((Number) header.get("working_copy_id")).longValue(),
                    ((Number) header.get("working_revision")).longValue(),
                    ((Number) header.get("created_by")).longValue(), owner, p, step, action, mapping,
                    read((String) row.get("result_json"), CoupangWriteGateway.Result.class), attemptId);
        }
        return null;
    }
    boolean owns(Job job) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM coupang_submission_account WHERE account_key=? AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP(6)",
                Long.class, job.prepared().accountKey(), job.leaseOwner()) == 1;
    }
    void recordRequest(Job job, CoupangWriteGateway.Step actual) {
        db.queryForList("SELECT account_key FROM coupang_submission_account WHERE account_key=? FOR UPDATE",
                String.class, job.prepared().accountKey());
        if (!owns(job))
            throw new CoupangSubmissionFailure(CONFLICT);
        // Server-only exact request snapshot; never returned through execution DTOs.
        db.update("UPDATE coupang_execution_attempt SET request_json=? WHERE id=? AND status='RUNNING'",
                json.writeValueAsString(actual), job.attemptId());
    }
    void heartbeat(Job job) {
        db.update("UPDATE coupang_submission_account SET lease_until=? WHERE account_key=? AND lease_owner=?",
                Timestamp.from(Instant.now().plusSeconds(300)), job.prepared().accountKey(), job.leaseOwner());
    }
    void finish(Job job, CoupangWriteGateway.Result result) {
        db.queryForList("SELECT account_key FROM coupang_submission_account WHERE account_key=? FOR UPDATE",
                String.class, job.prepared().accountKey());
        if (!owns(job))
            return;
        db.queryForList("SELECT id FROM coupang_product_working_copy WHERE id=? FOR UPDATE", Long.class,
                job.workingCopyId());
        execution(job.executionId(), true);
        if (job.prepared().listingVersion() != listingVersion(job.workingCopyId(), job.prepared().accountKey())) {
            db.update(
                    "UPDATE coupang_execution_step SET status='FAILED',result_code='MAPPING_CHANGED',result_message='이전 연결 세대의 결과입니다. 현재 연결은 변경하지 않습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=? AND status='RUNNING'",
                    job.executionId(), job.step().id());
            db.update(
                    "UPDATE coupang_execution_attempt SET status='FAILED',result_code='MAPPING_CHANGED',completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    job.attemptId());
            aggregate(job.executionId());
            db.update(
                    "UPDATE coupang_submission_account SET lease_owner=NULL,lease_until=NULL WHERE account_key=? AND lease_owner=?",
                    job.prepared().accountKey(), job.leaseOwner());
            return;
        }
        if (job.action().equals("RECONCILE") && result.state() == CoupangWriteGateway.State.FAILED
                && Set.of("ACCESS_DENIED", "ACCOUNT_CHANGED", "UNSUPPORTED_SNAPSHOT_VERSION")
                        .contains(publicCode(result.code()))) {
            // A failed check is not evidence that the previously dispatched write failed.
            var previous = job.previous();
            var retained = previous != null && (previous.state() == CoupangWriteGateway.State.UNKNOWN
                    || previous.state() == CoupangWriteGateway.State.ACCEPTED)
                            ? previous
                            : new CoupangWriteGateway.Result(CoupangWriteGateway.State.UNKNOWN, job.mapping(),
                                    "INTERRUPTED", null, Instant.now());
            String retainedState = retained.state() == CoupangWriteGateway.State.ACCEPTED ? "ACCEPTED" : "UNKNOWN";
            db.update(
                    "UPDATE coupang_execution_step SET status=?,result_json=?,result_code=?,result_message=?,updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=? AND status='RUNNING'",
                    retainedState, json.writeValueAsString(retained), publicCode(retained.code()),
                    "기존 전송 결과는 미확인 상태로 유지됩니다. 최근 결과 확인 실패: " + publicCode(result.code()), job.executionId(),
                    job.step().id());
            db.update(
                    "UPDATE coupang_execution_attempt SET status='FAILED',result_code=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    publicCode(result.code()), job.attemptId());
            aggregate(job.executionId());
            db.update(
                    "UPDATE coupang_submission_account SET lease_owner=NULL,lease_until=NULL WHERE account_key=? AND lease_owner=?",
                    job.prepared().accountKey(), job.leaseOwner());
            return;
        }
        String state = switch (result.state()) {
            case CONFIRMED -> "SUCCEEDED";
            case ACCEPTED -> "ACCEPTED";
            case FAILED -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
        String code = publicCode(result.code()), message = publicMessage(state, code);
        db.update(
                "UPDATE coupang_execution_step SET status=?,result_json=?,result_code=?,result_message=?,updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND step_id=? AND status='RUNNING'",
                state, json.writeValueAsString(result), code, message, job.executionId(), job.step().id());
        db.update(
                "UPDATE coupang_execution_attempt SET status=?,result_code=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                state, code, job.attemptId());
        if (result.mapping() != null && result.mapping().sellerProductId() != null) {
            saveMapping(job.workingCopyId(), job.revision(), result.mapping(), false);
            db.update("UPDATE coupang_execution_target SET external_product_id=? WHERE execution_id=?",
                    result.mapping().sellerProductId(), job.executionId());
        }
        if (result.state() == CoupangWriteGateway.State.FAILED
                && Set.of("BASELINE_CHANGED", "ACCOUNT_CHANGED", "ACCESS_DENIED", "MAPPING_CHANGED", "ALREADY_CREATED",
                        "ALREADY_REGISTERED", "LEGACY_INTENT", "UNSUPPORTED_SNAPSHOT_VERSION").contains(code)) {
            // This intent cannot safely be replayed. Release only never-started writes for
            // a fresh preview.
            db.update(
                    "UPDATE coupang_execution_step SET status='FAILED',result_code='NOT_EXECUTED',result_message='앞 단계 충돌로 실행하지 않았습니다. 새 변경 확인이 필요합니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='QUEUED' AND action='WRITE'",
                    job.executionId());
        }
        if (result.state() == CoupangWriteGateway.State.CONFIRMED)
            consumeAppliedChanges(job, false);
        aggregate(job.executionId());
        if (execution(job.executionId(), false).status() == Status.SUCCEEDED && result.mapping() != null) {
            saveMapping(job.workingCopyId(), job.revision(), result.mapping(), true);
            consumeAppliedChanges(job, true);
        }
        db.update(
                "UPDATE coupang_submission_account SET lease_owner=NULL,lease_until=NULL WHERE account_key=? AND lease_owner=?",
                job.prepared().accountKey(), job.leaseOwner());
    }

    void saveMapping(long workingCopyId, long revision, CoupangWriteGateway.Mapping mapping, boolean applied) {
        if (mapping.sellerProductId() == null)
            return;
        var previous = mapping(workingCopyId, mapping.accountKey(), true);
        if (previous != null && previous.sellerProductId() != null
                && !previous.sellerProductId().equals(mapping.sellerProductId()))
            throw new CoupangSubmissionFailure(CONFLICT);
        db.update(
                "UPDATE coupang_product_working_copy SET seller_product_id=?,mapping_json=?,applied_revision=IF(?,GREATEST(COALESCE(applied_revision,-1),?),applied_revision) WHERE id=? AND account_key=?",
                mapping.sellerProductId(), json.writeValueAsString(mapping), applied, revision, workingCopyId,
                mapping.accountKey());
        db.update(
                "UPDATE coupang_listing_state SET state='ACTIVE',reason=NULL WHERE working_copy_id=? AND account_key=?",
                workingCopyId, mapping.accountKey());
    }
    private void consumeAppliedChanges(Job job, boolean complete) {
        var intent = job.prepared().editIntent();
        if (intent == null)
            return;
        var current = db.queryForObject("SELECT changes_json FROM coupang_product_working_copy WHERE id=? FOR UPDATE",
                String.class, job.workingCopyId());
        var remaining = new ArrayList<>(
                json.readValue(current, new tools.jackson.core.type.TypeReference<List<CoupangEditing.Change>>() {
                }));
        remaining.removeIf(c -> intent.changes().stream().filter(done -> complete || coveredByStep(done, job))
                .anyMatch(done -> CoupangInputs.sameChange(json, done, c)));
        db.update("UPDATE coupang_product_working_copy SET changes_json=? WHERE id=?",
                json.writeValueAsString(remaining), job.workingCopyId());
    }
    private boolean coveredByStep(CoupangEditing.Change c, Job job) {
        var type = job.step().type();
        String field = c.path();
        if (type == CoupangWriteGateway.Type.CREATE)
            return true;
        if (type == CoupangWriteGateway.Type.APPROVAL)
            return false;
        if (type == CoupangWriteGateway.Type.DELIVERY)
            return field.startsWith("delivery.");
        var dedicated = Map.of(CoupangWriteGateway.Type.PRICE, "options.registration.salePrice",
                CoupangWriteGateway.Type.STOCK, "options.registration.maximumBuyCount",
                CoupangWriteGateway.Type.ORIGINAL_PRICE, "options.registration.originalPrice");
        if (dedicated.containsKey(type))
            return Objects.equals(c.optionId(), job.step().optionId()) && field.equals(dedicated.get(type));
        if (type != CoupangWriteGateway.Type.PRODUCT || field.startsWith("delivery."))
            return false;
        return job.prepared().steps().stream().noneMatch(step -> dedicated.containsKey(step.type())
                && Objects.equals(step.optionId(), c.optionId()) && field.equals(dedicated.get(step.type())));
    }
    boolean archived(long draft) {
        return !db.queryForList(
                "SELECT working_copy_id FROM legacy_coupang_editor_history_link WHERE previous_working_copy_id=?",
                Long.class, draft).isEmpty();
    }
    void aggregate(long id) {
        var statuses = db
                .queryForList("SELECT status FROM coupang_execution_step WHERE execution_id=?", String.class, id)
                .stream().map(Status::valueOf).toList();
        Status state = aggregateStatus(statuses);
        db.update("UPDATE coupang_execution SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", state.name(), id);
        db.update("UPDATE coupang_execution_target SET status=? WHERE execution_id=?", state.name(), id);
    }
    static Status aggregateStatus(List<Status> states) {
        if (states.contains(Status.RUNNING))
            return Status.RUNNING;
        if (states.contains(Status.UNKNOWN))
            return Status.UNKNOWN;
        if (states.contains(Status.ACCEPTED))
            return states.contains(Status.FAILED) ? Status.PARTIAL : Status.ACCEPTED;
        if (states.contains(Status.FAILED))
            return states.stream().anyMatch(s -> s == Status.SUCCEEDED || s == Status.ACCEPTED)
                    ? Status.PARTIAL
                    : Status.FAILED;
        if (states.contains(Status.QUEUED))
            return Status.QUEUED;
        return Status.SUCCEEDED;
    }
    void recoverExpired() {
        var ids = db.queryForList(
                "SELECT DISTINCT s.execution_id FROM coupang_execution_step s JOIN coupang_execution_target t ON t.execution_id=s.execution_id LEFT JOIN coupang_submission_account a ON a.account_key=t.account_key WHERE s.status='RUNNING' AND (a.lease_until IS NULL OR a.lease_until<CURRENT_TIMESTAMP(6))",
                Long.class);
        for (long id : ids) {
            String account = db.queryForObject("SELECT account_key FROM coupang_execution_target WHERE execution_id=?",
                    String.class, id);
            var leases = db.queryForList(
                    "SELECT lease_until FROM coupang_submission_account WHERE account_key=? FOR UPDATE", account);
            if (!leases.isEmpty()) {
                var until = (Timestamp) leases.getFirst().get("lease_until");
                if (until != null && until.toInstant().isAfter(Instant.now()))
                    continue;
            }
            execution(id, true);
            db.update(
                    "UPDATE coupang_execution_step SET status='UNKNOWN',result_code='INTERRUPTED',result_message='실행 중단으로 결과를 확인할 수 없습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='RUNNING'",
                    id);
            db.update(
                    "UPDATE coupang_execution_attempt SET status='UNKNOWN',result_code='INTERRUPTED',completed_at=CURRENT_TIMESTAMP(6) WHERE execution_id=? AND status='RUNNING'",
                    id);
            aggregate(id);
        }
    }
    List<Long> expiredUnexecutedPreviews() {
        return db.queryForList(
                "SELECT p.id FROM coupang_submission_preview p WHERE p.expires_at<CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR AND NOT EXISTS(SELECT 1 FROM coupang_execution e WHERE e.preview_id=p.id) AND EXISTS(SELECT 1 FROM coupang_execution_asset r WHERE r.submission_id=p.id) ORDER BY p.id LIMIT 20",
                Long.class);
    }
    void releaseExpiredPreviewPins(long previewId) {
        var preview = preview(previewId, true);
        if (preview.expiresAt().plusSeconds(86400).isAfter(Instant.now()) || byPreview(previewId) != null)
            return;
        assets.releaseReferences(previewId);
    }
    static String label(String type) {
        return switch (type) {
            case "CREATE" -> "상품 등록";
            case "PRODUCT" -> "상품 정보 변경";
            case "DELIVERY" -> "배송·반품 변경";
            case "ORIGINAL_PRICE" -> "정상가 변경";
            case "PRICE" -> "현재 가격 변경";
            case "STOCK" -> "현재 재고 변경";
            case "APPROVAL" -> "판매 승인 요청";
            default -> "반영 확인";
        };
    }
    static String publicCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,80}") ? code : "RESPONSE";
    }

    static String fallbackMessage(String status, String code) {
        if (status.equals("SUCCEEDED"))
            return "반영 확인 완료";
        if (code.equals("NEEDS_CORRECTION"))
            return "저장 완료 · 옵션 속성 보완 필요";
        if (code.equals("WAIT_RECONCILE"))
            return "등록 결과를 확인할 때까지 대기해 주세요.";
        if (status.equals("ACCEPTED"))
            return "접수 완료 · 반영 확인 필요";
        if (status.equals("UNKNOWN"))
            return "결과를 확인할 수 없습니다. 다시 등록하지 말고 결과를 확인해 주세요.";
        var reason = CoupangWriteRejection.message(code);
        if (reason != null)
            return reason;
        return switch (code) {
            case "ACCESS_DENIED" -> "실행 권한이 변경되었습니다.";
            case "BASELINE_CHANGED" -> "쿠팡 상품이 변경되었습니다. 다시 확인해 주세요.";
            case "LEGACY_INTENT" -> "최신 조회와 변경 항목을 다시 확인해 주세요.";
            case "ACCOUNT_CHANGED" -> "쿠팡 연결 계정이 변경되었습니다.";
            case "RATE_LIMIT" -> "쿠팡 호출 제한입니다. 잠시 후 다시 시도해 주세요.";
            case "AUTHENTICATION", "PERMISSION", "CONFIGURATION" -> "쿠팡 연결 설정·권한을 확인해 주세요.";
            default -> "요청이 반영되지 않았습니다. 입력과 실행 결과를 확인해 주세요.";
        };
    }
    static String publicMessage(String status, String code) {
        if (status.equals("SUCCEEDED") && code.equals("SAVED_FOR_APPROVAL"))
            return "쿠팡 저장 완료 · 다음 단계에서 판매 승인을 요청합니다.";
        if (code.equals("APPROVAL_NOT_READY"))
            return "쿠팡 저장 완료 · 상품 처리 중이므로 10분 후 승인 요청 단계만 재시도해 주세요.";
        if (status.equals("SUCCEEDED") && code.equals("CREATED_VALUES_DIFFER"))
            return "등록·옵션 연결 확인 완료 · 현재 상품값이 등록 요청과 다릅니다. 상품 수정에서 최신값을 확인해 주세요.";
        if (status.equals("SUCCEEDED") && code.equals("CREATED_CONFIRMED"))
            return "등록·옵션 연결 확인 완료 · 상품 수정에서 최신값을 확인해 주세요.";
        if (status.equals("ACCEPTED") && Set.of("CREATE_OPTIONS_UNCONFIRMED", "OPTIONS_UNCONFIRMED").contains(code))
            return "등록 상품은 조회되지만 옵션 연결 확인이 필요합니다. 같은 상품을 다시 등록하지 마세요.";
        if (status.equals("ACCEPTED") && Set.of("CREATE_STATUS_UNCONFIRMED", "STATUS_UNCONFIRMED").contains(code))
            return "등록 상품은 조회되지만 현재 판매·승인 상태를 확인하지 못했습니다.";
        if (status.equals("ACCEPTED") && code.equals("APPROVAL_PENDING"))
            return "등록 상품 확인 완료 · 판매 승인 대기 중입니다.";
        if (status.equals("ACCEPTED") && code.equals("APPROVAL_REJECTED"))
            return "등록 상품 확인 완료 · 승인 반려 사유를 판매자센터에서 확인해 주세요.";
        if (status.equals("ACCEPTED") && code.equals("EDIT_UNSUPPORTED"))
            return "등록 상품은 조회되지만 현재 상품 유형의 수정 반영을 자동 확인할 수 없습니다. 판매자센터에서 확인해 주세요.";
        if (status.equals("ACCEPTED") && code.equals("VERIFY_UNAVAILABLE"))
            return "결과 조회에 실패했습니다. 같은 요청을 다시 보내지 말고 잠시 후 결과를 확인해 주세요.";
        if (status.equals("FAILED") && code.startsWith("PREFLIGHT_"))
            return "전송 전 조회에 실패하여 상품 요청을 보내지 않았습니다. 잠시 후 다시 확인해 주세요.";
        if (status.equals("UNKNOWN") && code.equals("UNRESOLVED"))
            return "이번 요청의 등록상품 ID를 확인할 수 없습니다. 새로 등록하지 말고 쿠팡 Wing에서 등록 여부를 확인해 주세요.";
        return fallbackMessage(status, code);
    }
    private <T> T read(String value, Class<T> type) {
        return value == null ? null : CoupangSnapshots.read(json, value, type);
    }
}
