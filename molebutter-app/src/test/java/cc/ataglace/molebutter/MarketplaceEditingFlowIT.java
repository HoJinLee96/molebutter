package cc.ataglace.molebutter;

import cc.ataglace.molebutter.media.api.ImageAssets;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissions.*;
import cc.ataglace.molebutter.marketplace.internal.*;
import cc.ataglace.molebutter.identity.internal.*;
import cc.ataglace.molebutter.identity.api.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "product.refresh.worker-enabled=false","marketplace.submissions.worker-enabled=false","spring.config.import=classpath:bootstrap-admin-test.properties",
 "spring.datasource.username=test_app","spring.datasource.password=isolated-test-app-password",
 "spring.flyway.user=test_migrator","spring.flyway.password=isolated-test-migration-password",
 "spring.data.redis.host=127.0.0.1","spring.data.redis.password=","mail.provider=test",
 "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes","auth.cookie.secure=false"
})
@ActiveProfiles("bootstrap-admin")
@Import({AuthenticationFlowIT.MailConfiguration.class,MarketplaceSubmissionTestGateway.Configuration.class})
class MarketplaceEditingFlowIT {
 static final Path directory=createDirectory();
 static Path createDirectory(){try{return Files.createTempDirectory("molebutter-editing-it-");}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);r.add("marketplace.assets.directory",()->directory.toString());r.add("marketplace.coupang.vendor-id",()->"test-vendor");r.add("marketplace.coupang.access-key",()->"");r.add("marketplace.coupang.secret-key",()->"");}
 @org.springframework.test.context.bean.override.mockito.MockitoBean CoupangBrands selectedBrands;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DefaultMarketplaceDrafts draftService;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CoupangEditor testCatalog;
 @Autowired MarketplaceDrafts drafts;@Autowired MarketplaceSubmissions submissions;@Autowired DefaultMarketplaceSubmissions worker;
 @Autowired MarketplaceEditing editing;
 @Autowired MarketplaceSubmissionTestGateway gateway;@Autowired ImageAssets assets;@Autowired ImageAssets assetStore;
 @Autowired JdbcTemplate db;@Autowired UserRepository users;@Autowired DefaultAuthTokenService tokens;@Autowired ObjectMapper json;@LocalServerPort int port;
 long admin,staff,viewer,otherAdmin;
 @BeforeEach void prepare(){clear();gateway.reset();admin=users.findByEmail("admin@example.com").orElseThrow().getId();staff=account(UserRole.PRODUCT);viewer=account(UserRole.VIEWER);otherAdmin=account(UserRole.ADMIN);
  org.mockito.Mockito.doReturn(new Validation(true,List.of(),List.of())).when(draftService).validate(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(Document.class));
  org.mockito.Mockito.doReturn(currentProduct(1000L,9L)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
 }
 @AfterEach void cleanup(){clear();for(long actor:List.of(staff,viewer,otherAdmin))if(actor>0){db.update("DELETE FROM user_notification WHERE user_id=?",actor);users.deleteById(actor);}}
 @AfterAll static void cleanFiles()throws Exception{try(var s=Files.list(directory)){for(var p:s.toList())Files.deleteIfExists(p);}Files.deleteIfExists(directory);}
 void clear(){for(String table:List.of("marketplace_execution_attempt","marketplace_execution_step","marketplace_execution_target","marketplace_execution","marketplace_execution_asset","marketplace_submission_preview","marketplace_edit_session","marketplace_listing_mapping","marketplace_submission_account","marketplace_asset_publication","marketplace_draft_asset","marketplace_draft","marketplace_asset"))db.update("DELETE FROM "+table);}
 long account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@submission.test").name("전송 테스트").passwordHash("unused").role(role).status(UserStatus.ACTIVE).build()).getId();}

 CoupangEditor.EditorDocument currentProduct(Long price,Long quantity){return new CoupangEditor.EditorDocument(new CoupangEditor.Basic("9001","9101","조회 상품","노출 상품","제품명","브랜드","","123","승인완료"),new CoupangEditor.Limits(true,true,true),List.of(new CoupangEditor.EditOption("7001","8001","블랙",new CoupangCatalog.CurrentInventory("8001",price,quantity,true),null,true,List.of(),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku","SKU-1"),new CoupangCatalog.Field("salePrice","1000"),new CoupangCatalog.Field("maximumBuyCount","9")),List.of())),List.of(),List.of(),List.of());}
 Document linked(){return drafts.importCoupang(admin,"9001",null);}
 Document newInput(){return json.readValue("""
  {"common":{"productCode":"NEW-1","name":"신규 참조"},"options":[{"id":"10000000-1000-4000-8000-000000000001","name":"블랙","sku":"SKU-1","price":"1000","quantity":"9","attributes":[]}],"stockMode":"OPTION","productQuantity":"","services":[],"media":{"images":[],"contents":[]},"delivery":{},"selectedMarkets":["COUPANG"],"markets":{"COUPANG":{"categoryCode":"123"}}}
  """,Document.class);}
 MarketplaceEditing.Target coupang(MarketplaceEditing.Session s){return s.targets().stream().filter(t->t.market().equals("COUPANG")).findFirst().orElseThrow();}
 MarketplaceEditing.PrepareRequest request(MarketplaceEditing.Session s,MarketplaceEditing.Change... changes){return new MarketplaceEditing.PrepareRequest(s.draftId(),s.revision(),s.id(),false,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(changes))));}
 Document changedName(Document d,String name){var c=d.common();return new Document(d.id(),d.revision(),new Common(c.productCode(),name,c.productName(),c.brand(),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly()),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());}
 void observe(Long price,Long quantity){org.mockito.Mockito.doReturn(currentProduct(price,quantity)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));}
 @Test void latestExternalValuesStaySeparateFromSavedCommonReference(){
  var d=linked();observe(2200L,4L);var session=editing.start(admin,d.id());var t=coupang(session);
  assertThat(t.mode()).isEqualTo("UPDATE");assertThat(t.status()).isEqualTo("READY");assertThat(t.observedAt()).isNotBlank();
  assertThat(t.document().options().getFirst().id()).isEqualTo(d.options().getFirst().id());assertThat(t.document().options().getFirst().price()).isEqualTo("2200");assertThat(t.document().options().getFirst().quantity()).isEqualTo("4");
  assertThat(drafts.get(admin,d.id()).options().getFirst().price()).isEqualTo("1000");assertThat(drafts.get(admin,d.id()).options().getFirst().quantity()).isEqualTo("9");assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void newTargetUsesSavedCommonDefaultsWithoutExternalObservation(){
  var d=drafts.create(admin,newInput());var s=editing.start(admin,d.id());assertThat(coupang(s).mode()).isEqualTo("CREATE");assertThat(coupang(s).observedAt()).isNull();
  var option=d.options().getFirst();var changed=new Document(d.id(),d.revision(),d.common(),List.of(new Option(option.id(),option.name(),option.sku(),"2500","0",option.attributes())),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());var saved=editing.saveReference(admin,s.id(),changed);
  assertThat(saved.revision()).isEqualTo(s.revision()+1);assertThat(coupang(saved).document().options().getFirst().price()).isEqualTo("2500");assertThat(coupang(saved).document().options().getFirst().quantity()).isEqualTo("0");org.mockito.Mockito.verify(testCatalog,org.mockito.Mockito.never()).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void minimalCreateInitializesOptionConfigurationBeforeSelectedAttributePatch(){
  var d=drafts.create(admin,newInput());assertThat(d.markets().get(Market.COUPANG).coupang()).isNull();var s=editing.start(admin,d.id());var cp=coupang(s).document().markets().get(Market.COUPANG).coupang();assertThat(cp.options()).hasSize(1);assertThat(cp.options().getFirst().optionId()).isEqualTo(d.options().getFirst().id());assertThat(cp.options().getFirst().registration()).extracting(CoupangCatalog.Field::name).contains("unitCount","maximumBuyForPerson","outboundShippingTimeDay");
  var change=new MarketplaceEditing.Change("markets.COUPANG.coupang.options.attributes",d.options().getFirst().id(),List.of(new CoupangCatalog.Attribute("색상","블랙","EXPOSED")));var p=submissions.prepare(admin,request(s,change));assertThat(p.executable()).isTrue();assertThat(p.targets().getFirst().steps()).extracting(PlannedStep::type).containsExactly(StepType.CREATE);assertThat(gateway.selectedPaths()).containsExactly(change.path());assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void changedOptionIdentityIsAnObservationFailureRatherThanAReplacementMapping(){
  var d=linked();var original=currentProduct(1000L,9L);var o=original.options().getFirst();var changed=new CoupangEditor.EditorDocument(original.basic(),original.limits(),List.of(new CoupangEditor.EditOption("other-item","8001",o.itemName(),o.current(),o.currentError(),o.separateCurrentChanges(),o.attributes(),o.images(),o.contents(),o.notices(),o.registration(),o.certifications())),original.delivery(),original.settings(),original.documents());
  org.mockito.Mockito.doReturn(changed).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));var target=coupang(editing.start(admin,d.id()));assertThat(target.mode()).isEqualTo("UPDATE");assertThat(target.status()).isEqualTo("FAILED");assertThat(target.document()).isNull();assertThat(drafts.get(admin,d.id()).markets().get(Market.COUPANG).coupang().options().getFirst().sellerProductItemId()).isEqualTo("7001");assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void missingCurrentValuesRemainUnknownRatherThanZero(){
  var d=linked();observe(null,null);var target=coupang(editing.start(admin,d.id()));assertThat(target.status()).isEqualTo("READY");
  assertThat(target.document().options().getFirst().price()).isEmpty();assertThat(target.document().options().getFirst().quantity()).isEmpty();assertThat(gateway.dispatches()).isEmpty();
  observe(0L,null);var zeroPrice=coupang(editing.start(admin,d.id()));assertThat(zeroPrice.document().options().getFirst().price()).isEqualTo("0");assertThat(zeroPrice.document().options().getFirst().quantity()).isEmpty();
  observe(null,0L);var zeroQuantity=coupang(editing.start(admin,d.id()));assertThat(zeroQuantity.document().options().getFirst().price()).isEmpty();assertThat(zeroQuantity.document().options().getFirst().quantity()).isEqualTo("0");
 }
 @Test void failedObservationCannotBecomeCreateOrExecutablePreview(){
  var d=linked();org.mockito.Mockito.doThrow(new MarketplaceFailure(MarketplaceFailure.Kind.TIMEOUT)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
  var s=editing.start(admin,d.id());assertThat(coupang(s).mode()).isEqualTo("UPDATE");assertThat(coupang(s).status()).isEqualTo("FAILED");assertThat(coupang(s).document()).isNull();
  var p=submissions.prepare(admin,request(s,new MarketplaceEditing.Change("common.name",null,"변경 이름")));assertThat(p.executable()).isFalse();assertThat(p.targets().getFirst().issues()).isNotEmpty();assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void refreshRevokesPriorSessionAndItsPreviewWithoutExtendingExpiry(){
  var d=linked();var s=editing.start(admin,d.id());var p=submissions.prepare(admin,request(s,new MarketplaceEditing.Change("common.name",null,"변경 이름")));observe(3300L,2L);
  var refreshed=editing.refresh(admin,s.id(),"COUPANG");assertThat(refreshed.id()).isNotEqualTo(s.id());assertThat(refreshed.expiresAt()).isEqualTo(s.expiresAt());assertThat(coupang(refreshed).document().options().getFirst().price()).isEqualTo("3300");
  assertThatThrownBy(()->submissions.execute(admin,p.id(),UUID.randomUUID().toString())).isInstanceOf(MarketplaceEditingFailure.class);assertThatThrownBy(()->submissions.prepare(admin,request(s,new MarketplaceEditing.Change("common.name",null,"다른 변경")))).isInstanceOf(MarketplaceEditingFailure.class);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void saveReferenceUpdatesRevisionWithoutChangingObservedDataOrWritingRemotely()throws Exception{
  var d=linked();observe(2200L,4L);var s=editing.start(admin,d.id());var browser=new Browser(admin);
  var response=browser.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/reference",changedName(d,"공통 참조 변경"),true);assertThat(response.statusCode()).isEqualTo(200);var saved=json.treeToValue(json.readTree(response.body()).path("data"),MarketplaceEditing.Session.class);
  assertThat(saved.id()).isEqualTo(s.id());assertThat(saved.revision()).isEqualTo(s.revision()+1);assertThat(saved.targets()).isEqualTo(s.targets());assertThat(drafts.get(admin,d.id()).common().name()).isEqualTo("공통 참조 변경");assertThat(coupang(saved).document().common().name()).isEqualTo("조회 상품");assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void concurrentReferenceSaveAndQueueHaveSerializableOutcomesWithoutServerError()throws Exception{
  var d=linked();var s=editing.start(admin,d.id());var p=submissions.prepare(admin,request(s,new MarketplaceEditing.Change("common.name",null,"전송 변경")));var browser=new Browser(admin);browser.security();var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);List<HttpResponse<String>> responses;
  try{var save=pool.submit(()->{ready.countDown();go.await();return browser.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/reference",changedName(d,"동시 참조"),true);});var queue=pool.submit(()->{ready.countDown();go.await();return browser.send("POST","/api/marketplaces/submissions/"+p.id()+"/execute",Map.of("idempotencyKey",UUID.randomUUID().toString()),true);});assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();responses=List.of(save.get(10,java.util.concurrent.TimeUnit.SECONDS),queue.get(10,java.util.concurrent.TimeUnit.SECONDS));}finally{go.countDown();pool.shutdownNow();}
  assertThat(responses).extracting(HttpResponse::statusCode).allMatch(status->status==200||status==409).contains(200);assertThat(responses.getFirst().statusCode()).isEqualTo(200);assertThat(gateway.dispatches()).isEmpty();
  if(responses.get(1).statusCode()==200){var e=json.treeToValue(json.readTree(responses.get(1).body()).path("data"),Execution.class);assertThat(e.revision()).isEqualTo(d.revision());var intent=json.readTree(db.queryForObject("SELECT prepared_json FROM marketplace_submission_preview WHERE id=?",String.class,Long.parseLong(p.id()))).path("editIntent").path("changes").get(0);assertThat(intent.path("value").asText()).isEqualTo("전송 변경");}
  assertThat(drafts.get(admin,d.id()).common().name()).isEqualTo("동시 참조");
 }
 @Test void referenceSaveCompletingBeforeQueueReturnsConflictAndNoExternalWork()throws Exception{
  var d=linked();var s=editing.start(admin,d.id());var p=submissions.prepare(admin,request(s,new MarketplaceEditing.Change("common.name",null,"전송 변경")));var browser=new Browser(admin);assertThat(browser.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/reference",changedName(d,"참조 먼저 저장"),true).statusCode()).isEqualTo(200);
  var response=browser.send("POST","/api/marketplaces/submissions/"+p.id()+"/execute",Map.of("idempotencyKey",UUID.randomUUID().toString()),true);assertThat(response.statusCode()).isEqualTo(409);assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_execution",Long.class)).isZero();assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void explicitPriceSelectionUsesObservedBeforeAndLeavesStockUntouched(){
  var d=linked();observe(2200L,4L);var s=editing.start(admin,d.id());String option=d.options().getFirst().id();
  var p=submissions.prepare(admin,request(s,new MarketplaceEditing.Change("options.price",option,"2500")));assertThat(p.executable()).isTrue();assertThat(p.targets().getFirst().steps()).extracting(PlannedStep::type).containsExactly(StepType.PRICE);assertThat(p.targets().getFirst().changes()).extracting(Change::before).containsExactly("2200");assertThat(gateway.selectedPaths()).containsExactly("options.price");
  var e=submissions.execute(admin,p.id(),UUID.randomUUID().toString());worker.runPending();assertThat(gateway.dispatches()).containsExactly("PRICE");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);assertThat(drafts.get(admin,d.id()).options().getFirst().quantity()).isEqualTo("9");
 }
 @Test void noOpSelectionsCannotQueueAnExternalWrite(){
  var d=linked();var s=editing.start(admin,d.id());var empty=submissions.prepare(admin,request(s));assertThat(empty.executable()).isFalse();
  var same=submissions.prepare(admin,request(s,new MarketplaceEditing.Change("options.price",d.options().getFirst().id(),"1000")));assertThat(same.executable()).isFalse();assertThat(same.targets().getFirst().steps()).isEmpty();assertThatThrownBy(()->submissions.execute(admin,same.id(),UUID.randomUUID().toString())).isInstanceOf(MarketplaceSubmissionFailure.class);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void sessionExpiryAndChangedDraftRevisionRequireFreshSession(){
  var d=linked();var s=editing.start(admin,d.id());var expired=new MarketplaceEditing.Session(s.id(),s.draftId(),s.revision(),java.time.Instant.now().minusSeconds(1).toString(),s.targets());db.update("UPDATE marketplace_edit_session SET session_json=?,expires_at=NOW()-INTERVAL 1 MINUTE WHERE id=?",json.writeValueAsString(expired),Long.parseLong(s.id()));
  assertThatThrownBy(()->editing.refresh(admin,s.id(),"COUPANG")).isInstanceOf(MarketplaceEditingFailure.class).extracting("kind").isEqualTo(MarketplaceEditingFailure.Kind.EXPIRED);
  var fresh=editing.start(admin,d.id());drafts.save(admin,d.id(),d.revision(),changedName(d,"다른 화면 변경"));assertThatThrownBy(()->submissions.prepare(admin,request(fresh,new MarketplaceEditing.Change("common.name",null,"늦은 변경")))).isInstanceOf(MarketplaceEditingFailure.class).extracting("kind").isEqualTo(MarketplaceEditingFailure.Kind.CONFLICT);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void sessionsAreActorBoundAndAdminCsrfLatestStatusAreChecked()throws Exception{
  var d=linked();var s=editing.start(admin,d.id());assertThatThrownBy(()->editing.refresh(otherAdmin,s.id(),"COUPANG")).isInstanceOf(MarketplaceEditingFailure.class).extracting("kind").isEqualTo(MarketplaceEditingFailure.Kind.NOT_FOUND);
  assertThat(new Browser(otherAdmin).send("POST","/api/marketplaces/edit-sessions/"+s.id()+"/refresh",Map.of("market","COUPANG"),true).statusCode()).isEqualTo(404);
  for(long actor:List.of(staff,viewer)){var b=new Browser(actor);assertThat(b.send("POST","/api/marketplaces/drafts/"+d.id()+"/edit-sessions",null,true).statusCode()).isEqualTo(403);assertThat(b.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/reference",d,true).statusCode()).isEqualTo(403);assertThat(b.send("POST","/api/marketplaces/edit-sessions/"+s.id()+"/refresh",Map.of("market","COUPANG"),true).statusCode()).isEqualTo(403);}
  var b=new Browser(admin);assertThat(b.send("POST","/api/marketplaces/drafts/"+d.id()+"/edit-sessions",null,false).statusCode()).isEqualTo(403);db.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",admin);try{assertThat(b.send("POST","/api/marketplaces/edit-sessions/"+s.id()+"/refresh",Map.of("market","COUPANG"),true).statusCode()).isEqualTo(401);}finally{db.update("UPDATE `user` SET user_status='ACTIVE' WHERE id=?",admin);}
 }
 @Test void legacyPrepareWithoutSessionAndUnsupportedMarketRemainNonExecutable()throws Exception{
  var d=linked();var b=new Browser(admin);assertThat(b.send("POST","/api/marketplaces/submissions/prepare",Map.of("draftId",d.id(),"revision",d.revision()),true).statusCode()).isEqualTo(400);
  var original=drafts.get(admin,d.id());var selected=new Document(original.id(),original.revision(),original.common(),original.options(),original.stockMode(),original.productQuantity(),original.services(),original.media(),original.delivery(),List.of(Market.COUPANG,Market.NAVER),original.markets());selected=drafts.save(admin,selected.id(),selected.revision(),selected);var s=editing.start(admin,selected.id());
  assertThat(s.targets()).anyMatch(t->t.market().equals("NAVER")&&t.status().equals("UNSUPPORTED"));var p=submissions.prepare(admin,new MarketplaceEditing.PrepareRequest(s.draftId(),s.revision(),s.id(),false,List.of(new MarketplaceEditing.TargetChanges("NAVER",List.of()))));assertThat(p.executable()).isFalse();assertThat(p.targets().getFirst().issues()).isNotEmpty();
  assertThat(gateway.dispatches()).isEmpty();
 }
 class Browser {
  final HttpClient client=HttpClient.newHttpClient();final String token;String csrf,cookie;
  Browser(long actor){token=tokens.issueOnSignin(users.findById(actor).orElseThrow()).accessToken();}
  void security()throws Exception{if(csrf!=null)return;var r=get("/api/auth/csrf");csrf=json.readTree(r.body()).path("data").path("token").asText();cookie=r.headers().allValues("set-cookie").stream().map(s->s.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));}
  HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,false);}
  HttpResponse<String> send(String method,String path,Object body,boolean secure)throws Exception{if(secure)security();var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("X-Operation-Id",UUID.randomUUID().toString()).header("Content-Type","application/json");if(secure)b.header("X-XSRF-TOKEN",csrf).header("Cookie",cookie);return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
 }
}
