package cc.ataglace.molebutter.coupang.internal;

import java.time.*;
import java.util.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangSubmissions.*;
import static cc.ataglace.molebutter.coupang.api.CoupangSubmissionFailure.Kind.*;

@Service
public class DefaultCoupangSubmissions implements CoupangSubmissions {
    private CoupangProductSource registeredSource;
    @org.springframework.beans.factory.annotation.Autowired void registeredSource(CoupangProductSource source){this.registeredSource=source;}
    private RegisteredProductStore registered;
    @org.springframework.beans.factory.annotation.Autowired void registered(RegisteredProductStore registered){this.registered=registered;}
    private QuickEditGuard quickGuard;
    @org.springframework.beans.factory.annotation.Autowired void quickGuard(QuickEditGuard guard){this.quickGuard=guard;this.worker.quickGuard=guard;}
    private final BusinessAccess access;

    private final WorkingCopyStore copyStore;
    private final SubmissionStore store;
    private final DefaultCoupangWorkingCopies editing;
    private final CoupangWriteGateway writer;
    private final TransactionTemplate transactions;
    private final SubmissionWorker worker;

    public DefaultCoupangSubmissions(BusinessAccess access, WorkingCopyStore copyStore, SubmissionStore store,
            DefaultCoupangWorkingCopies editing, CoupangWriteGateway writer, PlatformTransactionManager manager,
            @Value("${coupang.submissions.worker-enabled:true}") boolean enabled) {
        this.access = access;
        this.copyStore = copyStore;
        this.store = store;
        this.editing = editing;
        this.writer = writer;
        transactions = new TransactionTemplate(manager);
        worker = new SubmissionWorker(access, store, writer, transactions, enabled);
    }
    @Override
    public Preview prepare(Long actor, CoupangEditing.PrepareRequest input) {return prepare(actor,input,null,null,null);}
    public Preview prepareAction(Long actor,CoupangEditing.PrepareRequest input,String action,String vendorItem) {
        return prepare(actor,input,action,vendorItem,null);
    }
    public Preview prepareSelected(Long actor,CoupangEditing.PrepareRequest input,CoupangOptionEdits.Basis basis){
        if(basis==null||input.changes()==null||input.changes().size()!=1||!Set.of("options.registration.salePrice","options.registration.maximumBuyCount").contains(input.changes().getFirst().path()))throw new InputValidationFailure("단일 옵션 수정 항목을 확인해 주세요.");
        quickGuard.check(actor,basis);return prepare(actor,input,null,null,basis);
    }
    public Preview prepareSelectedStored(Long actor,CoupangEditing.PrepareRequest input,CoupangOptionEdits.Basis basis){
        if(basis==null||input==null||input.changes()==null||input.changes().size()!=1||!Set.of("options.registration.salePrice","options.registration.maximumBuyCount").contains(input.changes().getFirst().path()))throw new InputValidationFailure("단일 옵션 수정 항목을 확인해 주세요.");
        quickGuard.check(actor,basis);return prepare(actor,input,null,null,basis,true);
    }
    private Preview prepare(Long actor,CoupangEditing.PrepareRequest input,String action,String vendorItem,CoupangOptionEdits.Basis basis) {return prepare(actor,input,action,vendorItem,basis,false);}
    private Preview prepare(Long actor,CoupangEditing.PrepareRequest rawInput,String action,String vendorItem,CoupangOptionEdits.Basis basis,boolean storedOnly) {
        var input=rawInput==null?null:new CoupangEditing.PrepareRequest(rawInput.workingCopyId(),rawInput.revision(),rawInput.sessionId(),CoupangPrices.changes(rawInput.changes()));
        access.productActor(actor, true);
        if (input == null || input.revision() == null || input.changes() == null || input.changes().size() > 500)
            throw new InputValidationFailure("작업본과 수정 항목을 확인해 주세요.");
        long workingCopy = id(input.workingCopyId()), previewId = BusinessIds.next();
        var session = transactions.execute(tx -> {
            var s = editing.require(actor, input.sessionId(), true);
            if (!s.session().workingCopyId().equals(input.workingCopyId())
                    || s.session().revision() != input.revision())
                throw new CoupangSubmissionFailure(CONFLICT);
            var binding = editing.requireTarget(s, true);
            if (store.active(workingCopy, binding.account(), -1))
                throw new CoupangSubmissionFailure(CONFLICT);
            return s;
        });
        var copy = copyStore.find(workingCopy, false);
        if (basis==null?!copy.changes().equals(input.changes()):!copy.changes().containsAll(input.changes()))
            throw new CoupangSubmissionFailure(CONFLICT);
        var issues = new ArrayList<CoupangWorkingCopies.Issue>();
        CoupangWriteGateway.Prepared prepared = null;
        try {
            if(action!=null && action.equals("DELETE") && !copy.changes().isEmpty())throw new InputValidationFailure("저장된 수정값을 먼저 반영하거나 취소한 뒤 삭제해 주세요.");
            if(action==null && registered!=null && copy.product()!=null) {
                var state=registered.optional(copy.account(),copy.product());
                if(state!=null && state.row().deleted())throw new InputValidationFailure("삭제된 상품입니다.");
            }
            prepared = (action==null?(storedOnly?writer.prepareStoredOption(actor,copy.document(),session.binding().mapping(),session.session().document(),input.changes()):writer.prepare(actor, copy.document(), session.binding().mapping(), session.session().document(),input.changes())):writer.prepareAction(actor,session.binding().mapping(),session.session().document(),action,vendorItem))
                .withListingVersion(session.binding().listingVersion());
            if(action==null && registered!=null && copy.product()!=null) {
                var state=registered.optional(copy.account(),copy.product());
                var source=registeredSource;
                if(state!=null && source!=null && registered.conflicts(state,copy,source).stream().filter(c->input.changes().stream().anyMatch(change->RegisteredProductStore.key(change).equals(c.key()))).anyMatch(c->Set.of("CONFLICT","UNVERIFIED","BASELINE_MISSING").contains(c.state())))
                    throw new InputValidationFailure("최신 쿠팡값과 저장된 수정값을 확인하고 사용할 값을 선택해 주세요.");
            }
            if(basis!=null)prepared=new CoupangWriteGateway.Prepared(prepared.accountKey(),prepared.mapping(),prepared.steps(),prepared.changes(),prepared.preparedAt(),prepared.expectedSkus(),new CoupangWriteGateway.EditIntent(prepared.editIntent().observed(),prepared.editIntent().changes(),basis),prepared.schemaVersion(),prepared.listingVersion());
            checkPrepared(prepared);
            if (!Objects.equals(prepared.accountKey(), writer.accountKey())
                    || !compatiblePreparedMapping(session.binding().mapping(), prepared))
                throw new CoupangSubmissionFailure(CONFLICT);
        } catch (InputValidationFailure failure) {
            issues.add(new CoupangWorkingCopies.Issue("changes", failure.getMessage()));
        } catch (CoupangFailure failure) {
            issues.add(new CoupangWorkingCopies.Issue("session", failure.getMessage()));
        }
        var proposal = new PreviewTarget(session.session().mode(),
                prepared == null
                        ? List.of()
                        : prepared.steps().stream()
                                .map(s -> new PlannedStep(s.id(), StepType.valueOf(s.type().name()), s.optionId(),
                                        SubmissionStore.label(s.type().name())))
                                .toList(),
                prepared == null
                        ? List.of()
                        : prepared.changes().stream().map(c -> new Change(c.path(), c.before(), c.after())).toList(),
                List.copyOf(issues), copy.document().options().stream().collect(java.util.stream.Collectors
                        .toMap(CoupangWorkingCopies.Option::id, o -> Objects.toString(o.itemName(), "옵션"))), imageWarnings(actor,copy.document(),session.session().mode().equals("CREATE"),input.changes()), store.creationRulesCheckedAt(prepared));
        var preview = new Preview(Long.toString(previewId), copy.id(), copy.revision(), session.session().expiresAt(),
                prepared != null && !prepared.steps().isEmpty() && issues.isEmpty(), true, proposal);
        var immutable = prepared;
        transactions.executeWithoutResult(tx -> {
            var current = editing.require(actor, input.sessionId(), true);
            if (!current.equals(session))
                throw new CoupangSubmissionFailure(CONFLICT);
            editing.requireTarget(current, true);
            store.insertPreview(previewId, actor, copy, true, Instant.parse(preview.expiresAt()));
            store.attachSession(previewId, input.sessionId());
            store.pinAssets(actor, previewId, copy.id(), CoupangWorkingCopyDocuments.assetIds(copy.document()));
            store.completePreview(previewId, immutable, preview);
        });
        return preview;
    }
    @org.springframework.beans.factory.annotation.Autowired private cc.ataglace.molebutter.media.api.ImageInspection imageInspection;
    private List<CoupangWorkingCopies.Issue> imageWarnings(Long actor,CoupangWorkingCopies.Document document,boolean create,List<CoupangEditing.Change> changes){
        if(imageInspection==null)return List.of();
        var changed=changes.stream().filter(c->c.path().equals("options.images")).map(CoupangEditing.Change::optionId).collect(java.util.stream.Collectors.toSet());
        if(!create&&changed.isEmpty())return List.of();
        var inputs=new ArrayList<cc.ataglace.molebutter.media.api.ImageInspection.Input>();
        for(var o:document.options())if(create||changed.contains(o.id()))for(var i:o.images())inputs.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input(i.imageType(),o.id(),i.id(),i.url(),i.assetId(),!create));
        // Shared photos across options need one warning check. Keep the inspection
        // API's 500-input bound even for an existing large option structure.
        record Key(String role,String url,String asset,boolean existing) {}
        var unique=new LinkedHashMap<Key,cc.ataglace.molebutter.media.api.ImageInspection.Input>();
        for(var i:inputs)unique.putIfAbsent(new Key(i.role(),i.url(),i.assetId(),i.existing()),i);
        var selected=new ArrayList<>(unique.values());var warnings=new LinkedHashSet<CoupangWorkingCopies.Issue>();
        for(int offset=0;offset<selected.size();offset+=500)
            for(var check:imageInspection.inspect(actor,selected.subList(offset,Math.min(offset+500,selected.size()))))
                for(var warning:check.warnings())warnings.add(new CoupangWorkingCopies.Issue("images",warning));
        return List.copyOf(warnings);
    }
    private void checkPrepared(CoupangWriteGateway.Prepared p) {
        if (p == null || p.schemaVersion() < 4 || !p.supported() || p.accountKey() == null
                || !p.accountKey().matches("[a-f0-9]{64}") || p.steps() == null || p.steps().size() > 500
                || p.changes() == null)
            throw new CoupangFailure(CoupangFailure.Kind.RESPONSE);
        var ids = new HashSet<String>();
        for (var s : p.steps())
            if (s == null || s.id() == null || !s.id().matches("[A-Za-z0-9_-]{1,80}") || !ids.add(s.id()))
                throw new CoupangFailure(CoupangFailure.Kind.RESPONSE);
        if (store.preparedSize(p) > 15 * 1024 * 1024)
            throw new InputValidationFailure("전송 정보가 너무 큽니다. 이미지·설명과 옵션을 확인해 주세요.");
    }
    @Override
    public Execution execute(Long actor, String previewValue, String key) {
        access.productActor(actor, true);
        long previewId = id(previewValue);
        checkKey(key);
        try {
            return transactions.execute(tx -> {
                access.productActor(actor, true);
                var byKey = store.byKey(key);
                if (byKey != null) {
                    var existing = store.byPreview(previewId);
                    if (!Objects.equals(existing, byKey))
                        throw new CoupangSubmissionFailure(CONFLICT);
                    return store.execution(byKey, false);
                }
                var preview = store.preview(previewId, true);
                var prior = store.byPreview(previewId);
                if (prior != null)
                    return store.execution(prior, false);
                var d = copyStore.find(preview.workingCopyId(), true);
                if (d.revision() != preview.revision())
                    throw new CoupangSubmissionFailure(CONFLICT);
                if (!Instant.now().isBefore(preview.expiresAt()))
                    throw new CoupangSubmissionFailure(EXPIRED);
                if (preview.preview() == null || !preview.preview().executable() || preview.prepared() == null
                        || !preview.prepared().supported() || preview.prepared().schemaVersion() < 4)
                    throw new CoupangSubmissionFailure(INVALID);
                if (!Objects.equals(writer.accountKey(), preview.prepared().accountKey()))
                    throw new CoupangSubmissionFailure(CONFLICT);
                if (preview.sessionId() == null || preview.prepared().editIntent() == null)
                    throw new CoupangSubmissionFailure(INVALID);
                var session = editing.require(actor, preview.sessionId(), true);
                if (!session.session().workingCopyId().equals(Long.toString(preview.workingCopyId()))
                        || session.session().revision() != preview.revision())
                    throw new CoupangSubmissionFailure(CONFLICT);
                var binding = editing.requireTarget(session, true);
                if (!binding.account().equals(preview.prepared().accountKey())
                        || !compatiblePreparedMapping(binding.mapping(), preview.prepared()))
                    throw new CoupangSubmissionFailure(CONFLICT);
                if (store.active(preview.workingCopyId(), preview.prepared().accountKey(), -1))
                    throw new CoupangSubmissionFailure(CONFLICT);
                if(quickGuard!=null)quickGuard.check(actor,preview.prepared().editIntent().quickEditBasis());
                long execution = store.createExecution(actor, preview, key);
                return store.execution(execution, false);
            });
        } catch (DuplicateKeyException duplicate) {
            Long existing = store.byPreview(previewId);
            Long keyed = store.byKey(key);
            if (existing != null && (keyed == null || existing.equals(keyed)))
                return store.execution(existing, false);
            throw new CoupangSubmissionFailure(CONFLICT);
        }
    }
    static boolean compatiblePreparedMapping(CoupangWriteGateway.Mapping bound, CoupangWriteGateway.Prepared prepared) {
        return SubmissionMappingPolicy.compatiblePreparedMapping(bound, prepared);
    }
    @Override
    public Execution get(Long actor, String value) {
        access.productActor(actor, true);
        return store.execution(id(value), false);
    }
    @Override
    public PageResponse<Execution> list(Long actor, String workingCopyId, int page, int size) {
        access.productActor(actor, true);
        if (page < 0 || page > 1_000_000 || !Set.of(10, 20, 50, 100).contains(size))
            throw new InputValidationFailure("페이지 크기와 위치를 확인해 주세요.");
        long draft = id(workingCopyId);
        copyStore.find(draft, false);
        return store.list(draft, page, size);
    }
    @Override
    public Execution retry(Long actor, String value) {
        access.productActor(actor, true);
        long executionId = id(value);
        return transactions.execute(tx -> {
            access.productActor(actor, true);
            var original = store.execution(executionId, false);
            var d = copyStore.find(id(original.workingCopyId()), true);
            var e = store.execution(executionId, true);
            if (store.unsupportedSnapshotVersion(executionId, true))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (store.archived(id(original.workingCopyId())))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.revised())
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.status() != Status.FAILED && e.status() != Status.PARTIAL)
                throw new CoupangSubmissionFailure(CONFLICT);
            if (d.revision() != e.revision())
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.target().steps().stream()
                    .anyMatch(step -> Set.of(Status.RUNNING, Status.UNKNOWN, Status.ACCEPTED).contains(step.status())))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.target() == null || writer.accountKey() == null)
                throw new CoupangSubmissionFailure(CONFLICT);
            if (store.active(id(e.workingCopyId()), writer.accountKey(), executionId))
                throw new CoupangSubmissionFailure(CONFLICT);
            // Baseline/account conflicts need a fresh preview, not a replay of an old
            // payload.
            if (e.target().steps().stream().anyMatch(s -> s.status() == Status.FAILED && s.code() != null
                    && Set.of("BASELINE_CHANGED", "ACCOUNT_CHANGED", "ACCESS_DENIED", "MAPPING_CHANGED",
                            "ALREADY_CREATED", "ALREADY_REGISTERED", "LEGACY_INTENT", "UNSUPPORTED_SNAPSHOT_VERSION")
                            .contains(s.code())))
                throw new CoupangSubmissionFailure(CONFLICT);
            store.queueRetry(executionId);
            return store.execution(executionId, false);
        });
    }
    @Override
    public Execution reconcile(Long actor, String value) {
        access.productActor(actor, true);
        long executionId = id(value);
        return transactions.execute(tx -> {
            access.productActor(actor, true);
            var original = store.execution(executionId, false);
            copyStore.find(id(original.workingCopyId()), true);
            var e = store.execution(executionId, true);
            if (store.unsupportedSnapshotVersion(executionId))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.status() != Status.UNKNOWN && e.status() != Status.ACCEPTED && e.status() != Status.PARTIAL)
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.target().steps().stream()
                    .noneMatch(s -> s.status() == Status.UNKNOWN || s.status() == Status.ACCEPTED))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.target() == null || writer.accountKey() == null)
                throw new CoupangSubmissionFailure(CONFLICT);
            if (store.active(id(e.workingCopyId()), writer.accountKey(), executionId))
                throw new CoupangSubmissionFailure(CONFLICT);
            store.queueReconcile(executionId);
            return store.execution(executionId, false);
        });
    }
    @Override
    public Execution revise(Long actor, String value) {
        access.productActor(actor, true);
        long executionId = id(value);
        return transactions.execute(tx -> {
            access.productActor(actor, true);
            var original = store.execution(executionId, false);
            copyStore.find(id(original.workingCopyId()), true);
            var e = store.execution(executionId, true);
            if (e.target() == null || writer.accountKey() == null
                    || !Objects.equals(writer.accountKey(), store.executionAccount(executionId)))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (e.revised())
                return e;
            if (e.status() != Status.FAILED && e.status() != Status.PARTIAL)
                throw new CoupangSubmissionFailure(CONFLICT);
            var steps = e.target().steps().stream().toList();
            if (steps.stream().noneMatch(s -> s.status() == Status.FAILED)
                    || steps.stream()
                            .anyMatch(s -> Set.of(Status.RUNNING, Status.UNKNOWN, Status.ACCEPTED).contains(s.status()))
                    || store.pendingRevisionWork(executionId))
                throw new CoupangSubmissionFailure(CONFLICT);
            if (store.active(id(e.workingCopyId()), writer.accountKey(), executionId))
                throw new CoupangSubmissionFailure(CONFLICT);
            store.revise(executionId, actor);
            return store.execution(executionId, false);
        });
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 15000)
    public void poll() {
        worker.poll();
    }
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void heartbeat() {
        worker.heartbeat();
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanupExpiredPreviews() {
        for (long preview : store.expiredUnexecutedPreviews())
            transactions.executeWithoutResult(tx -> store.releaseExpiredPreviewPins(preview));
    }
    /** 모의 게이트웨이 검사에서 자동 폴링 없이 한 단계를 실행한다. */
    public void runPending() {
        worker.runPending();
    }
    @PreDestroy
    public void close() {
        worker.close();
    }
    static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0 || !value.equals(Long.toString(id)))
                throw new NumberFormatException();
            return id;
        } catch (RuntimeException e) {
            throw new InputValidationFailure("실행 식별자를 확인해 주세요.");
        }
    }
    static void checkKey(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new InputValidationFailure("실행 요청 키를 확인해 주세요.");
    }

}
