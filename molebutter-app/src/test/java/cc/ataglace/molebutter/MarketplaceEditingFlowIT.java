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
 "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes","auth.cookie.secure=false","marketplace.assets.public-base-url="
})
@ActiveProfiles("bootstrap-admin")
@Import({AuthenticationFlowIT.MailConfiguration.class,MarketplaceSubmissionTestGateway.Configuration.class,cc.ataglace.molebutter.marketplaceelevenst.internal.ProviderElevenstFixture.class,cc.ataglace.molebutter.marketplacelotteon.internal.product.ProviderLotteonFixture.class})
class MarketplaceEditingFlowIT {
 static final Path directory=createDirectory();
 static Path createDirectory(){try{return Files.createTempDirectory("molebutter-editing-it-");}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);r.add("marketplace.assets.directory",()->directory.toString());r.add("marketplace.coupang.vendor-id",()->"test-vendor");r.add("marketplace.coupang.access-key",()->"");r.add("marketplace.coupang.secret-key",()->"");}
 @org.springframework.test.context.bean.override.mockito.MockitoBean CoupangBrands selectedBrands;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DefaultMarketplaceDrafts draftService;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CoupangEditor testCatalog;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean NaverProductDocuments naverDocuments;
 @Autowired cc.ataglace.molebutter.marketplacelotteon.internal.product.ProviderLotteonFixture.Reads lotteonReads;
 @Autowired cc.ataglace.molebutter.marketplaceelevenst.internal.ProviderElevenstFixture.Reads elevenstReads;
 @Autowired MarketplaceDrafts drafts;@Autowired MarketplaceSubmissions submissions;@Autowired DefaultMarketplaceSubmissions worker;
 @Autowired MarketplaceEditing editing;
 @Autowired MarketplaceProductEditor productEditor;
 @Autowired cc.ataglace.molebutter.media.api.ImageEdits imageEdits;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean cc.ataglace.molebutter.media.api.R2Images r2Images;
 @Autowired CoupangProductRegistrations nativeRegistrations;
 @Autowired MarketplaceSubmissionTestGateway gateway;@Autowired ImageAssets assets;@Autowired ImageAssets assetStore;
 @Autowired org.springframework.transaction.PlatformTransactionManager imageTransactions;@Autowired BusinessAccess imageAccess;
 @Autowired JdbcTemplate db;@Autowired UserRepository users;@Autowired DefaultAuthTokenService tokens;@Autowired ObjectMapper json;@LocalServerPort int port;
 long admin,staff,viewer,otherAdmin;
 @BeforeEach void prepare(){clear();gateway.reset();admin=users.findByEmail("admin@example.com").orElseThrow().getId();staff=account(UserRole.PRODUCT);viewer=account(UserRole.VIEWER);otherAdmin=account(UserRole.ADMIN);
  org.mockito.Mockito.doReturn(new Validation(true,List.of(),List.of())).when(draftService).validate(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(Document.class));
  org.mockito.Mockito.doReturn(currentProduct(1000L,9L)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
 }
 @AfterEach void cleanup(){clear();for(long actor:List.of(staff,viewer,otherAdmin))if(actor>0){db.update("DELETE FROM user_notification WHERE user_id=?",actor);users.deleteById(actor);}}
 @AfterAll static void cleanFiles()throws Exception{try(var s=Files.list(directory)){for(var p:s.toList())Files.deleteIfExists(p);}Files.deleteIfExists(directory);}
 void clear(){for(String table:List.of("marketplace_media_comparison","marketplace_image_edit","marketplace_editor_history_link","marketplace_execution_attempt","marketplace_execution_step","marketplace_execution_target","marketplace_execution","marketplace_execution_asset","marketplace_submission_preview","marketplace_edit_session","marketplace_listing_history_asset","marketplace_listing_history","marketplace_listing_state","marketplace_listing_mapping","marketplace_submission_account","marketplace_asset_publication","marketplace_draft_asset","marketplace_draft","marketplace_asset"))db.update("DELETE FROM "+table);}
 long account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@submission.test").name("전송 테스트").passwordHash("unused").role(role).status(UserStatus.ACTIVE).build()).getId();}

 CoupangEditor.EditorDocument currentProduct(Long price,Long quantity){return new CoupangEditor.EditorDocument(new CoupangEditor.Basic("9001","9101","조회 상품","노출 상품","제품명","브랜드","","123","승인완료"),new CoupangEditor.Limits(true,true,true),List.of(new CoupangEditor.EditOption("7001","8001","블랙",new CoupangCatalog.CurrentInventory("8001",price,quantity,true),null,true,List.of(),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku","SKU-1"),new CoupangCatalog.Field("salePrice","1000"),new CoupangCatalog.Field("maximumBuyCount","9")),List.of())),List.of(),List.of(),List.of());}
 Document linked(){return drafts.importCoupang(admin,"9001",null);}
 void missingCoupang(){org.mockito.Mockito.doThrow(new MarketplaceListingMissing("COUPANG_DELETED")).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));}
 @Test void deletedRecreatePreservesCommonOtherMarketAndPendingInputThroughReentry(){
  var common=linked();String option=common.options().getFirst().id();var remote=productEditor.open(admin,"MARKET_EDIT","LOTTEON","LO5001",null);common=productEditor.connect(admin,new MarketplaceProductEditor.Link(remote.token(),common.id(),common.revision(),null,List.of(new MarketplaceProductEditor.OptionLink(remote.reference().options().getFirst().id(),option,false)),List.of())).reference();
  var session=editing.startLazy(admin,common.id(),"COUPANG");var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(common,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("options.price",option,"1200"))))));
  var before=saved.reference();String other=db.queryForObject("SELECT mapping_json FROM marketplace_listing_mapping WHERE draft_id=? AND market='LOTTEON'",String.class,Long.parseLong(common.id()));missingCoupang();var deleted=editing.startLazy(admin,common.id(),"COUPANG");assertThat(coupang(deleted).status()).isEqualTo("DELETED");assertThat(coupang(deleted).listing().recreateAllowed()).isTrue();
  var request=new MarketplaceProductEditor.Recreate(deleted.id(),before.revision(),"COUPANG",1L,UUID.randomUUID().toString(),before,List.of());var result=productEditor.recreate(admin,request);var target=coupang(result.session());assertThat(target.mode()).isEqualTo("CREATE");assertThat(target.listing().version()).isEqualTo(2);assertThat(target.document().options().getFirst().price()).isEqualTo("1200");assertThat(target.document().markets().get(Market.COUPANG).coupang().sellerProductId()).isNull();assertThat(target.document().markets().get(Market.COUPANG).coupang().source()).isNull();assertThat(target.document().markets().get(Market.COUPANG).coupang().options().getFirst().vendorItemId()).isNull();
  assertThat(result.reference().common()).isEqualTo(before.common());assertThat(result.reference().options()).isEqualTo(before.options());assertThat(result.reference().markets().get(Market.LOTTEON)).isEqualTo(before.markets().get(Market.LOTTEON));assertThat(db.queryForObject("SELECT mapping_json FROM marketplace_listing_mapping WHERE draft_id=? AND market='LOTTEON'",String.class,Long.parseLong(common.id()))).isEqualTo(other);
  assertThatThrownBy(()->productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(request.sessionId(),request.revision(),"NAVER",1L,request.requestId(),before,List.of()))).isInstanceOf(MarketplaceEditingFailure.class);
  var repeated=productEditor.recreate(admin,request);assertThat(repeated.reference().revision()).isEqualTo(result.reference().revision());assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history",Long.class)).isEqualTo(1);
  var reopened=productEditor.open(admin,"COMMON_EDIT",null,null,common.id());var reloaded=coupang(reopened.session());assertThat(reloaded.mode()).isEqualTo("CREATE");assertThat(reloaded.document().options().getFirst().price()).isEqualTo("1200");assertThat(reloaded.document().options().getFirst().id()).isEqualTo(option);assertThatThrownBy(()->editing.saveReference(admin,session.id(),before)).isInstanceOf(MarketplaceEditingFailure.class);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void recreatedCreateGetsNewMappingWhileOldFailedExecutionCannotRunAgain(){
  var d=linked();var original=editing.startLazy(admin,d.id(),"COUPANG");var preview=submissions.prepare(admin,request(original,new MarketplaceEditing.Change("common.name",null,"수정 이름")));gateway.outcomes("PRODUCT","FAILED");var execution=submissions.execute(admin,preview.id(),UUID.randomUUID().toString());worker.runPending();int oldDispatches=gateway.dispatches().size();assertThat(oldDispatches).isEqualTo(1);
  missingCoupang();var deleted=editing.startLazy(admin,d.id(),"COUPANG");var recreated=productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),d.revision(),"COUPANG",1L,UUID.randomUUID().toString(),d,List.of()));
  db.update("UPDATE marketplace_execution_step SET status='QUEUED',action='WRITE' WHERE execution_id=?",Long.parseLong(execution.id()));worker.runPending();assertThat(gateway.dispatches()).hasSize(oldDispatches);assertThat(submissions.get(admin,execution.id()).targets().getFirst().steps().getFirst().code()).isEqualTo("MAPPING_CHANGED");
  gateway.createdProduct("99002");var next=submissions.prepare(admin,new MarketplaceEditing.PrepareRequest(d.id(),recreated.reference().revision(),recreated.session().id(),false,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of()))));assertThat(next.executable()).as(next.toString()).isTrue();assertThat(next.targets().getFirst().mode()).isEqualTo("CREATE");var created=submissions.execute(admin,next.id(),UUID.randomUUID().toString());worker.runPending();assertThat(submissions.get(admin,created.id()).status()).isEqualTo(Status.SUCCEEDED);assertThat(db.queryForObject("SELECT external_product_id FROM marketplace_listing_mapping WHERE draft_id=? AND market='COUPANG'",String.class,Long.parseLong(d.id()))).isEqualTo("99002");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history",Long.class)).isEqualTo(1);
 }
 @Test void unknownExecutionBlocksRecreateEvenWhenDeletionIsConfirmed(){
  var d=linked();var original=editing.startLazy(admin,d.id(),"COUPANG");var preview=submissions.prepare(admin,request(original,new MarketplaceEditing.Change("common.name",null,"미확인 변경")));gateway.outcomes("PRODUCT","UNKNOWN");submissions.execute(admin,preview.id(),UUID.randomUUID().toString());worker.runPending();missingCoupang();var deleted=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(deleted).listing().recreateAllowed()).isFalse();assertThatThrownBy(()->productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),d.revision(),"COUPANG",1L,UUID.randomUUID().toString(),d,List.of()))).hasMessageContaining("결과 미확인");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history",Long.class)).isZero();
 }
 @Test void concurrentRecreationArchivesOneGenerationOnly()throws Exception{
  var d=linked();editing.startLazy(admin,d.id(),"COUPANG");missingCoupang();var deleted=editing.startLazy(admin,d.id(),"COUPANG");var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
  try{for(int i=0;i<2;i++)results.add(pool.submit(()->{ready.countDown();go.await();try{productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),d.revision(),"COUPANG",1L,UUID.randomUUID().toString(),d,List.of()));return true;}catch(MarketplaceEditingFailure conflict){return false;}}));assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();var successes=new ArrayList<Boolean>();for(var f:results)successes.add(f.get(20,java.util.concurrent.TimeUnit.SECONDS));assertThat(successes).containsExactlyInAnyOrder(true,false);}finally{go.countDown();pool.shutdownNow();}
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history",Long.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT listing_version FROM marketplace_listing_state WHERE draft_id=?",Long.class,Long.parseLong(d.id()))).isEqualTo(2);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void invalidPreservedInputRollsBackRecreationAndKeepsOldMapping(){
  var d=linked();editing.startLazy(admin,d.id(),"COUPANG");missingCoupang();var deleted=editing.startLazy(admin,d.id(),"COUPANG");var before=json.writeValueAsString(drafts.get(admin,d.id()));
  var invalid=new MarketplaceEditing.TargetChanges("NAVER",List.of(new MarketplaceEditing.Change("common.name",null,"잘못된 대상")));assertThatThrownBy(()->productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),d.revision(),"COUPANG",1L,UUID.randomUUID().toString(),changedName(d,"저장되지 않을 공통 이름"),List.of(invalid)))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
  assertThat(json.writeValueAsString(drafts.get(admin,d.id()))).isEqualTo(before);assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history",Long.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_mapping WHERE draft_id=?",Long.class,Long.parseLong(d.id()))).isEqualTo(1);assertThat(db.queryForObject("SELECT state FROM marketplace_listing_state WHERE draft_id=?",String.class,Long.parseLong(d.id()))).isEqualTo("DELETED_CONFIRMED");assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void unlinkedStandaloneRecreationReentersAsDedicatedRegistrationWithoutCommonCreation(){
  var entry=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(entry.token(),"COUPANG",entry.reference(),List.of()));missingCoupang();var deleted=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);var result=productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.session().id(),deleted.reference().revision(),"COUPANG",1L,UUID.randomUUID().toString(),deleted.reference(),List.of()));assertThat(result.kind()).isEqualTo("MARKET_ONLY");assertThat(result.commonId()).isNull();var reopened=productEditor.open(admin,"MARKET_NEW","COUPANG",null,result.reference().id());assertThat(reopened.session().targets().getFirst().mode()).isEqualTo("CREATE");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft WHERE editor_kind='COMMON'",Long.class)).isZero();
 }
 @Test void deletionMustStillBeConfirmedAtRecreateTime(){
  var d=linked();editing.startLazy(admin,d.id(),"COUPANG");missingCoupang();var deleted=editing.startLazy(admin,d.id(),"COUPANG");observe(1000L,9L);
  assertThatThrownBy(()->productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),d.revision(),"COUPANG",1L,UUID.randomUUID().toString(),d,List.of()))).hasMessageContaining("다시 조회할 수");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history",Long.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_mapping",Long.class)).isEqualTo(1);
 }
 @Test void permissionFailureIsUnverifiedAndCannotBeRecreated(){
  var d=linked();org.mockito.Mockito.doThrow(new MarketplaceFailure(MarketplaceFailure.Kind.PERMISSION)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));var session=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(session).listing().state()).isEqualTo("UNVERIFIED");assertThat(coupang(session).listing().recreateAllowed()).isFalse();assertThatThrownBy(()->productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(session.id(),d.revision(),"COUPANG",1L,UUID.randomUUID().toString(),d,List.of()))).isInstanceOf(MarketplaceFailure.class);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void lotteonStandaloneDraftImportLinkLazyReadAndPendingReentry()throws Exception{
  lotteonReads.calls.clear();var observed=productEditor.open(admin,"MARKET_EDIT","LOTTEON","LO5001",null);assertThat(observed.activeTabs()).containsExactly("LOTTEON");assertThat(observed.optionIdentities()).hasSize(1);assertThat(LotteonInput.get(observed.reference()).product().options().getFirst().price()).isEqualTo("87000");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isZero();
  var connected=productEditor.connect(admin,new MarketplaceProductEditor.Link(observed.token(),null,null,"LOT-IMPORTED",null,List.of()));assertThat(connected.reference().common().productCode()).isEqualTo("LOT-IMPORTED");var i=LotteonInput.get(connected.reference());var row=i.product().options().getFirst();var changed=new PartnerEditor.Option(row.id(),row.name(),row.sku(),row.price(),"0",row.attributes(),row.usable());var change=new MarketplaceEditing.Change("lotteon.product.options",null,List.of(changed));var saved=editing.saveDraft(admin,connected.session().id(),new MarketplaceEditing.DraftSaveRequest(connected.reference(),List.of(new MarketplaceEditing.TargetChanges("LOTTEON",List.of(change)))));var reopened=productEditor.open(admin,"MARKET_EDIT","LOTTEON","LO5001",null);var target=reopened.session().targets().stream().filter(v->v.market().equals("LOTTEON")).findFirst().orElseThrow();assertThat(target.mode()).isEqualTo("UPDATE");assertThat(target.pendingChanges()).hasSize(1);assertThat(LotteonInput.get(target.document()).product().options().getFirst().quantity()).isEqualTo("9");assertThat(saved.reference().id()).isEqualTo(reopened.commonId());assertThat(lotteonReads.calls).noneMatch(c->c.contains("/request")||c.contains("/change"));
 }
 @Test void lotteonManualLinkRekeysNativeMapAndReadsOtherMarketOnlyWhenVisited(){
  var common=linked();String option=common.options().getFirst().id();var entry=productEditor.open(admin,"MARKET_EDIT","LOTTEON","LO5001",null);String source=entry.reference().options().getFirst().id();var connected=productEditor.connect(admin,new MarketplaceProductEditor.Link(entry.token(),common.id(),common.revision(),null,List.of(new MarketplaceProductEditor.OptionLink(source,option,false)),List.of()));assertThat(connected.activeTabs()).contains("","COUPANG","LOTTEON");assertThat(connected.reference().common().name()).isEqualTo(common.common().name());assertThat(LotteonInput.get(connected.reference()).items()).containsKey(option).doesNotContainKey(source);assertThat(LotteonInput.get(connected.reference()).representativeOptionId()).isEqualTo(option);assertThat(connected.session().targets().stream().filter(t->t.market().equals("COUPANG")).findFirst().orElseThrow().status()).isEqualTo("UNLOADED");int count=lotteonReads.calls.size();editing.refresh(admin,connected.session().id(),"COUPANG");assertThat(lotteonReads.calls).hasSize(count);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void lotteonNativeCreateUsesActualReturnedProductAndItemsWithFinalSalePrice(){
  var observed=productEditor.open(admin,"MARKET_EDIT","LOTTEON","LO5001",null);var d=observed.reference();var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(null,"LOTTEON",d,List.of()));assertThat(saved.reference().options().getFirst().price()).isEqualTo("87000");String url=LotteonInput.get(d).product().images().getFirst().url();org.mockito.Mockito.doReturn(new cc.ataglace.molebutter.media.api.R2Images.Image("key",url,"image/jpeg",750,750,1000)).when(r2Images).resolve(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(url));var preview=submissions.prepare(admin,new MarketplaceEditing.PrepareRequest(saved.reference().id(),saved.reference().revision(),saved.session().id(),false,List.of(new MarketplaceEditing.TargetChanges("LOTTEON",List.of()))));assertThat(preview.executable()).as(preview.toString()).isTrue();lotteonReads.allowWrites=true;try{var execution=submissions.execute(admin,preview.id(),UUID.randomUUID().toString());worker.runPending();var result=submissions.get(admin,execution.id());assertThat(result.status()).as(result.toString()).isEqualTo(Status.SUCCEEDED);assertThat(result.targets().getFirst().externalProductId()).isEqualTo("LOCREATED");var entry=productEditor.open(admin,"MARKET_EDIT","LOTTEON","LOCREATED",null);assertThat(entry.session().targets().getFirst().mode()).isEqualTo("UPDATE");assertThat(LotteonInput.get(entry.reference()).product().options().getFirst().price()).isEqualTo("87000");}finally{lotteonReads.allowWrites=false;}
 }
 @Test void lotteonHttpRequiresAdminAndReadsOpaqueIds()throws Exception{assertThat(new Browser(viewer).get("/api/marketplaces/lotteon/schema").statusCode()).isEqualTo(403);var browser=new Browser(admin);assertThat(browser.get("/api/marketplaces/lotteon/products").statusCode()).isEqualTo(200);assertThat(browser.get("/api/marketplaces/lotteon/products/LO5001").statusCode()).isEqualTo(200);assertThat(browser.get("/api/marketplaces/lotteon/products/invalid%20id").statusCode()).isEqualTo(400);}
 @Test void lotteonActualEmptyResponseBecomesSuccessfulHttpPage()throws Exception{
  lotteonReads.emptyProducts=true;try{var response=new Browser(admin).get("/api/marketplaces/lotteon/products");assertThat(response.statusCode()).isEqualTo(200);var data=json.readTree(response.body()).path("data");assertThat(data.path("items").isArray()).isTrue();assertThat(data.path("items").size()).isZero();assertThat(data.path("hasNext").asBoolean()).isFalse();}finally{lotteonReads.emptyProducts=false;}
 }
 @Test void registrationMetadataUsesAuthenticatedReadOnlyEndpoints()throws Exception{
  var browser=new Browser(admin);
  for(String path:List.of("/api/marketplaces/elevenst/categories","/api/marketplaces/elevenst/addressbooks?kind=outbound","/api/marketplaces/elevenst/shipping-templates","/api/marketplaces/elevenst/delivery-companies","/api/marketplaces/elevenst/origins","/api/marketplaces/lotteon/metadata/categories/page?parent=123","/api/marketplaces/lotteon/metadata/outbound/page"))assertThat(browser.get(path).statusCode()).as(path).isEqualTo(200);
  var brands=browser.get("/api/marketplaces/elevenst/brands?name=DAKS");assertThat(brands.statusCode()).isEqualTo(200);assertThat(json.readTree(brands.body()).path("data").path("available").asBoolean()).isFalse();
 }
 @Test void elevenstMockRegistrationPersistsReturnedProductAndOptionMapping(){
  var base=newInput();String option=base.options().getFirst().id(),image=UUID.randomUUID().toString(),url="https://assets.example.test/products/SYNTHETIC/official/01.jpg";var f=new LinkedHashMap<String,Object>();
  f.putAll(Map.of("prdNm","11번가 등록 모의","dispCtgrNo","20","brand","모의 브랜드","selMthdCd","01","prdTypCd","01","prdStatCd","01","dlvClf","02","dlvCnAreaCd","01","dlvWyCd","01","minorSelCnYn","Y"));
  f.putAll(Map.of("rmaterialTypCd","04","orgnTypCd","03","orgnNmVal","상품상세 참조","suplDtyfrPrdClfCd","01","selPrc","128000","cuponcheck","Y","dscAmtPercnt","41000","cupnDscMthdCd","01","selPrdClfCd","30:105"));
  f.putAll(Map.of("dlvSendCloseTmpltNo","99","dlvEtprsCd","00034","dlvCstInstBasiCd","01","addrSeqOut","7","addrSeqIn","7","rtngdDlvCst","4000","exchDlvCst","8000","asDetail","AS","rtngExchDetail","반품"));for(String g:List.of("01","02","03","04"))f.put("cert"+g+".target",g.equals("04")?"05":"03");
  var input=new PartnerEditor.Input(f,List.of(new PartnerEditor.Option(option,"레드","SKU","87000","9",List.of(),true)),List.of(new PartnerEditor.Image(image,null,url,true,0)),"설명","123",List.of(new Attribute("456","가죽")),List.of());
  var doc=new Document(null,null,base.common(),base.options(),base.stockMode(),base.productQuantity(),base.services(),base.media(),base.delivery(),List.of(Market.ELEVENST),Map.of(Market.ELEVENST,new MarketConfig("20",null,null,null,null,null,null,new Elevenst(input,null))));
  org.mockito.Mockito.doReturn(new cc.ataglace.molebutter.media.api.R2Images.Image("key",url,"image/jpeg",750,750,1000)).when(r2Images).resolve(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(url));
  var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(null,"ELEVENST",doc,List.of()));assertThat(saved.reference().options().getFirst().price()).isEqualTo("87000");
  var preview=submissions.prepare(admin,new MarketplaceEditing.PrepareRequest(saved.reference().id(),saved.reference().revision(),saved.session().id(),false,List.of(new MarketplaceEditing.TargetChanges("ELEVENST",List.of()))));assertThat(preview.executable()).as(preview.toString()).isTrue();
  elevenstReads.allowWrites=true;try{var execution=submissions.execute(admin,preview.id(),UUID.randomUUID().toString());worker.runPending();var result=submissions.get(admin,execution.id());assertThat(result.status()).isEqualTo(Status.SUCCEEDED);assertThat(result.targets().getFirst().externalProductId()).isEqualTo("5002");var entry=productEditor.open(admin,"MARKET_EDIT","ELEVENST","5002",null);assertThat(entry.session().targets().getFirst().mode()).isEqualTo("UPDATE");assertThat(entry.reference().markets().get(Market.ELEVENST).elevenst().editorInput().options().getFirst().id()).isEqualTo(option);}finally{elevenstReads.allowWrites=false;}
  assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void elevenstStandaloneImportLinkLazyReadAndPendingReentry()throws Exception{
  elevenstReads.calls.clear();var observed=productEditor.open(admin,"MARKET_EDIT","ELEVENST","5001",null);
  assertThat(observed.activeTabs()).containsExactly("ELEVENST");assertThat(observed.reference().markets().get(Market.ELEVENST).elevenst().editorInput().options().getFirst().price()).isEqualTo("87000");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isZero();
  var connected=productEditor.connect(admin,new MarketplaceProductEditor.Link(observed.token(),null,null,"11-IMPORTED",null,List.of()));
  assertThat(connected.reference().common().productCode()).isEqualTo("11-IMPORTED");assertThat(connected.reference().options().getFirst().originalPrice()).isEqualTo("128000");
  var row=connected.reference().markets().get(Market.ELEVENST).elevenst().editorInput().options().getFirst();
  var changed=new PartnerEditor.Option(row.id(),row.name(),row.sku(),row.price(),"0",row.attributes(),row.usable());
  var saved=editing.saveDraft(admin,connected.session().id(),new MarketplaceEditing.DraftSaveRequest(connected.reference(),List.of(new MarketplaceEditing.TargetChanges("ELEVENST",List.of(new MarketplaceEditing.Change("partner.options",null,List.of(changed)))))));
  var reopened=productEditor.open(admin,"MARKET_EDIT","ELEVENST","5001",null);var target=reopened.session().targets().stream().filter(v->v.market().equals("ELEVENST")).findFirst().orElseThrow();
  assertThat(reopened.commonId()).isEqualTo(saved.reference().id());assertThat(target.mode()).isEqualTo("UPDATE");assertThat(target.pendingChanges()).hasSize(1);assertThat(target.document().markets().get(Market.ELEVENST).elevenst().editorInput().options().getFirst().quantity()).isEqualTo("9");
  assertThat(elevenstReads.calls).allMatch(c->c.startsWith("GET "));assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void elevenstLinksOnlyManuallyChosenUuidAndDoesNotReadOtherMarketUntilRequested(){
  var common=linked();String option=common.options().getFirst().id();var beforeName=common.common().name();elevenstReads.calls.clear();
  var entry=productEditor.open(admin,"MARKET_EDIT","ELEVENST","5001",null);var remote=entry.reference().options().getFirst().id();
  var connected=productEditor.connect(admin,new MarketplaceProductEditor.Link(entry.token(),common.id(),common.revision(),null,List.of(new MarketplaceProductEditor.OptionLink(remote,option,false)),List.of()));
  assertThat(connected.reference().common().name()).isEqualTo(beforeName);assertThat(connected.activeTabs()).contains("","COUPANG","ELEVENST");
  assertThat(connected.session().targets().stream().filter(t->t.market().equals("COUPANG")).findFirst().orElseThrow().status()).isEqualTo("UNLOADED");
  int count=elevenstReads.calls.size();var other=editing.refresh(admin,connected.session().id(),"COUPANG");assertThat(other.targets().stream().filter(t->t.market().equals("COUPANG")).findFirst().orElseThrow().status()).isEqualTo("READY");assertThat(elevenstReads.calls).hasSize(count);
  assertThat(connected.reference().markets().get(Market.ELEVENST).elevenst().editorInput().options().getFirst().id()).isEqualTo(option);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void elevenstReadHttpUsesAuthenticatedAdminAndSharedEditor()throws Exception{
  assertThat(new Browser(viewer).get("/api/marketplaces/elevenst/schema").statusCode()).isEqualTo(403);
  var browser=new Browser(admin);assertThat(browser.get("/api/marketplaces/elevenst/products").statusCode()).isEqualTo(200);assertThat(browser.get("/api/marketplaces/elevenst/products/invalid").statusCode()).isEqualTo(400);assertThat(browser.get("/api/marketplaces/elevenst/schema").statusCode()).isEqualTo(200);
 }
 @Test void croppedAssetsPersistOriginalAndDescriptionReferencesAcrossReentry()throws Exception{
  var image=new BufferedImage(750,962,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);String url="https://assets.example.test/products/SYNTHETIC/official/portrait.png";
  org.mockito.Mockito.doReturn(new cc.ataglace.molebutter.media.api.R2Images.Content(new cc.ataglace.molebutter.media.api.R2Images.Image("key",url,"image/png",750,962,bytes.size()),bytes.toByteArray())).when(r2Images).read(admin,url);
  var opened=imageEdits.open(admin,url,null);var cropped=imageEdits.crop(admin,new cc.ataglace.molebutter.media.api.ImageEdits.Request(opened.token(),new cc.ataglace.molebutter.media.api.ImageEdits.Rect(0,100,750,750)));
  assertThat(cropped.width()).isEqualTo(750);assertThat(cropped.height()).isEqualTo(750);
  var cropRequest=Map.of("token",opened.token(),"rect",Map.of("x",0,"y",100,"width",750,"height",750));
  assertThat(new Browser(admin).send("POST","/api/marketplaces/image-edits",cropRequest,false).statusCode()).isEqualTo(403);
  assertThat(new Browser(admin).send("POST","/api/marketplaces/image-edits",cropRequest,true).statusCode()).isEqualTo(200);
  var publisher=new cc.ataglace.molebutter.media.internal.LocalImageAssets(db,imageAccess,imageTransactions,directory.toString(),"https://app.example.test");String published=publisher.submissionUrl(admin,cropped.id());
  var publicResponse=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+URI.create(published).getPath())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
  assertThat(publicResponse.statusCode()).isEqualTo(200);assertThat(publicResponse.headers().firstValue("Content-Type")).contains("image/png");assertThat(publicResponse.body()).isEqualTo(assets.read(admin,cropped.id()).bytes());
  var initial=newInput();var edited=new Document(null,null,initial.common(),initial.options(),initial.stockMode(),initial.productQuantity(),initial.services(),new Media(List.of(new Image(UUID.randomUUID().toString(),cropped.id(),cropped.url(),true,0,null)),List.of(new Content(UUID.randomUUID().toString(),"IMAGE",cropped.url(),null,cropped.id()))),initial.delivery(),initial.selectedMarkets(),initial.markets());
  var saved=drafts.create(admin,edited);assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=?",Long.class,Long.parseLong(saved.id()))).isEqualTo(2);
  var reopened=productEditor.open(admin,"COMMON_EDIT",null,null,saved.id());assertThat(reopened.reference().media().contents().getFirst().assetId()).isEqualTo(cropped.id());
  var source=imageEdits.open(admin,null,cropped.id());assertThat(source.width()).isEqualTo(750);assertThat(source.height()).isEqualTo(962);assertThat(source.originalUrl()).isEqualTo(url);assertThat(source.rect().y()).isEqualTo(100);
  assertThat(new Browser(admin).get(source.previewUrl()).statusCode()).isEqualTo(200);assertThat(new Browser(otherAdmin).get(source.previewUrl()).statusCode()).isEqualTo(400);
  var result=productEditor.validate(admin,new MarketplaceProductEditor.Validate(null,reopened.session().id(),reopened.reference(),List.of(),List.of("COUPANG")));
  assertThat(result.valid()).isFalse();assertThat(result.unverified()).anyMatch(i->i.message().contains("HTTPS"));assertThat(result.images()).hasSize(2);
  assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void unifiedHttpEntryAndExplicitConnectKeepAdminCsrfBoundaries()throws Exception{
  for(long actor:List.of(staff,viewer))assertThat(new Browser(actor).get("/api/marketplaces/editor/entry?mode=MARKET_EDIT&market=COUPANG&productId=9001").statusCode()).isEqualTo(403);
  var browser=new Browser(admin);var response=browser.get("/api/marketplaces/editor/entry?mode=MARKET_EDIT&market=COUPANG&productId=9001");assertThat(response.statusCode()).isEqualTo(200);var entry=json.readTree(response.body()).path("data");assertThat(entry.path("activeTabs").get(0).asText()).isEqualTo("COUPANG");
  var refresh=browser.send("POST","/api/marketplaces/editor/refresh",Map.of("token",entry.path("token").asText()),true);assertThat(refresh.statusCode()).isEqualTo(200);var fresh=json.readTree(refresh.body()).path("data");assertThat(fresh.path("reference").path("options").get(0).path("id").asText()).isEqualTo(entry.path("reference").path("options").get(0).path("id").asText());assertThat(fresh.path("token").asText()).isNotEqualTo(entry.path("token").asText());entry=fresh;
  var validationInput=Map.of("token",entry.path("token").asText(),"reference",json.convertValue(entry.path("reference"),Object.class),"targets",List.of(),"markets",List.of("COUPANG"));
  assertThat(browser.send("POST","/api/marketplaces/editor/validate",validationInput,false).statusCode()).isEqualTo(403);assertThat(browser.send("POST","/api/marketplaces/editor/validate",validationInput,true).statusCode()).isEqualTo(200);assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isZero();
  var body=Map.of("token",entry.path("token").asText(),"productCode","HTTP-INTERNAL","options",List.of(),"changes",List.of());
  assertThat(browser.send("POST","/api/marketplaces/editor/connect",body,false).statusCode()).isEqualTo(403);
  assertThat(new Browser(otherAdmin).send("POST","/api/marketplaces/editor/connect",body,true).statusCode()).isEqualTo(404);
  var connected=browser.send("POST","/api/marketplaces/editor/connect",body,true);assertThat(connected.statusCode()).isEqualTo(200);assertThat(json.readTree(connected.body()).path("data").path("commonId").asText()).isNotBlank();
  var results=browser.get("/api/marketplaces/editor/candidates?query=HTTP-INTERNAL");assertThat(results.statusCode()).isEqualTo(200);assertThat(json.readTree(results.body()).path("data").path("items").size()).isEqualTo(1);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void unifiedNativeRegistrationPreservesEachOptionPriceStockAndShippingTime(){
  var first=newInput();var second=new Option("10000000-1000-4000-8000-000000000002","파랑","SKU-2","2000","4",List.of());
  var input=new Document(null,null,first.common(),List.of(first.options().getFirst(),second),first.stockMode(),first.productQuantity(),first.services(),first.media(),first.delivery(),first.selectedMarkets(),first.markets());
  var changes=List.of(new MarketplaceEditing.Change("options.price",first.options().getFirst().id(),"1200"),new MarketplaceEditing.Change("options.quantity",second.id(),"0"),new MarketplaceEditing.Change("delivery.chargeType",null,"FREE"),new MarketplaceEditing.Change("delivery.charge",null,"0"),new MarketplaceEditing.Change("markets.COUPANG.coupang.options.registration.outboundShippingTimeDay",first.options().getFirst().id(),"2"),new MarketplaceEditing.Change("markets.COUPANG.coupang.options.registration.outboundShippingTimeDay",second.id(),"4"));
  var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(null,"COUPANG",input,List.of(new MarketplaceEditing.TargetChanges("COUPANG",changes))));
  assertThat(saved.reference().options()).extracting(Option::price).containsExactly("1200","2000");assertThat(saved.reference().options()).extracting(Option::quantity).containsExactly("9","0");
  var nativeInput=nativeRegistrations.get(admin,saved.reference().id()).input();assertThat(nativeInput.options()).extracting(o->o.registration().get("salePrice")).containsExactly("1200","2000");assertThat(nativeInput.options()).extracting(o->o.registration().get("maximumBuyCount")).containsExactly("9","0");assertThat(nativeInput.delivery().get("deliveryChargeType")).isEqualTo("FREE");
  var reopened=productEditor.open(admin,"MARKET_NEW","COUPANG",null,saved.reference().id());assertThat(reopened.activeTabs()).containsExactly("COUPANG");
  assertThat(reopened.reference().markets().get(Market.COUPANG).coupang().options()).extracting(o->o.registration().stream().filter(f->f.name().equals("outboundShippingTimeDay")).findFirst().orElseThrow().value()).containsExactly("2","4");
  assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void unifiedEntryImportAndLazyReloadPersistOnlyExplicitInternalWrites(){
  var entry=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);
  assertThat(entry.activeTabs()).containsExactly("COUPANG");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft",Long.class)).isZero();
  var option=entry.reference().options().getFirst().id();
  var imported=productEditor.connect(admin,new MarketplaceProductEditor.Link(entry.token(),null,null,"EXPLICIT-INTERNAL-CODE",null,List.of(new MarketplaceEditing.Change("options.price",option,"1200"))));
  assertThat(imported.commonId()).isNotBlank();assertThat(imported.reference().common().productCode()).isEqualTo("EXPLICIT-INTERNAL-CODE");
  assertThat(coupang(imported.session()).pendingChanges()).containsExactly(new MarketplaceEditing.Change("options.price",option,"1200"));
  var lazy=editing.startLazy(admin,imported.commonId(),null);assertThat(coupang(lazy).status()).isEqualTo("UNLOADED");
  var saved=editing.saveDraft(admin,lazy.id(),new MarketplaceEditing.DraftSaveRequest(imported.reference(),List.of()));assertThat(coupang(saved.session()).mode()).isEqualTo("UPDATE");assertThat(coupang(saved.session()).status()).isEqualTo("UNLOADED");
  assertThat(coupang(editing.refresh(admin,saved.session().id(),"COUPANG")).pendingChanges()).hasSize(1);
  assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void standaloneFirstSaveAdvancesLinkObservationBeforeFurtherEdits(){
  var entry=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);String option=entry.reference().options().getFirst().id();
  org.mockito.Mockito.doReturn(currentProduct(1000L,5L)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
  var price=new MarketplaceEditing.Change("options.price",option,"1200");
  var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(entry.token(),"COUPANG",entry.reference(),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(price)))));
  assertThat(coupang(saved.session()).document().options().getFirst().quantity()).isEqualTo("5");
  var quantity=new MarketplaceEditing.Change("options.quantity",option,"4");
  var connected=productEditor.connect(admin,new MarketplaceProductEditor.Link(entry.token(),null,null,"AFTER-FIRST-SAVE",null,List.of(price,quantity)));
  assertThat(coupang(connected.session()).pendingChanges()).contains(price,quantity);
  assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void standaloneLinkStillRejectsARealExternalChangeAfterSaving(){
  var entry=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);String option=entry.reference().options().getFirst().id();
  org.mockito.Mockito.doReturn(currentProduct(1000L,5L)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
  var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(entry.token(),"COUPANG",entry.reference(),List.of()));
  org.mockito.Mockito.doReturn(currentProduct(1000L,6L)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
  assertThatThrownBy(()->productEditor.connect(admin,new MarketplaceProductEditor.Link(entry.token(),null,null,"REAL-CONFLICT",null,List.of(new MarketplaceEditing.Change("options.quantity",option,"4"))))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
  assertThat(db.queryForObject("SELECT editor_kind FROM marketplace_draft WHERE id=?",String.class,Long.parseLong(saved.reference().id()))).isEqualTo("MARKET_ONLY");
  assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void manualExistingLinkTransfersTechnicalDraftPendingAndPreservesItsExecutionHistory(){
  var target=drafts.create(admin,newInput());var entry=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);String sourceOption=entry.reference().options().getFirst().id();
  var saved=productEditor.save(admin,new MarketplaceProductEditor.Save(entry.token(),"COUPANG",entry.reference(),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("options.price",sourceOption,"1200"))))));
  assertThat(db.queryForObject("SELECT editor_kind FROM marketplace_draft WHERE id=?",String.class,Long.parseLong(saved.reference().id()))).isEqualTo("MARKET_ONLY");
  assertThat(drafts.list(admin,"",0,20).items()).extracting(Summary::id).containsExactly(target.id());
  var preview=submissions.prepare(admin,request(saved.session(),new MarketplaceEditing.Change("options.price",sourceOption,"1200")));var execution=submissions.execute(admin,preview.id(),UUID.randomUUID().toString());worker.runPending();
  assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.SUCCEEDED);
  var linked=productEditor.connect(admin,new MarketplaceProductEditor.Link(entry.token(),target.id(),target.revision(),null,List.of(new MarketplaceProductEditor.OptionLink(sourceOption,target.options().getFirst().id(),false)),List.of(new MarketplaceEditing.Change("options.price",sourceOption,"1200"))));
  assertThat(linked.reference().common()).isEqualTo(target.common());assertThat(linked.reference().options()).isEqualTo(target.options());
  assertThat(db.queryForObject("SELECT draft_id FROM marketplace_listing_mapping WHERE external_product_id='9001'",String.class)).isEqualTo(target.id());
  assertThat(submissions.list(admin,target.id(),0,10).items()).extracting(Execution::id).contains(execution.id());
  assertThat(submissions.get(admin,execution.id()).draftId()).isEqualTo(saved.reference().id());
  assertThatThrownBy(()->submissions.retry(admin,execution.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
 }

 MarketplaceProductEditor.Entry comparisonFixture(String market){
  var d=linked();if(market.equals("COUPANG"))return productEditor.open(admin,"COMMON_EDIT",null,null,d.id());
  if(market.equals("NAVER")){
   d=drafts.save(admin,d.id(),d.revision(),naverInput(d,"앱 저장 이름"));var mapping=new MarketplaceWriteGateway.Mapping(naverDocuments.accountKey(),"2001",List.of(),"2002");db.update("INSERT INTO marketplace_listing_mapping(draft_id,market,account_key,external_product_id,mapping_json,updated_at) VALUES(?,'NAVER',?,?,?,CURRENT_TIMESTAMP(6))",Long.parseLong(d.id()),mapping.accountKey(),mapping.sellerProductId(),json.writeValueAsString(mapping));
   org.mockito.Mockito.doReturn(new MarketplaceProductAdapter.Observation(naverInput(d,"마켓 이름"),null)).when(naverDocuments).observeTarget(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());return productEditor.open(admin,"MARKET_EDIT","NAVER","2001",null);
  }
  var remote=productEditor.open(admin,"MARKET_EDIT",market,market.equals("LOTTEON")?"LO5001":"5001",null);return productEditor.connect(admin,new MarketplaceProductEditor.Link(remote.token(),d.id(),d.revision(),null,List.of(new MarketplaceProductEditor.OptionLink(remote.reference().options().getFirst().id(),d.options().getFirst().id(),false)),List.of()));
 }
 String comparisonPath(String market){return switch(market){case "COUPANG"->"options.price";case "NAVER"->"markets.NAVER.naver.editorInput.fields.originProduct.name";case "LOTTEON"->"lotteon.product.fields.spdNm";default->"partner.fields.prdNm";};}
 MarketplaceEditing.Target comparisonTarget(MarketplaceEditing.Session session,String market){return session.targets().stream().filter(t->t.market().equals(market)).findFirst().orElseThrow();}
 MarketplaceEditing.ComparisonRow comparisonRow(MarketplaceEditing.Target t,String path){return t.comparison().rows().stream().filter(r->r.fields().stream().anyMatch(f->f.path().equals(path))).findFirst().orElseThrow();}
 MarketplaceEditing.Resolve choice(MarketplaceEditing.Session session,String market,String path,String action){var t=comparisonTarget(session,market);return new MarketplaceEditing.Resolve(session.id(),null,session.revision(),market,t.listing().version(),t.comparison().token(),UUID.randomUUID().toString(),List.of(new MarketplaceEditing.Resolution(comparisonRow(t,path).key(),action)));}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"COUPANG","NAVER","LOTTEON","ELEVENST"})
 void bidirectionalComparisonPersistsOnlySelectedProviderAndNeverWritesRemote(String market){
  var entry=comparisonFixture(market);var session=entry.session();if(market.equals("COUPANG"))session=editing.refresh(admin,session.id(),market);
  var before=entry.reference();String path=comparisonPath(market),option=market.equals("COUPANG")?before.options().getFirst().id():null;
  var appValue=market.equals("COUPANG")?"2000":"앱에서 의도한 이름";
  var edited=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(before,List.of(new MarketplaceEditing.TargetChanges(market,List.of(new MarketplaceEditing.Change(path,option,appValue))))));
  var reopened=editing.startLazy(admin,before.id(),market);var row=comparisonRow(comparisonTarget(reopened,market),path);assertThat(row.savedKnown()).isTrue();assertThat(row.saved()).isEqualTo(appValue);assertThat(row.replayAllowed()).isTrue();
  var request=choice(reopened,market,path,"APP");var prepared=productEditor.resolve(admin,request);assertThat(prepared.applied()).isTrue();assertThat(comparisonTarget(prepared.session(),market).pendingChanges()).anyMatch(c->c.path().equals(path)&&Objects.equals(c.optionId(),option));
  var repeated=productEditor.resolve(admin,request);assertThat(repeated.reference().revision()).isEqualTo(prepared.reference().revision());
  var afterReentry=editing.startLazy(admin,before.id(),market);assertThat(comparisonTarget(afterReentry,market).conflicts()).isEmpty();assertThat(comparisonRow(comparisonTarget(afterReentry,market),path).saved()).isEqualTo(appValue);
  var adopted=productEditor.resolve(admin,choice(afterReentry,market,path,"MARKET"));assertThat(adopted.applied()).isTrue();assertThat(adopted.reference().common()).isEqualTo(before.common());assertThat(adopted.reference().options()).isEqualTo(before.options());
  if(!market.equals("COUPANG"))assertThat(adopted.reference().markets().get(Market.COUPANG)).isEqualTo(before.markets().get(Market.COUPANG));
  var finalSession=editing.startLazy(admin,before.id(),market);assertThat(comparisonTarget(finalSession,market).pendingChanges()).noneMatch(c->c.path().equals(path));assertThat(comparisonTarget(finalSession,market).comparison().rows()).noneMatch(r->r.fields().stream().anyMatch(f->f.path().equals(path)));assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void staleComparisonDoesNotAdoptChangedRemoteValueOrDiscardOtherPending(){
  var d=linked();var s=editing.startLazy(admin,d.id(),"COUPANG");String option=d.options().getFirst().id();var edits=List.of(new MarketplaceEditing.Change("options.price",option,"2000"),new MarketplaceEditing.Change("options.quantity",option,"0"));var saved=editing.saveDraft(admin,s.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",edits))));
  var request=choice(saved.session(),"COUPANG","options.price","MARKET");observe(1500L,9L);var stale=productEditor.resolve(admin,request);assertThat(stale.applied()).isFalse();assertThat(stale.reference().revision()).isEqualTo(saved.reference().revision());assertThat(comparisonTarget(stale.session(),"COUPANG").pendingChanges()).hasSize(2);
  var adopted=productEditor.resolve(admin,choice(stale.session(),"COUPANG","options.price","MARKET"));var reopened=editing.startLazy(admin,d.id(),"COUPANG");assertThat(comparisonTarget(reopened,"COUPANG").pendingChanges()).containsExactly(edits.get(1));assertThat(comparisonTarget(reopened,"COUPANG").comparison().rows()).noneMatch(r->r.fields().stream().anyMatch(f->f.path().equals("options.price")));assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void standaloneAdoptionCreatesMarketOnlyAndDoesNotInventAppValue(){
  var entry=productEditor.open(admin,"MARKET_EDIT","COUPANG","9001",null);var t=entry.session().targets().getFirst();var row=comparisonRow(t,"options.price");assertThat(row.savedKnown()).isFalse();assertThat(row.replayAllowed()).isFalse();
  var request=new MarketplaceEditing.Resolve(null,entry.token(),0L,"COUPANG",1L,t.comparison().token(),UUID.randomUUID().toString(),List.of(new MarketplaceEditing.Resolution(row.key(),"MARKET")));var result=productEditor.resolve(admin,request);assertThat(result.applied()).isTrue();assertThat(db.queryForObject("SELECT editor_kind FROM marketplace_draft WHERE id=?",String.class,Long.parseLong(result.reference().id()))).isEqualTo("MARKET_ONLY");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft WHERE editor_kind='COMMON'",Long.class)).isZero();assertThat(gateway.dispatches()).isEmpty();
  assertThatThrownBy(()->productEditor.resolve(admin,new MarketplaceEditing.Resolve(null,entry.token(),0L,"COUPANG",1L,t.comparison().token(),UUID.randomUUID().toString(),request.choices()))).isInstanceOf(MarketplaceEditingFailure.class);
  assertThat(productEditor.resolve(admin,request).reference().revision()).isEqualTo(result.reference().revision());
 }

 void observedPeriod(String start,String end){var p=currentProduct(1000L,9L);org.mockito.Mockito.doReturn(new CoupangEditor.EditorDocument(p.basic(),p.limits(),p.options(),p.delivery(),List.of(new CoupangCatalog.Field("saleStartedAt",start),new CoupangCatalog.Field("saleEndedAt",end)),p.documents())).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));}
 @Test void resolvingOneCoupangDatePreservesOtherDateConflictAcrossReentry(){
  observedPeriod("2026-10-14T16:47:46","2099-12-31T00:00:00");var d=linked();var session=editing.startLazy(admin,d.id(),"COUPANG");String start="markets.COUPANG.coupang.settings.saleStartedAt",end="markets.COUPANG.coupang.settings.saleEndedAt";
  var changes=List.of(new MarketplaceEditing.Change(start,null,"2026-10-13T18:32:58"),new MarketplaceEditing.Change(end,null,"2099-12-31T23:59:00"));var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",changes))));
  observedPeriod("2026-10-14T17:47:46","2099-12-31T00:01:00");var reopened=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(reopened).conflicts()).hasSize(2);var result=productEditor.resolve(admin,choice(reopened,"COUPANG",start,"APP"));assertThat(result.applied()).isTrue();var again=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(again).pendingChanges()).containsExactlyInAnyOrderElementsOf(changes);assertThat(coupang(again).conflicts()).extracting(MarketplaceEditing.Conflict::path).containsExactly(end);
  var adopted=productEditor.resolve(admin,choice(again,"COUPANG",end,"MARKET"));var last=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(last).pendingChanges()).containsExactly(changes.getFirst());assertThat(coupang(last).conflicts()).isEmpty();assertThat(adopted.reference().common()).isEqualTo(d.common());assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void concurrentDuplicateComparisonResolutionCommitsOneRevision()throws Exception{
  var d=linked();var session=editing.startLazy(admin,d.id(),"COUPANG");var edited=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("options.price",d.options().getFirst().id(),"2000"))))));var request=choice(edited.session(),"COUPANG","options.price","MARKET");
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try{var calls=pool.invokeAll(List.of(()->productEditor.resolve(admin,request),()->productEditor.resolve(admin,request)));for(var call:calls)assertThat(((MarketplaceEditing.ResolveResult)call.get()).applied()).isTrue();}finally{pool.shutdownNow();}
  assertThat(drafts.get(admin,d.id()).revision()).isEqualTo(edited.reference().revision()+1);assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_comparison_resolution WHERE request_id=?",Long.class,request.requestId())).isEqualTo(1);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void mediaEvidenceIgnoresCdnRenamingDetectsChangedBytesAndPreservesEmptyOverride()throws Exception{
  String source="https://assets.test/source.png",cdn="https://img1a.coupangcdn.com/image/result.png";
  var bitmap=new java.awt.image.BufferedImage(750,750,java.awt.image.BufferedImage.TYPE_INT_RGB);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(bitmap,"png",out);byte[] original=out.toByteArray();
  var content=new cc.ataglace.molebutter.media.api.R2Images.Content(new cc.ataglace.molebutter.media.api.R2Images.Image("key",source,"image/png",750,750,original.length),original);
  org.mockito.Mockito.doReturn(content).when(r2Images).read(admin,source);org.mockito.Mockito.doReturn(content).when(r2Images).read(admin,cdn);
  var p=currentProduct(1000L,9L);var o=p.options().getFirst();
  var remote=new CoupangEditor.EditOption(o.sellerProductItemId(),o.vendorItemId(),o.itemName(),o.current(),o.currentError(),o.separateCurrentChanges(),o.attributes(),List.of(new CoupangCatalog.Image(0,"REPRESENTATION",cdn)),o.contents(),o.notices(),o.registration(),o.certifications());
  org.mockito.Mockito.doReturn(new CoupangEditor.EditorDocument(p.basic(),p.limits(),List.of(remote),p.delivery(),p.settings(),p.documents())).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));
  var d=linked();String option=d.options().getFirst().id();var session=editing.startLazy(admin,d.id(),"COUPANG");var own=new Image(UUID.randomUUID().toString(),null,source,true,0,option,"REPRESENTATION");
  var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("media.images",option,List.of(own)))))));
  var same=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(same).comparison().rows()).noneMatch(r->r.fields().stream().anyMatch(f->f.path().equals("media.images")));assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_media_comparison",Long.class)).isPositive();
  bitmap.setRGB(0,0,0xff0000);out.reset();javax.imageio.ImageIO.write(bitmap,"png",out);byte[] changed=out.toByteArray();org.mockito.Mockito.doReturn(new cc.ataglace.molebutter.media.api.R2Images.Content(content.image(),changed)).when(r2Images).read(admin,cdn);
  var different=editing.startLazy(admin,d.id(),"COUPANG");assertThat(comparisonRow(coupang(different),"media.images").kind()).isEqualTo("DIFFERENCE");
  var oldChoice=choice(different,"COUPANG","media.images","APP");bitmap.setRGB(1,0,0x00ff00);out.reset();javax.imageio.ImageIO.write(bitmap,"png",out);org.mockito.Mockito.doReturn(new cc.ataglace.molebutter.media.api.R2Images.Content(content.image(),out.toByteArray())).when(r2Images).read(admin,cdn);
  var stale=productEditor.resolve(admin,oldChoice);assertThat(stale.applied()).isFalse();assertThat(stale.reference().revision()).isEqualTo(saved.reference().revision());
  var prepared=productEditor.resolve(admin,choice(stale.session(),"COUPANG","media.images","APP"));assertThat(prepared.applied()).isTrue();assertThat(coupang(prepared.session()).pendingChanges()).anyMatch(c->c.path().equals("media.images"));
  var adopted=productEditor.resolve(admin,choice(prepared.session(),"COUPANG","media.images","MARKET"));assertThat(adopted.applied()).isTrue();assertThat(adopted.reference().common()).isEqualTo(d.common());
  var empty=editing.saveDraft(admin,adopted.session().id(),new MarketplaceEditing.DraftSaveRequest(adopted.reference(),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("media.images",option,List.of()))))));
  var reentry=editing.startLazy(admin,d.id(),"COUPANG");assertThat(comparisonRow(coupang(reentry),"media.images").saved()).isEqualTo(List.of());assertThat(coupang(reentry).pendingChanges()).anyMatch(c->c.path().equals("media.images")&&((List<?>)c.value()).isEmpty());assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void forgedComparisonTokenGenerationAndCrossActorCannotPersistAnyDecision(){
  var d=linked();var s=editing.startLazy(admin,d.id(),"COUPANG");var t=coupang(s);var row=comparisonRow(t,"options.price");var choices=List.of(new MarketplaceEditing.Resolution(row.key(),"MARKET"));
  assertThatThrownBy(()->productEditor.resolve(admin,new MarketplaceEditing.Resolve(s.id(),null,s.revision(),"COUPANG",1L,"forged",UUID.randomUUID().toString(),choices))).isInstanceOf(MarketplaceEditingFailure.class);
  assertThatThrownBy(()->productEditor.resolve(admin,new MarketplaceEditing.Resolve(s.id(),null,s.revision(),"COUPANG",2L,t.comparison().token(),UUID.randomUUID().toString(),choices))).isInstanceOf(MarketplaceEditingFailure.class);
  assertThatThrownBy(()->productEditor.resolve(otherAdmin,new MarketplaceEditing.Resolve(s.id(),null,s.revision(),"COUPANG",1L,t.comparison().token(),UUID.randomUUID().toString(),choices))).isInstanceOf(MarketplaceEditingFailure.class);
  assertThat(drafts.get(admin,d.id()).revision()).isEqualTo(d.revision());assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_comparison_resolution",Long.class)).isZero();assertThat(gateway.dispatches()).isEmpty();
 }

 @Test void olderComparisonPolicyCannotPersistAndKeepsDraftInput(){var d=linked();var session=editing.startLazy(admin,d.id(),"COUPANG");var request=choice(session,"COUPANG","options.price","MARKET");db.update("UPDATE marketplace_edit_session SET session_json=JSON_SET(session_json,'$.targets[0].comparison.policyVersion',1) WHERE id=?",Long.parseLong(session.id()));assertThatThrownBy(()->productEditor.resolve(admin,request)).isInstanceOf(MarketplaceEditingFailure.class);assertThat(drafts.get(admin,d.id())).isEqualTo(d);assertThat(gateway.dispatches()).isEmpty();}
 @Test void comparisonResolveHttpRequiresCsrfAndPersistsThroughAuthenticatedRoute()throws Exception{
  var d=linked();var s=editing.startLazy(admin,d.id(),"COUPANG");var request=choice(s,"COUPANG","options.price","MARKET");var browser=new Browser(admin);
  assertThat(browser.send("POST","/api/marketplaces/editor/resolve",request,false).statusCode()).isEqualTo(403);
  var response=browser.send("POST","/api/marketplaces/editor/resolve",request,true);assertThat(response.statusCode()).isEqualTo(200);var result=json.treeToValue(json.readTree(response.body()).path("data"),MarketplaceEditing.ResolveResult.class);assertThat(result.applied()).isTrue();assertThat(result.reference().revision()).isEqualTo(d.revision()+1);assertThat(result.reference().common()).isEqualTo(d.common());assertThat(gateway.dispatches()).isEmpty();
 }
 Document newInput(){return json.readValue("""
  {"common":{"productCode":"NEW-1","name":"신규 참조"},"options":[{"id":"10000000-1000-4000-8000-000000000001","name":"블랙","sku":"SKU-1","price":"1000","quantity":"9","attributes":[]}],"stockMode":"OPTION","productQuantity":"","services":[],"media":{"images":[],"contents":[]},"delivery":{},"selectedMarkets":["COUPANG"],"markets":{"COUPANG":{"categoryCode":"123"}}}
  """,Document.class);}
 MarketplaceEditing.Target coupang(MarketplaceEditing.Session s){return s.targets().stream().filter(t->t.market().equals("COUPANG")).findFirst().orElseThrow();}
 MarketplaceEditing.PrepareRequest request(MarketplaceEditing.Session s,MarketplaceEditing.Change... changes){return new MarketplaceEditing.PrepareRequest(s.draftId(),s.revision(),s.id(),false,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(changes))));}
 Document changedName(Document d,String name){var c=d.common();return new Document(d.id(),d.revision(),new Common(c.productCode(),name,c.productName(),c.brand(),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly()),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());}
 void observe(Long price,Long quantity){org.mockito.Mockito.doReturn(currentProduct(price,quantity)).when(testCatalog).edit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq("9001"),org.mockito.ArgumentMatchers.nullable(String.class));}
 Document naverInput(Document d,String name){
  var config=d.markets().get(Market.NAVER);var old=config==null?null:config.naver();var value=new NaverEditor.Input(Map.of("originProduct.name",name),"NONE",List.of(),List.of(),List.of(),"");
  var n=old==null?new Naver("","SALE","NEW","00","","",List.of(),List.of(),null,null,null,value):new Naver(old.channelId(),old.status(),old.saleType(),old.originCode(),old.deliveryTemplateId(),old.afterServiceTelephone(),old.attributes(),old.notices(),old.channelProductName(),old.naverShoppingRegistration(),old.channelProductDisplayStatusType(),value,old.brandId(),old.editorState());
  var markets=new EnumMap<Market,MarketConfig>(Market.class);markets.putAll(d.markets());markets.put(Market.NAVER,new MarketConfig("500001",null,null,n,null));return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),List.of(Market.COUPANG,Market.NAVER),markets);
 }
 MarketplaceEditing.Target naver(MarketplaceEditing.Session s){return s.targets().stream().filter(t->t.market().equals("NAVER")).findFirst().orElseThrow();}
 @Test void commonAndNaverPendingSaveAtomicallyAndReopenAgainstTrustedBaseline()throws Exception{
  var original=linked();var d=drafts.save(admin,original.id(),original.revision(),naverInput(original,"원래 네이버"));
  var mapping=new MarketplaceWriteGateway.Mapping(naverDocuments.accountKey(),"2001",List.of(),"2002");
  db.update("INSERT INTO marketplace_listing_mapping(draft_id,market,account_key,external_product_id,mapping_json,applied_revision,updated_at) VALUES(?,'NAVER',?,?,?,NULL,CURRENT_TIMESTAMP(6))",Long.parseLong(d.id()),mapping.accountKey(),mapping.sellerProductId(),json.writeValueAsString(mapping));
  org.mockito.Mockito.doReturn(new MarketplaceProductAdapter.Observation(naverInput(d,"원래 네이버"),new NaverEditor.Context(new NaverEditor.Limits(false,false,true,false,""),List.of()))).when(naverDocuments).observeTarget(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  var s=editing.start(admin,d.id());assertThat(s.targets()).extracting(MarketplaceEditing.Target::mode).containsOnly("UPDATE");
  var change=new MarketplaceEditing.Change("markets.NAVER.naver.editorInput.fields.originProduct.name",null,"작성 네이버");var browser=new Browser(admin);
  var body=new MarketplaceEditing.DraftSaveRequest(changedName(d,"공통 변경"),List.of(new MarketplaceEditing.TargetChanges("NAVER",List.of(change))));
  var response=browser.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/draft",body,true);assertThat(response.statusCode()).isEqualTo(200);var saved=json.treeToValue(json.readTree(response.body()).path("data"),MarketplaceEditing.DraftSaveResult.class);
  assertThat(saved.reference().revision()).isEqualTo(d.revision()+1);assertThat(saved.reference().common().name()).isEqualTo("공통 변경");assertThat(saved.reference().markets().get(Market.COUPANG)).isEqualTo(d.markets().get(Market.COUPANG));assertThat(naver(saved.session()).pendingChanges()).containsExactly(change);
  var intent=json.readValue(db.queryForObject("SELECT intent_json FROM marketplace_draft_target_edit WHERE draft_id=? AND market='NAVER'",String.class,Long.parseLong(d.id())),MarketplaceWriteGateway.EditIntent.class);assertThat(intent.observed().markets().get(Market.NAVER).naver().editorInput().fields().get("originProduct.name")).isEqualTo("원래 네이버");
  assertThat(browser.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/draft",body,true).statusCode()).isEqualTo(409);
  db.update("DELETE FROM marketplace_edit_session WHERE draft_id=?",Long.parseLong(d.id()));
  org.mockito.Mockito.doReturn(new MarketplaceProductAdapter.Observation(naverInput(saved.reference(),"외부 변경"),null)).when(naverDocuments).observeTarget(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  var reopened=editing.start(admin,d.id());assertThat(naver(reopened).pendingChanges()).containsExactly(change);assertThat(naver(reopened).conflicts()).hasSize(1);assertThat(naver(reopened).conflicts().getFirst().before()).isEqualTo("원래 네이버");assertThat(naver(reopened).conflicts().getFirst().current()).isEqualTo("외부 변경");
  assertThatThrownBy(()->editing.saveDraft(admin,reopened.id(),new MarketplaceEditing.DraftSaveRequest(saved.reference(),List.of(new MarketplaceEditing.TargetChanges("NAVER",List.of(change)))))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
  assertThat(drafts.get(admin,d.id()).revision()).isEqualTo(saved.reference().revision());assertThat(gateway.dispatches()).isEmpty();
  org.mockito.Mockito.doReturn(new MarketplaceProductAdapter.Observation(naverInput(saved.reference(),"작성 네이버"),null)).when(naverDocuments).observeTarget(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  assertThat(naver(editing.start(admin,d.id())).pendingChanges()).isEmpty();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_target_edit WHERE draft_id=?",Long.class,Long.parseLong(d.id()))).isZero();
 }
 @Test void naverDeletedChannelRecreationPreservesProviderEditsAndCommonOptionUuids(){
  var original=linked();var d=drafts.save(admin,original.id(),original.revision(),naverInput(original,"원래 네이버"));var mapping=new MarketplaceWriteGateway.Mapping(naverDocuments.accountKey(),"2001",List.of(),"2002");db.update("INSERT INTO marketplace_listing_mapping(draft_id,market,account_key,external_product_id,mapping_json,updated_at) VALUES(?,'NAVER',?,?,?,CURRENT_TIMESTAMP(6))",Long.parseLong(d.id()),mapping.accountKey(),mapping.sellerProductId(),json.writeValueAsString(mapping));
  org.mockito.Mockito.doReturn(new MarketplaceProductAdapter.Observation(naverInput(d,"원래 네이버"),null)).when(naverDocuments).observeTarget(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());var session=editing.startLazy(admin,d.id(),"NAVER");var change=new MarketplaceEditing.Change("markets.NAVER.naver.editorInput.fields.originProduct.name",null,"보관된 네이버 이름");var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("NAVER",List.of(change)))));
  org.mockito.Mockito.doThrow(new MarketplaceListingMissing("NAVER_CHANNEL_NOT_FOUND")).when(naverDocuments).observeTarget(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());var deleted=editing.startLazy(admin,d.id(),"NAVER");assertThat(naver(deleted).status()).isEqualTo("DELETED");var recreated=productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),saved.reference().revision(),"NAVER",1L,UUID.randomUUID().toString(),saved.reference(),List.of()));var target=naver(recreated.session());assertThat(target.mode()).isEqualTo("CREATE");assertThat(target.document().markets().get(Market.NAVER).naver().editorInput().fields().get("originProduct.name")).isEqualTo("보관된 네이버 이름");assertThat(target.document().markets().get(Market.NAVER).naver().channelId()).isEmpty();assertThat(recreated.reference().common()).isEqualTo(saved.reference().common());assertThat(recreated.reference().options()).isEqualTo(saved.reference().options());assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_mapping WHERE draft_id=? AND market='COUPANG'",Long.class,Long.parseLong(d.id()))).isEqualTo(1);assertThat(gateway.dispatches()).isEmpty();
 }
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
 @Test void coupangCreateEditsPersistSeparatelyAcrossNewSessionsAndCommonChanges(){
  var d=drafts.create(admin,newInput());var s=editing.start(admin,d.id());String option=d.options().getFirst().id();
  var edits=List.of(new MarketplaceEditing.Change("common.name",null,"마켓 전용 이름"),new MarketplaceEditing.Change("common.manufacturer",null,""),new MarketplaceEditing.Change("options.quantity",option,"0"),new MarketplaceEditing.Change("delivery.charge",null,"0"),new MarketplaceEditing.Change("markets.COUPANG.categoryCode",null,"777"),new MarketplaceEditing.Change("markets.COUPANG.coupang.options.registration.originalPrice",option,"0"),new MarketplaceEditing.Change("markets.COUPANG.coupang.options.notices",option,List.of(new CoupangCatalog.Notice("기타 재화","품명","마켓 전용"))),new MarketplaceEditing.Change("media.contents",option,List.of(new Content(UUID.randomUUID().toString(),"HTML","<p>마켓 설명</p>",option))));
  var saved=editing.saveDraft(admin,s.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",edits))));
  assertThat(saved.reference().common()).isEqualTo(d.common());assertThat(saved.reference().media()).isEqualTo(d.media());assertThat(coupang(saved.session()).pendingChanges()).hasSize(edits.size());
  var reopened=editing.start(admin,d.id());assertThat(coupang(reopened).pendingChanges()).hasSize(edits.size());assertThat(coupang(reopened).conflicts()).isEmpty();
  var fresh=saved.reference();var c=fresh.common();var common=new Common(c.productCode(),"새 공통 이름",c.productName(),c.brand(),"새 공통 제조사",c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly());
  var changed=new Document(fresh.id(),fresh.revision(),common,fresh.options(),fresh.stockMode(),fresh.productQuantity(),fresh.services(),fresh.media(),fresh.delivery(),fresh.selectedMarkets(),fresh.markets());
  var again=editing.saveDraft(admin,reopened.id(),new MarketplaceEditing.DraftSaveRequest(changed,List.of(new MarketplaceEditing.TargetChanges("COUPANG",edits))));
  assertThat(coupang(again.session()).document().common().name()).isEqualTo("새 공통 이름");assertThat((tools.jackson.databind.JsonNode)json.valueToTree(coupang(editing.start(admin,d.id())).pendingChanges())).isEqualTo(json.valueToTree(edits));
  var current=editing.start(admin,d.id());editing.saveDraft(admin,current.id(),new MarketplaceEditing.DraftSaveRequest(again.reference(),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of()))));
  assertThat(coupang(editing.start(admin,d.id())).pendingChanges()).isEmpty();assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void coupangUpdateEditsPersistAndReopenWithExternalConflict(){
  var d=linked();observe(1000L,9L);var s=editing.start(admin,d.id());var change=new MarketplaceEditing.Change("options.price",d.options().getFirst().id(),"2000");
  var saved=editing.saveDraft(admin,s.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(change)))));
  assertThat(saved.reference().options()).isEqualTo(d.options());observe(1500L,9L);var reopened=editing.start(admin,d.id());assertThat(coupang(reopened).pendingChanges()).hasSize(1);assertThat(coupang(reopened).conflicts()).hasSize(1);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void savedCoupangImageRemainsPinnedAfterPendingCancelForExplicitReplay()throws Exception{
  var image=new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);var asset=assets.upload(admin,bytes.toByteArray());
  var d=drafts.create(admin,newInput());var session=editing.start(admin,d.id());String option=d.options().getFirst().id();
  var change=new MarketplaceEditing.Change("media.images",option,List.of(new Image(UUID.randomUUID().toString(),asset.id(),asset.url(),true,0,option)));
  var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(change)))));
  assertThat(saved.reference().media().images()).isEmpty();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,Long.parseLong(d.id()),asset.id())).isEqualTo(1);
  var legacy=drafts.save(admin,d.id(),saved.reference().revision(),saved.reference());
  var reopened=editing.start(admin,d.id());var again=editing.saveReference(admin,reopened.id(),legacy);assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,Long.parseLong(d.id()),asset.id())).isEqualTo(1);
  editing.saveDraft(admin,again.id(),new MarketplaceEditing.DraftSaveRequest(drafts.get(admin,d.id()),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of()))));assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=?",Long.class,Long.parseLong(d.id()))).isEqualTo(1);assertThat(gateway.dispatches()).isEmpty();
 }
 @Test void createIntentIsRetiredOnlyAfterRegistrationIsConfirmed(){
  var d=drafts.create(admin,newInput());var session=editing.start(admin,d.id());var change=new MarketplaceEditing.Change("common.name",null,"등록할 이름");var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(change)))));
  var mapping=new MarketplaceWriteGateway.Mapping(db.queryForObject("SELECT account_key FROM marketplace_draft_target_edit WHERE draft_id=?",String.class,Long.parseLong(d.id())),"9001",List.of(new MarketplaceWriteGateway.OptionMapping(d.options().getFirst().id(),"7001","8001")));
  db.update("INSERT INTO marketplace_listing_mapping(draft_id,market,account_key,external_product_id,mapping_json,applied_revision,updated_at) VALUES(?,'COUPANG',?,'9001',?,NULL,CURRENT_TIMESTAMP(6))",Long.parseLong(d.id()),mapping.accountKey(),json.writeValueAsString(mapping));
  assertThat(coupang(editing.start(admin,d.id())).status()).isEqualTo("FAILED");assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_target_edit WHERE draft_id=?",Long.class,Long.parseLong(d.id()))).isEqualTo(1);
  db.update("UPDATE marketplace_listing_mapping SET applied_revision=? WHERE draft_id=?",saved.reference().revision(),Long.parseLong(d.id()));var confirmed=editing.start(admin,d.id());assertThat(coupang(confirmed).mode()).isEqualTo("UPDATE");assertThat(coupang(confirmed).status()).isEqualTo("READY");assertThat(coupang(confirmed).pendingChanges()).isEmpty();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_target_edit WHERE draft_id=?",Long.class,Long.parseLong(d.id()))).isZero();assertThat(gateway.dispatches()).isEmpty();
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"ACCEPTED,false,false","CONFIRMED,true,false","ACCEPTED,false,true","CONFIRMED,true,true"}) void acceptedCreateIntentSurvivesRevisionAdvanceWithoutFalseMappingConflict(String createState,boolean approvalFailure,boolean placeholder){
  var d=drafts.create(admin,newInput());var initial=editing.start(admin,d.id());var changes=List.of(new MarketplaceEditing.Change("common.name",null,"등록할 이름"));var saved=editing.saveDraft(admin,initial.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",changes))));
  if(approvalFailure){gateway.saveThenApprove();gateway.outcomes("APPROVAL","FAILED");}var preview=submissions.prepare(admin,new MarketplaceEditing.PrepareRequest(d.id(),saved.reference().revision(),saved.session().id(),false,List.of(new MarketplaceEditing.TargetChanges("COUPANG",changes))));assertThat(preview.executable()).isTrue();gateway.outcomes("CREATE",createState);var execution=submissions.execute(admin,preview.id(),UUID.randomUUID().toString());worker.runPending();if(approvalFailure)worker.runPending();assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(approvalFailure?Status.PARTIAL:Status.ACCEPTED);
  // The real Coupang writer stores a CREATE option mapping before external IDs exist.
  if(placeholder){var tree=json.readTree(db.queryForObject("SELECT prepared_json FROM marketplace_submission_preview WHERE id=?",String.class,Long.parseLong(preview.id()))).asObject();var m=tree.putObject("mapping");m.put("accountKey",tree.path("accountKey").asString());m.putNull("sellerProductId");m.putNull("channelProductId");m.putArray("options").addObject().put("optionId",d.options().getFirst().id()).putNull("sellerProductItemId").putNull("vendorItemId");db.update("UPDATE marketplace_submission_preview SET prepared_json=? WHERE id=?",json.writeValueAsString(tree),Long.parseLong(preview.id()));}
  // Historical common-only saves advanced the pending revision without rebinding its CREATE intent.
  var after=drafts.save(admin,d.id(),saved.reference().revision(),changedName(saved.reference(),"새 공통 이름"));db.update("UPDATE marketplace_draft_target_edit SET draft_revision=? WHERE draft_id=?",after.revision(),Long.parseLong(d.id()));
  var pendingTree=json.readTree(db.queryForObject("SELECT intent_json FROM marketplace_draft_target_edit WHERE draft_id=? AND market='COUPANG'",String.class,Long.parseLong(d.id()))).asObject();pendingTree.path("observed").path("markets").asObject().putObject("LOTTEON").put("categoryCode","UNRELATED_METADATA");db.update("UPDATE marketplace_draft_target_edit SET intent_json=? WHERE draft_id=? AND market='COUPANG'",json.writeValueAsString(pendingTree),Long.parseLong(d.id()));
  var reopened=editing.startLazy(admin,d.id(),"COUPANG");assertThat(coupang(reopened).status()).isEqualTo("READY");assertThat(coupang(reopened).mode()).isEqualTo("UPDATE");assertThat(coupang(reopened).pendingChanges()).isEmpty();assertThat(coupang(reopened).document().common().name()).isEqualTo("조회 상품");assertThat(gateway.dispatches()).containsExactlyElementsOf(approvalFailure?List.of("CREATE","APPROVAL"):List.of("CREATE"));
  var commonSaved=editing.saveDraft(admin,reopened.id(),new MarketplaceEditing.DraftSaveRequest(after,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of()))));assertThat(commonSaved.reference().revision()).isGreaterThan(after.revision());if(!approvalFailure){var intended=editing.saveDraft(admin,commonSaved.session().id(),new MarketplaceEditing.DraftSaveRequest(commonSaved.reference(),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(new MarketplaceEditing.Change("common.name",null,"승인 중 개별 변경"))))));assertThat(coupang(intended.session()).pendingChanges()).hasSize(1);assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.ACCEPTED);}
 }
 @Test void recreatedImageIsPinnedByHistoryAndShadowAcrossCommonOnlySave()throws Exception{
  var image=new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);var asset=assets.upload(admin,bytes.toByteArray());var d=linked();var session=editing.startLazy(admin,d.id(),"COUPANG");String option=d.options().getFirst().id();var change=new MarketplaceEditing.Change("media.images",option,List.of(new Image(UUID.randomUUID().toString(),asset.id(),asset.url(),true,0,option)));
  var saved=editing.saveDraft(admin,session.id(),new MarketplaceEditing.DraftSaveRequest(d,List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of(change)))));missingCoupang();var deleted=editing.startLazy(admin,d.id(),"COUPANG");var recreated=productEditor.recreate(admin,new MarketplaceProductEditor.Recreate(deleted.id(),saved.reference().revision(),"COUPANG",1L,UUID.randomUUID().toString(),saved.reference(),List.of()));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_history_asset WHERE asset_id=?",Long.class,asset.id())).isEqualTo(1);var next=editing.saveReference(admin,recreated.session().id(),recreated.reference());assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,Long.parseLong(d.id()),asset.id())).isEqualTo(1);assertThat(coupang(editing.startLazy(admin,d.id(),"COUPANG")).document().media().images()).extracting(Image::assetId).contains(asset.id());assertThat(next.revision()).isGreaterThan(recreated.reference().revision());assertThat(gateway.dispatches()).isEmpty();
  new org.springframework.transaction.support.TransactionTemplate(imageTransactions).executeWithoutResult(t->assets.replaceReferences(admin,d.id(),Set.of()));db.update("UPDATE marketplace_asset SET pending_delete_at=NOW()-INTERVAL 2 DAY WHERE id=?",asset.id());assetStore.reapUnreferenced();assertThat(assets.metadata(admin,asset.id()).id()).isEqualTo(asset.id());
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
  assertThat(saved.id()).isEqualTo(s.id());assertThat(saved.revision()).isEqualTo(s.revision()+1);assertThat(coupang(saved).document()).isEqualTo(coupang(s).document());assertThat(coupang(saved).comparison().token()).isNotEqualTo(coupang(s).comparison().token());assertThat(drafts.get(admin,d.id()).common().name()).isEqualTo("공통 참조 변경");assertThat(coupang(saved).document().common().name()).isEqualTo("조회 상품");assertThat(gateway.dispatches()).isEmpty();
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
  for(long actor:List.of(staff,viewer)){var b=new Browser(actor);assertThat(b.send("POST","/api/marketplaces/drafts/"+d.id()+"/edit-sessions",null,true).statusCode()).isEqualTo(403);assertThat(b.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/reference",d,true).statusCode()).isEqualTo(403);assertThat(b.send("PUT","/api/marketplaces/edit-sessions/"+s.id()+"/draft",new MarketplaceEditing.DraftSaveRequest(d,List.of()),true).statusCode()).isEqualTo(403);assertThat(b.send("POST","/api/marketplaces/edit-sessions/"+s.id()+"/refresh",Map.of("market","COUPANG"),true).statusCode()).isEqualTo(403);}
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
