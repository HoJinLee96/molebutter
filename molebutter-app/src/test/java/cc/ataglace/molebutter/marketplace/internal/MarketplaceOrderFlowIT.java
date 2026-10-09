package cc.ataglace.molebutter.marketplace.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrderGateway;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.identity.internal.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrders.*;

/** All remote reads are synthetic; DB/Redis are the isolated script services. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "product.refresh.worker-enabled=false","spring.config.import=classpath:bootstrap-admin-test.properties",
 "spring.datasource.username=test_app","spring.datasource.password=isolated-test-app-password",
 "spring.flyway.user=test_migrator","spring.flyway.password=isolated-test-migration-password",
 "spring.data.redis.host=127.0.0.1","spring.data.redis.password=","mail.provider=test",
 "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes","auth.cookie.secure=false"
})
@ActiveProfiles("bootstrap-admin")
@Import(MarketplaceOrderFlowIT.MailConfiguration.class)
class MarketplaceOrderFlowIT {
 @TestConfiguration static class MailConfiguration { @Bean EmailSender orderTestMail(){return (to,subject,body)->{};} }
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("MOLEBUTTER_TEST_DB_URL");
  if(url==null||!url.contains("/molebutter_test?"))throw new IllegalStateException("Run scripts/test-integration.sh");
  r.add("spring.datasource.url",()->url);r.add("spring.flyway.url",()->url);
  r.add("spring.data.redis.port",()->System.getenv("MOLEBUTTER_TEST_REDIS_PORT"));
  r.add("marketplace.coupang.vendor-id",()->"");r.add("marketplace.coupang.access-key",()->"");r.add("marketplace.coupang.secret-key",()->"");
 }
 @MockitoBean MarketplaceOrderGateway gateway;
 @Autowired org.springframework.context.ApplicationContext context;
 @Autowired MarketplaceOrders orders;
 @Autowired MarketplaceOrderCollections collections;
 @Autowired MarketplaceOrderStore store;
 @Autowired JdbcTemplate db;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @Autowired UserRepository users;
 @Autowired DefaultAuthTokenService tokens;
 @Autowired ObjectMapper json;
 @LocalServerPort int port;
 long admin,staff,viewer;
 final String account="a".repeat(64),orderId="9007199254740993";
 @BeforeEach void prepare(){
  clear();admin=users.findByEmail("admin@example.com").orElseThrow().getId();staff=account(UserRole.PRODUCT);viewer=account(UserRole.VIEWER);
  when(gateway.market()).thenReturn("COUPANG");when(gateway.orderStream(anyString())).thenAnswer(c->"ACCEPT".equals(c.getArgument(0)));
  when(gateway.accountKey()).thenReturn(account);when(gateway.configured()).thenReturn(true);
  when(gateway.streams()).thenReturn(List.of("ACCEPT","CLAIMS_UNVERIFIED"));
  when(gateway.fetch(anyString(),any(),any(),nullable(String.class))).thenAnswer(call->{
   if("CLAIMS_UNVERIFIED".equals(call.getArgument(0)))return new MarketplaceOrderGateway.Page(List.of(),List.of(),null,false,"클레임 조회 계약 확인 필요");
   return new MarketplaceOrderGateway.Page(List.of(new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),false)),List.of(),null,true,null);
  });
  when(gateway.snapshot(orderId)).thenReturn(new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true));
  when(gateway.detail(orderId)).thenReturn(new Detail(orderId,List.of(new ShipmentDetail("11","ACCEPT","SYNTHETIC-RECIPIENT","SYNTHETIC-PHONE","00000","SYNTHETIC-ADDRESS","","","","")),List.of(row("11",1L))));
 }
 @AfterEach void cleanup(){clear();for(long id:List.of(staff,viewer))if(id>0){db.update("DELETE FROM user_notification WHERE user_id=?",id);users.deleteById(id);}}
 void clear(){for(String table:List.of("marketplace_order_job_claim","marketplace_order_failed_detail","marketplace_order_job_order","marketplace_order_checkpoint","marketplace_order_item","marketplace_order_claim","marketplace_order_job","marketplace_order"))db.update("DELETE FROM "+table);}
 long account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@orders.test").name("주문 테스트").passwordHash("unused").role(role).status(UserStatus.ACTIVE).build()).getId();}
 OrderRow row(String box,Long quantity){return new OrderRow("COUPANG",orderId,box,"001","123","456","SKU-SYNTHETIC","모의 주문 상품","블랙","ACCEPT",quantity,0L,0L,new Money("KRW","0"),new Money("KRW","0"),"2026-10-07T09:00:00+09:00","2026-10-07T09:01:00+09:00",Instant.now().toString(),null,List.of());}
 MarketplaceOrderCollections.Request request(){return new MarketplaceOrderCollections.Request(List.of("COUPANG"),"2026-10-07","2026-10-07",UUID.randomUUID().toString());}
 MarketplaceOrderCollections.Job await(String id)throws Exception{
  for(int i=0;i<160;i++){var job=collections.get(admin,id);if(!Set.of("QUEUED","RUNNING").contains(job.status()))return job;Thread.sleep(50);}
  throw new AssertionError("Synthetic collection did not finish");
 }
 @Test void orderCollectionUsesSeparateScheduler(){
  var schedulers=context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class);
  assertThat(schedulers).containsKeys("taskScheduler","marketplaceOrderScheduler");
  assertThat(schedulers.get("taskScheduler")).isNotSameAs(schedulers.get("marketplaceOrderScheduler"));
 }
 @Test void collectionPersistsPartialDataAndResponseLossUsesSameJob()throws Exception{
  var request=request();var first=collections.start(admin,request);assertThat(collections.start(admin,request).id()).isEqualTo(first.id());
  var finished=await(first.id());assertThat(finished.status()).isEqualTo("PARTIAL");
  var page=orders.orders(admin,new Search("COUPANG","","SKU-SYNTHETIC","",""),0,10);assertThat(page.total()).isEqualTo(1);assertThat(page.items().getFirst().orderId()).isEqualTo(orderId);
  assertThat(page.items().getFirst().unitPrice().amount()).isEqualTo("0");
  var browser=new Browser(admin);var detail=browser.get("/api/marketplace-orders/COUPANG/"+orderId+"/detail");assertThat(detail.statusCode()).isEqualTo(200);
  assertThat(detail.headers().firstValue("cache-control")).contains("no-store");assertThat(detail.body()).contains("SYNTHETIC-RECIPIENT");
  assertThat(json.readTree(detail.body()).path("data").path("fetchedAt").asString()).isNotBlank();
  assertThat(json.readTree(detail.body()).path("data").path("storedCollectedAt").asString()).isNotBlank();
  assertThat(db.queryForList("SELECT snapshot_json FROM marketplace_order_item",String.class)).allMatch(value->!value.contains("SYNTHETIC-RECIPIENT")&&!value.contains("SYNTHETIC-PHONE")&&!value.contains("SYNTHETIC-ADDRESS"));
  assertThat(db.queryForList("SELECT request_json FROM marketplace_order_job",String.class)).allMatch(value->!value.contains("SYNTHETIC-RECIPIENT"));
 }
 @Test void completeSnapshotReplacesChangedShipmentWithoutDeletingOnFailure(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(s->store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true),0));
  tx.executeWithoutResult(s->store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("22",0L)),true),0));
  assertThat(orders.orders(admin,new Search("COUPANG","","","",""),0,10).items()).singleElement().satisfies(r->{assertThat(r.shipmentBoxId()).isEqualTo("22");assertThat(r.quantity()).isZero();});
  assertThatThrownBy(()->tx.executeWithoutResult(s->store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(),false),0))).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
  assertThat(orders.orders(admin,new Search("COUPANG","","","",""),0,10).total()).isEqualTo(1);
 }
 @Test void onlyCurrentAdminMayUsePageApiAndCollectionMutations()throws Exception{
  for(long actor:List.of(staff,viewer)){
   var browser=new Browser(actor);assertThat(browser.get("/marketplace-orders").statusCode()).isEqualTo(403);assertThat(browser.get("/api/marketplace-orders").statusCode()).isEqualTo(403);
   assertThat(browser.send("POST","/api/marketplace-orders/collections",request(),true).statusCode()).isEqualTo(403);
  }
  var browser=new Browser(admin);assertThat(browser.get("/marketplace-orders").statusCode()).isEqualTo(200);
  assertThat(browser.send("POST","/api/marketplace-orders/collections",request(),false).statusCode()).isEqualTo(403);
  db.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",admin);
  try{assertThat(browser.get("/api/marketplace-orders").statusCode()).isEqualTo(401);}finally{db.update("UPDATE `user` SET user_status='ACTIVE' WHERE id=?",admin);}
 }
 @Test void withdrawalUpdatesOriginalReceiptRatherThanLeavingPendingDuplicate(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(s->{
   store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true),0);
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("RETURN","501","RETURNS_UNCHECKED",1L,"2026-10-07T10:00:00+09:00",false),"2026-10-07T09:00:00+09:00"));
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,null,"123",new ClaimSummary("RETURN","501","WITHDRAWN",null,"2026-10-07T11:00:00+09:00",false)));
  });
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_order_claim",Long.class)).isEqualTo(1);
  assertThat(orders.orders(admin,new Search("COUPANG","","","",""),0,10).items().getFirst().claims()).singleElement().satisfies(c->assertThat(c.status()).isEqualTo("WITHDRAWN"));
  assertThat(store.openClaims(account)).isEmpty();
 }
 @Test void confirmedUnavailableKeepsClaimsStopsRetriesAndSeparatesCounts()throws Exception{
  when(gateway.streams()).thenReturn(List.of("RETURN_PR"));
  var summary=new ClaimSummary("RETURN","701","RETURNS_COMPLETED",1L,"2026-10-07T12:00:00",false,true,"모의 상품","블랙","456","123","11",2L,"2026-10-07T11:00:00");
  when(gateway.fetch(anyString(),any(),any(),nullable(String.class))).thenReturn(new MarketplaceOrderGateway.Page(List.of(),List.of(new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",summary,"2026-10-07T11:00:00")),null,true,null));
  when(gateway.snapshot(orderId)).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.ORDER_UNAVAILABLE));
  var finished=await(collections.start(admin,request()).id());assertThat(finished.status()).isEqualTo("SUCCEEDED");assertThat(finished.failures()).isEmpty();
  var page=orders.orders(admin,new Search("COUPANG","","","",""),0,10);assertThat(page.productTotal()).isZero();assertThat(page.claimOnlyTotal()).isEqualTo(1);
  assertThat(page.items().getFirst().status()).isEqualTo("ORDER_UNAVAILABLE");assertThat(page.items().getFirst().quantity()).isNull();
  assertThat(page.items().getFirst().claims().getFirst().productName()).isEqualTo("모의 상품");
  var detail=orders.detail(admin,"COUPANG",orderId);assertThat(detail.unavailableReason()).isEqualTo("ORDER_UNAVAILABLE");assertThat(detail.shipments()).isEmpty();verify(gateway,never()).detail(orderId);
  await(collections.start(admin,request()).id());verify(gateway,times(1)).snapshot(orderId);
 }
 @Test void invalidOrderRemainsActionableButIsNotRequeued()throws Exception{
  when(gateway.streams()).thenReturn(List.of("ACCEPT"));when(gateway.snapshot(orderId)).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.ORDER_INVALID));
  var first=await(collections.start(admin,request()).id());assertThat(first.status()).isEqualTo("PARTIAL");assertThat(first.failures().getFirst().code()).isEqualTo("ORDER_INVALID");
  collections.retry(admin,first.id());await(first.id());verify(gateway,times(1)).snapshot(orderId);
 }
 @Test void unavailableDetailPreservesThePreviouslyCollectedNormalOrder(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(t->{store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",2L)),true),0);store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("RETURN","704","RETURNS_COMPLETED",1L,"2026-10-07T12:00:00",false)));store.markUnavailable(account,orderId);});
  var page=orders.orders(admin,new Search("COUPANG","","","",""),0,10);assertThat(page.productTotal()).isEqualTo(1);assertThat(page.claimOnlyTotal()).isZero();assertThat(page.items().getFirst().quantity()).isEqualTo(2);
  var detail=orders.detail(admin,"COUPANG",orderId);assertThat(detail.items().getFirst().quantity()).isEqualTo(2);assertThat(detail.items().getFirst().claims()).hasSize(1);assertThat(detail.shipments()).isEmpty();verify(gateway,never()).detail(orderId);
 }
 @Test void claimOnlyOrdersRemainVisibleWithoutInventedProductQuantityOrMoney(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(s->store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,null,"123",new ClaimSummary("CANCEL","502","COMPLETE",1L,"2026-10-07T10:00:00+09:00",false))));
  var page=orders.orders(admin,new Search("COUPANG","",orderId,"","","CANCEL","CLAIM"),0,10);
  assertThat(page.total()).isEqualTo(1);assertThat(page.items()).singleElement().satisfies(r->{assertThat(r.quantity()).isNull();assertThat(r.orderPrice()).isNull();assertThat(r.claims()).hasSize(1);assertThat(r.claims().getFirst().linked()).isFalse();});
 }
 @Test void claimTypeAndDateMustMatchTheSameClaim(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(t->{
   store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true),0);
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("RETURN","601","RETURNS_UNCHECKED",1L,"2026-10-06T10:00:00+09:00",false)));
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("CANCEL","602","COMPLETE",1L,"2026-10-07T10:00:00+09:00",false)));
  });
  assertThat(orders.orders(admin,new Search("COUPANG","","","2026-10-07","2026-10-07","RETURN","CLAIM"),0,10).total()).isZero();
  assertThat(orders.orders(admin,new Search("COUPANG","","","2026-10-06","2026-10-06","RETURN","CLAIM"),0,10).total()).isEqualTo(1);
 }
 @Test void olderReturnCannotUndoNewerWithdrawal(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(t->{
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("RETURN","603","RETURNS_UNCHECKED",1L,"2026-10-07T10:00:00+09:00",false)));
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,null,null,new ClaimSummary("RETURN","603","WITHDRAWN",null,"2026-10-07T11:00:00+09:00",false)));
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("RETURN","603","RETURNS_UNCHECKED",1L,"2026-10-07T09:00:00+09:00",false)));
  });
  assertThat(orders.orders(admin,new Search("COUPANG","","","",""),0,10).items().getFirst().claims()).singleElement().satisfies(c->assertThat(c.status()).isEqualTo("WITHDRAWN"));
 }
 @Test void claimWithoutComparableTimeRemainsUnverifiedAndRefreshable(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(t->store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,null,null,new ClaimSummary("RETURN","604","WITHDRAWN",null,null,false))));
  var claim=orders.orders(admin,new Search("COUPANG","","","",""),0,10).items().getFirst().claims().getFirst();
  assertThat(claim.latestVerified()).isFalse();assertThat(store.withdrawnReceipt(account,"604")).isFalse();assertThat(store.openClaims(account)).hasSize(1);
 }
 @Test void cancellationDuringDetailReadCannotCommitLateResponse()throws Exception{
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  when(gateway.snapshot(orderId)).thenAnswer(call->{entered.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}return new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true);});
  var first=collections.start(admin,request());
  try{
   assertThat(entered.await(8,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   assertThat(collections.cancel(admin,first.id()).status()).isEqualTo("CANCELLED");release.countDown();
   Thread.sleep(100);
   assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_order_item",Long.class)).isZero();
   assertThat(collections.get(admin,first.id()).status()).isEqualTo("CANCELLED");
  }finally{release.countDown();}
 }
 @Test void accountLeaseAllowsOnlyOneConcurrentJob()throws Exception{
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);String other="b".repeat(64);
  tx.executeWithoutResult(t->{store.insertJob(admin,other,request());store.insertJob(admin,other,request());});
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var start=new java.util.concurrent.CountDownLatch(1);
   var one=pool.submit(()->{start.await();return tx.execute(t->store.claimJob("one",other));});
   var two=pool.submit(()->{start.await();return tx.execute(t->store.claimJob("two",other));});start.countDown();
   Long first=one.get(10,java.util.concurrent.TimeUnit.SECONDS),second=two.get(10,java.util.concurrent.TimeUnit.SECONDS);
   assertThat(first==null ^ second==null).isTrue();long active=first!=null?first:second;
   db.update("UPDATE marketplace_order_job SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",active);
   db.update("UPDATE marketplace_order_account_lock SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE active_job_id=?",active);
   Long resumed=tx.execute(t->store.claimJob("new-owner",other));assertThat(resumed).isNotNull().isNotEqualTo(active);
   assertThat(store.find(active,false).status()).isEqualTo("INTERRUPTED");
  }
 }
 @Test void claimAssociationDoesNotMarkOlderSnapshotAsObservedInThisJob(){
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  tx.executeWithoutResult(s->{
   store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true),0);
   long job=store.insertJob(admin,account,request());
   store.saveClaim(account,new MarketplaceOrderGateway.ClaimObservation(orderId,"11","123",new ClaimSummary("RETURN","503","RETURNS_UNCHECKED",1L,null,false)),job);
   assertThat(store.observed(job,account,orderId)).isFalse();
   store.saveSnapshot(account,new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("22",1L)),true),job);
   assertThat(store.observed(job,account,orderId)).isTrue();
  });
 }
 @Test void failedDetailRetriesOnlyDetailAndRetainsSuccessfulCheckpoint()throws Exception{
  when(gateway.snapshot(orderId)).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE)).thenReturn(new MarketplaceOrderGateway.Snapshot(orderId,List.of(row("11",1L)),true));
  var first=collections.start(admin,request());assertThat(await(first.id()).status()).isEqualTo("PARTIAL");
  var checkpoint=store.checkpoints(Long.parseLong(first.id())).stream().filter(c->c.stream().equals("ACCEPT")).findFirst().orElseThrow();
  assertThat(checkpoint.status()).isEqualTo("DONE");assertThat(checkpoint.cursor()).isNull();
  assertThat(collections.get(admin,first.id()).progress().failedDetails()).isEqualTo(1);
  assertThat(collections.get(admin,first.id()).failures()).anySatisfy(f->{assertThat(f.stage()).isEqualTo("ORDER_DETAIL");assertThat(f.code()).isEqualTo("RESPONSE");});
  collections.retry(admin,first.id());assertThat(await(first.id()).status()).isEqualTo("PARTIAL");
  verify(gateway,times(2)).snapshot(orderId);
  verify(gateway,times(1)).fetch(eq("ACCEPT"),any(),any(),nullable(String.class));
  assertThat(collections.get(admin,first.id()).progress().failedDetails()).isZero();
  assertThat(store.checkpoints(Long.parseLong(first.id())).stream().filter(c->c.stream().equals("ACCEPT")).findFirst().orElseThrow().status()).isEqualTo("DONE");
  assertThat(orders.orders(admin,new Search("COUPANG","","","",""),0,10).total()).isEqualTo(1);
 }
 class Browser {
  final HttpClient client=HttpClient.newHttpClient();final String token;String csrf,cookie;
  Browser(long actor){token=tokens.issueOnSignin(users.findById(actor).orElseThrow()).accessToken();}
  void security()throws Exception {if(csrf!=null)return;var response=get("/api/auth/csrf");csrf=json.readTree(response.body()).path("data").path("token").asString();cookie=response.headers().allValues("set-cookie").stream().map(s->s.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));}
  HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,false);}
  HttpResponse<String> send(String method,String path,Object body,boolean secure)throws Exception {
   if(secure)security();var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("X-Operation-Id",UUID.randomUUID().toString()).header("Content-Type","application/json");
   if(secure)b.header("X-XSRF-TOKEN",csrf).header("Cookie",cookie);
   return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  }
 }
}
