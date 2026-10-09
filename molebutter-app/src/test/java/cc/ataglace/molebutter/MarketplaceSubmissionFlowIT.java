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
class MarketplaceSubmissionFlowIT {
 static final Path directory=createDirectory();
 static Path createDirectory(){try{return Files.createTempDirectory("molebutter-submissions-it-");}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);r.add("marketplace.assets.directory",()->directory.toString());r.add("marketplace.coupang.vendor-id",()->"test-vendor");r.add("marketplace.coupang.access-key",()->"");r.add("marketplace.coupang.secret-key",()->"");}
 @org.springframework.test.context.bean.override.mockito.MockitoBean CoupangBrands selectedBrands;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DefaultMarketplaceDrafts draftService;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CoupangEditor testCatalog;
 @Autowired MarketplaceDrafts drafts;@Autowired MarketplaceSubmissions submissions;@Autowired DefaultMarketplaceSubmissions worker;
 @Autowired MarketplaceEditing editing;
 @Autowired CoupangProductRegistrations registrations;
 @Autowired MarketplaceSubmissionTestGateway gateway;@Autowired ImageAssets assets;@Autowired ImageAssets assetStore;
 @Autowired JdbcTemplate db;@Autowired UserRepository users;@Autowired DefaultAuthTokenService tokens;@Autowired ObjectMapper json;@LocalServerPort int port;
 long admin,staff,viewer;
 @BeforeEach void prepare(){clear();gateway.reset();admin=users.findByEmail("admin@example.com").orElseThrow().getId();staff=account(UserRole.PRODUCT);viewer=account(UserRole.VIEWER);
  org.mockito.Mockito.doReturn(new Validation(true,List.of(),List.of())).when(draftService).validate(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(Document.class));
  org.mockito.Mockito.doReturn(currentProduct()).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
 }
 @AfterEach void cleanup(){clear();for(long actor:List.of(staff,viewer))if(actor>0){db.update("DELETE FROM user_notification WHERE user_id=?",actor);users.deleteById(actor);}}
 @AfterAll static void cleanFiles()throws Exception{try(var s=Files.list(directory)){for(var p:s.toList())Files.deleteIfExists(p);}Files.deleteIfExists(directory);}
 void clear(){for(String table:List.of("marketplace_execution_attempt","marketplace_execution_step","marketplace_execution_target","marketplace_execution","marketplace_execution_asset","marketplace_submission_preview","marketplace_edit_session","marketplace_listing_mapping","marketplace_submission_account","marketplace_asset_publication","marketplace_draft_asset","marketplace_draft","marketplace_asset"))db.update("DELETE FROM "+table);}
 long account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@submission.test").name("전송 테스트").passwordHash("unused").role(role).status(UserStatus.ACTIVE).build()).getId();}
 Document blank(){return json.readValue("""
  {"common":{"productCode":"SUBMIT-1","name":"테스트 상품"},"options":[{"id":"10000000-1000-4000-8000-000000000001","name":"블랙","sku":"SKU-1","price":"1000","quantity":"9","attributes":[]}],"stockMode":"OPTION","productQuantity":"","services":[],"media":{"images":[],"contents":[]},"delivery":{},"selectedMarkets":["COUPANG"],"markets":{"COUPANG":{"categoryCode":"123"}}}
  """,Document.class);}
 Document changed(Document d){var c=d.common();return new Document(d.id(),d.revision(),new Common(c.productCode(),"수정된 상품",c.productName(),c.brand(),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly()),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());}
 CoupangEditor.EditorDocument currentProduct(){return new CoupangEditor.EditorDocument(new CoupangEditor.Basic("9001","9101","테스트 상품","테스트 상품","제품명","브랜드","","123","승인완료"),new CoupangEditor.Limits(true,true,true),List.of(new CoupangEditor.EditOption("7001","8001","블랙",new CoupangCatalog.CurrentInventory("8001",1000L,9L,true),null,true,List.of(),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("salePrice","1000"),new CoupangCatalog.Field("maximumBuyCount","9")),List.of())),List.of(),List.of(),List.of());}
 MarketplaceEditing.PrepareRequest request(Document d){var session=editing.start(admin,d.id());return new MarketplaceEditing.PrepareRequest(d.id(),d.revision(),session.id(),false,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("common.name",null,d.common().name())))));}
 Preview preview(Document d){return submissions.prepare(admin,request(d));}
 Execution start(Preview p){return submissions.execute(admin,p.id(),UUID.randomUUID().toString());}
 CoupangProductRegistrations.Input registrationInput(){return new CoupangProductRegistrations.Input(Map.of("sellerProductName","신규 테스트","brand","브랜드","displayCategoryCode","123"),List.of(new CoupangProductRegistrations.Option("10000000-1000-4000-8000-000000000001",List.of(new CoupangCatalog.Attribute("색상","블랙","EXPOSED")),List.of(),List.of(),List.of(),Map.of("salePrice","0","maximumBuyCount","0","modelNo","MODEL-TEST"),List.of(),"")),Map.of(),Map.of("brandId","KR-TEST"),List.of());}
 @Test void dedicatedRegistrationTemporarySaveReentryRevisionAndOwnership(){
  var d=registrations.create(admin,registrationInput());assertThat(gateway.dispatches()).isEmpty();
  assertThat(registrations.get(admin,d.id()).input().options().getFirst().id()).isEqualTo("10000000-1000-4000-8000-000000000001");
  assertThat(drafts.list(admin,"",0,20).items()).anySatisfy(v->{assertThat(v.id()).isEqualTo(d.id());assertThat(v.editorKind()).isEqualTo("COUPANG_REGISTRATION");});
  var saved=registrations.save(admin,d.id(),new CoupangProductRegistrations.Save(d.revision(),registrationInput()));assertThat(saved.revision()).isGreaterThan(d.revision());
  assertThatThrownBy(()->registrations.save(admin,d.id(),new CoupangProductRegistrations.Save(d.revision(),registrationInput()))).isInstanceOf(MarketplaceDraftFailure.class);
  assertThatThrownBy(()->registrations.get(staff,d.id())).isInstanceOf(cc.ataglace.molebutter.common.api.BusinessException.class);
  db.update("UPDATE `user` SET user_role='ADMIN' WHERE id=?",staff);assertThatThrownBy(()->registrations.get(staff,d.id())).isInstanceOf(MarketplaceDraftFailure.class);assertThatThrownBy(()->editing.start(staff,d.id())).isInstanceOf(MarketplaceEditingFailure.class);
  assertThatThrownBy(()->drafts.save(admin,d.id(),saved.revision(),blank())).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
  db.update("UPDATE marketplace_draft SET registration_account='different' WHERE id=?",Long.parseLong(d.id()));
  assertThatThrownBy(()->registrations.get(admin,d.id())).isInstanceOf(MarketplaceDraftFailure.class);assertThatThrownBy(()->editing.start(admin,d.id())).isInstanceOf(MarketplaceEditingFailure.class);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void dedicatedRegistrationCreateApprovalPendingAndUnknownNeverRepeatPost(){
  var d=registrations.create(admin,registrationInput());var p=registrations.prepare(admin,d.id(),new CoupangProductRegistrations.Prepare(d.revision(),true));
  assertThat(p.requested()).isTrue();assertThat(p.targets().getFirst().mode()).isEqualTo("CREATE");assertThat(gateway.dispatches()).isEmpty();
  gateway.outcomes("CREATE","UNKNOWN");var e=start(p);worker.runPending();assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.UNKNOWN);
  assertThat(registrations.get(admin,d.id()).blocked()).isTrue();assertThatThrownBy(()->registrations.prepare(admin,d.id(),new CoupangProductRegistrations.Prepare(d.revision(),true))).isInstanceOf(MarketplaceDraftFailure.class);
  assertThatThrownBy(()->submissions.retry(admin,e.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
  submissions.reconcile(admin,e.id());worker.runPending();assertThat(registrations.get(admin,d.id()).externalProductId()).isEqualTo("9001");
  assertThatThrownBy(()->registrations.save(admin,d.id(),new CoupangProductRegistrations.Save(d.revision(),registrationInput()))).isInstanceOf(MarketplaceDraftFailure.class);
  assertThat(gateway.dispatches()).containsExactly("CREATE");
 }
 @Test void dedicatedRegistrationSaveOnlyAndAcceptedRemainLinked(){
  var d=registrations.create(admin,registrationInput());var p=registrations.prepare(admin,d.id(),new CoupangProductRegistrations.Prepare(d.revision(),false));assertThat(p.requested()).isFalse();
  gateway.outcomes("CREATE","ACCEPTED");var e=start(p);worker.runPending();assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.ACCEPTED);
  assertThat(registrations.get(admin,d.id()).externalProductId()).isEqualTo("9001");assertThat(registrations.get(admin,d.id()).blocked()).isTrue();
  submissions.reconcile(admin,e.id());worker.runPending();assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);assertThat(gateway.dispatches()).containsExactly("CREATE");
 }
 @Test void dedicatedRegistrationHttpRejectsInjectedRemoteIdentityAndSupportsIncompleteDraft()throws Exception{
  var browser=new Browser(admin);String path="/api/marketplaces/coupang/product-registrations/drafts";
  var value=json.valueToTree(registrationInput());((tools.jackson.databind.node.ObjectNode)value).put("sellerProductId","9001");
  assertThat(browser.send("POST",path,value,true).statusCode()).isEqualTo(400);
  assertThat(browser.send("POST",path,registrationInput(),true).statusCode()).isEqualTo(200);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void previewQueuesOnlyAfterExplicitExecutionAndDuplicateKeysNeverDispatchTwice()throws Exception{
  var browser=new Browser(admin);var d=drafts.create(admin,blank());
  var response=browser.send("POST","/api/marketplaces/submissions/prepare",request(d),true);
  assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).doesNotContain("private-payload","bodyJson","baselineJson");
  var p=json.treeToValue(json.readTree(response.body()).path("data"),Preview.class);assertThat(p.executable()).isTrue();assertThat(p.targets().getFirst().optionNames()).containsValue("블랙");assertThat(gateway.dispatches()).isEmpty();
  var key=UUID.randomUUID().toString();var e=submissions.execute(admin,p.id(),key);assertThat(e.status()).isEqualTo(Status.QUEUED);
  assertThat(submissions.execute(admin,p.id(),key).id()).isEqualTo(e.id());assertThat(submissions.execute(admin,p.id(),UUID.randomUUID().toString()).id()).isEqualTo(e.id());
  worker.runPending();worker.runPending();assertThat(gateway.dispatches()).containsExactly("CREATE");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
  assertThat(browser.get("/api/marketplaces/submissions/"+e.id()).body()).doesNotContain("private-payload","requestJson","bodyJson","baselineJson");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_execution_attempt",Long.class)).isEqualTo(1);
 }
 @Test void staleExpiredAndNonExecutablePreviewsCannotExecute(){
  var d=drafts.create(admin,blank());var stale=preview(d);d=drafts.save(admin,d.id(),d.revision(),changed(d));
  assertThatThrownBy(()->start(stale)).isInstanceOf(MarketplaceSubmissionFailure.class);
  var expired=preview(d);db.update("UPDATE marketplace_submission_preview SET expires_at=NOW()-INTERVAL 1 MINUTE WHERE id=?",Long.parseLong(expired.id()));
  assertThatThrownBy(()->start(expired)).isInstanceOf(MarketplaceSubmissionFailure.class);
  org.mockito.Mockito.doReturn(new Validation(false,List.of(new Issue("COUPANG","common.name","필수")),List.of())).when(draftService).validate(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(Document.class));
  var invalid=preview(d);assertThat(invalid.executable()).isFalse();assertThatThrownBy(()->start(invalid)).isInstanceOf(MarketplaceSubmissionFailure.class);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void partialFailureRetryDispatchesOnlyFailedStockStep(){
  gateway.types("PRICE","STOCK");gateway.outcomes("STOCK","FAILED","CONFIRMED");var e=start(preview(drafts.create(admin,blank())));
  worker.runPending();worker.runPending();assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.PARTIAL);
  submissions.retry(admin,e.id());worker.runPending();assertThat(gateway.dispatches()).containsExactly("PRICE","STOCK","STOCK");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
 }
 @Test void failureStopsRemainingStagesAndRetryDoesNotRepeatSuccess(){
  gateway.types("DELIVERY","ORIGINAL_PRICE","PRICE","STOCK","PRODUCT");gateway.outcomes("PRICE","FAILED","CONFIRMED");var e=start(preview(drafts.create(admin,blank())));
  for(int i=0;i<6;i++)worker.runPending();
  assertThat(gateway.dispatches()).containsExactly("DELIVERY","ORIGINAL_PRICE","PRICE");
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.PARTIAL);
  assertThat(submissions.get(admin,e.id()).targets().getFirst().steps()).extracting(Step::status).containsExactly(Status.SUCCEEDED,Status.SUCCEEDED,Status.FAILED,Status.QUEUED,Status.QUEUED);
  submissions.retry(admin,e.id());for(int i=0;i<4;i++)worker.runPending();
  assertThat(gateway.dispatches()).containsExactly("DELIVERY","ORIGINAL_PRICE","PRICE","PRICE","STOCK","PRODUCT");
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
 }
 @Test void historicalLengthRequiredFailureGetsActionableMessageWithoutSendingPendingStock(){
  gateway.types("PRICE","STOCK");gateway.outcomes("PRICE","FAILED");var e=start(preview(drafts.create(admin,blank())));worker.runPending();
  db.update("UPDATE marketplace_execution_step SET result_code='HTTP_411_REJECTED',result_message='과거 일반 오류' WHERE execution_id=? AND step_type='PRICE'",Long.parseLong(e.id()));
  var shown=submissions.get(admin,e.id());assertThat(shown.targets().getFirst().steps().getFirst().message()).contains("Content-Length: 0","HTTP 411");
  worker.runPending();assertThat(gateway.dispatches()).containsExactly("PRICE");
  assertThat(shown.targets().getFirst().steps().getLast().status()).isEqualTo(Status.QUEUED);
 }
 @Test void conflictBeforeDispatchStopsRemainingWritesAndReleasesFreshPreparation(){
  gateway.types("DELIVERY","PRICE","STOCK");var d=drafts.create(admin,blank());var e=start(preview(d));
  db.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",admin);
  try{worker.runPending();}finally{db.update("UPDATE `user` SET user_status='ACTIVE' WHERE id=?",admin);}
  var stopped=submissions.get(admin,e.id());assertThat(stopped.status()).isEqualTo(Status.FAILED);
  assertThat(stopped.targets().getFirst().steps()).extracting(Step::code).containsExactly("ACCESS_DENIED","NOT_EXECUTED","NOT_EXECUTED");
  assertThat(gateway.dispatches()).isEmpty();assertThatThrownBy(()->submissions.retry(admin,e.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
  var fresh=preview(drafts.save(admin,d.id(),d.revision(),changed(d)));assertThat(fresh.executable()).isTrue();
 }
 @Test void ambiguousCreateCanOnlyReconcileWithoutAnotherPost(){
  gateway.outcomes("CREATE","UNKNOWN");var e=start(preview(drafts.create(admin,blank())));worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.UNKNOWN);assertThatThrownBy(()->submissions.retry(admin,e.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
  submissions.reconcile(admin,e.id());worker.runPending();assertThat(gateway.dispatches()).containsExactly("CREATE");assertThat(gateway.readbacks()).containsExactly("CREATE");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
 }
 @Test void readbackUsesActualRecordedRequestRatherThanOriginalPreview()throws Exception{
  gateway.rebaseActualRequest();gateway.outcomes("CREATE","UNKNOWN");var p=preview(drafts.create(admin,blank()));var e=start(p);worker.runPending();assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.UNKNOWN);
  var recorded=json.readTree(db.queryForObject("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=? AND action='WRITE'",String.class,Long.parseLong(e.id())));assertThat(recorded.path("id").asText()).isEqualTo("rebased-create");assertThat(recorded.path("bodyJson").asText()).contains("actual-request");
  assertThat(new Browser(admin).get("/api/marketplaces/submissions/"+e.id()).body()).doesNotContain("actual-request","bodyJson","expectedJson");submissions.reconcile(admin,e.id());worker.runPending();
  assertThat(gateway.lastReconciledStep().id()).isEqualTo("create");assertThat(gateway.lastReconciledStep().bodyJson()).isEqualTo(recorded.path("bodyJson").asText());assertThat(gateway.lastReconciledStep().expectedJson()).isEqualTo(recorded.path("expectedJson").asText());assertThat(gateway.dispatches()).containsExactly("CREATE");assertThat(gateway.readbacks()).containsExactly("CREATE");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
 }
 @Test void acceptedStepPausesRemainingWritesUntilReadbackConfirms(){
  gateway.types("PRODUCT","PRICE");gateway.outcomes("PRODUCT","ACCEPTED");var d=drafts.create(admin,blank());var e=start(preview(d));worker.runPending();worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.ACCEPTED);assertThat(gateway.dispatches()).containsExactly("PRODUCT");assertThatThrownBy(()->preview(d)).isInstanceOf(MarketplaceSubmissionFailure.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_execution_attempt WHERE request_json IS NOT NULL",Long.class)).isEqualTo(1);
  submissions.reconcile(admin,e.id());worker.runPending();worker.runPending();assertThat(gateway.readbacks()).containsExactly("PRODUCT");assertThat(gateway.dispatches()).containsExactly("PRODUCT","PRICE");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
 }
 @Test void concurrentDifferentPreviewsCannotQueueDuplicateCreateAndUnknownBlocksLaterWrites()throws Exception{
  var d=drafts.create(admin,blank());var first=preview(d);var second=preview(d);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);List<Object> results;
  try{
   var futures=new ArrayList<java.util.concurrent.Future<Object>>();for(var p:List.of(first,second))futures.add(pool.submit(()->{ready.countDown();go.await();try{return start(p);}catch(MarketplaceSubmissionFailure failure){return failure;}}));
   assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();results=new ArrayList<>();for(var f:futures)results.add(f.get(10,java.util.concurrent.TimeUnit.SECONDS));
  }finally{go.countDown();pool.shutdownNow();}
  assertThat(results.stream().filter(Execution.class::isInstance)).hasSize(1);assertThat(results.stream().filter(MarketplaceSubmissionFailure.class::isInstance)).hasSize(1);
  var execution=(Execution)results.stream().filter(Execution.class::isInstance).findFirst().orElseThrow();gateway.outcomes("CREATE","UNKNOWN");worker.runPending();assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.UNKNOWN);
  var unused=db.queryForObject("SELECT id FROM marketplace_submission_preview WHERE id<>? AND draft_id=?",Long.class,db.queryForObject("SELECT preview_id FROM marketplace_execution WHERE id=?",Long.class,Long.parseLong(execution.id())),Long.parseLong(d.id()));
  assertThatThrownBy(()->submissions.execute(admin,unused.toString(),UUID.randomUUID().toString())).isInstanceOf(MarketplaceSubmissionFailure.class);
  // Simulate a previously persisted duplicate from an older application version; the worker must also guard it.
  long duplicate=cc.ataglace.molebutter.common.api.BusinessIds.next();
  db.update("INSERT INTO marketplace_execution(id,preview_id,draft_id,draft_revision,idempotency_key,created_by,status,created_at,updated_at) SELECT ?,?,draft_id,draft_revision,?,created_by,'QUEUED',NOW(),NOW() FROM marketplace_execution WHERE id=?",duplicate,unused,UUID.randomUUID().toString(),Long.parseLong(execution.id()));
  db.update("INSERT INTO marketplace_execution_target(execution_id,market,account_key,mode,status,external_product_id) SELECT ?,market,account_key,mode,'QUEUED',NULL FROM marketplace_execution_target WHERE execution_id=?",duplicate,Long.parseLong(execution.id()));
  db.update("INSERT INTO marketplace_execution_step(execution_id,step_id,sequence_no,step_type,option_id,status,action,updated_at) VALUES(?,'create',0,'CREATE',NULL,'QUEUED','WRITE',NOW())",duplicate);
  worker.runPending();worker.runPending();assertThat(gateway.dispatches()).containsExactly("CREATE");assertThat(submissions.get(admin,Long.toString(duplicate)).status()).isEqualTo(Status.QUEUED);
 }
 @Test void abandonedRunningAttemptBecomesUnknownAndDoesNotAutomaticallyRepeat(){
  var e=start(preview(drafts.create(admin,blank())));String account=db.queryForObject("SELECT account_key FROM marketplace_execution_target WHERE execution_id=?",String.class,Long.parseLong(e.id()));
  db.update("INSERT INTO marketplace_submission_account(account_key,lease_owner,lease_until) VALUES(?,'crashed',NOW()-INTERVAL 1 MINUTE)",account);
  db.update("UPDATE marketplace_execution_step SET status='RUNNING' WHERE execution_id=?",Long.parseLong(e.id()));
  db.update("INSERT INTO marketplace_execution_attempt(id,execution_id,step_id,action,status,started_at) VALUES(123,?,'create','WRITE','RUNNING',NOW()-INTERVAL 10 MINUTE)",Long.parseLong(e.id()));
  worker.runPending();assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.UNKNOWN);assertThat(gateway.dispatches()).isEmpty();
  submissions.reconcile(admin,e.id());worker.runPending();assertThat(gateway.readbacks()).containsExactly("CREATE");assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void createdExternalMappingIsUsedByFollowingPreview(){
  var d=drafts.create(admin,blank());var e=start(preview(d));worker.runPending();assertThat(submissions.get(admin,e.id()).targets().getFirst().externalProductId()).isEqualTo("9001");
  assertThat(drafts.importCoupang(admin,"9001",null).id()).isEqualTo(d.id());org.mockito.Mockito.verify(testCatalog,org.mockito.Mockito.never()).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
  d=drafts.save(admin,d.id(),d.revision(),changed(d));var next=preview(d);assertThat(gateway.mappedProduct()).isEqualTo("9001");assertThat(next.targets().getFirst().mode()).isEqualTo("UPDATE");assertThat(next.targets().getFirst().steps().getFirst().type()).isEqualTo(StepType.PRODUCT);
 }
 @Test void previewSnapshotPinsImagesWhenLaterDraftRemovesThem()throws Exception{
  var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),"PNG",bytes);var asset=assets.upload(admin,bytes.toByteArray());
  var d=blank();d=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(new Image(UUID.randomUUID().toString(),asset.id(),asset.url(),true,0,null)),List.of()),d.delivery(),d.selectedMarkets(),d.markets());d=drafts.create(admin,d);var p=preview(d);
  var removed=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(),List.of()),d.delivery(),d.selectedMarkets(),d.markets());drafts.save(admin,d.id(),d.revision(),removed);
  db.update("UPDATE marketplace_asset SET pending_delete_at=NOW()-INTERVAL 2 DAY WHERE id=?",asset.id());assetStore.reapUnreferenced();assertThat(assets.metadata(admin,asset.id()).id()).isEqualTo(asset.id());
  db.update("UPDATE marketplace_submission_preview SET expires_at=NOW()-INTERVAL 2 DAY WHERE id=?",Long.parseLong(p.id()));worker.cleanupExpiredPreviews();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_execution_asset WHERE asset_id=?",Long.class,asset.id())).isZero();assetStore.reapUnreferenced();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=?",Long.class,asset.id())).isZero();
 }
 @Test void executedPreviewPinsSurviveExpiryCleanup()throws Exception{
  var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),"PNG",bytes);var asset=assets.upload(admin,bytes.toByteArray());
  var d=blank();d=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(new Image(UUID.randomUUID().toString(),asset.id(),asset.url(),true,0,null)),List.of()),d.delivery(),d.selectedMarkets(),d.markets());d=drafts.create(admin,d);var p=preview(d);start(p);worker.runPending();
  var removed=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(),List.of()),d.delivery(),d.selectedMarkets(),d.markets());drafts.save(admin,d.id(),d.revision(),removed);
  db.update("UPDATE marketplace_submission_preview SET expires_at=NOW()-INTERVAL 2 DAY WHERE id=?",Long.parseLong(p.id()));db.update("UPDATE marketplace_asset SET pending_delete_at=NOW()-INTERVAL 2 DAY WHERE id=?",asset.id());worker.cleanupExpiredPreviews();assetStore.reapUnreferenced();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_execution_asset WHERE asset_id=?",Long.class,asset.id())).isEqualTo(1);assertThat(assets.metadata(admin,asset.id()).id()).isEqualTo(asset.id());
 }
 @Test void adminCsrfAndLatestAccountStatusAreRequired()throws Exception{
  var d=drafts.create(admin,blank());for(long actor:List.of(staff,viewer)){var b=new Browser(actor);assertThat(b.send("POST","/api/marketplaces/submissions/prepare",Map.of("draftId",d.id(),"revision",d.revision()),true).statusCode()).isEqualTo(403);assertThat(b.get("/api/marketplaces/submissions?draftId="+d.id()).statusCode()).isEqualTo(403);}
  var browser=new Browser(admin);assertThat(browser.send("POST","/api/marketplaces/submissions/prepare",Map.of("draftId",d.id(),"revision",d.revision()),false).statusCode()).isEqualTo(403);
  var e=start(preview(d));db.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",admin);
  try{worker.runPending();assertThat(gateway.dispatches()).isEmpty();assertThat(db.queryForObject("SELECT result_code FROM marketplace_execution_step WHERE execution_id=?",String.class,Long.parseLong(e.id()))).isEqualTo("ACCESS_DENIED");assertThat(browser.get("/api/marketplaces/submissions/"+e.id()).statusCode()).isEqualTo(401);}
  finally{db.update("UPDATE `user` SET user_status='ACTIVE' WHERE id=?",admin);}
 }
 @Test void standaloneObservationPreparationExecutionAndReopenReuseReference()throws Exception{
  var browser=new Browser(admin);String base="/api/marketplaces/coupang/products/9001";
  var observationResponse=browser.get(base+"/edit-observation");assertThat(observationResponse.statusCode()).isEqualTo(200);
  var observation=json.treeToValue(json.readTree(observationResponse.body()).path("data"),CoupangProductSaving.Observation.class);
  assertThat(observation.draftId()).isNull();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isZero();
  var input=new CoupangProductSaving.Prepare(observation.token(),List.of(new CoupangProductSaving.Change("common.name",null,"개별 수정 상품"),new CoupangProductSaving.Change("options.quantity","7001","0")));
  var preparedResponse=browser.send("POST",base+"/save-preparation",input,true);assertThat(preparedResponse.statusCode()).isEqualTo(200);
  var prepared=json.treeToValue(json.readTree(preparedResponse.body()).path("data"),Preview.class);assertThat(prepared.executable()).isTrue();assertThat(prepared.requested()).isTrue();
  var before=drafts.get(admin,prepared.draftId());assertThat(before.common().name()).isEqualTo("테스트 상품");assertThat(gateway.dispatches()).isEmpty();
  var executedResponse=browser.send("POST","/api/marketplaces/submissions/"+prepared.id()+"/execute",Map.of("idempotencyKey",UUID.randomUUID().toString()),true);assertThat(executedResponse.statusCode()).isEqualTo(200);
  var execution=json.treeToValue(json.readTree(executedResponse.body()).path("data"),Execution.class);
  worker.runPending();worker.runPending();assertThat(gateway.dispatches()).containsExactly("PRODUCT","STOCK");
  assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.SUCCEEDED);assertThat(drafts.get(admin,before.id())).isEqualTo(before);
  var reopened=json.treeToValue(json.readTree(new Browser(admin).get(base+"/edit-observation").body()).path("data"),CoupangProductSaving.Observation.class);
  assertThat(reopened.draftId()).isEqualTo(before.id());assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isEqualTo(1);
  assertThat(new Browser(admin).get("/api/marketplaces/submissions?draftId="+reopened.draftId()).body()).contains(execution.id());
 }
 @Test void standaloneInvalidScopeNoopAndCsrfNeverDispatch()throws Exception{
  var browser=new Browser(admin);String base="/api/marketplaces/coupang/products/9001";
  var observation=json.treeToValue(json.readTree(browser.get(base+"/edit-observation").body()).path("data"),CoupangProductSaving.Observation.class);
  var input=new CoupangProductSaving.Prepare(observation.token(),List.of(new CoupangProductSaving.Change("common.name",null,"테스트 상품")));
  assertThat(browser.send("POST",base+"/save-preparation",input,false).statusCode()).isEqualTo(403);
  assertThat(new Browser(staff).send("POST",base+"/save-preparation",input,true).statusCode()).isEqualTo(403);
  assertThat(browser.send("POST","/api/marketplaces/coupang/products/9002/save-preparation",input,true).statusCode()).isEqualTo(404);
  var invalid=new CoupangProductSaving.Prepare(observation.token(),List.of(new CoupangProductSaving.Change("markets.COUPANG.coupang.source",null,Map.of("vendorId","forged"))));
  assertThat(browser.send("POST",base+"/save-preparation",invalid,true).statusCode()).isBetween(400,499);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isZero();
  var noopResponse=browser.send("POST",base+"/save-preparation",input,true);assertThat(noopResponse.statusCode()).isEqualTo(200);
  var noop=json.treeToValue(json.readTree(noopResponse.body()).path("data"),Preview.class);assertThat(noop.executable()).isFalse();assertThat(noop.targets().getFirst().steps()).isEmpty();assertThat(gateway.dispatches()).isEmpty();
 }
 class Browser {
  final HttpClient client=HttpClient.newHttpClient();final String token;String csrf,cookie;
  Browser(long actor){token=tokens.issueOnSignin(users.findById(actor).orElseThrow()).accessToken();}
  void security()throws Exception{if(csrf!=null)return;var r=get("/api/auth/csrf");csrf=json.readTree(r.body()).path("data").path("token").asText();cookie=r.headers().allValues("set-cookie").stream().map(s->s.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));}
  HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,false);}
  HttpResponse<String> send(String method,String path,Object body,boolean secure)throws Exception{if(secure)security();var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("X-Operation-Id",UUID.randomUUID().toString()).header("Content-Type","application/json");if(secure)b.header("X-XSRF-TOKEN",csrf).header("Cookie",cookie);return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
 }
}
