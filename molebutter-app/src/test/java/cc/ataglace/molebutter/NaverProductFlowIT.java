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
 @Test void responseLossBlocksNewPostAndOnlyAllowsReadReconciliation(){
  var d=registrations.create(admin,input());var e=execute(registrations.prepare(admin,d.id(),new NaverProductRegistrations.Prepare(d.revision())));gateway.loseNextResponse();worker.runPending();
  assertThat(submissions.get(admin,e.id()).status()).isEqualTo(Status.UNKNOWN);assertThatThrownBy(()->submissions.retry(admin,e.id())).isInstanceOf(MarketplaceSubmissionFailure.class);
  assertThatThrownBy(()->registrations.prepare(admin,d.id(),new NaverProductRegistrations.Prepare(d.revision()))).isInstanceOf(MarketplaceDraftFailure.class);
  submissions.reconcile(admin,e.id());worker.runPending();assertThat(gateway.writes()).containsExactly("POST /v2/products");
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
}
