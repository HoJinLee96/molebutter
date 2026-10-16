package cc.ataglace.molebutter;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway.*;
import cc.ataglace.molebutter.identity.internal.UserRepository;
import cc.ataglace.molebutter.coupang.internal.DefaultCoupangSubmissions;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.refresh.worker-enabled=false", "coupang.submissions.worker-enabled=false",
        "coupang.orders.worker-enabled=false", "spring.config.import=classpath:bootstrap-admin-test.properties",
        "spring.datasource.username=test_app", "spring.datasource.password=isolated-test-app-password",
        "spring.flyway.user=test_migrator", "spring.flyway.password=isolated-test-migration-password",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.password=", "mail.provider=test",
        "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes", "auth.cookie.secure=false"})
@ActiveProfiles("bootstrap-admin")
@Import(AuthenticationFlowIT.MailConfiguration.class)
class CoupangWorkingCopyFlowIT {
    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry r) {
        AuthenticationFlowIT.databases(r);
    }
    @Autowired
    CoupangWorkingCopies copies;
    @Autowired
    DefaultCoupangSubmissions submissions;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    CoupangWriteGateway writer;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    CoupangEditor editor;
    String account = "a".repeat(64);
    long actor;
    CoupangEditor.EditorDocument source;
    @BeforeEach
    void setup() {
        actor = users.findByEmail("admin@example.com").orElseThrow().getId();
        when(writer.accountKey()).thenReturn(account);
        source = new CoupangEditor.EditorDocument(
                new CoupangEditor.Basic("123", null, "쿠팡 상품", "쿠팡 상품", "제품", "브랜드", null, "10", "승인완료"),
                new CoupangEditor.Limits(true, true, true),
                List.of(new CoupangEditor.EditOption("111", "222", "옵션",
                        new CoupangCatalog.CurrentInventory("222", 120000L, 9L, true), null, true, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());
        doReturn(source).when(editor).edit(actor, "123");
    }
    Entry changed(Entry e, String name) {
        var c = new CoupangEditing.Change("basic.sellerProductName", null, name);
        var d = CoupangInputs.apply(json, e.document(), List.of(c));
        return copies.save(actor, e.workingCopyId(), new Save(e.sessionId(), e.revision(), d, List.of(c)));
    }
    @Test
    void missingSessionIsValidationFailureAndDoesNotCreateAWorkingCopy() {
        long count = db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy", Long.class);
        for (String session : java.util.Arrays.asList(null, "", " \t", "0", "-1", "01", "9223372036854775808",
                "invalid"))
            assertThatThrownBy(
                    () -> copies.save(actor, null, new Save(session, null, CoupangInputs.empty(), List.of())))
                    .isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy", Long.class)).isEqualTo(count);
    }
    @Test
    void newEditorCreatesNoRowUntilSaveAndSavedInputsSurviveNewSession() {
        long count = db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy", Long.class);
        var e = copies.open(actor, null, null);
        assertThat(e.workingCopyId()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy", Long.class)).isEqualTo(count);
        var saved = changed(e, "신규 저장 입력");
        assertThat(saved.revision()).isZero();
        var reopened = copies.open(actor, null, saved.workingCopyId());
        assertThat(reopened.document().basic()).containsEntry("sellerProductName", "신규 저장 입력");
        assertThat(reopened.document().options().getFirst().id()).isEqualTo(e.document().options().getFirst().id());
        assertThat(reopened.sessionId()).isNotEqualTo(saved.sessionId());
    }
    @Test
    void registeredProductReusesCanonicalCopyAndRevisionConflictsRetainSavedInput() {
        var start = copies.open(actor, "123", null);
        var saved = changed(start, "사용자 저장 입력");
        var second = copies.open(actor, "123", null);
        assertThat(second.workingCopyId()).isEqualTo(saved.workingCopyId());
        assertThat(second.document().basic()).containsEntry("sellerProductName", "사용자 저장 입력");
        var advanced = changed(second, "다음 저장 입력");
        assertThatThrownBy(() -> changed(saved, "stale")).isInstanceOf(CoupangEditingFailure.class);
        assertThat(copies.open(actor, null, saved.workingCopyId()).document().basic())
                .containsEntry("sellerProductName", "다음 저장 입력");
        assertThat(advanced.revision()).isEqualTo(saved.revision() + 1);
    }
    @Test
    void accountChangeCannotReadAnotherAccountsCopy() {
        var saved = changed(copies.open(actor, null, null), "다른 계정 보호");
        when(writer.accountKey()).thenReturn("b".repeat(64));
        assertThatThrownBy(() -> copies.open(actor, null, saved.workingCopyId()))
                .isInstanceOf(CoupangEditingFailure.class);
    }
    @Test
    void expiredSessionRequiresReentryAndRestoresSavedInputs() {
        var saved = changed(copies.open(actor, null, null), "만료 복구 입력");
        db.update(
                "UPDATE coupang_edit_session SET session_json=JSON_SET(session_json,'$.expiresAt','2020-01-01T00:00:00Z') WHERE id=?",
                Long.parseLong(saved.sessionId()));
        assertThatThrownBy(() -> changed(saved, "unsaved")).isInstanceOf(CoupangEditingFailure.class);
        assertThat(copies.open(actor, null, saved.workingCopyId()).document().basic())
                .containsEntry("sellerProductName", "만료 복구 입력");
    }
    @Test
    void unknownExecutionBlocksRewritesAndReconciliationUsesOriginalRequest() {
        var start = copies.open(actor, "123", null);
        var saved = changed(start, "전송 입력");
        when(writer.prepare(anyLong(), any(), any(), any(), anyList())).thenAnswer(i -> {
            var mapping = (Mapping) i.getArgument(2);
            var step = new Step("step", Type.PRODUCT, null, "PUT", "/synthetic/coupang", "", "{\"actual\":true}", "{}",
                    "{}");
            return new Prepared(account, mapping, List.of(step),
                    List.of(new CoupangWriteGateway.Change("name", "old", "new")), Instant.now(), List.of(),
                    new EditIntent(i.getArgument(3), i.getArgument(4)), 4);
        });
        var preview = submissions.prepare(actor, new CoupangEditing.PrepareRequest(saved.workingCopyId(),
                saved.revision(), saved.sessionId(), saved.changes()));
        String key = UUID.randomUUID().toString();
        var execution = submissions.execute(actor, preview.id(), key);
        assertThat(submissions.execute(actor, preview.id(), key).id()).isEqualTo(execution.id());
        when(writer.execute(anyLong(), any(), any(), any(), any())).thenAnswer(i -> {
            ((Consumer<Step>) i.getArgument(4)).accept(i.getArgument(2));
            return new Result(State.UNKNOWN, i.getArgument(3), "NETWORK", null, Instant.now());
        });
        submissions.runPending();
        assertThat(submissions.get(actor, execution.id()).status()).isEqualTo(CoupangSubmissions.Status.UNKNOWN);
        assertThatThrownBy(
                () -> submissions
                        .prepare(actor,
                                new CoupangEditing.PrepareRequest(saved.workingCopyId(), saved.revision(),
                                        saved.sessionId(), saved.changes())))
                .isInstanceOf(CoupangSubmissionFailure.class);
        assertThatThrownBy(() -> submissions.retry(actor, execution.id())).isInstanceOf(CoupangSubmissionFailure.class);
        when(writer.reconcile(anyLong(), any(), any(), any())).thenAnswer(i -> new Result(State.CONFIRMED,
                ((Result) i.getArgument(3)).mapping(), "VERIFIED", null, Instant.now()));
        submissions.reconcile(actor, execution.id());
        submissions.runPending();
        assertThat(submissions.get(actor, execution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);
        verify(writer, times(1)).execute(anyLong(), any(), any(), any(), any());
        assertThat(db.queryForObject(
                "SELECT request_json FROM coupang_execution_attempt WHERE execution_id=? AND action='WRITE'",
                String.class, Long.parseLong(execution.id()))).contains("actual");
    }
    CoupangEditor.EditorDocument product(String id, List<CoupangCatalog.Attribute> attrs) {
        var o = source.options().getFirst();
        return new CoupangEditor.EditorDocument(
                new CoupangEditor.Basic(id, null, "쿠팡 상품", "쿠팡 상품", "제품", "브랜드", null, "10", "승인완료"), source.limits(),
                List.of(new CoupangEditor.EditOption(o.sellerProductItemId(), o.vendorItemId(), o.itemName(),
                        o.current(), null, true, attrs, List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());
    }
    @Test
    void rowIntentReentryPreservesSameNameSearchAndUntouchedSizeAndLegacyInputIsNotGuessed() {
        doReturn(product("124", List.of(new CoupangCatalog.Attribute("색상", "검색값", "NONE"),
                new CoupangCatalog.Attribute("색상", "빨강", "EXPOSED"),
                new CoupangCatalog.Attribute("사이즈", "M", "EXPOSED")))).when(editor).edit(actor, "124");
        var e = copies.open(actor, "124", null);
        var edit = new CoupangEditing.Change("options.attributes.value", e.document().options().getFirst().id(), "파랑",
                List.of("색상", "EXPOSED"), "SET");
        var saved = copies.save(actor, null,
                new Save(e.sessionId(), null, CoupangInputs.apply(json, e.document(), List.of(edit)), List.of(edit)));
        doReturn(product("124", List.of(new CoupangCatalog.Attribute("색상", "최신 검색값", "NONE"),
                new CoupangCatalog.Attribute("색상", "빨강", "EXPOSED"),
                new CoupangCatalog.Attribute("사이즈", "L", "EXPOSED")))).when(editor).edit(actor, "124");
        var reopened = copies.open(actor, null, saved.workingCopyId());
        assertThat(reopened.document().options().getFirst().attributes()).extracting(CoupangCatalog.Attribute::value)
                .containsExactly("최신 검색값", "파랑", "L");
        assertThat(reopened.changes()).containsExactly(edit);
        var again = copies.save(actor, reopened.workingCopyId(),
                new Save(reopened.sessionId(), reopened.revision(), reopened.document(), reopened.changes()));
        var legacy = new CoupangEditing.Change("options.attributes", edit.optionId(),
                again.document().options().getFirst().attributes());
        db.update("UPDATE coupang_product_working_copy SET changes_json=? WHERE id=?",
                json.writeValueAsString(List.of(legacy)), Long.parseLong(again.workingCopyId()));
        var blocked = copies.open(actor, null, again.workingCopyId());
        assertThat(blocked.recoveryMessage()).isNotBlank();
        assertThat(blocked.document()).isEqualTo(again.document());
        assertThat(blocked.recoveryBase().options().getFirst().attributes())
                .contains(new CoupangCatalog.Attribute("색상", "최신 검색값", "NONE"),
                        new CoupangCatalog.Attribute("색상", "빨강", "EXPOSED"));
        assertThatThrownBy(() -> copies.save(actor, blocked.workingCopyId(),
                new Save(blocked.sessionId(), blocked.revision(), blocked.document(), blocked.changes())))
                .isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test
    void partialSuccessConsumesOnlyCompletedIntentAcrossNewerLocalRevision() {
        doReturn(product("125", List.of())).when(editor).edit(actor, "125");
        var e = copies.open(actor, "125", null);
        String option = e.document().options().getFirst().id();
        var price = new CoupangEditing.Change("options.registration.salePrice", option, "130000");
        var name = new CoupangEditing.Change("basic.sellerProductName", null, "전송 상품명");
        var edits = List.of(price, name);
        var saved = copies.save(actor, null,
                new Save(e.sessionId(), null, CoupangInputs.apply(json, e.document(), edits), edits));
        when(writer.prepare(anyLong(), any(), any(), any(), anyList())).thenAnswer(i -> {
            var mapping = (Mapping) i.getArgument(2);
            return new Prepared(account, mapping,
                    List.of(new Step("price", Type.PRICE, option, "PUT", "/synthetic/price", "", null, "{}", "{}"),
                            new Step("info", Type.PRODUCT, null, "PUT", "/synthetic/info", "", "{}", "{}", "{}")),
                    List.of(), Instant.now(), List.of(), new EditIntent(i.getArgument(3), i.getArgument(4)), 4);
        });
        var preview = submissions.prepare(actor, new CoupangEditing.PrepareRequest(saved.workingCopyId(),
                saved.revision(), saved.sessionId(), saved.changes()));
        var execution = submissions.execute(actor, preview.id(), UUID.randomUUID().toString());
        var newerName = new CoupangEditing.Change("basic.sellerProductName", null, "새로 저장한 상품명");
        var newer = List.of(price, newerName);
        var advanced = copies.save(actor, saved.workingCopyId(), new Save(saved.sessionId(), saved.revision(),
                CoupangInputs.apply(json, saved.document(), List.of(newerName)), newer));
        when(writer.execute(anyLong(), any(), any(), any(), any())).thenAnswer(i -> {
            var step = (Step) i.getArgument(2);
            ((Consumer<Step>) i.getArgument(4)).accept(step);
            return new Result(step.type() == Type.PRICE ? State.CONFIRMED : State.FAILED, i.getArgument(3),
                    step.type() == Type.PRICE ? "VERIFIED" : "HTTP_400", null, Instant.now());
        });
        submissions.runPending();
        submissions.runPending();
        assertThat(submissions.get(actor, execution.id()).status()).isEqualTo(CoupangSubmissions.Status.PARTIAL);
        var fresh = copies.open(actor, null, advanced.workingCopyId());
        assertThat(fresh.revision()).isEqualTo(advanced.revision());
        assertThat(fresh.changes()).containsExactly(newerName);
    }

    @Test
    void aCompletedPriceCannotReturnThroughAnUnrefreshedSessionSave() {
        doReturn(product("126", List.of())).when(editor).edit(actor, "126");
        var e = copies.open(actor, "126", null);
        String option = e.document().options().getFirst().id();
        var price = new CoupangEditing.Change("options.registration.salePrice", option, "130000");
        var saved = copies.save(actor, null,
                new Save(e.sessionId(), null, CoupangInputs.apply(json, e.document(), List.of(price)), List.of(price)));
        when(writer.prepare(anyLong(), any(), any(), any(), anyList()))
                .thenAnswer(i -> new Prepared(account, i.getArgument(2),
                        List.of(new Step("price", Type.PRICE, option, "PUT", "/synthetic/price", "", null, "{}", "{}")),
                        List.of(), Instant.now(), List.of(), new EditIntent(i.getArgument(3), i.getArgument(4)), 4));
        var preview = submissions.prepare(actor, new CoupangEditing.PrepareRequest(saved.workingCopyId(),
                saved.revision(), saved.sessionId(), saved.changes()));
        var execution = submissions.execute(actor, preview.id(), UUID.randomUUID().toString());
        when(writer.execute(anyLong(), any(), any(), any(), any())).thenAnswer(i -> {
            ((Consumer<Step>) i.getArgument(4)).accept(i.getArgument(2));
            return new Result(State.CONFIRMED, i.getArgument(3), "VERIFIED", null, Instant.now());
        });
        submissions.runPending();
        assertThat(submissions.get(actor, execution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);
        var name = new CoupangEditing.Change("basic.sellerProductName", null, "완료 직후 저장한 이름");
        var next = copies.save(actor, saved.workingCopyId(), new Save(saved.sessionId(), saved.revision(),
                CoupangInputs.apply(json, saved.document(), List.of(name)), List.of(price, name)));
        assertThat(next.changes()).containsExactly(name);
        var again = changed(next, "한 번 더 저장");
        assertThat(again.changes()).extracting(CoupangEditing.Change::path).containsExactly("basic.sellerProductName");
        var newPrice = new CoupangEditing.Change(price.path(), option, "140000");
        var edited = copies.save(actor, again.workingCopyId(),
                new Save(again.sessionId(), again.revision(),
                        CoupangInputs.apply(json, again.document(), List.of(newPrice)),
                        List.of(again.changes().getFirst(), newPrice)));
        assertThat(edited.changes()).contains(newPrice);
    }
    @Test
    void repeatedImageUrlsAndDescriptionTextKeepDistinctIdsOnReentry() {
        var initial = product("127", List.of());
        var o = initial.options().getFirst();
        String url = "https://image.example.test/repeated.png";
        var repeated = new CoupangEditor.EditorDocument(initial.basic(), initial.limits(),
                List.of(new CoupangEditor.EditOption(o.sellerProductItemId(), o.vendorItemId(), o.itemName(),
                        o.current(), null, true, List.of(),
                        List.of(new CoupangCatalog.Image(0, "REPRESENTATION", url),
                                new CoupangCatalog.Image(1, "DETAIL", url)),
                        List.of(new CoupangCatalog.Content("HTML", "TEXT", "same"),
                                new CoupangCatalog.Content("HTML", "TEXT", "same")),
                        List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());
        doReturn(repeated).when(editor).edit(actor, "127");
        var saved = changed(copies.open(actor, "127", null), "중복 URL 작업본");
        var reopened = copies.open(actor, null, saved.workingCopyId());
        var old = saved.document().options().getFirst();
        var fresh = reopened.document().options().getFirst();
        assertThat(fresh.images()).extracting(Image::id).doesNotHaveDuplicates()
                .containsExactlyElementsOf(old.images().stream().map(Image::id).toList());
        assertThat(fresh.contents()).extracting(Content::id).doesNotHaveDuplicates()
                .containsExactlyElementsOf(old.contents().stream().map(Content::id).toList());
        assertThat(changed(reopened, "다시 저장").changes()).extracting(CoupangEditing.Change::path)
                .containsExactly("basic.sellerProductName");
        var shifted = new CoupangEditor.EditorDocument(repeated.basic(), repeated.limits(),
                List.of(new CoupangEditor.EditOption(o.sellerProductItemId(), o.vendorItemId(), o.itemName(),
                        o.current(), null, true, List.of(),
                        List.of(new CoupangCatalog.Image(0, "REPRESENTATION", "https://image.example.test/new.png"),
                                new CoupangCatalog.Image(1, "DETAIL", url), new CoupangCatalog.Image(2, "DETAIL", url)),
                        List.of(new CoupangCatalog.Content("HTML", "TEXT", "new"),
                                new CoupangCatalog.Content("HTML", "IMAGE", "same"),
                                new CoupangCatalog.Content("HTML", "TEXT", "same")),
                        List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());
        doReturn(shifted).when(editor).edit(actor, "127");
        var moved = copies.open(actor, null, saved.workingCopyId()).document().options().getFirst();
        assertThat(moved.images()).extracting(Image::id).doesNotHaveDuplicates()
                .containsAll(old.images().stream().map(Image::id).toList());
        assertThat(moved.contents()).extracting(Content::id).doesNotHaveDuplicates()
                .containsAll(old.contents().stream().map(Content::id).toList());
        assertThat(moved.contents().get(1).detailType()).isEqualTo("IMAGE");

    }

    @Test
    void aSessionWithoutPendingIntentIsRenewedWithoutChangingSavedInputs() {
        var saved = changed(copies.open(actor, null, null), "이전 세션 입력");
        db.update(
                "UPDATE coupang_edit_session SET session_json=JSON_REMOVE(session_json,'$.pendingChanges') WHERE id=?",
                Long.parseLong(saved.sessionId()));
        assertThatThrownBy(() -> changed(saved, "unsaved")).isInstanceOf(CoupangEditingFailure.class).extracting("kind")
                .isEqualTo(CoupangEditingFailure.Kind.EXPIRED);
        assertThat(copies.open(actor, null, saved.workingCopyId()).document().basic())
                .containsEntry("sellerProductName", "이전 세션 입력");
    }

    @Test
    void unsupportedFreshMediaKeepsTheSavedDocumentAvailableForRecovery() {
        var saved = changed(copies.open(actor, "123", null), "조회 실패에도 보관할 입력");
        var o = source.options().getFirst();
        doReturn(new CoupangEditor.EditorDocument(source.basic(), source.limits(),
                List.of(new CoupangEditor.EditOption(o.sellerProductItemId(), o.vendorItemId(), o.itemName(),
                        o.current(), null, true, List.of(),
                        List.of(new CoupangCatalog.Image(0, "REPRESENTATION", "http://image.example.test/a.png")),
                        List.of(), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of())).when(editor).edit(actor, "123");
        var recovered = copies.open(actor, null, saved.workingCopyId());
        assertThat(recovered.blocked()).isTrue();
        assertThat(recovered.observation()).isNull();
        assertThat(recovered.document()).isEqualTo(saved.document());
        assertThatThrownBy(
                () -> submissions
                        .prepare(actor,
                                new CoupangEditing.PrepareRequest(recovered.workingCopyId(), recovered.revision(),
                                        recovered.sessionId(), recovered.changes())))
                .isInstanceOf(CoupangEditingFailure.class);
    }

}
