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
        "product.refresh.worker-enabled=false", "coupang.submissions.worker-enabled=false", "coupang.vendor-id=synthetic",
        "coupang.orders.worker-enabled=false", "coupang.products.worker-enabled=false", "spring.config.import=classpath:bootstrap-admin-test.properties",
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
        r.add("coupang.vendor-id",()->"synthetic");
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
    @MockitoBean CoupangQuickEditSources quickSources;
    @Autowired CoupangPricing pricing;
    @Autowired CoupangRegistrationDefaults registrationDefaults;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean cc.ataglace.molebutter.media.api.R2Images r2Images;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean cc.ataglace.molebutter.media.api.ImageInspection imageInspection;
    @Autowired CoupangCatalog catalog;
    @Autowired cc.ataglace.molebutter.coupang.internal.DefaultCoupangRegisteredProducts registry;
    @Autowired CoupangObservations observations;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cc.ataglace.molebutter.operations.api.NotificationService notifications;
    String account = "a".repeat(64);
    @Autowired cc.ataglace.molebutter.app.internal.CoupangSearchTagsController searchTags;
    long actor;
    CoupangEditor.EditorDocument source;
    @Test void prefilledBaselineIsUnsavedOwnedAndSurvivesFirstSaveWithoutSyntheticChanges(){
        var blank=CoupangInputs.empty();var source=blank.options().getFirst();
        long priorRows=db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class);
        var generated=searchTags.generate(new cc.ataglace.molebutter.identity.api.UserPrincipal(actor,"synthetic@example.test",cc.ataglace.molebutter.identity.api.UserRole.ADMIN),new CoupangSearchTags.Input("닥스 남성 반지갑","닥스",new CoupangSearchTags.Context("남성","소가죽","반지갑",List.of("블랙")))).getData();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class)).isEqualTo(priorRows);
        var option=new Option(source.id(),"단일 상품",Map.of("externalVendorSku","SKU-PREFILL","salePrice","139000","searchTags",String.join(", ",generated.tags())),List.of(new CoupangCatalog.Attribute("패션 의류/잡화 색상계열","브라운 톤","NONE")),List.of(),List.of(),List.of(),List.of(),null);
        var document=new Document(Map.of("sellerProductName","서버 자동 입력 SKU-PREFILL"),List.of(option),Map.of(),Map.of("saleStartedAt","2026-10-20T13:00:00","saleEndedAt","2099-12-31T23:59:00"),List.of());
        long before=db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class);
        var entry=copies.prefill(actor,document);assertThat(entry.workingCopyId()).isNull();assertThat(entry.changes()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class)).isEqualTo(before);
        var saved=copies.save(actor,null,new Save(entry.sessionId(),null,entry.document(),List.of()));
        var reopened=copies.open(actor,null,saved.workingCopyId());assertThat(reopened.document()).isEqualTo(saved.document());assertThat(reopened.changes()).isEmpty();assertThat(reopened.document().options().getFirst().registration()).containsEntry("searchTags",String.join(", ",generated.tags()));
        account="f".repeat(64);when(writer.accountKey()).thenReturn(account);assertThatThrownBy(()->copies.save(actor,saved.workingCopyId(),new Save(reopened.sessionId(),saved.revision(),reopened.document(),List.of()))).isInstanceOf(RuntimeException.class);
    }
    @Test void imageWarningsAreNonblockingAndPersistInPreviewWithoutChangingIntent(){
        var entry=copies.open(actor,"123",null);var option=entry.document().options().getFirst();
        var image=new Image(UUID.randomUUID().toString(),null,"https://assets.example.test/products/SKU/official/01.png","REPRESENTATION",0);
        var change=new CoupangEditing.Change("options.images",option.id(),List.of(image));
        var saved=copies.save(actor,null,new Save(entry.sessionId(),null,CoupangInputs.apply(json,entry.document(),List.of(change)),List.of(change)));
        var check=new cc.ataglace.molebutter.media.api.ImageInspection.Check("REPRESENTATION",option.id(),image.id(),image.url(),"PASS",750,962,900L,List.of(),List.of("직사각형 원본을 사용합니다."));
        doReturn(List.of(check)).when(imageInspection).inspect(eq(actor),anyList());
        when(writer.prepare(anyLong(),any(),any(),any(),anyList())).thenAnswer(i->new Prepared(account,i.getArgument(2),List.of(new Step("product",Type.PRODUCT,null,"PUT","/synthetic/product","",null,"{}","{}")),List.of(),Instant.now(),List.of(),new EditIntent(i.getArgument(3),i.getArgument(4)),4));
        var preview=submissions.prepare(actor,new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),saved.changes()));
        assertThat(preview.executable()).isTrue();assertThat(preview.target().issues()).isEmpty();assertThat(preview.target().warnings()).containsExactly(new Issue("images","직사각형 원본을 사용합니다."));
        var persisted=json.readValue(db.queryForObject("SELECT preview_json FROM coupang_submission_preview WHERE id=?",String.class,Long.parseLong(preview.id())),CoupangSubmissions.Preview.class);assertThat(persisted.target().warnings()).isEqualTo(preview.target().warnings());
        assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(copies.open(actor,null,saved.workingCopyId()).changes())).isEqualTo(json.valueToTree(saved.changes()));
    }
    @Test void registrationDefaultsAreAccountScopedRevisionCheckedAndNeverWriteAProduct(){
        var initial=registrationDefaults.get(actor);assertThat(initial.revision()).isZero();assertThat(initial.delivery()).isEmpty();
        var input=new CoupangRegistrationDefaults.Save(0L,Map.of("deliveryMethod","SEQUENCIAL","deliveryCompanyCode","CJGLS","deliveryCharge","0"),Map.of("maximumBuyForPerson","0","taxType","TAX"),Map.of("bundleInfo.bundleType","SINGLE"));
        var saved=registrationDefaults.save(actor,input);assertThat(saved.revision()).isEqualTo(1);assertThat(saved.delivery()).containsEntry("deliveryCharge","0");
        assertThatThrownBy(()->registrationDefaults.save(actor,input)).isInstanceOf(RuntimeException.class);assertThat(registrationDefaults.get(actor)).isEqualTo(saved);
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(1L,Map.of("deliveryCharge","-1"),Map.of(),Map.of()))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(1L,Map.of("jeju","5000"),Map.of(),Map.of()))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        account="f".repeat(64);when(writer.accountKey()).thenReturn(account);assertThat(registrationDefaults.get(actor).delivery()).isEmpty();
    }
    @Test void directCommaFormattedSaveStoresNoNewIntentForAnEqualPrice(){
        var entry=copies.open(actor,"123",null);var option=entry.document().options().getFirst();
        String value=option.registration().get("salePrice");String formatted=String.format(java.util.Locale.US,"%,d",Long.parseLong(value));
        var change=new CoupangEditing.Change("options.registration.salePrice",option.id(),formatted);
        var saved=copies.save(actor,null,new Save(entry.sessionId(),null,CoupangInputs.apply(json,entry.document(),List.of(change)),List.of(change)));
        assertThat(saved.changes()).isEmpty();assertThat(saved.document().options().getFirst().registration()).containsEntry("salePrice",value);
        var resumed=copies.open(actor,null,saved.workingCopyId());
        var changed=new CoupangEditing.Change("options.registration.salePrice",option.id(),"139,000");
        var pending=copies.save(actor,saved.workingCopyId(),new Save(resumed.sessionId(),resumed.revision(),CoupangInputs.apply(json,resumed.document(),List.of(changed)),List.of(changed)));
        var again=copies.open(actor,null,pending.workingCopyId());
        var retained=copies.save(actor,pending.workingCopyId(),new Save(again.sessionId(),again.revision(),again.document(),List.of(changed)));
        assertThat(retained.changes()).containsExactly(new CoupangEditing.Change(changed.path(),option.id(),"139000"));
    }
    @Test void freeShippingDefaultsFillRequiredZerosWithoutChangingReturnFees(){
        var saved=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(0L,Map.of("deliveryChargeType","FREE","deliveryChargeOnReturn","4000","returnCharge","4000"),Map.of(),Map.of()));
        assertThat(saved.delivery()).containsEntry("deliveryCharge","0").containsEntry("freeShipOverAmount","0").containsEntry("deliveryChargeOnReturn","4000").containsEntry("returnCharge","4000");
        saved=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(saved.revision(),Map.of("deliveryChargeType","FREE","deliveryCharge","3000","freeShipOverAmount","50000"),Map.of(),Map.of()));
        assertThat(registrationDefaults.get(actor).delivery()).containsEntry("deliveryCharge","0").containsEntry("freeShipOverAmount","0");
        saved=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(saved.revision(),Map.of("deliveryChargeType","NOT_FREE","deliveryCharge","3000"),Map.of(),Map.of()));
        assertThat(saved.delivery()).containsEntry("deliveryCharge","3000").doesNotContainKey("freeShipOverAmount");
    }
    @Test void purchaseLimitSettingsKeepUnsetDisabledAndEnabledDistinctAndRejectInvalidPeriods(){
        var initial=registrationDefaults.get(actor);assertThat(initial.registration()).doesNotContainKeys("maximumBuyForPerson","maximumBuyForPersonPeriod");
        var off=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(initial.revision(),Map.of(),Map.of("maximumBuyForPerson","0","maximumBuyForPersonPeriod","1"),Map.of()));
        assertThat(registrationDefaults.get(actor).registration()).containsEntry("maximumBuyForPerson","0").containsEntry("maximumBuyForPersonPeriod","1");
        var on=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(off.revision(),Map.of(),Map.of("maximumBuyForPerson","3","maximumBuyForPersonPeriod","7"),Map.of()));
        assertThat(registrationDefaults.get(actor).registration()).containsEntry("maximumBuyForPerson","3").containsEntry("maximumBuyForPersonPeriod","7");
        for(var invalid:List.of(Map.of("maximumBuyForPerson","0","maximumBuyForPersonPeriod","2"),Map.of("maximumBuyForPerson","3","maximumBuyForPersonPeriod","0")))
            assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(on.revision(),Map.of(),invalid,Map.of()))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        assertThat(registrationDefaults.get(actor)).isEqualTo(on);
        var unset=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(on.revision(),Map.of(),Map.of(),Map.of()));
        assertThat(unset.registration()).doesNotContainKeys("maximumBuyForPerson","maximumBuyForPersonPeriod");
    }
    @Test void brandLogosKeepLegacyOmissionExplicitClearRevisionAccountAndImageFailures(){
        String url="https://assets.example.test/common/daks.png";doReturn(new cc.ataglace.molebutter.media.api.R2Images.Image("common/daks.png",url,"image/png",300,150,1000)).when(r2Images).resolve(actor,url);
        var logo=new CoupangRegistrationDefaults.BrandLogo("닥스",List.of("DAKS","daks","닥스"),url);
        var saved=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(0L,Map.of(),Map.of(),Map.of(),List.of(),List.of(),List.of(logo)));
        assertThat(saved.brandLogos()).containsExactly(new CoupangRegistrationDefaults.BrandLogo("닥스",List.of("DAKS"),url));
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(0L,Map.of(),Map.of(),Map.of(),List.of(),List.of(),List.of()))).isInstanceOf(RuntimeException.class);
        var prior=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(saved.revision(),Map.of(),Map.of(),Map.of()));assertThat(prior.brandLogos()).isEqualTo(saved.brandLogos());
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(prior.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of(),List.of(logo,logo)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(prior.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of(url),List.of(logo)))).isInstanceOf(RuntimeException.class);
        doThrow(new cc.ataglace.molebutter.common.api.InputValidationFailure("image missing")).when(r2Images).resolve(actor,url);
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(prior.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of(),List.of(logo)))).isInstanceOf(RuntimeException.class);assertThat(registrationDefaults.get(actor)).isEqualTo(prior);
        String originalAccount=account;account="b".repeat(64);when(writer.accountKey()).thenReturn(account);assertThat(registrationDefaults.get(actor).brandLogos()).isEmpty();account=originalAccount;when(writer.accountKey()).thenReturn(account);
        assertThat(registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(prior.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of(),List.of())).brandLogos()).isEmpty();
    }
    @Test void managedLogoPurposeAndRowIdSurviveSavedWorkingCopyWithoutChangingOtherContents(){
        var blank=CoupangInputs.empty();var old=blank.options().getFirst();var logo=new Content(UUID.randomUUID().toString(),"IMAGE","IMAGE","https://assets.example.test/common/daks.png",null,"BRAND_LOGO");var other=new Content(UUID.randomUUID().toString(),"HTML","TEXT","<p>keep</p>",null);
        var option=new Option(old.id(),old.itemName(),old.registration(),old.attributes(),old.images(),List.of(logo,other),old.notices(),old.certifications(),old.noticeCategory());
        var entry=copies.prefill(actor,new Document(blank.basic(),List.of(option),blank.delivery(),blank.settings(),blank.documents()));var saved=copies.save(actor,null,new Save(entry.sessionId(),null,entry.document(),entry.changes()));var reopened=copies.open(actor,null,saved.workingCopyId());assertThat(reopened.document().options().getFirst().contents()).containsExactly(logo,other);
        var replaced=new Content(logo.id(),"IMAGE","IMAGE","https://assets.example.test/common/other.png",null,"BRAND_LOGO");var change=new CoupangEditing.Change("options.contents",old.id(),List.of(replaced,other));var updated=copies.save(actor,saved.workingCopyId(),new Save(reopened.sessionId(),reopened.revision(),CoupangInputs.apply(json,reopened.document(),List.of(change)),List.of(change)));assertThat(copies.open(actor,null,updated.workingCopyId()).document().options().getFirst().contents()).containsExactly(replaced,other);
    }
    @Test void descriptionPrefixesKeepOrderLegacyOmissionAndExplicitClearWithoutMigration(){
        String a="https://assets.example.test/common/first.png",b="https://assets.example.test/common/second.png";
        for(String url:List.of(a,b))doReturn(new cc.ataglace.molebutter.media.api.R2Images.Image("common/"+(url.equals(a)?"first.png":"second.png"),url,"image/png",300,900,1000)).when(r2Images).resolve(actor,url);
        long revision=registrationDefaults.get(actor).revision();
        var saved=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(revision,Map.of(),Map.of(),Map.of(),List.of(),List.of(b,a)));
        assertThat(saved.descriptionPrefixImages()).containsExactly(b,a);
        var legacy=registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(saved.revision(),Map.of(),Map.of(),Map.of()));
        assertThat(legacy.descriptionPrefixImages()).containsExactly(b,a);
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(legacy.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of(a,a)))).isInstanceOf(RuntimeException.class);
        assertThat(registrationDefaults.get(actor)).isEqualTo(legacy);
        doThrow(new cc.ataglace.molebutter.common.api.InputValidationFailure("이미지 없음")).when(r2Images).resolve(actor,a);
        assertThatThrownBy(()->registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(legacy.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of(a)))).isInstanceOf(RuntimeException.class);
        assertThat(registrationDefaults.get(actor)).isEqualTo(legacy);
        assertThat(registrationDefaults.save(actor,new CoupangRegistrationDefaults.Save(legacy.revision(),Map.of(),Map.of(),Map.of(),List.of(),List.of())).descriptionPrefixImages()).isEmpty();
    }
    @BeforeEach
    void setup() {
        account=UUID.randomUUID().toString().replace("-","").repeat(2);
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
    @Test void completionReopensPersistedDetailWithoutExternalReadsOrInventingCurrentInventory() {
        var saved=changed(copies.open(actor,"123",null),"미저장 아닌 보존 입력");
        var product=new CoupangCatalog.Product("123","서버 조회 상품","브랜드","승인완료",null,null);
        var item=new CoupangCatalog.ProductOption("111","222","옵션",null,null,List.of(),List.of(),List.of(),List.of(),
                List.of(new CoupangCatalog.Field("externalVendorSku","SAME-CODE")),List.of());
        var detail=new CoupangCatalog.ProductDetail(product,null,null,null,null,null,null,null,List.of(item),List.of(),List.of());
        var raw=json.readTree("{\"sellerProductId\":123,\"vendorId\":\"synthetic\",\"sellerProductName\":\"서버 조회 상품\",\"brand\":\"브랜드\",\"displayCategoryCode\":10,\"statusName\":\"승인완료\",\"items\":[{\"sellerProductItemId\":111,\"vendorItemId\":222,\"itemName\":\"옵션\",\"externalVendorSku\":\"SAME-CODE\",\"salePrice\":120000,\"maximumBuyCount\":9}]}");
        observations.record(new CoupangObservations.Read(account,actor,cc.ataglace.molebutter.common.api.BusinessIds.next(),"DETAIL","123",null,json.writeValueAsString(raw),product,detail,null,null));
        clearInvocations(editor);
        var reopened=copies.openObserved(actor,saved.workingCopyId());
        assertThat(reopened.productId()).isEqualTo("123");assertThat(reopened.blocked()).isFalse();
        assertThat(reopened.document().basic()).containsEntry("sellerProductName","미저장 아닌 보존 입력");
        assertThat(reopened.changes()).isEqualTo(saved.changes());assertThat(reopened.revision()).isEqualTo(saved.revision());
        assertThat(reopened.observation().options().getFirst().current()).isNull();
        verify(editor,never()).edit(anyLong(),anyString());
        account="b".repeat(64);when(writer.accountKey()).thenReturn(account);
        assertThatThrownBy(()->copies.openObserved(actor,saved.workingCopyId())).isInstanceOf(CoupangEditingFailure.class);
    }
    @Test void serverRulesAndPreviewTimestampRoundTripWithoutTrustingSavedDocuments() {
        String checked="2026-10-21T01:00:00Z";
        var rules=new CoupangEditor.CategoryRules("10",true,List.of(),List.of(),List.of(),List.of(),List.of(),checked);
        doReturn(rules).when(editor).cachedCategory(actor,"10");
        var blank=CoupangInputs.empty();var document=new Document(Map.of("sellerProductName","규격 보존 상품","displayCategoryCode","10"),blank.options(),blank.delivery(),blank.settings(),blank.documents());
        var entry=copies.prefill(actor,document);assertThat(entry.categoryRules()).isEqualTo(rules);
        var saved=copies.save(actor,null,new Save(entry.sessionId(),null,entry.document(),List.of()));
        var loaded=copies.open(actor,null,saved.workingCopyId());assertThat(loaded.categoryRules()).isEqualTo(rules);
        when(writer.prepare(anyLong(),any(),any(),any(),anyList())).thenAnswer(i->{
            var d=(Document)i.getArgument(1);var mapping=new Mapping(account,null,d.options().stream().map(o->new OptionMapping(o.id(),null,null)).toList());
            return new Prepared(account,mapping,List.of(new Step("create",Type.CREATE,null,"POST","/synthetic/product","","{}","{\"creationRulesCheckedAt\":\""+checked+"\"}","{}")),List.of(),Instant.now(),List.of(),new EditIntent(d,List.of()),4);
        });
        var preview=submissions.prepare(actor,new CoupangEditing.PrepareRequest(loaded.workingCopyId(),loaded.revision(),loaded.sessionId(),List.of()));
        assertThat(preview.target().categoryRulesCheckedAt()).isEqualTo(checked);
        var persisted=json.readValue(db.queryForObject("SELECT preview_json FROM coupang_submission_preview WHERE id=?",String.class,Long.parseLong(preview.id())),CoupangSubmissions.Preview.class);
        assertThat(persisted.target().categoryRulesCheckedAt()).isEqualTo(checked);
        verify(writer,never()).execute(anyLong(),any(),any(),any(),any());
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
    void approvalAutoChecksStayRunningAndCompleteWithoutAReconcileClick() throws Exception {
        var saved=changed(copies.open(actor,null,null),"자동 승인 결과 확인 상품");
        when(writer.prepare(anyLong(),any(),any(),any(),anyList())).thenAnswer(i->{
            var create=new Step("create-auto",Type.CREATE,null,"POST","/synthetic/create","","{}","{}","{}");
            var approve=new Step("approve-auto",Type.APPROVAL,null,"PUT","/synthetic/approve","",null,"{}",json.writeValueAsString(create));
            return new Prepared(account,i.getArgument(2),List.of(create,approve),List.of(),Instant.now(),
                    List.of(),new EditIntent(i.getArgument(3),i.getArgument(4)),4);
        });
        var preview=submissions.prepare(actor,new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),saved.changes()));
        var execution=submissions.execute(actor,preview.id(),UUID.randomUUID().toString());
        var checking=new java.util.concurrent.CountDownLatch(1);var finishChecks=new java.util.concurrent.CountDownLatch(1);
        when(writer.execute(anyLong(),any(),any(),any(),any())).thenAnswer(i->{
            var step=(Step)i.getArgument(2);((Consumer<Step>)i.getArgument(4)).accept(step);
            var mapping=new Mapping(account,"555",List.of());
            if(step.type()==Type.CREATE)return new Result(State.CONFIRMED,mapping,"SAVED_FOR_APPROVAL",null,Instant.now());
            checking.countDown();assertThat(finishChecks.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return new Result(State.CONFIRMED,mapping,"CREATED_CONFIRMED",null,Instant.now());
        });
        submissions.runPending();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task=executor.submit(submissions::runPending);
            try {
                assertThat(checking.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var during=submissions.get(actor,execution.id());
                assertThat(during.status()).isEqualTo(CoupangSubmissions.Status.RUNNING);
                assertThat(during.target().steps().getLast().status()).isEqualTo(CoupangSubmissions.Status.RUNNING);
                submissions.heartbeat();
                assertThatThrownBy(()->submissions.retry(actor,execution.id())).isInstanceOf(CoupangSubmissionFailure.class);
                assertThatThrownBy(()->submissions.reconcile(actor,execution.id())).isInstanceOf(CoupangSubmissionFailure.class);
            } finally { finishChecks.countDown(); }
            task.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(submissions.get(actor,execution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);
        verify(writer,times(2)).execute(anyLong(),any(),any(),any(),any());
        verify(writer,never()).reconcile(anyLong(),any(),any(),any());
    }
    @Test
    void unknownExecutionBlocksItsOwnRewritesButAnotherProductCanCompleteAndReconciliationUsesOriginalRequest() {
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
            Mapping target=i.getArgument(3);
            return new Result("123".equals(target.sellerProductId())?State.UNKNOWN:State.CONFIRMED,target,
                    "123".equals(target.sellerProductId())?"NETWORK":"VERIFIED", null, Instant.now());
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
        var other=json.valueToTree(source).deepCopy();
        other.path("basic").asObject().put("sellerProductId","124");
        other.path("options").get(0).asObject().put("sellerProductItemId","444").put("vendorItemId","333");
        other.path("options").get(0).path("current").asObject().put("vendorItemId","333");
        doReturn(json.treeToValue(other,CoupangEditor.EditorDocument.class)).when(editor).edit(actor,"124");
        var second=changed(copies.open(actor,"124",null),"다른 상품 B의 수정값");
        var secondPreview=submissions.prepare(actor,new CoupangEditing.PrepareRequest(second.workingCopyId(),second.revision(),second.sessionId(),second.changes()));
        var secondExecution=submissions.execute(actor,secondPreview.id(),UUID.randomUUID().toString());
        submissions.runPending();
        assertThat(submissions.get(actor,secondExecution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);
        assertThat(submissions.get(actor,execution.id()).status()).isEqualTo(CoupangSubmissions.Status.UNKNOWN);
        when(writer.reconcile(anyLong(), any(), any(), any())).thenAnswer(i -> new Result(State.CONFIRMED,
                ((Result) i.getArgument(3)).mapping(), "VERIFIED", null, Instant.now()));
        submissions.reconcile(actor, execution.id());
        submissions.runPending();
        assertThat(submissions.get(actor, execution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);
        verify(writer, times(2)).execute(anyLong(), any(), any(), any(), any());
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
    void newWorkingCopyRetainsSelectedNoticeCategoryAndReferenceContentsAcrossReentry() {
        var entry=copies.open(actor,null,null);
        var tree=json.valueToTree(entry.document()).asObject();var option=tree.path("options").get(0).asObject();
        option.put("noticeCategory","기타 재화");
        option.putArray("notices").addObject().put("category","기타 재화").put("name","품명").put("content","상품 상세페이지 참조");
        var document=json.treeToValue(tree,Document.class);
        var saved=copies.save(actor,null,new Save(entry.sessionId(),null,document,List.of()));
        var loaded=copies.open(actor,null,saved.workingCopyId());
        assertThat(loaded.mode()).isEqualTo("CREATE");
        assertThat(loaded.document().options().getFirst().noticeCategory()).isEqualTo("기타 재화");
        assertThat(loaded.document().options().getFirst().notices()).allSatisfy(n->assertThat(n.content()).isEqualTo("상품 상세페이지 참조"));
        verify(writer,never()).execute(anyLong(),any(),any(),any(),any());
    }
    @Test
    void exactDatesAndDistinctNoticeCategoriesSurviveDatabaseReloadWithoutDispatch() {
        var opened=copies.open(actor,null,null);
        var tree=json.valueToTree(opened.document()).asObject();
        var settings=tree.path("settings").asObject();settings.put("saleStartedAt","2026-10-19T22:51:37");settings.put("saleEndedAt","2099-12-31T00:00:00");
        var first=tree.path("options").get(0).asObject();first.put("noticeCategory","패션잡화");
        first.putArray("notices").addObject().put("category","패션잡화").put("name","소재").put("content","가죽");
        var second=first.deepCopy();second.put("id",UUID.randomUUID().toString());second.put("noticeCategory","가방");
        second.putArray("notices").addObject().put("category","가방").put("name","종류").put("content","상품 상세페이지 참조");tree.path("options").asArray().add(second);
        var document=json.treeToValue(tree,Document.class);
        var saved=copies.save(actor,null,new Save(opened.sessionId(),null,document,List.of()));
        var loaded=copies.open(actor,null,saved.workingCopyId());
        assertThat(loaded.document()).isEqualTo(document);
        assertThat(loaded.changes()).isEmpty();
        verify(writer,never()).execute(anyLong(),any(),any(),any(),any());
    }
    @Test
    void redactedRejectionSurvivesDatabaseAndHistoryReloadWithoutRawMessageOrExtraDispatch() {
        var opened = copies.open(actor, "123", null);
        var saved = changed(opened, "합성 수정 상품");
        when(writer.prepare(anyLong(), any(), any(), any(), anyList())).thenAnswer(i -> new Prepared(account,
                i.getArgument(2), List.of(new Step("info", Type.PRODUCT, null, "PUT", "/synthetic/info", "", "{}", "{}", "{}"),
                        new Step("approval", Type.APPROVAL, null, "PUT", "/synthetic/approval", "", null, "{}", "{}")),
                List.of(), Instant.now(), List.of(), new EditIntent(i.getArgument(3), i.getArgument(4)), 4));
        var preview = submissions.prepare(actor, new CoupangEditing.PrepareRequest(saved.workingCopyId(),
                saved.revision(), saved.sessionId(), saved.changes()));
        String key = UUID.randomUUID().toString();
        var execution = submissions.execute(actor, preview.id(), key);
        when(writer.execute(anyLong(), any(), any(), any(), any())).thenAnswer(i -> {
            ((Consumer<Step>) i.getArgument(4)).accept(i.getArgument(2));
            return new Result(State.FAILED, i.getArgument(3), "HTTP_200_REJECTED", "PRIVATE_RAW_RESPONSE", Instant.now(),
                    null, "필수 구매 옵션을 입력해주세요.\n옵션 1 · 색상: 값이 없습니다.\n연락처 010-1234-5678");
        });
        submissions.runPending();
        submissions.runPending(); // An approval waiting behind a rejected write must never be dispatched.
        assertThat(submissions.execute(actor, preview.id(), key).id()).isEqualTo(execution.id());
        var loaded = submissions.get(actor, execution.id());
        assertThat(loaded.status()).isEqualTo(CoupangSubmissions.Status.FAILED);
        assertThat(loaded.target().steps().getFirst().message()).contains("쿠팡 상세 사유:", "옵션 1 · 색상")
                .doesNotContain("PRIVATE_RAW_RESPONSE", "010-1234-5678");
        assertThat(loaded.target().steps().getLast().status()).isEqualTo(CoupangSubmissions.Status.QUEUED);
        String persisted = db.queryForObject("SELECT result_json FROM coupang_execution_step WHERE execution_id=? AND step_id='info'",
                String.class, Long.parseLong(execution.id()));
        assertThat(persisted).contains("rejectionDetails", "필수 구매 옵션").doesNotContain("PRIVATE_RAW_RESPONSE", "010-1234-5678");
        assertThat(submissions.list(actor, saved.workingCopyId(), 0, 20).items().getFirst().target().steps().getFirst().message())
                .isEqualTo(loaded.target().steps().getFirst().message());
        assertThat(copies.open(actor, null, saved.workingCopyId()).changes()).isNotEmpty();
        verify(writer, times(1)).execute(anyLong(), any(), any(), any(), any());
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


    @Test void createDraftFilterIsServerPagedAndCountsExcludeUpdateAndOtherAccounts(){
        String prefix="불러오기-"+UUID.randomUUID();
        for(int n=0;n<21;n++)changed(copies.open(actor,null,null),prefix+"-"+n);
        changed(copies.open(actor,"123",null),prefix+"-수정");
        when(writer.accountKey()).thenReturn("b".repeat(64));changed(copies.open(actor,null,null),prefix+"-다른계정");when(writer.accountKey()).thenReturn(account);
        var first=copies.list(actor,prefix,"CREATE",0,20);var second=copies.list(actor,prefix,"CREATE",1,20);
        assertThat(first.totalElements()).isEqualTo(21);assertThat(first.totalPages()).isEqualTo(2);assertThat(first.items()).hasSize(20).allMatch(row->row.mode().equals("CREATE"));
        assertThat(second.items()).hasSize(1);assertThat(first.items()).extracting(CoupangWorkingCopies.Summary::id).doesNotContain(second.items().getFirst().id());
        assertThat(copies.list(actor,prefix,"UPDATE",0,20).totalElements()).isEqualTo(1);
        assertThat(copies.list(actor,prefix,0,20).totalElements()).isEqualTo(22);
        assertThatThrownBy(()->copies.list(actor,prefix,"invalid",0,20)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    long resultCount(String job){return db.queryForObject("SELECT COUNT(*) FROM notification_event WHERE event_type='COUPANG_COLLECTION_RESULT' AND event_key LIKE ?",Long.class,"coupang-collection:"+job+":%");}
    void emptyCollections(){doReturn(new CoupangCatalog.ProductPage(List.of(),"",false)).when(catalog).products(anyLong(),eq(100),anyString(),any());}
    @Test void collectionResultsReachOnlyRequesterInboxOncePerAttemptAndReadsDoNotRepublish(){
        emptyCollections();var job=registry.collect(actor,new CoupangRegisteredProducts.Collect(List.of()));registry.runPending();
        assertThat(registry.collection(actor,job.id()).status()).isEqualTo("SUCCEEDED");assertThat(resultCount(job.id())).isEqualTo(1);
        var notice=db.queryForMap("SELECT id,title,message FROM notification_event WHERE event_key LIKE ?","coupang-collection:"+job.id()+":%");
        assertThat(notice.get("title")).isEqualTo("쿠팡 전체 가져오기 완료");assertThat(notice.get("message")).isEqualTo("대상 0 · 완료 0 · 실패 0");
        assertThat(db.queryForList("SELECT user_id FROM user_notification WHERE event_id=?",Long.class,notice.get("id"))).containsExactly(actor);
        assertThat(notifications.target(actor,((Number)notice.get("id")).longValue()).url()).isEqualTo("/coupang/products");
        registry.latestCollection(actor);registry.collection(actor,job.id());registry.runPending();assertThat(resultCount(job.id())).isEqualTo(1);
        var cancelled=registry.collect(actor,new CoupangRegisteredProducts.Collect(List.of()));registry.cancel(actor,cancelled.id());registry.cancel(actor,cancelled.id());assertThat(resultCount(cancelled.id())).isEqualTo(1);
        registry.resume(actor,cancelled.id());registry.runPending();assertThat(resultCount(cancelled.id())).isEqualTo(2);
    }
    @Test void failedInboxWriteRollsBackResultAndLeaseRecoveryNotifiesOnce(){
        emptyCollections();var job=registry.collect(actor,new CoupangRegisteredProducts.Collect(List.of()));
        var publisher=org.springframework.test.util.AopTestUtils.<cc.ataglace.molebutter.operations.api.NotificationService>getUltimateTargetObject(notifications);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("synthetic inbox failure");}).when(publisher).publish(anyString(),eq("COUPANG_COLLECTION_RESULT"),anyString(),anyString(),anyString(),anyString(),isNull(),anyCollection());
        registry.runPending();assertThat(registry.collection(actor,job.id()).status()).isEqualTo("RUNNING");assertThat(resultCount(job.id())).isZero();
        doCallRealMethod().when(publisher).publish(anyString(),eq("COUPANG_COLLECTION_RESULT"),anyString(),anyString(),anyString(),anyString(),isNull(),anyCollection());
        db.update("UPDATE coupang_product_collection SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",Long.parseLong(job.id()));
        registry.runPending();assertThat(registry.collection(actor,job.id()).status()).isEqualTo("INTERRUPTED");assertThat(resultCount(job.id())).isEqualTo(1);registry.runPending();assertThat(resultCount(job.id())).isEqualTo(1);
    }

    @Test void pausedFailedAndPartialResultsIncludeCountsAndReasonsInInbox(){
        doThrow(new CoupangFailure(CoupangFailure.Kind.RATE_LIMIT)).when(catalog).products(anyLong(),eq(100),anyString(),any());
        var paused=registry.collect(actor,new CoupangRegisteredProducts.Collect(List.of()));registry.runPending();
        assertThat(registry.collection(actor,paused.id()).status()).isEqualTo("PAUSED");
        assertThat(resultMessage(paused.id())).contains("대상 0 · 완료 0 · 실패 0","RATE_LIMIT");
        emptyCollections();registry.resume(actor,paused.id());registry.runPending();assertThat(resultCount(paused.id())).isEqualTo(2);
        doThrow(new IllegalStateException("synthetic failure")).when(catalog).products(anyLong(),eq(100),anyString(),any());
        var failed=registry.collect(actor,new CoupangRegisteredProducts.Collect(List.of()));registry.runPending();
        assertThat(registry.collection(actor,failed.id()).status()).isEqualTo("FAILED");assertThat(resultMessage(failed.id())).contains("INTERNAL").doesNotContain("synthetic failure");
        String id="900001";var summary=new CoupangCatalog.Product(id,"알림 검증 상품","브랜드","APPROVED","910001","2026-10-16T10:00:00");
        observations.record(new CoupangObservations.Read(account,actor,cc.ataglace.molebutter.common.api.BusinessIds.next(),"SUMMARY",id,null,json.writeValueAsString(summary),summary,null,null,null));
        doThrow(new CoupangFailure(CoupangFailure.Kind.UPSTREAM)).when(catalog).productForCollection(actor,id);
        var partial=registry.collect(actor,new CoupangRegisteredProducts.Collect(List.of(id)));registry.runPending();
        assertThat(registry.collection(actor,partial.id()).status()).isEqualTo("PARTIAL");assertThat(resultMessage(partial.id())).contains("대상 1 · 완료 0 · 실패 1","UPSTREAM");
        assertThat(resultCount(partial.id())).isEqualTo(1);
    }
    String resultMessage(String job){return db.queryForObject("SELECT message FROM notification_event WHERE event_key LIKE ? ORDER BY id DESC LIMIT 1",String.class,"coupang-collection:"+job+":%");}
    @Autowired CoupangOptionEdits optionEdits;
    @Test void bulkDbContextsGroupOptionsAndReuseUnsavedMappingWithoutExternalReadsOrIntents() {
        String seller="812";var p=new CoupangCatalog.Product(seller,"DB 상품",null,"승인완료","999",null);
        var items=new java.util.ArrayList<CoupangCatalog.ProductOption>();var raw=json.createObjectNode();raw.put("sellerProductId",seller);raw.put("sellerProductName","DB 상품");raw.put("statusName","승인완료");raw.put("vendorId","synthetic");var rawItems=raw.putArray("items");
        for(int n=0;n<2;n++){
            String item="8120"+n,vendor="8121"+n;rawItems.addObject().put("sellerProductItemId",item).put("vendorItemId",vendor).put("itemName","옵션").put("externalVendorSku","SKU").put("salePrice",120000).put("maximumBuyCount",9);
            items.add(new CoupangCatalog.ProductOption(item,vendor,"옵션",null,null,List.of(),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku","SKU")),List.of()));
        }
        var detail=new CoupangCatalog.ProductDetail(p,null,null,null,null,null,null,null,items,List.of(),List.of());
        observations.record(new CoupangObservations.Read(account,actor,cc.ataglace.molebutter.common.api.BusinessIds.next(),"DETAIL",seller,null,json.writeValueAsString(raw),p,detail,null,null));
        for(var item:items){var c=new CoupangCatalog.CurrentInventory(item.vendorItemId(),120000L,9L,true);observations.record(new CoupangObservations.Read(account,actor,cc.ataglace.molebutter.common.api.BusinessIds.next(),"INVENTORY",seller,item.vendorItemId(),json.writeValueAsString(c),null,null,c,null));}
        when(quickSources.get(actor,"42")).thenReturn(new CoupangQuickEditSources.Source("42","SKU",90000L,null,true,List.of(),"bulk-fp"));
        long count=db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class);clearInvocations(editor,writer,quickSources);
        var request=new CoupangOptionEdits.BulkOpen("PRICE",List.of(new CoupangOptionEdits.OptionRequest("81210","42"),new CoupangOptionEdits.OptionRequest("81211","42")));
        var initial=optionEdits.stored(actor,seller,request);assertThat(initial).hasSize(2).allMatch(c->c.currentValue()==120000);assertThat(initial.getFirst().entry().sessionId()).isEqualTo(initial.get(1).entry().sessionId());
        verify(quickSources,times(1)).get(actor,"42");verify(editor,never()).edit(eq(actor),eq(seller));verify(writer,never()).prepare(anyLong(),any(),any(),any(),anyList());verify(writer,never()).execute(anyLong(),any(),any(),any(),any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class)).isEqualTo(count);
        var fresh=optionEdits.stored(actor,seller,new CoupangOptionEdits.BulkOpen("PRICE",request.options(),initial.getFirst().entry().sessionId()));
        assertThat(fresh).extracting(CoupangOptionEdits.Context::optionId).containsExactlyElementsOf(initial.stream().map(CoupangOptionEdits.Context::optionId).toList());
        // Persist another intent, then prepare only one selected price through the DB route.
        var entry=fresh.getFirst().entry();var name=new CoupangEditing.Change("basic.sellerProductName",null,"다른 수정 유지");
        copies.save(actor,null,new Save(entry.sessionId(),null,CoupangInputs.apply(json,entry.document(),List.of(name)),List.of(name)));
        var current=optionEdits.stored(actor,seller,request).getFirst();
        when(writer.prepareStoredOption(anyLong(),any(),any(),any(),anyList())).thenAnswer(i->new Prepared(account,i.getArgument(2),List.of(new Step("bulk-price",Type.PRICE,current.optionId(),"PUT","/synthetic/price","",null,"{\"salePrice\":120000}","{\"salePrice\":130000}")),List.of(new Change("salePrice","120000","130000")),Instant.now(),List.of(),new EditIntent(i.getArgument(3),i.getArgument(4)),4));
        var result=optionEdits.prepareStored(actor,seller,"81210",new CoupangOptionEdits.Prepare("PRICE",130000L,current.entry().sessionId(),current.entry().revision(),current.registry().state().version(),"42","bulk-fp",current.settings().revision(),90000L));
        assertThat(result.preview().executable()).isTrue();assertThat(result.entry().changes()).contains(name).hasSize(2);verify(writer,never()).prepare(anyLong(),any(),any(),any(),anyList());verify(editor,never()).edit(eq(actor),eq(seller));
        when(writer.execute(anyLong(),any(),any(),any(),any())).thenAnswer(i->{((Consumer<Step>)i.getArgument(4)).accept(i.getArgument(2));return new Result(State.CONFIRMED,i.getArgument(3),"VERIFIED",null,Instant.now());});
        String key=UUID.randomUUID().toString();var execution=submissions.execute(actor,result.preview().id(),key);assertThat(submissions.execute(actor,result.preview().id(),key).id()).isEqualTo(execution.id());submissions.runPending();
        assertThat(submissions.get(actor,execution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);verify(writer,times(1)).execute(anyLong(),any(),any(),any(),any());
        assertThat(optionEdits.stored(actor,seller,request).getFirst().entry().changes()).containsExactly(name);
        assertThatThrownBy(()->optionEdits.stored(actor,seller,new CoupangOptionEdits.BulkOpen("PRICE",List.of(request.options().getFirst(),request.options().getFirst())))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }

    @Test void quickEditPreparesOnlySelectedIntentAndPreservesUnrelatedChangesAfterSuccess() {
        doReturn(product("789", List.of())).when(editor).edit(actor,"789");
        var entry=copies.open(actor,"789",null);String option=entry.document().options().getFirst().id();
        var price=new CoupangEditing.Change("options.registration.salePrice",option,"147000");
        var name=new CoupangEditing.Change("basic.sellerProductName",null,"다른 수정 유지");
        var saved=copies.save(actor,null,new Save(entry.sessionId(),null,CoupangInputs.apply(json,entry.document(),List.of(price,name)),List.of(price,name)));
        when(quickSources.get(actor,"42")).thenReturn(new CoupangQuickEditSources.Source("42","HJWA908BK",115170L,null,true,List.of(),"fingerprint"));
        when(writer.prepare(anyLong(),any(),any(),any(),anyList())).thenAnswer(i->new Prepared(account,i.getArgument(2),List.of(new Step("price",Type.PRICE,option,"PUT","/synthetic/price","",null,"{}","{}")),List.of(),Instant.now(),List.of(),new EditIntent(i.getArgument(3),i.getArgument(4)),4));
        var basis=new CoupangOptionEdits.Basis("42","fingerprint",pricing.get(actor).revision());
        clearInvocations(imageInspection);
        var preview=submissions.prepareSelected(actor,new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),List.of(price)),basis);
        verifyNoInteractions(imageInspection);
        assertThat(preview.executable()).isTrue();
        verify(writer).prepare(eq(actor),any(),any(),any(),eq(List.of(price)));
        // A settings edit after preview prevents execution before any external dispatch.
        var settings=pricing.get(actor);pricing.save(actor,new CoupangPricing.Save(settings.revision(),4001L,settings.commissionPercent(),settings.marginPercent()));
        var oldPreview=preview;assertThatThrownBy(()->submissions.execute(actor,oldPreview.id(),UUID.randomUUID().toString())).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        var newBasis=new CoupangOptionEdits.Basis("42","fingerprint",pricing.get(actor).revision());
        preview=submissions.prepareSelected(actor,new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),List.of(price)),newBasis);
        var execution=submissions.execute(actor,preview.id(),UUID.randomUUID().toString());
        when(writer.execute(anyLong(),any(),any(),any(),any())).thenAnswer(i->{((Consumer<Step>)i.getArgument(4)).accept(i.getArgument(2));return new Result(State.CONFIRMED,i.getArgument(3),"VERIFIED",null,Instant.now());});
        submissions.runPending();
        assertThat(submissions.get(actor,execution.id()).status()).isEqualTo(CoupangSubmissions.Status.SUCCEEDED);
        assertThat(copies.open(actor,null,saved.workingCopyId()).changes()).containsExactly(name);
        assertThatThrownBy(()->submissions.prepareSelected(actor,new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),List.of(name)),newBasis)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }

    @Test void quickEditRejectsChangedSupplierAtExecutionAndBeforeDispatch(){
        doReturn(product("790",List.of())).when(editor).edit(actor,"790");var entry=copies.open(actor,"790",null);String option=entry.document().options().getFirst().id();
        var price=new CoupangEditing.Change("options.registration.salePrice",option,"147000");var saved=copies.save(actor,null,new Save(entry.sessionId(),null,CoupangInputs.apply(json,entry.document(),List.of(price)),List.of(price)));
        var original=new CoupangQuickEditSources.Source("42","HJWA908BK",115170L,null,true,List.of(),"old");var changed=new CoupangQuickEditSources.Source("42","HJWA908BK",116000L,null,false,List.of(),"new");when(quickSources.get(actor,"42")).thenReturn(original);
        when(writer.prepare(anyLong(),any(),any(),any(),anyList())).thenAnswer(i->new Prepared(account,i.getArgument(2),List.of(new Step("price",Type.PRICE,option,"PUT","/synthetic/price","",null,"{}","{}")),List.of(),Instant.now(),List.of(),new EditIntent(i.getArgument(3),i.getArgument(4)),4));
        var preview=submissions.prepareSelected(actor,new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),List.of(price)),new CoupangOptionEdits.Basis("42","old",pricing.get(actor).revision()));
        when(quickSources.get(actor,"42")).thenReturn(changed);assertThatThrownBy(()->submissions.execute(actor,preview.id(),UUID.randomUUID().toString())).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        when(quickSources.get(actor,"42")).thenReturn(original);var execution=submissions.execute(actor,preview.id(),UUID.randomUUID().toString());when(quickSources.get(actor,"42")).thenReturn(changed);
        var dispatched=new java.util.concurrent.atomic.AtomicInteger();when(writer.execute(anyLong(),any(),any(),any(),any())).thenAnswer(i->{try{((Consumer<Step>)i.getArgument(4)).accept(i.getArgument(2));}catch(cc.ataglace.molebutter.common.api.InputValidationFailure prevented){return new Result(State.FAILED,i.getArgument(3),"PREFLIGHT",prevented.getMessage(),Instant.now());}dispatched.incrementAndGet();return new Result(State.CONFIRMED,i.getArgument(3),"VERIFIED",null,Instant.now());});
        submissions.runPending();assertThat(dispatched.get()).isZero();assertThat(submissions.get(actor,execution.id()).status()).isEqualTo(CoupangSubmissions.Status.FAILED);assertThat(copies.open(actor,null,saved.workingCopyId()).changes()).containsExactly(price);
    }

}
