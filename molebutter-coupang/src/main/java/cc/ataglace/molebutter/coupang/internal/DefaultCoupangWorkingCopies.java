package cc.ataglace.molebutter.coupang.internal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
import static cc.ataglace.molebutter.coupang.api.CoupangEditingFailure.Kind.*;
@Service
public final class DefaultCoupangWorkingCopies implements CoupangWorkingCopies {
    private final BusinessAccess access;
    private final WorkingCopyStore copies;
    private final EditingStore sessions;
    private final SubmissionStore submissions;
    private final CoupangEditor editor;
    private final CoupangWriteGateway writer;
    private final ImageAssets assets;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    // Unsaved CREATE sessions are local to this single app instance; NOT_FOUND
    // recovery retains the full input.
    private final Map<String, EditingStore.Stored> unsaved = new ConcurrentHashMap<>();
    public DefaultCoupangWorkingCopies(BusinessAccess access, WorkingCopyStore copies, EditingStore sessions,
            SubmissionStore submissions, CoupangEditor editor, CoupangWriteGateway writer, ImageAssets assets,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.access = access;
        this.copies = copies;
        this.sessions = sessions;
        this.submissions = submissions;
        this.editor = editor;
        this.writer = writer;
        this.assets = assets;
        this.json = json;
        tx = new TransactionTemplate(manager);
    }
    private String account() {
        String account = writer.accountKey();
        if (account == null || !account.matches("[a-f0-9]{64}"))
            throw new CoupangFailure(CoupangFailure.Kind.CONFIGURATION);
        return account;
    }
    public Entry open(Long actor, String product, String workingCopy) {
        access.productActor(actor, true);
        String account = account();
        WorkingCopyStore.Copy copy = workingCopy == null
                ? (product == null ? null : copies.product(account, product))
                : copies.find(id(workingCopy), false);
        if (copy != null) {
            if (!account.equals(copy.account()) || product != null && !product.equals(copy.product()))
                throw new CoupangEditingFailure(CONFLICT);
            product = copy.product();
        }
        var mapping = copy == null ? null : copy.mapping();
        CoupangEditor.EditorDocument observation = null;
        Document base = copy == null ? CoupangInputs.empty() : copy.document();
        boolean blocked = false;
        if (product != null) {
            try {
                observation = editor.edit(actor, product);
                if (mapping == null)
                    mapping = CoupangInputs.mapping(account, observation, copy == null ? null : copy.document());
                var fresh = CoupangInputs.from(observation, mapping);
                if (copy != null)
                    fresh = preserveMedia(actor, fresh, copy.document());
                fresh = CoupangWorkingCopyDocuments.normalize(fresh, json);
                var observed = observation;
                observation = new CoupangEditor.EditorDocument(observed.basic(), observed.limits(),
                        mapping.options().stream().map(link -> observed.options().stream()
                                .filter(o -> Objects.equals(o.sellerProductItemId(), link.sellerProductItemId()))
                                .findFirst().orElseThrow()).toList(),
                        observed.delivery(), observed.settings(), observed.documents());
                base = fresh;
            } catch (CoupangFailure | InputValidationFailure failure) {
                if (copy == null)
                    throw failure;
                blocked = true;
                observation = null;
            }
        }
        var changes = copy == null ? List.<CoupangEditing.Change>of() : copy.changes();
        if (product == null)
            changes = changes.stream().filter(c -> !CoupangInputs.legacyList(c)).toList();
        boolean recovery = changes.stream().anyMatch(CoupangInputs::legacyList);
        var document = recovery && copy != null
                ? copy.document()
                : blocked ? base : CoupangInputs.apply(json, base, changes);
        String sid = Long.toString(BusinessIds.next());
        long revision = copy == null ? 0 : copy.revision();
        var s = new CoupangEditing.Session(sid, copy == null ? null : copy.id(), revision,
                Instant.now().plusSeconds(600).toString(), product == null ? "CREATE" : "UPDATE", base, observation,
                changes);
        var binding = new EditingStore.Binding(account, mapping,
                copy == null ? 1L : submissions.listingVersion(id(copy.id()), account));
        var stored = new EditingStore.Stored(actor, binding, s, false);
        if (copy == null) {
            unsaved.entrySet().removeIf(e -> Instant.parse(e.getValue().session().expiresAt()).isBefore(Instant.now()));
            if (unsaved.size() > 1000)
                throw new InputValidationFailure("열린 편집기가 너무 많습니다.");
            unsaved.put(sid, stored);
        } else {
            var expected = copy;
            tx.executeWithoutResult(t -> {
                if (copies.find(id(expected.id()), true).revision() != expected.revision())
                    throw new CoupangEditingFailure(CONFLICT);
                sessions.insert(actor, binding, s);
            });
        }
        return new Entry(s.mode(), s.workingCopyId(), copy == null ? null : revision, product, document, sid,
                s.expiresAt(), observation,
                blocked || copy != null && tx.execute(t -> submissions.active(id(s.workingCopyId()), account, -1)),
                changes, account, recovery && !blocked ? base : null,
                recovery ? "이전 목록 수정의 항목별 의도를 확인할 수 없습니다. 입력을 보관한 뒤 최신 값으로 다시 준비해 주세요." : null,
                copy == null ? null : copy.appliedRevision());
    }
    private Document preserveMedia(Long actor, Document fresh, Document previous) {
        var tokens = copies.publications(CoupangWorkingCopyDocuments.assetIds(previous));
        var publicUrls = new HashMap<String, String>();
        for (String asset : tokens.keySet())
            try {
                publicUrls.put(asset, assets.submissionUrl(actor, asset));
            } catch (InputValidationFailure unavailable) {
            }
        return CoupangInputs.preserveMedia(fresh, previous, tokens, publicUrls);
    }

    public Entry save(Long actor, String workingCopy, Save input) {
        access.productActor(actor, true);
        if (input == null || input.document() == null || input.changes() == null)
            throw new InputValidationFailure("작업본과 수정 항목을 확인해 주세요.");
        var initial = require(actor, input.sessionId(), false);
        if (!Objects.equals(initial.session().workingCopyId(), workingCopy) || workingCopy != null
                && (input.expectedRevision() == null || input.expectedRevision() != initial.session().revision()))
            throw new CoupangEditingFailure(CONFLICT);
        var normalized = CoupangWorkingCopyDocuments.normalize(input.document(), json);
        CoupangInputs.verify(json, initial.session().document(), normalized, input.changes(),
                initial.session().mode().equals("CREATE"));
        Entry result = tx.execute(t -> {
            var stored = require(actor, input.sessionId(), workingCopy != null);
            if (!stored.equals(initial))
                throw new CoupangEditingFailure(CONFLICT);
            WorkingCopyStore.Copy copy;
            var baseline = stored.session().document();
            var accepted = input.changes();
            if (workingCopy == null) {
                if (input.expectedRevision() != null)
                    throw new CoupangEditingFailure(CONFLICT);
                copy = copies.insert(actor, stored.binding().account(),
                        stored.binding().mapping() == null ? null : stored.binding().mapping().sellerProductId(),
                        normalized, input.changes(), stored.binding().mapping(), stored.session().observation());
            } else {
                copy = copies.find(id(workingCopy), true);
                if (copy.revision() != input.expectedRevision() || !copy.account().equals(stored.binding().account()))
                    throw new CoupangEditingFailure(CONFLICT);
                var pending = copy.changes();
                var completed = stored.session().pendingChanges().stream().filter(
                        old -> pending.stream().noneMatch(current -> CoupangInputs.sameChange(json, old, current)))
                        .toList();
                if (!completed.isEmpty()) {
                    baseline = CoupangInputs.apply(json, baseline, completed);
                    accepted = accepted.stream().filter(
                            change -> completed.stream().noneMatch(old -> CoupangInputs.sameChange(json, old, change)))
                            .toList();
                    CoupangInputs.verify(json, baseline, normalized, accepted,
                            stored.session().mode().equals("CREATE"));
                }
                if (submissions.active(id(copy.id()), copy.account(), -1) && !copy.document().options().stream()
                        .map(Option::id).collect(java.util.stream.Collectors.toSet()).equals(normalized.options()
                                .stream().map(Option::id).collect(java.util.stream.Collectors.toSet())))
                    throw new CoupangEditingFailure(CONFLICT);
                copy = copies.update(actor, copy, normalized, accepted, stored.session().observation());
            }
            var session = new CoupangEditing.Session(stored.session().id(), copy.id(), copy.revision(),
                    stored.session().expiresAt(), stored.session().mode(), baseline, stored.session().observation(),
                    copy.changes());
            if (workingCopy == null)
                sessions.insert(actor, stored.binding(), session);
            else
                sessions.update(session);
            assets.replaceReferences(actor, copy.id(), CoupangWorkingCopyDocuments.assetIds(copy.document()));
            return new Entry(session.mode(), copy.id(), copy.revision(), copy.product(), copy.document(), session.id(),
                    session.expiresAt(), session.observation(), false, copy.changes(), stored.binding().account(), null,
                    null, copy.appliedRevision());
        });
        if (workingCopy == null)
            unsaved.remove(input.sessionId());
        return result;
    }
    public PageResponse<Summary> list(Long actor, String query, int page, int size) {
        access.productActor(actor, true);
        if (page < 0 || page > 1000000 || !Set.of(10, 20, 50, 100).contains(size))
            throw new InputValidationFailure("페이지를 확인해 주세요.");
        return copies.list(account(), Objects.toString(query, ""), page, size);
    }
    EditingStore.Stored require(Long actor, String sid, boolean lock) {
        access.productActor(actor, true);
        if (sid == null || sid.isBlank())
            throw new InputValidationFailure("편집 세션 식별자를 확인해 주세요.");
        var stored = unsaved.get(sid);
        if (stored == null)
            stored = sessions.find(id(sid), lock);
        if (stored.revoked() || !Objects.equals(stored.actor(), actor) || stored.session().pendingChanges() == null
                || Instant.parse(stored.session().expiresAt()).isBefore(Instant.now()))
            throw new CoupangEditingFailure(EXPIRED);
        if (!Objects.equals(stored.binding().account(), account()))
            throw new CoupangEditingFailure(CONFLICT);
        if (stored.session().workingCopyId() != null) {
            var copy = copies.find(id(stored.session().workingCopyId()), lock);
            if (copy.revision() != stored.session().revision()
                    || !Objects.equals(copy.account(), stored.binding().account())
                    || !Objects.equals(copy.mapping(), stored.binding().mapping()))
                throw new CoupangEditingFailure(CONFLICT);
        }
        return stored;
    }
    EditingStore.Binding requireTarget(EditingStore.Stored stored, boolean lock) {
        var s = stored.session();
        if (s.workingCopyId() == null)
            throw new InputValidationFailure("먼저 임시 저장해 주세요.");
        var copy = copies.find(id(s.workingCopyId()), lock);
        var binding = stored.binding();
        if (copy.revision() != s.revision() || !Objects.equals(copy.mapping(), binding.mapping())
                || submissions.listingVersion(id(copy.id()), binding.account()) != binding.listingVersion()
                || s.mode().equals("UPDATE") && s.observation() == null)
            throw new CoupangEditingFailure(CONFLICT);
        return binding;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanup() {
        tx.executeWithoutResult(t -> sessions.cleanup());
        unsaved.entrySet().removeIf(e -> Instant.parse(e.getValue().session().expiresAt()).isBefore(Instant.now()));
    }
    private static long id(String value) {
        return DefaultCoupangSubmissions.id(value);
    }
}
