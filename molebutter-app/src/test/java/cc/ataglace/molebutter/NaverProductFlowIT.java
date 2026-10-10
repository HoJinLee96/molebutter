package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissions.*;
import cc.ataglace.molebutter.marketplace.internal.DefaultMarketplaceSubmissions;
import cc.ataglace.molebutter.marketplace.internal.NaverProductTestGateway;
import cc.ataglace.molebutter.identity.internal.UserRepository;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "product.refresh.worker-enabled=false","marketplace.submissions.worker-enabled=false","spring.config.import=classpath:bootstrap-admin-test.properties",
 "spring.datasource.username=test_app","spring.datasource.password=isolated-test-app-password",
 "spring.flyway.user=test_migrator","spring.flyway.password=isolated-test-migration-password",
 "spring.data.redis.host=127.0.0.1","spring.data.redis.password=","mail.provider=test",
 "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes","auth.cookie.secure=false","cloudflare.r2.enabled=false"
})
@ActiveProfiles("bootstrap-admin")
@Import({AuthenticationFlowIT.MailConfiguration.class,NaverProductTestGateway.Configuration.class})
class NaverProductFlowIT {
 @DynamicPropertySource static void databases(DynamicPropertyRegistry registry){AuthenticationFlowIT.databases(registry);}
 @Autowired NaverProductRegistrations registrations;@Autowired NaverProductSaving saving;@Autowired MarketplaceSubmissions submissions;
 @Autowired DefaultMarketplaceSubmissions worker;@Autowired NaverProductTestGateway gateway;@Autowired JdbcTemplate db;@Autowired UserRepository users;@Autowired ObjectMapper json;
 long admin;
 @BeforeEach void setup(){clear();gateway.reset();admin=users.findByEmail("admin@example.com").orElseThrow().getId();}
 @AfterEach void cleanup(){clear();}
 void clear(){for(String table:List.of("marketplace_execution_attempt","marketplace_execution_step","marketplace_execution_target","marketplace_execution","marketplace_execution_asset","marketplace_submission_preview","marketplace_edit_session","marketplace_listing_mapping","marketplace_submission_account","marketplace_asset_publication","marketplace_draft_asset","marketplace_draft","marketplace_asset"))db.update("DELETE FROM "+table);}
 NaverEditor.Input input(){
  var fields=new LinkedHashMap<String,Object>();fields.put("originProduct.name","합성 스마트스토어 상품");fields.put("originProduct.leafCategoryId","50000001");fields.put("originProduct.statusType","SALE");fields.put("originProduct.saleType","NEW");fields.put("originProduct.salePrice",1000L);fields.put("originProduct.stockQuantity",0L);
  fields.put("originProduct.detailAttribute.afterServiceInfo.afterServiceTelephoneNumber","010-0000-0000");fields.put("originProduct.detailAttribute.afterServiceInfo.afterServiceGuideContent","합성 AS 안내");fields.put("originProduct.detailAttribute.originAreaInfo.originAreaCode","00");fields.put("originProduct.detailAttribute.minorPurchasable",true);fields.put("originProduct.detailAttribute.taxType","TAX");fields.put("originProduct.detailAttribute.sellerCodeInfo.sellerManagementCode","SYNTHETIC-NAVER-1");
  fields.put("smartstoreChannelProduct.naverShoppingRegistration",true);fields.put("smartstoreChannelProduct.channelProductDisplayStatusType","ON");
  fields.put("originProduct.detailAttribute.productInfoProvidedNotice",Map.of("productInfoProvidedNoticeType","BAG","bag",Map.of("type","상품 상세페이지 참조","material","상품 상세페이지 참조","color","상품 상세페이지 참조","size","상품 상세페이지 참조","manufacturer","상품 상세페이지 참조","caution","상품 상세페이지 참조","warrantyPolicy","상품 상세페이지 참조","afterServiceDirector","상품 상세페이지 참조","returnCostReason","상품 상세페이지 참조","noRefundReason","상품 상세페이지 참조")));
  return new NaverEditor.Input(fields,"NONE",List.of(),List.of(),List.of(new NaverEditor.Image(UUID.randomUUID().toString(),null,"https://assets.molebutter.link/synthetic.jpg",true,0)),"<p>합성 상세설명</p>");
 }
 NaverEditor.Input changed(NaverEditor.Input input,String path,Object value){var f=new HashMap<>(input.fields());f.put(path,value);return new NaverEditor.Input(f,input.optionMode(),input.optionNames(),input.options(),input.images(),input.description());}
 Execution execute(Preview preview){assertThat(preview.executable()).as(preview.targets().toString()).isTrue();return submissions.execute(admin,preview.id(),UUID.randomUUID().toString());}
 @Test void incompleteDraftAndRevisionConflictDoNotSendAnyRemoteWrite(){
  var incomplete=new NaverEditor.Input(Map.of(),"NONE",List.of(),List.of(),List.of(),"");var d=registrations.create(admin,incomplete);assertThat(registrations.get(admin,d.id()).input().fields()).isEmpty();assertThat(gateway.writes()).isEmpty();
  var saved=registrations.save(admin,d.id(),new NaverProductRegistrations.Save(d.revision(),input()));assertThat(saved.revision()).isGreaterThan(d.revision());
  assertThatThrownBy(()->registrations.save(admin,d.id(),new NaverProductRegistrations.Save(d.revision(),incomplete))).isInstanceOf(MarketplaceDraftFailure.class);assertThat(gateway.writes()).isEmpty();
  assertThat(db.queryForObject("SELECT editor_kind FROM marketplace_draft WHERE id=?",String.class,Long.parseLong(d.id()))).isEqualTo("NAVER_REGISTRATION");
 }
 @Test void confirmedCreateMapsOriginAndChannelAndCannotCreateTwice(){
  var d=registrations.create(admin,input());var p=registrations.prepare(admin,d.id(),new NaverProductRegistrations.Prepare(d.revision()));assertThat(gateway.writes()).isEmpty();
  var key=UUID.randomUUID().toString();var e=submissions.execute(admin,p.id(),key);assertThat(submissions.execute(admin,p.id(),key).id()).isEqualTo(e.id());worker.runPending();
  assertThat(gateway.writes()).containsExactly("POST /v2/products");assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
  var saved=registrations.get(admin,d.id());assertThat(saved.externalProductId()).isEqualTo("101");assertThat(saved.blocked()).isTrue();
  assertThat(db.queryForObject("SELECT mapping_json FROM marketplace_listing_mapping WHERE draft_id=? AND market='NAVER'",String.class,Long.parseLong(d.id()))).contains("\"sellerProductId\":\"101\"","\"channelProductId\":\"202\"");
  assertThatThrownBy(()->registrations.prepare(admin,d.id(),new NaverProductRegistrations.Prepare(d.revision()))).isInstanceOf(MarketplaceDraftFailure.class);
 }
 @Test void mappedProductReopensWithoutChannelNumberAndStillRejectsAnotherChannel(){
  var draft=registrations.create(admin,input());var created=execute(registrations.prepare(admin,draft.id(),new NaverProductRegistrations.Prepare(draft.revision())));worker.runPending();
  assertThat(submissions.get(admin,created.id()).status()).isEqualTo(Status.SUCCEEDED);
  gateway.omitChannelNumber();var observation=saving.observe(admin,"101");assertThat(observation.document().channelProductNo()).isNull();
  var modified=execute(saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),changed(observation.document().input(),"originProduct.salePrice",2000L))));worker.runPending();
  assertThat(submissions.get(admin,modified.id()).status()).isEqualTo(Status.SUCCEEDED);
  assertThat(gateway.writes()).containsExactly("POST /v2/products","PUT /v2/products/origin-products/101");
  assertThat(db.queryForObject("SELECT mapping_json FROM marketplace_listing_mapping WHERE draft_id=? AND market='NAVER'",String.class,Long.parseLong(draft.id()))).contains("\"channelProductId\":\"202\"");
  gateway.channelNumber(999);var conflict=saving.observe(admin,"101");
  assertThatThrownBy(()->saving.prepare(admin,"101",new NaverProductSaving.Prepare(conflict.token(),changed(conflict.document().input(),"originProduct.salePrice",3000L)))).isInstanceOf(MarketplaceEditingFailure.class);
  assertThat(gateway.writes()).hasSize(2);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void nonAdvertiserCreateRequiresTheResponseIdBeforeMappingAndNextEdit(boolean loseResponse){
  gateway.shoppingAdvertiser(false);var draft=registrations.create(admin,input());
  var created=execute(registrations.prepare(admin,draft.id(),new NaverProductRegistrations.Prepare(draft.revision())));
  if(loseResponse)gateway.loseNextResponse();worker.runPending();
  if(loseResponse){
   assertThat(submissions.get(admin,created.id()).status()).isEqualTo(Status.UNKNOWN);submissions.reconcile(admin,created.id());worker.runPending();
   assertUnknownAndLocked(draft,created);return;
  }
  assertThat(submissions.get(admin,created.id()).status()).isEqualTo(Status.SUCCEEDED);
  assertThat(gateway.source().path("smartstoreChannelProduct").path("naverShoppingRegistration").asBoolean()).isFalse();
  gateway.omitChannelNumber();var observation=saving.observe(admin,"101");
  var modified=execute(saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),changed(observation.document().input(),"originProduct.salePrice",2000L))));worker.runPending();
  assertThat(submissions.get(admin,modified.id()).status()).isEqualTo(Status.SUCCEEDED);
  assertThat(gateway.writes()).containsExactly("POST /v2/products","PUT /v2/products/origin-products/101");
 }
 @Test void responseLossBlocksNewPostAndOnlyAllowsReadReconciliation(){
  var d=registrations.create(admin,input());var e=execute(registrations.prepare(admin,d.id(),new NaverProductRegistrations.Prepare(d.revision())));gateway.loseNextResponse();worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.UNKNOWN);assertThatThrownBy(()->submissions.retry(admin,e.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
  assertThatThrownBy(()->registrations.prepare(admin,d.id(),new NaverProductRegistrations.Prepare(d.revision()))).isInstanceOf(MarketplaceDraftFailure.class);
  for(int i=0;i<3;i++){submissions.reconcile(admin,e.id());worker.runPending();assertUnknownAndLocked(d,e);}
  assertThat(gateway.writes()).containsExactly("POST /v2/products");
 }
 void assertUnknownAndLocked(NaverProductRegistrations.Draft draft,Execution execution){
  assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.UNKNOWN);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_listing_mapping WHERE draft_id=?",Integer.class,Long.parseLong(draft.id()))).isZero();
  assertThat(registrations.get(admin,draft.id()).blocked()).isTrue();
  assertThatThrownBy(()->submissions.retry(admin,execution.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
  assertThatThrownBy(()->registrations.prepare(admin,draft.id(),new NaverProductRegistrations.Prepare(draft.revision()))).isInstanceOf(MarketplaceDraftFailure.class);
 }
 @Test void nameOnlyUpdateDoesNotRestoreStockSoldImmediatelyBeforePut(){
  gateway.seed(changed(input(),"originProduct.stockQuantity",5L));var observation=saving.observe(admin,"101");
  var edited=changed(observation.document().input(),"originProduct.name","name changed without stock edit");
  var e=execute(saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),edited)));gateway.stockBeforeNextPut(4);worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
  assertThat(gateway.source().path("originProduct").path("stockQuantity").asLong()).isEqualTo(4);
  String request=db.queryForObject("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=?",String.class,Long.parseLong(e.id()));
  var body=json.readTree(json.readTree(request).path("bodyJson").asString());assertThat(body.path("originProduct").has("stockQuantity")).isFalse();
 }
 @Test void editPreservesUnchangedDescriptionAndUnmappedRemoteFieldsAndZeroStock(){
  gateway.seed(input());var observation=saving.observe(admin,"101");var edited=changed(observation.document().input(),"originProduct.salePrice",2000L);
  var e=execute(saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),edited)));worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);assertThat(gateway.writes()).containsExactly("PUT /v2/products/origin-products/101");
  assertThat(gateway.source().path("originProduct").path("preservedRemote").path("value").asString()).isEqualTo("keep-this-remote-field");assertThat(gateway.source().path("originProduct").path("stockQuantity").asLong()).isZero();
  String request=db.queryForObject("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=?",String.class,Long.parseLong(e.id()));assertThat(json.readTree(request).path("bodyJson").asString()).doesNotContain("detailContent");
 }
 @Test void externalChangedSelectedFieldBlocksWriteAndUnchangedSaveHasNoSteps(){
  gateway.seed(input());var observation=saving.observe(admin,"101");var noChange=saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),observation.document().input()));assertThat(noChange.executable()).isFalse();
  gateway.salePrice(3000L);var conflict=saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),changed(observation.document().input(),"originProduct.salePrice",2000L)));
  assertThat(conflict.executable()).isFalse();assertThat(conflict.targets().getFirst().issues()).isNotEmpty();assertThat(gateway.writes()).isEmpty();
 }
 @Test void firstImportWithoutChannelIdCanChangeChannelAndEnterReleaseDateWithZeroStock(){
  gateway.seed(changed(input(),"originProduct.statusType","SUSPENSION"));gateway.omitChannelNumber();
  var observation=saving.observe(admin,"101");assertThat(observation.document().channelProductNo()).isNull();
  var edited=changed(changed(changed(observation.document().input(),"originProduct.statusType","SALE"),"smartstoreChannelProduct.channelProductDisplayStatusType","SUSPENSION"),"originProduct.detailAttribute.releaseDate","2026-10-10");
  var e=execute(saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),edited)));worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.SUCCEEDED);
  assertThat(gateway.writes()).containsExactly("PUT /v2/products/origin-products/101");
  assertThat(gateway.source().path("originProduct").path("statusType").asString()).isEqualTo("OUTOFSTOCK");
  assertThat(gateway.source().path("smartstoreChannelProduct").path("channelProductDisplayStatusType").asString()).isEqualTo("SUSPENSION");
  assertThat(gateway.source().path("originProduct").path("detailAttribute").path("releaseDate").asString()).isEqualTo("2026-10-10");
  String request=db.queryForObject("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=?",String.class,Long.parseLong(e.id()));
  var expected=json.readTree(json.readTree(request).path("expectedJson").asString());assertThat(expected.path("markets.NAVER.naver.editorInput.fields.originProduct.statusType").asString()).isEqualTo("OUTOFSTOCK");
  var next=saving.observe(admin,"101");assertThat(saving.prepare(admin,"101",new NaverProductSaving.Prepare(next.token(),changed(next.document().input(),"originProduct.salePrice",2000L))).executable()).isTrue();
 }
 NaverEditor.Input combination(){var before=input();return new NaverEditor.Input(before.fields(),"COMBINATION",List.of("색상"),List.of(new NaverEditor.Option(UUID.randomUUID().toString(),List.of("A"),100L,5L,"A",true),new NaverEditor.Option(UUID.randomUUID().toString(),List.of("B"),200L,5L,"B",true)),before.images(),before.description());}
 NaverEditor.Input firstOptionStock(NaverEditor.Input before,long stock){var rows=new ArrayList<>(before.options());var a=rows.getFirst();rows.set(0,new NaverEditor.Option(a.id(),a.values(),a.price(),stock,a.sellerManagerCode(),a.usable()));return new NaverEditor.Input(before.fields(),before.optionMode(),before.optionNames(),rows,before.images(),before.description());}
 long optionStock(int index){return gateway.source().path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations").get(index).path("stockQuantity").asLong();}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void optionStockWritesOnlySelectedRowAndRecoversLostResponseWithoutResending(boolean lost){
  gateway.seed(combination());var observation=saving.observe(admin,"101");var edited=firstOptionStock(observation.document().input(),3);
  gateway.optionStock(702,4);var preview=saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),edited));assertThat(preview.executable()).as(preview.targets().toString()).isTrue();
  var key=UUID.randomUUID().toString();var execution=submissions.execute(admin,preview.id(),key);assertThat(submissions.execute(admin,preview.id(),key).id()).isEqualTo(execution.id());
  gateway.optionStockBeforeNextPut(702,2);if(lost)gateway.loseNextResponse();worker.runPending();
  assertThat(optionStock(0)).isEqualTo(3);assertThat(optionStock(1)).isEqualTo(2);
  if(lost){assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.UNKNOWN);assertThatThrownBy(()->submissions.retry(admin,execution.id())).isInstanceOf(MarketplaceSubmissionFailure.class);gateway.optionStock(702,1);submissions.reconcile(admin,execution.id());worker.runPending();}
  assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.SUCCEEDED);assertThat(gateway.writes()).containsExactly("PUT /v1/products/origin-products/101/option-stock");
  String request=db.queryForObject("SELECT request_json FROM marketplace_execution_attempt WHERE execution_id=? AND request_json IS NOT NULL ORDER BY id LIMIT 1",String.class,Long.parseLong(execution.id()));
  var actual=json.readTree(request);assertThat(actual.path("type").asString()).isEqualTo("STOCK");var rows=json.readTree(actual.path("bodyJson").asString()).path("optionInfo").path("optionCombinations");assertThat(rows.size()).isEqualTo(1);assertThat(rows.get(0).path("id").asLong()).isEqualTo(701);assertThat(rows.get(0).path("stockQuantity").asLong()).isEqualTo(3);
  var next=saving.observe(admin,"101");assertThat(saving.prepare(admin,"101",new NaverProductSaving.Prepare(next.token(),firstOptionStock(next.document().input(),2))).executable()).isTrue();
 }
 @Test void combinationGeneralEditAndSelectedStockConflictDoNotWrite(){
  gateway.seed(combination());var observation=saving.observe(admin,"101");
  var blocked=saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),changed(observation.document().input(),"originProduct.name","unsafe general PUT")));assertThat(blocked.executable()).isFalse();assertThat(blocked.targets().getFirst().issues()).isNotEmpty();
  var execution=execute(saving.prepare(admin,"101",new NaverProductSaving.Prepare(observation.token(),firstOptionStock(observation.document().input(),3))));gateway.optionStock(701,4);worker.runPending();
  assertThat(submissions.get(admin,execution.id()).status()).isEqualTo(Status.FAILED);assertThat(gateway.writes()).isEmpty();assertThat(optionStock(0)).isEqualTo(4);
 }

}
