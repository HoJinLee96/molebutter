package cc.ataglace.molebutter;
import cc.ataglace.molebutter.app.internal.ProductHttpDtos.*;
import cc.ataglace.molebutter.common.fixture.WorkbookFixture;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;

import cc.ataglace.molebutter.common.api.BusinessTime;
import cc.ataglace.molebutter.common.api.NamedSettingInput;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.identity.internal.User;
import cc.ataglace.molebutter.identity.api.UserStatus;
import cc.ataglace.molebutter.identity.internal.UserRepository;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupService;
import cc.ataglace.molebutter.procurement.internal.SearchCompletion;
import cc.ataglace.molebutter.procurement.api.ProductCodePolicy;
import cc.ataglace.molebutter.app.internal.ProductService;
import cc.ataglace.molebutter.procurement.internal.DefaultProductSupplierService;
import cc.ataglace.molebutter.catalog.internal.DefaultSharedSettingsService;
import cc.ataglace.molebutter.procurement.internal.RecommendationLookupService;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierPreferenceService;
import cc.ataglace.molebutter.procurement.internal.ProductStore;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierRefreshService;
import cc.ataglace.molebutter.procurement.internal.ProductSearchStatusRepair;
import cc.ataglace.molebutter.procurement.internal.SupplierBranch;
import cc.ataglace.molebutter.procurement.internal.NaverSearchRecoveryService;
import cc.ataglace.molebutter.procurement.internal.SupplierStorePolicy;
import cc.ataglace.molebutter.procurement.internal.ProductStatusRepair;
import cc.ataglace.molebutter.procurement.internal.DefaultProductChangeService;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierStockLookupService;
import cc.ataglace.molebutter.procurement.internal.DefaultOfficialBrandStoreService;




@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "product.refresh.worker-enabled=false", "spring.config.import=classpath:bootstrap-admin-test.properties",
    "spring.datasource.username=test_app", "spring.datasource.password=isolated-test-app-password",
    "spring.flyway.user=test_migrator", "spring.flyway.password=isolated-test-migration-password",
    "spring.data.redis.host=127.0.0.1", "spring.data.redis.password=", "mail.provider=test",
    "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes", "auth.cookie.secure=false",
    "auth.signin.rate-limit-max-attempts=1000"
})
@ActiveProfiles("bootstrap-admin")
@Import({AuthenticationFlowIT.MailConfiguration.class,ProductFlowIT.ClockConfig.class})
class ProductFlowIT {
    @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);}
    @TestConfiguration(proxyBeanMethods=false) static class ClockConfig{@Bean @Primary TestTime productTestTime(){return new TestTime();}
        @Bean @Primary BrandGateway brandGateway(){return new BrandGateway();}}
    static class BrandGateway implements cc.ataglace.molebutter.procurement.internal.SupplierProductGateway {
        String payload="";
        public String validateUrl(ProcurementMall mall,String url){return url;}
        public List<SourceOption> options(ProcurementMall mall,String id,String url){throw new AssertionError("공식몰 등록은 재고를 조회하지 않는다");}
        public SourceDetails inspect(ProcurementMall mall,String id,String url){return new cc.ataglace.molebutter.procurement.internal.MallOptionParser(new ObjectMapper()).details(mall,payload,id);}
    }
    @Autowired BrandGateway brandGateway; @Autowired DefaultOfficialBrandStoreService officialStores;
    static class TestTime extends BusinessTime {volatile LocalDateTime value=LocalDateTime.parse("2026-09-24T18:00:00");@Override public LocalDateTime now(){return value;}}
    @Autowired cc.ataglace.molebutter.operations.internal.DefaultNotificationService notifications;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired cc.ataglace.molebutter.attendance.internal.DefaultAttendanceService attendance;
    @Autowired DefaultSupplierPreferenceService preferred; @Autowired DefaultProductSupplierService supplierService;
    @Autowired DefaultSharedSettingsService settings; @Autowired ProductService products; @Autowired DefaultSupplierRefreshService refresh; @Autowired ProductStore store;
    @Autowired UserRepository users; @Autowired PasswordEncoder encoder; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @Autowired TestTime time; @LocalServerPort int port;
    Long actor;String brand;
    @Autowired cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard catalogGuard;
    @Test void workerRuntimeChangesRetainTheCatalogGuardBeforeTheirOwnRowLock() throws Exception {
        var product=create("ABCD6F123BK");start(product);var work=refresh.claim("guard-worker");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var entered=new CountDownLatch(4);
        try(var pool=Executors.newFixedThreadPool(5)) {
            var blocker=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                catalogGuard.shared();held.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test lock timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return true;
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                List<Callable<Object>> changes=List.of(
                    ()->refresh.heartbeat("guard-worker",work),
                    ()->refresh.searchFinished("guard-worker",work,true),
                    ()->{recovery.searchInterval();return true;},
                    ()->{refresh.blocked("guard-worker",work,"blocked for guard test");return true;});
                var pending=changes.stream().map(change->pool.submit(()->{entered.countDown();return change.call();})).toList();
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                for(var operation:pending)assertThatThrownBy(()->operation.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(jdbc.queryForObject("SELECT status FROM product_refresh_run WHERE id=?",String.class,work.runId())).isEqualTo("RUNNING");
                release.countDown();blocker.get(5,TimeUnit.SECONDS);
                for(var operation:pending)operation.get(5,TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT status FROM product_refresh_run WHERE id=?",String.class,work.runId())).isEqualTo("BLOCKED");
                assertThat(jdbc.queryForObject("SELECT worker_owner FROM procurement_runtime WHERE id=1",String.class)).isNull();
            } finally {release.countDown();}
        }
    }
    @Autowired cc.ataglace.molebutter.catalog.api.CatalogCommands catalogCommands;
    @Autowired cc.ataglace.molebutter.procurement.api.ProcurementLifecycle procurementLifecycle;
    @Autowired cc.ataglace.molebutter.inventory.api.InventoryLinkService inventoryLinks;

    void exclusiveTransaction(Runnable work) {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{catalogGuard.exclusive();work.run();});
    }
    @Test void directCatalogContractsRejectInvalidProductsAndKeepRejectedEditsUnchanged() {
        var product=create("ABCD6F123BK");var other=create("ABCD6F124BK");
        var input=new cc.ataglace.molebutter.catalog.api.CatalogCommands.ProductInput(
                new cc.ataglace.molebutter.catalog.api.CatalogCommands.BrandSelection("",null),product.productCode(),List.of());
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.create(ProductStore.id(),input,time.now()))).hasMessageContaining("이미 등록");
        for(String invalid:List.of("", "X".repeat(101))) {
            var bad=new cc.ataglace.molebutter.catalog.api.CatalogCommands.ProductInput(input.brand(),invalid,List.of());
            assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.create(ProductStore.id(),bad,time.now()))).hasMessageContaining("필수 입력값");
        }
        var badBrand=new cc.ataglace.molebutter.catalog.api.CatalogCommands.ProductInput(
                new cc.ataglace.molebutter.catalog.api.CatalogCommands.BrandSelection("999999",null),product.productCode(),null);
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.edit(Long.parseLong(product.id()),product.revision(),badBrand,time.now()))).hasMessageContaining("브랜드");
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.editBrand(Long.parseLong(product.id()),product.revision(),badBrand.brand(),time.now()))).hasMessageContaining("브랜드");
        var duplicate=new cc.ataglace.molebutter.catalog.api.CatalogCommands.ProductInput(input.brand(),other.productCode(),null);
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.edit(Long.parseLong(product.id()),product.revision(),duplicate,time.now()))).hasMessageContaining("이미 등록");
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.validateMerge(Long.parseLong(product.id()),List.of(Long.parseLong(product.id()),Long.parseLong(other.id())),product.productCode()))).hasMessageContaining("동일한 전체 상품코드");
        var valid=new cc.ataglace.molebutter.catalog.api.CatalogCommands.ProductInput(input.brand(),product.productCode(),null);
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.edit(Long.parseLong(product.id()),99L,valid,time.now()))).hasMessageContaining("다른 작업");
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.editBrand(Long.parseLong(product.id()),99L,input.brand(),time.now()))).hasMessageContaining("다른 작업");
        assertThatThrownBy(()->exclusiveTransaction(()->catalogCommands.bump(999999L))).isInstanceOf(cc.ataglace.molebutter.common.api.BusinessException.class);
        assertThat(current(product).revision()).isEqualTo(product.revision());
        exclusiveTransaction(()->{catalogCommands.appendRegistrationNames(Long.parseLong(product.id()),List.of("헤지스 가방"));catalogCommands.appendRegistrationNames(Long.parseLong(product.id()),List.of("헤지스 가방"));});
        assertThat(jdbc.queryForObject("SELECT registration_names FROM catalog_product WHERE id=?",String.class,product.id())).isEqualTo("[\"헤지스 가방\"]");
    }
    @Test void directAndOfflineMutationsRequireTheActualWritableTransactionAndExclusiveGuard() {
        var product=create("ABCD6F123BK");long id=Long.parseLong(product.id());
        var rawGuard=cc.ataglace.molebutter.catalog.api.CatalogMaintenance.guard(jdbc);
        var rawCommands=cc.ataglace.molebutter.catalog.api.CatalogMaintenance.commands(jdbc);
        assertThatThrownBy(rawGuard::exclusive).hasMessageContaining("actual transaction");
        assertThatThrownBy(()->rawCommands.bump(id)).hasMessageContaining("actual transaction");
        var brandSelection=new cc.ataglace.molebutter.catalog.api.CatalogCommands.BrandSelection(brand,null);
        assertThatThrownBy(()->rawCommands.editBrand(id,product.revision(),brandSelection,time.now())).hasMessageContaining("actual transaction");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{
            assertThatThrownBy(()->rawCommands.bump(id)).hasMessageContaining("guard required");
            assertThatThrownBy(()->rawCommands.editBrand(id,product.revision(),brandSelection,time.now())).hasMessageContaining("guard required");
            assertThatThrownBy(()->procurementLifecycle.managed(id,true)).hasMessageContaining("guard required");
            assertThatThrownBy(()->inventoryLinks.assertDeletable(List.of(id))).hasMessageContaining("guard required");
            status.setRollbackOnly();
        });
        tx.setReadOnly(true);
        assertThatThrownBy(()->tx.executeWithoutResult(status->rawCommands.bump(id))).hasMessageContaining("writable transaction");
        assertThatThrownBy(()->tx.executeWithoutResult(status->rawCommands.editBrand(id,product.revision(),brandSelection,time.now()))).hasMessageContaining("writable transaction");
        assertThat(current(product).revision()).isEqualTo(product.revision());
    }
    @Test void sharedGuardsCannotUpgradeAndExclusiveStateDoesNotLeakAcrossTransactions() {
        var raw=cc.ataglace.molebutter.catalog.api.CatalogMaintenance.guard(jdbc);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{raw.shared();raw.shared();raw.requireShared();
            assertThatThrownBy(raw::exclusive).hasMessageContaining("before any shared");
            assertThatThrownBy(raw::requireExclusive).hasMessageContaining("guard required");
        });
        tx.executeWithoutResult(status->{raw.exclusive();raw.exclusive();raw.shared();raw.requireShared();raw.requireExclusive();
            var child=new org.springframework.transaction.support.TransactionTemplate(transactions);
            child.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            child.executeWithoutResult(inner->{assertThatThrownBy(raw::requireExclusive).hasMessageContaining("guard required");});
            raw.requireExclusive();status.setRollbackOnly();
        });
        tx.executeWithoutResult(status->{assertThatThrownBy(raw::requireExclusive).hasMessageContaining("guard required");raw.exclusive();});
        tx.executeWithoutResult(status->{assertThatThrownBy(raw::requireShared).hasMessageContaining("guard required");});
    }
    @Test void missingGuardRowsAndUnmanagedDataSourcesFailInsteadOfAllowingUnlockedWrites() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var raw=cc.ataglace.molebutter.catalog.api.CatalogMaintenance.guard(jdbc);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{jdbc.update("DELETE FROM catalog_consistency_guard WHERE id=1");raw.exclusive();})).hasMessageContaining("Missing catalog");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{jdbc.update("DELETE FROM catalog_consistency_guard WHERE id=1");raw.shared();})).hasMessageContaining("Missing catalog");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_consistency_guard WHERE id=1",Long.class)).isEqualTo(1);
        var wrong=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DelegatingDataSource(jdbc.getDataSource()));
        var unmanaged=cc.ataglace.molebutter.catalog.api.CatalogMaintenance.guard(wrong);
        assertThatThrownBy(()->tx.executeWithoutResult(status->unmanaged.exclusive())).hasMessageContaining("transaction's DataSource");
    }
    @Test void brandInferencePreservesHistoricalCodeAndLookupCriteria() {
        var product=products.create(actor,new ProductEditRequest(null,"","BRAND-LEGACY","내 검색어"));
        jdbc.update("UPDATE catalog_product SET product_code=?,registration_names=? WHERE id=?","  brand-legacy  ","[\"헤지스 가방\"]",product.id());
        var before=current(product);
        var result=products.inferBrands(actor,new BrandInferenceInput(List.of(new VersionedId(before.id(),before.revision()))));
        var after=current(product);
        assertThat(result.assigned()).isEqualTo(1);
        assertThat(after.productCode()).isEqualTo(before.productCode());
        assertThat(after.searchQuery()).isEqualTo(before.searchQuery());
        assertThat(after.lookupRevision()).isEqualTo(before.lookupRevision());
        assertThat(after.revision()).isEqualTo(before.revision()+1);
        assertThat(after.brandId()).isEqualTo(brand);
    }
    @Test void bulkBrandChangesPreserveHistoricalCodesAndProcurementWithAndWithoutDuplicates() {
        for(boolean duplicate:List.of(false,true)) {
            var product=create("BRAND-BULK-"+duplicate);
            String originalCode=duplicate ? "  abcd6f124bk  " : "  abcd6f123bk  ";
            jdbc.update("UPDATE catalog_product SET product_code=? WHERE id=?",originalCode,product.id());
            var ids=new ArrayList<String>();ids.add(product.id());
            if(duplicate) {
                var copy=create("BRAND-BULK-COPY");
                jdbc.update("UPDATE catalog_product SET product_code=? WHERE id=?","ABCD6F124BK",copy.id());
                ids.add(copy.id());
            }
            for(String id:ids)jdbc.update("UPDATE procurement_product SET search_query='  수동 검색 원문  ',search_mode='AUTO',code_type='LF_ACCESSORY',comparison_code='LEGACY',lookup_revision=7,latest_status='SUCCESS',latest_result=JSON_OBJECT('status','SUCCESS'),last_good_result=JSON_OBJECT('status','SUCCESS'),latest_at=? WHERE product_id=?",time.now(),id);
            for(String requestedBrand:List.of("",brand,brand)) {
                var before=ids.stream().map(id->products.product(Long.parseLong(id))).toList();
                var criteria=ids.stream().map(id->jdbc.queryForMap("SELECT * FROM procurement_product WHERE product_id=?",id)).toList();
                products.bulk(actor,new BulkEdit(before.stream().map(this::version).toList(),null,null,requestedBrand));
                for(int index=0;index<ids.size();index++) {
                    var after=products.product(Long.parseLong(ids.get(index)));
                    assertThat(after.productCode()).isEqualTo(before.get(index).productCode());
                    assertThat(after.brandId()).isEqualTo(requestedBrand.isBlank() ? null : requestedBrand);
                    assertThat(after.revision()).isEqualTo(before.get(index).revision()+1);
                    assertThat(jdbc.queryForMap("SELECT * FROM procurement_product WHERE product_id=?",after.id())).isEqualTo(criteria.get(index));
                }
            }
        }
    }
    @BeforeEach void reset(){
        jdbc.update("UPDATE inventory_movement SET reference_id=NULL,reverses_id=NULL");
        jdbc.update("DELETE FROM inventory_movement");jdbc.update("DELETE FROM inventory_item");jdbc.update("DELETE FROM inventory_purchase");
        brandGateway.payload="";jdbc.update("DELETE FROM user_notification");jdbc.update("DELETE FROM notification_event");
        jdbc.update("UPDATE catalog_product SET merged_into=NULL");
        for(String table:List.of("product_search_attempt","product_value_observation","product_change_review","product_change_summary","recommendation_lookup_diagnostic","supplier_stock_lookup","product_supplier_selection","product_supplier_change","supplier_preference","product_refresh_entry","product_refresh_search","product_lookup_history","product_refresh_run","product_supplier","catalog_merge_history","procurement_product","catalog_product","product_brand","supplier_store"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE procurement_settings s JOIN procurement_runtime r ON r.id=s.id SET s.revision=0,s.schedule_enabled=TRUE,s.schedule_time='18:00',last_schedule_date=NULL,worker_owner=NULL,worker_until=NULL,next_search_at=NULL,stock_lookup_blocked_job=NULL,search_cooldown_until=NULL,search_manual_resume_required=FALSE,search_gate_run_id=NULL,search_gate_attempt_id=NULL,search_gate_version=0 WHERE s.id=1");
        jdbc.update("UPDATE supplier_mall_policy SET branch_required=(mall NOT IN ('LFMALL','HAZZYS'))");
        time.value=LocalDateTime.parse("2026-09-24T18:00:00");actor=users.findByEmail("admin@example.com").orElseThrow().getId();
        brand=settings.saveBrand(actor,null,new NamedSettingInput("헤지스",null)).id();
        for(var mall:ProcurementMall.values())preferred.saveRule(actor,null,new RuleInput(null,mall,null));
    }
    @Test void hundredProductPageUsesBoundedQueriesAndDatabasePagination() {
        for(int n=0;n<105;n++)products.create(actor,new ProductEditRequest(null,"","BATCH-"+n,"page-search"));
        var source=jdbc.getDataSource();var statements=new java.util.concurrent.atomic.AtomicInteger();
        jdbc.setDataSource(new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {return counted(super.getConnection());}
            @Override public java.sql.Connection getConnection(String user,String password) throws java.sql.SQLException {return counted(super.getConnection(user,password));}
            private java.sql.Connection counted(java.sql.Connection connection) {
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement"))statements.incrementAndGet();
                    try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }
        });
        try {
            var small=products.list(actor,"page-search","ALL","",0,20);int smallQueries=statements.getAndSet(0);
            var large=products.list(actor,"page-search","ALL","",0,100);int largeQueries=statements.getAndSet(0);
            assertThat(small.items()).hasSize(20);assertThat(large.items()).hasSize(100);
            assertThat(large.totalElements()).isEqualTo(105);assertThat(large.totalPages()).isEqualTo(2);
            assertThat(largeQueries).isEqualTo(smallQueries).isLessThan(10);
            var last=products.list(actor,"page-search","ALL","",1,100);assertThat(last.items()).hasSize(5);assertThat(last.totalElements()).isEqualTo(105);
            assertThat(last.items()).extracting(ProcurementProductView::id).doesNotContainAnyElementsOf(large.items().stream().map(ProcurementProductView::id).toList());
        } finally {jdbc.setDataSource(source);}
    }
    @Autowired DefaultProductChangeService changeService;
    SupplierResult deltaListing(long price,Long stock){
        var x=listing(ProcurementMall.LFMALL,"delta",price,"온라인점","CONFIRMED");
        return new SupplierResult(x.offer(),x.match(),stock==null?"OPTIONS_UNKNOWN":"CONFIRMED",stock==null?List.of():List.of(new SourceOption("black","블랙",stock,stock==0?"SOLD_OUT":"AVAILABLE")),null,null,null,null,x.branch());
    }
    void deltaFinish(ProcurementProductView p,SupplierResult... results){
        time.value=time.value.plusMinutes(1);start(current(p));var w=refresh.claim("delta");
        var offers=Arrays.stream(results).map(SupplierResult::offer).toList();
        var keys=offers.stream().map(SupplierStorePolicy::listingKey).toList();
        refresh.cache(w,new SearchResult(offers,true,null,"COMPLETED",keys));
        refresh.finish("delta",w,new RefreshResult("SUCCESS",null,null,null,List.of(results),time.now(),null,false,List.of(),"COMPLETED"));
        refresh.claim("delta");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"SUCCESS,true", "PARTIAL,true", "SOLD_OUT,true", "NO_MATCH,true", "FAILED,false", "STALE,false"})
    void completedRefreshWritesOneBackgroundAuditForTheCommittedResult(String outcome,boolean completed){
        var p=create("DCWA279BK");start(current(p));var w=refresh.claim("audit-result");
        var observed=switch(outcome){
            case "SUCCESS"->List.of(deltaListing(156450,100L));
            case "PARTIAL"->List.of(deltaListing(156450,null));
            case "SOLD_OUT"->List.of(deltaListing(156450,0L));
            default->List.<SupplierResult>of();
        };
        var result=new RefreshResult(outcome,null,null,null,observed,time.now(),null);
        String target=w.runId()+":"+w.productId();
        refresh.finish("audit-result",w,result);
        refresh.finish("audit-result",w,result);
        var entries=jdbc.queryForList("SELECT * FROM operation_audit_log WHERE event_type='SUPPLIER_LOOKUP_RESULT' AND target_id=?",target);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst()).containsEntry("success",completed)
            .containsEntry("failure_reason",completed?null:outcome);
        assertThat(jdbc.queryForObject("SELECT status FROM product_refresh_entry WHERE run_id=? AND product_id=?",String.class,w.runId(),w.productId())).isEqualTo(outcome);
        assertThat(entries.getFirst()).containsEntry("execution_source","BACKGROUND")
            .containsEntry("user_id",null).containsEntry("user_role",null).containsEntry("email",null);
        assertThat(entries.getFirst().get("operation_id").toString()).isEqualTo(UUID.fromString(entries.getFirst().get("operation_id").toString()).toString());
    }
    @Test void deltasKeepReviewBaselineAcrossRefreshesAndRevertWithoutDeletingHistory(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));choose(p,byMall(p,ProcurementMall.LFMALL));
        assertThat(changeService.summary(Long.parseLong(p.id())).anyChanged()).isFalse();
        deltaFinish(p,deltaListing(149900,37L));var changes=changeService.summary(Long.parseLong(p.id()));
        assertThat(changes.selectedChanged()).isTrue();assertThat(changes.listings().getFirst().deltas()).extracting(cc.ataglace.molebutter.procurement.api.ChangeDtos.Delta::difference).containsExactly(-6550L,-63L);
        deltaFinish(p,deltaListing(140000,20L));assertThat(changeService.summary(Long.parseLong(p.id())).listings().getFirst().deltas().getFirst().before()).isEqualTo(156450);
        deltaFinish(p,deltaListing(156450,100L));assertThat(changeService.summary(Long.parseLong(p.id())).anyChanged()).isFalse();
        assertThat(changeService.history(actor,Long.parseLong(p.id()),"","PRICE",0).items()).hasSize(3);
    }
    @Test void reviewIsSharedVersionedAndDoesNotModifyLegacyValues(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));deltaFinish(p,deltaListing(149900,37L));
        long id=Long.parseLong(p.id());var summary=changeService.summary(id);
        var legacy=jdbc.queryForMap("SELECT latest_result,latest_at,last_good_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",id);
        var reviewed=changeService.review(actor,id,new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(summary.version()));
        assertThat(reviewed.anyChanged()).isFalse();assertThat(reviewed.reviewer()).isNotBlank();
        assertThatThrownBy(()->changeService.review(actor,id,new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(summary.version()))).hasMessageContaining("변경");
        assertThat(jdbc.queryForMap("SELECT latest_result,latest_at,last_good_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",id)).isEqualTo(legacy);
        deltaFinish(p,deltaListing(148900,36L));assertThat(changeService.summary(id).listings().getFirst().deltas()).extracting(cc.ataglace.molebutter.procurement.api.ChangeDtos.Delta::difference).containsExactly(-1000L,-1L);
    }
    @Test void unknownStockReviewKeepsKnownBaselineAndFreshListingsNeedNoZeroBaseline(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));deltaFinish(p,deltaListing(156450,null));
        long id=Long.parseLong(p.id());assertThat(changeService.summary(id).anyChanged()).isFalse();
        changeService.review(actor,id,new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(changeService.summary(id).version()));
        deltaFinish(p,deltaListing(156450,37L),listing(ProcurementMall.LFMALL,"other",100000,"온라인점","CONFIRMED"));
        assertThat(changeService.summary(id).listings()).anyMatch(l->l.fresh()).anyMatch(l->l.deltas().stream().anyMatch(d->Objects.equals(d.difference(),-63L)));
    }
    @Test void missingUsesSearchMembershipRatherThanCollectedSuppliersAndDoesNotRepeatAfterReview(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));long id=Long.parseLong(p.id());
        deltaFinish(p);assertThat(changeService.summary(id).listings()).anyMatch(l->l.missing());
        changeService.review(actor,id,new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(changeService.summary(id).version()));
        assertThat(changeService.summary(id).anyChanged()).isFalse();deltaFinish(p);assertThat(changeService.summary(id).anyChanged()).isFalse();
        deltaFinish(p,deltaListing(156450,100L));assertThat(changeService.history(actor,id,"","STATE",0).items()).anyMatch(h->h.changes().stream().anyMatch(d->d.kind().equals("REAPPEARED")));
    }
    @Test void changeFiltersCountsAndPreferenceChangesAreServerSide(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));choose(p,byMall(p,ProcurementMall.LFMALL));deltaFinish(p,deltaListing(149900,37L));
        assertThat(products.list(actor,"DCWA","ALL","",0,20,"SELECTED").items()).hasSize(1);
        assertThat(products.changeCounts(actor,"DCWA","ALL","").selected()).isEqualTo(1);
        supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(current(p).revision(),null));
        assertThat(products.changeCounts(actor,"DCWA","ALL","").selected()).isZero();
        preferred.deleteMall(actor,ProcurementMall.LFMALL,preferred.get(actor).revision());
        assertThat(products.changeCounts(actor,"DCWA","ALL","").all()).isZero();
    }
    @Test void changesRejectStaleReviewDuringRefreshAndNewSearchCriteriaResetTheBaseline(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));long id=Long.parseLong(p.id());var previous=changeService.summary(id);
        start(current(p));assertThatThrownBy(()->changeService.review(actor,id,new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(previous.version()))).hasMessageContaining("변경");
        long run=jdbc.queryForObject("SELECT MAX(id) FROM product_refresh_run",Long.class);refresh.control(actor,run,"cancel");
        var current=current(p);products.edit(actor,id,new ProductEditRequest(current.revision(),null,p.productCode(),"새 검색어",p.brandId(),null,null));
        assertThat(changeService.summary(id).version()).isZero();assertThat(changeService.history(actor,id,"","ALL",0).items()).isNotEmpty();
        deltaFinish(p,deltaListing(100000,20L));assertThat(changeService.summary(id).anyChanged()).isFalse();
        assertThat(changeService.summary(id).version()).isGreaterThan(previous.version());
        assertThatThrownBy(()->changeService.review(actor,id,new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(previous.version()))).hasMessageContaining("변경");
    }
    @Test void changeBaselineSeedIsVersionedIdempotentAndLeavesLegacyValuesUntouched(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));long id=Long.parseLong(p.id());
        jdbc.update("DELETE FROM product_change_summary WHERE product_id=?",id);jdbc.update("DELETE FROM product_change_review WHERE product_id=?",id);
        var legacy=jdbc.queryForMap("SELECT latest_result,latest_at,last_good_result,revision FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",id);
        var candidate=changeService.previewInitial().getFirst();assertThat(candidate.listings()).isEqualTo(1);
        assertThat(changeService.seed(candidate)).isTrue();assertThat(changeService.seed(candidate)).isFalse();
        assertThat(changeService.summary(id).reviewKind()).isEqualTo("SYSTEM_INITIAL");assertThat(changeService.summary(id).anyChanged()).isFalse();
        assertThat(jdbc.queryForMap("SELECT latest_result,latest_at,last_good_result,revision FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",id)).isEqualTo(legacy);
        jdbc.update("DELETE FROM product_change_summary WHERE product_id=?",id);start(current(p));assertThat(changeService.previewInitial()).isEmpty();assertThat(changeService.seed(candidate)).isFalse();
    }
    @Test void reviewRaceAcceptsOnlyOneVersionAndApiEnforcesPermissionAndCsrf()throws Exception{
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));deltaFinish(p,deltaListing(149900,37L));long id=Long.parseLong(p.id());
        var input=new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(changeService.summary(id).version());
        var pool=Executors.newFixedThreadPool(2);try{
            var attempts=List.of(pool.submit(()->{try{changeService.review(actor,id,input);return true;}catch(IllegalStateException ex){return false;}}),pool.submit(()->{try{changeService.review(actor,id,input);return true;}catch(IllegalStateException ex){return false;}}));
            assertThat(List.of(attempts.get(0).get(),attempts.get(1).get())).containsExactlyInAnyOrder(true,false);
        }finally{pool.shutdownNow();}
        var staff=login(account(UserRole.PRODUCT));String path="/api/products/"+id+"/change-reviews";var body=Map.of("version",changeService.summary(id).version());
        status(new Browser().post(path,body),401);status(login(account(UserRole.VIEWER)).post(path,body),403);status(staff.send("POST",path,body,null),403);status(staff.post(path,body),200);status(staff.post(path,body),409);
        status(staff.get("/api/products/"+id+"/changes?kind=PRICE&page=0"),200);
        var page=staff.get("/api/products/"+id);status(page,200);assertThat(json.readTree(page.body()).path("data").path("changeSuppliers").size()).isEqualTo(1);status(staff.get("/api/products/"+id+"/changes?kind=WRONG"),400);
    }
    @Test void stockOnlyObservationDoesNotChangePriceTimeAndFirstKnownStockIsNotIncrease(){
        var p=stockProduct();var l=skipped(p);var before=changeService.summary(Long.parseLong(p.id()));var j=enqueueStock(p);
        assertThat(changeService.summary(Long.parseLong(p.id())).reviewable()).isFalse();assertThat(changeService.summary(Long.parseLong(p.id())).version()).isGreaterThan(before.version());
        time.value=time.value.plusHours(1);var w=stockQueue.claim("stock-delta");stockQueue.finish("stock-delta",w,stockListing("6617877030",13,false,w.product().runId()));
        var o=json.readValue(jdbc.queryForObject("SELECT snapshot FROM product_value_observation WHERE source_key=?",String.class,"STOCK:"+j.id()),cc.ataglace.molebutter.procurement.api.ChangeDtos.Observation.class);
        assertThat(o.priceAt()).isEqualTo(l.priceCheckedAt());assertThat(o.options().getFirst().checkedAt()).isEqualTo(time.now());
        assertThat(changeService.summary(Long.parseLong(p.id())).listings().stream().filter(c->c.supplierId().equals(l.id())).findFirst().orElseThrow().deltas()).isEmpty();
        assertThat(changeService.summary(Long.parseLong(p.id())).reviewable()).isTrue();
    }
    @Test void cheaperAlternativesRequireConfirmedPositiveStockAndExactThreshold(){
        var p=create("DCWA279BK");deltaFinish(p,deltaListing(156450,100L));choose(p,byMall(p,ProcurementMall.LFMALL));
        var cheap=listing(ProcurementMall.LFMALL,"cheap",155450,"온라인점","CONFIRMED");var near=listing(ProcurementMall.LFMALL,"near",155451,"온라인점","CONFIRMED");var unknown=listing(ProcurementMall.LFMALL,"unknown",100000,"온라인점","OPTIONS_UNKNOWN");
        deltaFinish(p,deltaListing(156450,100L),cheap,near,new SupplierResult(unknown.offer(),unknown.match(),"OPTIONS_UNKNOWN",List.of(),null));
        var summary=changeService.summary(Long.parseLong(p.id()));assertThat(summary.alternatives()).hasSize(1);assertThat(summary.alternatives().getFirst().saving()).isEqualTo(1000);
        assertThat(summary.groups().getFirst().cheapestChanged()).isTrue();assertThat(summary.groups().getFirst().difference()).isEqualTo(-56450);
        deltaFinish(p,cheap);assertThat(changeService.summary(Long.parseLong(p.id())).alternatives()).isEmpty();
    }
    @Test void firstFailedRefreshDoesNotBecomeInitialBaselineOrInventNewListingChange(){
        var p=create("DCWA279BK");start(p);var w=refresh.claim("failed");refresh.finish("failed",w,new RefreshResult("FAILED",null,null,null,List.of(),time.now(),"실패"));refresh.claim("failed");
        assertThat(changeService.summary(Long.parseLong(p.id())).reviewedAt()).isNull();
        deltaFinish(p,deltaListing(156450,100L));assertThat(changeService.summary(Long.parseLong(p.id())).anyChanged()).isFalse();
        assertThat(changeService.summary(Long.parseLong(p.id())).reviewKind()).isEqualTo("SYSTEM_INITIAL");
    }
    @Test void rawSearchMembershipPreventsFalseMissingWhenDetailWasOmitted(){
        var p=create("DCWA279BK");var listing=deltaListing(156450,100L);deltaFinish(p,listing);start(current(p));var w=refresh.claim("membership");
        refresh.cache(w,new SearchResult(List.of(),false,"최대 3페이지 조회","PAGE_LIMIT",List.of(SupplierStorePolicy.listingKey(listing.offer()))));
        refresh.finish("membership",w,new RefreshResult("NO_MATCH",null,null,null,List.of(),time.now(),null));
        assertThat(changeService.summary(Long.parseLong(p.id())).listings()).noneMatch(c->c.missing());
    }
    @Test void observationRetryDoesNotDuplicateHistoryOrProjection(){
        var p=create("DCWA279BK");start(p);var w=refresh.claim("same");var r=new RefreshResult("SUCCESS",null,null,null,List.of(deltaListing(156450,100L)),time.now(),null);
        refresh.finish("same",w,r);long version=changeService.summary(Long.parseLong(p.id())).version();
        jdbc.update("UPDATE procurement_runtime SET worker_owner='same',worker_until=? WHERE id=1",time.now().plusMinutes(1));refresh.finish("same",w,r);
        assertThat(changeService.summary(Long.parseLong(p.id())).version()).isEqualTo(version);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_value_observation WHERE product_id=?",Long.class,p.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history WHERE product_id=?",Long.class,p.id())).isEqualTo(1);
    }
    @Autowired ProductStatusRepair productStatusRepair;
    @Test void selectedMissingUsesRawSearchKeysAndPreservesChoiceAndReviewHistory(){
        var p=create("DCWA279BK");var selected=deltaListing(156450,100L);deltaFinish(p,selected);choose(p,byMall(p,ProcurementMall.LFMALL));
        var before=current(p).selectedSupplier();var alternative=listing(ProcurementMall.HAZZYS,"other",140000,"온라인점","CONFIRMED");
        deltaFinish(p,alternative);
        var missing=current(p).selectedSupplier();assertThat(missing.selectedMissing()).isTrue();assertThat(missing.selectedSearchAt()).isNotNull();
        assertThat(current(p).latestStatus()).isEqualTo("SUCCESS");assertThat(missing.id()).isEqualTo(before.id());assertThat(missing.referencePrice()).isEqualTo(156450);
        assertThat(missing.result().options()).isEqualTo(before.result().options());assertThat(missing.current()).isFalse();
        assertThat(compare(p).groups()).flatExtracting(Group::listings).extracting(Listing::id).doesNotContain(before.id());
        assertThat(compare(p).recommendationStatus().state()).isEqualTo("PRICE_UNCONFIRMED");
        var summary=changeService.summary(Long.parseLong(p.id()));changeService.review(actor,Long.parseLong(p.id()),new cc.ataglace.molebutter.procurement.api.ChangeDtos.ReviewInput(summary.version()));
        assertThat(current(p).selectedSupplier().selectedMissing()).isTrue();
        deltaFinish(p,selected);assertThat(current(p).selectedSupplier().selectedMissing()).isFalse();
    }
    @Test void selectedInRawSearchButDetailFailsIsNotMissingAndCancelledSearchHasNoMissingEvidence(){
        var p=create("DCWA279BK");var selected=deltaListing(100,2L);deltaFinish(p,selected);choose(p,byMall(p,ProcurementMall.LFMALL));
        start(current(p));var w=refresh.claim("evidence");refresh.cache(w,new SearchResult(List.of(),true,null,"COMPLETED",List.of(SupplierStorePolicy.listingKey(selected.offer()))));
        var failed=new SupplierResult(selected.offer(),selected.match(),"FAILED",List.of(),"재고 조회 시간 초과");
        refresh.finish("evidence",w,SearchCompletion.summarize(List.of(failed),"COMPLETED",time.now(),w.preferences(),w.manualStores(),false,List.of(),w.selectionBasis()));refresh.claim("evidence");
        assertThat(current(p).latestStatus()).isEqualTo("PARTIAL");assertThat(current(p).selectedSupplier().selectedMissing()).isFalse();
        var run=Long.parseLong(start(current(p)));var work=refresh.claim("cancelled");refresh.cache(work,new SearchResult(List.of(),true,null,"COMPLETED",List.of()));
        refresh.control(actor,run,"cancel");assertThat(current(p).selectedSupplier().selectedMissing()).isFalse();
    }
    @Test void newStatusRepairIsIdempotentAndDoesNotRewriteObservationsOrOldRun(){
        var p=create("DCWA279BK");legacyLimitedResult(p,false);var old=jdbc.queryForList("SELECT * FROM product_lookup_history");var entries=jdbc.queryForList("SELECT * FROM product_refresh_entry");
        var observations=jdbc.queryForList("SELECT * FROM product_value_observation");var at=current(p).latestAt();
        var plan=productStatusRepair.preview();assertThat(plan).hasSize(1);assertThat(productStatusRepair.apply(plan.getFirst())).isTrue();assertThat(productStatusRepair.apply(plan.getFirst())).isFalse();assertThat(productStatusRepair.preview()).isEmpty();
        assertThat(current(p).latestResult().statusPolicyVersion()).isEqualTo(2);assertThat(current(p).latestAt()).isEqualTo(at);
        assertThat(jdbc.queryForList("SELECT * FROM product_lookup_history")).isEqualTo(old);assertThat(jdbc.queryForList("SELECT * FROM product_refresh_entry")).isEqualTo(entries);assertThat(jdbc.queryForList("SELECT * FROM product_value_observation")).isEqualTo(observations);
    }
    @Test void supplierBlockedIsPartialButJobStillBlocksAndSearchBlockedIsFailed(){
        var p=create("DCWA279BK");var run=Long.parseLong(start(p));var w=refresh.claim("status");
        refresh.blocked("status",w,"SUPPLIER_ACCESS_RESTRICTED","매입처 재고 접속 제한");
        var status=refresh.status(actor,Long.parseLong(p.id()));assertThat(status.status()).isEqualTo("PARTIAL");assertThat(status.runStatus()).isEqualTo("BLOCKED");assertThat(status.active()).isTrue();assertThat(refresh.claim("another")).isNull();
        refresh.control(actor,run,"resume");w=refresh.claim("status");refresh.blocked("status",w,"ACCESS_RESTRICTED","네이버 접속 제한");
        assertThat(refresh.status(actor,Long.parseLong(p.id())).status()).isEqualTo("FAILED");assertThat(refresh.status(actor,Long.parseLong(p.id())).active()).isTrue();
    }
    @Autowired ProductSearchStatusRepair statusRepair;
    DefaultSupplierRefreshService.Work legacyLimitedResult(ProcurementProductView p,boolean stockProblem){
        start(p);var w=refresh.claim("repair-test");
        refresh.cache(w,new SearchResult(List.of(),false,"최대 3페이지 범위의 검색 결과입니다. 이후 페이지는 확인하지 않았습니다."));
        var listing=listing(ProcurementMall.LFMALL,"one",100,"온라인점","CONFIRMED");
        if(stockProblem)listing=new SupplierResult(listing.offer(),listing.match(),"OPTIONS_PARTIAL",listing.options(),null);
        refresh.finish("repair-test",w,new RefreshResult("PARTIAL",100L,"LF몰",0L,List.of(listing),time.now(),"검색 일부 조회"));
        refresh.claim("repair-test");return w;
    }
    @Test void statusRepairPreservesHistorySelectionAndTimestampsAndIsIdempotent(){
        var p=create("DCWA279BK");var w=legacyLimitedResult(p,false);choose(p,byMall(p,ProcurementMall.LFMALL));
        var before=current(p);var history=jdbc.queryForList("SELECT * FROM product_lookup_history WHERE product_id=?",p.id());
        var entry=jdbc.queryForMap("SELECT * FROM product_refresh_entry WHERE product_id=?",p.id());
        var good=jdbc.queryForObject("SELECT last_good_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",String.class,p.id());
        var plan=statusRepair.preview();assertThat(plan).hasSize(1);assertThat(plan.getFirst().after()).isEqualTo("SUCCESS");
        assertThat(statusRepair.apply(plan.getFirst())).isTrue();assertThat(statusRepair.apply(plan.getFirst())).isFalse();
        assertThat(statusRepair.preview()).isEmpty();var after=current(p);
        assertThat(after.latestStatus()).isEqualTo("SUCCESS");assertThat(after.latestAt()).isEqualTo(before.latestAt());
        assertThat(after.selectedSupplier()).isEqualTo(before.selectedSupplier());assertThat(after.latestResult().suppliers()).isEqualTo(before.latestResult().suppliers());
        assertThat(after.latestResult().message()).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM product_lookup_history WHERE product_id=?",p.id())).isEqualTo(history);
        assertThat(jdbc.queryForMap("SELECT * FROM product_refresh_entry WHERE product_id=?",p.id())).isEqualTo(entry);
        assertThat(jdbc.queryForObject("SELECT last_good_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",String.class,p.id())).isEqualTo(good);
    }
    @Test void statusRepairRejectsChangedVersionNewLookupAndUnknownSearchEvidence(){
        var p=create("DCWA279BK");var w=legacyLimitedResult(p,false);var plan=statusRepair.preview().getFirst();
        jdbc.update("UPDATE catalog_product SET revision=revision+1 WHERE id=?",p.id());assertThat(statusRepair.apply(plan)).isFalse();
        refresh.cache(w,new SearchResult(List.of(),false,"검색 오류"));assertThat(statusRepair.preview()).isEmpty();
        refresh.cache(w,new SearchResult(List.of(),false,null,"PAGE_LIMIT"));plan=statusRepair.preview().getFirst();
        start(p);assertThat(statusRepair.apply(plan)).isFalse();assertThat(statusRepair.preview()).isEmpty();
    }
    @Test void statusRepairRetainsRealStockProblemAndRejectsConcurrentRepair(){
        var p=create("DCWA279BK");legacyLimitedResult(p,true);var plan=statusRepair.preview().getFirst();
        assertThat(plan.after()).isEqualTo("PARTIAL");assertThat(statusRepair.apply(plan)).isTrue();assertThat(statusRepair.preview()).isEmpty();
        assertThat(current(p).latestResult().message()).contains("재고 미확인 1건");
    }
    @Test void searchStatusRepairKeepsUnknownSelectedSupplierOutsidePreferences(){
        verifySearchRepairSelectedUnknown(false);
    }
    @Test void searchStatusRepairKeepsUnknownSelectionDespiteHealthyPreferredSupplier(){
        verifySearchRepairSelectedUnknown(true);
    }
    void verifySearchRepairSelectedUnknown(boolean healthyOther){
        var p=create("ABCD6F123BK");
        finish(p,List.of(listing(ProcurementMall.LFMALL,"selected",100,"온라인점","CONFIRMED")));
        choose(p,byMall(p,ProcurementMall.LFMALL));
        var selected=byMall(p,ProcurementMall.LFMALL).result();
        preferred.deleteMall(actor,ProcurementMall.LFMALL,preferred.get(actor).revision());
        start(current(p));var work=refresh.claim("repair-selection");
        var unknown=new SupplierResult(selected.offer(),selected.match(),"CONFIRMED",List.of(new SourceOption("FREE","FREE",null,"STOCK_UNKNOWN")),null,null,null,null,selected.branch());
        var observations=new ArrayList<SupplierResult>();observations.add(unknown);
        if(healthyOther)observations.add(listing(ProcurementMall.HAZZYS,"healthy",120,"온라인점","CONFIRMED"));
        var offers=observations.stream().map(SupplierResult::offer).toList();
        refresh.cache(work,new SearchResult(offers,true,null,"COMPLETED",offers.stream().map(SupplierStorePolicy::listingKey).toList()));
        var result=SearchCompletion.summarize(observations,"COMPLETED",time.now(),work.preferences(),work.manualStores(),false,List.of(),work.selectionBasis());
        assertThat(result.status()).isEqualTo("PARTIAL");
        refresh.finish("repair-selection",work,result);refresh.claim("repair-selection");
        // A legacy search notice still needs repair, but must not discard the selected stock problem.
        jdbc.update("UPDATE procurement_product SET latest_result=JSON_SET(latest_result,'$.message','검색 일부 조회') WHERE product_id=?",p.id());
        var before=current(p);
        var history=jdbc.queryForList("SELECT * FROM product_lookup_history WHERE product_id=?",p.id());
        var entry=jdbc.queryForMap("SELECT * FROM product_refresh_entry WHERE run_id=? AND product_id=?",work.runId(),p.id());
        var plan=statusRepair.preview().stream().filter(c->c.productId()==Long.parseLong(p.id())).findFirst().orElseThrow();
        assertThat(plan.after()).isEqualTo("PARTIAL");assertThat(plan.message()).contains("재고 미확인 1건");
        assertThat(statusRepair.apply(plan)).isTrue();assertThat(statusRepair.apply(plan)).isFalse();
        var after=current(p);
        assertThat(after.latestStatus()).isEqualTo("PARTIAL");assertThat(after.latestResult().message()).contains("재고 미확인 1건").doesNotContain("검색 일부 조회");
        assertThat(after.selectedSupplier()).isEqualTo(before.selectedSupplier());
        assertThat(after.latestResult().suppliers()).isEqualTo(before.latestResult().suppliers());
        assertThat(after.latestAt()).isEqualTo(before.latestAt());
        assertThat(jdbc.queryForList("SELECT * FROM product_lookup_history WHERE product_id=?",p.id())).isEqualTo(history);
        assertThat(jdbc.queryForMap("SELECT * FROM product_refresh_entry WHERE run_id=? AND product_id=?",work.runId(),p.id())).isEqualTo(entry);
        assertThat(statusRepair.preview()).noneMatch(c->c.productId()==Long.parseLong(p.id()));
    }
    @Autowired RecommendationLookupService recommendationJournal;
    RecommendationDiagnostic recommendationDiagnostic(DefaultSupplierRefreshService.Work w,ProcurementMall mall,String id,String kind){return new RecommendationDiagnostic(Long.toString(w.runId()),Long.toString(w.productId()),mall+":"+id+":nv",mall,id,150190L,"https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no="+id,kind,"HTTP_RESTRICTED",403,time.now());}
    @Test void recommendationJournalSurvivesLeaseRecoveryAndRejectsStaleWorkers()throws Exception{
        var p=create("DBBA468BK");start(p);var work=refresh.claim("first");var d=recommendationDiagnostic(work,ProcurementMall.LOTTE_IMALL,"42","FAILED");
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->recommendationJournal.record("first",work,d,true));var b=pool.submit(()->recommendationJournal.record("first",work,d,true));a.get();b.get();}finally{pool.shutdownNow();}
        assertThat(recommendationJournal.list(work.runId(),work.productId())).hasSize(1);
        assertThat(recommendationJournal.restrictions(work.runId())).containsKey(ProcurementMall.LOTTE_IMALL);
        time.value=time.value.plusMinutes(11);var recovered=refresh.claim("second");assertThat(recovered.runId()).isEqualTo(work.runId());
        assertThatThrownBy(()->recommendationJournal.record("first",work,recommendationDiagnostic(work,ProcurementMall.LOTTE_IMALL,"43","FAILED"),true)).isInstanceOf(IllegalStateException.class);
        recommendationJournal.record("second",recovered,recommendationDiagnostic(recovered,ProcurementMall.LOTTE_IMALL,"43","SKIPPED"),false);
        var item=refresh.items(actor,work.runId(),0).items().getFirst();assertThat(item.get("recommendationFailed")).isEqualTo(1L);assertThat(item.get("recommendationSkipped")).isEqualTo(1L);
        refresh.control(actor,work.runId(),"cancel");assertThatThrownBy(()->recommendationJournal.record("second",recovered,d,true)).isInstanceOf(IllegalStateException.class);
        assertThat(recommendationJournal.list(work.runId(),work.productId())).hasSize(2);
        long next=Long.parseLong(start(p));assertThat(recommendationJournal.restrictions(next)).isEmpty();
    }
    @Test void recommendationFailurePersistsNormalResultsAndIsReadableThroughAuthorizedApi()throws Exception{
        var p=create("DBBA468BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"selected",166880,"목동점","CONFIRMED")));choose(p,byMall(p,ProcurementMall.HI_THEHYUNDAI));
        jdbc.update("DELETE FROM supplier_preference WHERE mall='LOTTE_IMALL'");start(p);var work=refresh.claim("w");
        var previousOffer=byMall(p,ProcurementMall.HI_THEHYUNDAI).result().offer();
        var selected=new Offer(previousOffer.naverProductId(),previousOffer.title(),"더현대Hi",previousOffer.mallProductId(),"https://hi.thehyundai.com/product/"+previousOffer.mallProductId(),166880L,0L,ProcurementMall.HI_THEHYUNDAI,null);
        var candidate=new Offer("candidate","가방","롯데홈쇼핑","42","https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no=42",150190L,0L,ProcurementMall.LOTTE_IMALL,null);
        var calls=new ArrayList<String>();var gateway=new cc.ataglace.molebutter.procurement.internal.SupplierProductGateway(){
            public String validateUrl(ProcurementMall mall,String url){return url;}public List<SourceOption> options(ProcurementMall mall,String id,String url){return inspect(mall,id,url).options();}
            public SourceDetails inspect(ProcurementMall mall,String id,String url){calls.add(id);if(mall==ProcurementMall.LOTTE_IMALL)throw new cc.ataglace.molebutter.procurement.internal.SupplierAccessRestricted(mall,"HTTP_RESTRICTED",403);return new SourceDetails("商品","","",List.of(new SourceOption("FREE","FREE",50L,"AVAILABLE")),"목동점");}
        };
        var lookup=new SupplierLookupService(gateway,time,recommendationJournal);var result=lookup.lookup(work,new SearchResult(List.of(selected,candidate),true,null),()->refresh.heartbeat("w",work),"w");
        // Recover after a process/lease loss before the product result was committed.
        time.value=time.value.plusMinutes(11);var recovered=refresh.claim("recovered");
        var resumed=new SupplierLookupService(gateway,time,recommendationJournal).lookup(recovered,new SearchResult(List.of(selected,candidate),true,null),()->refresh.heartbeat("recovered",recovered),"recovered");
        assertThat(Collections.frequency(calls,"42")).isEqualTo(1);
        refresh.finish("recovered",recovered,resumed);assertThat(refresh.claim("recovered")).isNull();
        assertThat(current(p).latestStatus()).isEqualTo("SUCCESS");assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(166880);
        assertThat(current(p).latestResult().recommendationDiagnostics()).hasSize(1);assertThat(compare(p).recommendations()).isEmpty();
        assertThatThrownBy(()->refresh.retry(actor,work.runId())).hasMessageContaining("재조회할 항목이 없습니다");
        status(new Browser().get("/api/product-refresh/"+work.runId()),401);status(login(account(UserRole.VIEWER)).get("/api/product-refresh/"+work.runId()),403);
        var response=login(account(UserRole.PRODUCT)).get("/api/product-refresh/"+work.runId());status(response,200);assertThat(response.body()).contains("recommendationDiagnostics","HTTP_RESTRICTED","150190");
        // A new lookup instance represents restart; the persisted restriction prevents another request.
        var recorded=recommendationJournal.restrictions(work.runId());assertThat(recorded).containsKey(ProcurementMall.LOTTE_IMALL);
    }
    @Test void recommendationRestrictionPreventsManualStockBypass(){
        var p=stockProduct();var job=enqueueStock(p);long run=jdbc.queryForObject("SELECT run_id FROM supplier_stock_lookup WHERE id=?",Long.class,job.id());
        // Install the same persisted restriction another product in this run can record after enqueue.
        var d=new RecommendationDiagnostic(Long.toString(run),p.id(),"blocked",ProcurementMall.NAVER_SMART_STORE,"blocked",100L,"https://shopping.naver.com/","FAILED","HTTP_RESTRICTED",429,time.now());
        jdbc.update("INSERT INTO recommendation_lookup_diagnostic(id,run_id,product_id,listing_key,mall,kind,cause_code,http_status,restricted,created_at,payload) VALUES(?,?,?,?,?,'FAILED','HTTP_RESTRICTED',429,TRUE,?,?)",ProductStore.id(),run,p.id(),"blocked",ProcurementMall.NAVER_SMART_STORE.name(),time.now(),json.writeValueAsString(d));
        assertThat(stockQueue.claim("manual")).isNull();assertThat(stockJob(job).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->enqueueStock(p)).hasMessageContaining("접속 제한");assertThat(jdbc.queryForObject("SELECT stock_lookup_blocked_job FROM procurement_runtime WHERE id=1",Long.class)).isNull();
    }
    @Autowired DefaultSupplierStockLookupService stockQueue;
    SupplierResult stockListing(String id,long quantity,boolean skipped,long run){
        var offer=new Offer("nv"+id,"가방","헤지스ACC",id,"https://shopping.naver.com/outlink/itemdetail/"+id,217720L,0L,ProcurementMall.NAVER_SMART_STORE,null,new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"),new SearchStoreEvidence("1000008804","1000008804","헤지스ACC","현대백화점 목동점","1","백화점"));
        var evidence=new StoreEvidence("BRANCH","현대백화점","목동점","NAVER_DEPARTMENT","10001/10001004",Map.of("channelId","1000008804"));
        return new SupplierResult(offer,new CodeMatch("SEARCH_RESULT",null,null,null,null,null),skipped?"SKIPPED_SAME_STORE":"CONFIRMED",skipped?List.of():List.of(new SourceOption("FREE","FREE",quantity,"AVAILABLE")),null,null,null,null,new BranchInfo("목동점","CONFIRMED",skipped?"SAME_CHANNEL":"API",null,evidence),new StockEvidence(run,skipped?"NAVER_SMART_STORE:13656623827:nv13656623827":null,"1000008804",skipped?null:time.now(),!skipped));
    }
    ProcurementProductView stockProduct(){var p=create("HIBA113K2");finish(p,List.of(stockListing("13656623827",25,false,1),stockListing("6617877030",0,true,1)));return p;}
    Listing skipped(ProcurementProductView p){return compare(p).groups().stream().flatMap(g->g.listings().stream()).filter(l->l.result().skipped()).findFirst().orElseThrow();}
    StockLookupJob enqueueStock(ProcurementProductView p){var l=skipped(p);return stockQueue.enqueue(actor,Long.parseLong(p.id()),Long.parseLong(l.id()),new StockLookupInput(current(p).revision(),l.revision()));}
    StockLookupJob stockJob(StockLookupJob j){return stockQueue.get(actor,Long.parseLong(j.productId()),Long.parseLong(j.supplierId()),Long.parseLong(j.id()));}
    @Test void stockQueueDeduplicatesAndPreservesSearchPriceTimeAndImmutableHistory()throws Exception{
        var p=stockProduct();var l=skipped(p);assertThat(l.selectable()).isFalse();assertThatThrownBy(()->choose(p,l)).hasMessageContaining("재고 조회");
        var pool=Executors.newFixedThreadPool(2);StockLookupJob j;try{var a=pool.submit(()->enqueueStock(p));var b=pool.submit(()->enqueueStock(p));j=a.get();assertThat(b.get().id()).isEqualTo(j.id());}finally{pool.shutdownNow();}
        var original=jdbc.queryForObject("SELECT payload FROM product_lookup_history WHERE product_id=? ORDER BY id LIMIT 1",String.class,p.id());var priceTime=l.priceCheckedAt();time.value=time.value.plusMinutes(2);
        var w=stockQueue.claim("manual");assertThat(w).isNotNull();assertThat(stockQueue.claim("other")).isNull();
        stockQueue.finish("manual",w,stockListing("6617877030",13,false,w.product().runId()));stockQueue.finish("manual",w,stockListing("6617877030",99,false,w.product().runId()));
        var fresh=compare(p).groups().getFirst().listings().stream().filter(v->v.id().equals(l.id())).findFirst().orElseThrow();
        assertThat(fresh.result().options().getFirst().stock()).isEqualTo(13);assertThat(fresh.priceCheckedAt()).isEqualTo(priceTime);assertThat(fresh.result().stockEvidence().checkedAt()).isEqualTo(time.now());assertThat(fresh.selectable()).isTrue();assertThat(compare(p).selected()).isNull();
        assertThat(stockJob(j).status()).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history WHERE product_id=?",Long.class,p.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload FROM product_lookup_history WHERE product_id=? ORDER BY id LIMIT 1",String.class,p.id())).isEqualTo(original);
        choose(p,fresh);assertThat(compare(p).selected().id()).isEqualTo(l.id());
    }
    @Test void manualStockLookupPreservesStructuredNormalSearchCompletion(){
        var p=stockProduct();
        jdbc.update("UPDATE procurement_product SET latest_result=JSON_SET(latest_result,'$.completionReason','PAGE_LIMIT','$.message','최대 3페이지 조회') WHERE product_id=?",p.id());
        var job=enqueueStock(p);var w=stockQueue.claim("manual");stockQueue.finish("manual",w,stockListing("6617877030",13,false,w.product().runId()));
        assertThat(current(p).latestStatus()).isEqualTo("SUCCESS");assertThat(current(p).latestResult().completionReason()).isEqualTo("COMPLETED");
        assertThat(current(p).latestResult().message()).isNull();
    }
    @Test void manualStockLookupReadsLegacySearchCacheWithoutQueryOnQueueWork(){
        var p=stockProduct();
        jdbc.update("INSERT INTO product_refresh_search(run_id,query_hash,result,checked_at) SELECT run_id,SHA2(query,256),?,checked_at FROM product_refresh_entry WHERE product_id=?",json.writeValueAsString(new SearchResult(List.of(),false,"최대 3페이지 범위의 검색 결과입니다. 이후 페이지는 확인하지 않았습니다.")),p.id());
        var job=enqueueStock(p);var w=stockQueue.claim("manual");assertThat(w.product().query()).isEmpty();
        stockQueue.finish("manual",w,stockListing("6617877030",13,false,w.product().runId()));
        assertThat(current(p).latestStatus()).isEqualTo("SUCCESS");assertThat(current(p).latestResult().completionReason()).isEqualTo("COMPLETED");
    }
    @Test void stockQueueReclaimsExpiredLeaseAndIgnoresLatePreviousWorker(){var p=stockProduct();var j=enqueueStock(p);var old=stockQueue.claim("old");time.value=time.value.plusMinutes(11);var next=stockQueue.claim("new");assertThat(next.id()).isEqualTo(old.id());stockQueue.finish("old",old,stockListing("6617877030",99,false,old.product().runId()));assertThat(stockJob(j).status()).isEqualTo("RUNNING");stockQueue.finish("new",next,stockListing("6617877030",13,false,next.product().runId()));assertThat(stockJob(j).status()).isEqualTo("SUCCEEDED");}
    @Test void stockQueueCancellationDoesNotApplyAnInFlightResponse(){var p=stockProduct();var j=enqueueStock(p);var w=stockQueue.claim("manual");j=stockJob(j);stockQueue.cancel(actor,Long.parseLong(p.id()),w.supplier(),w.id(),j.revision());stockQueue.finish("manual",w,stockListing("6617877030",13,false,w.product().runId()));assertThat(skipped(p)).isNotNull();assertThat(stockJob(j).status()).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history WHERE product_id=?",Long.class,p.id())).isEqualTo(1);}
    @Test void stockQueueSelectionChangePreventsOldResponseFromOverwriting(){var p=stockProduct();var j=enqueueStock(p);var w=stockQueue.claim("manual");choose(p,compare(p).groups().getFirst().listings().stream().filter(l->!l.result().skipped()).findFirst().orElseThrow());stockQueue.finish("manual",w,stockListing("6617877030",13,false,w.product().runId()));assertThat(stockJob(j).status()).isEqualTo("STALE");assertThat(skipped(p)).isNotNull();assertThat(compare(p).selected()).isNotNull();}
    @Test void stockQueuePauseRestrictionAndCancelCannotBypassGlobalGate(){
        var p=stockProduct();var j=enqueueStock(p);var other=create("OTHER");long run=Long.parseLong(start(other));refresh.control(actor,run,"pause");assertThat(stockQueue.claim("manual")).isNull();refresh.control(actor,run,"resume");var w=stockQueue.claim("manual");assertThat(w).isNotNull();assertThat(refresh.claim("automatic")).isNull();
        stockQueue.blocked("manual",w,"재고 조회 제한 · HTTP 429");assertThat(stockJob(j).blocking()).isTrue();assertThat(refresh.claim("automatic")).isNull();assertThat(stockQueue.claim("another")).isNull();assertThatThrownBy(()->start(other)).hasMessageContaining("접속 제한");
        j=stockJob(j);stockQueue.cancel(actor,Long.parseLong(p.id()),w.supplier(),w.id(),j.revision());assertThat(stockJob(j).blocking()).isTrue();j=stockJob(j);stockQueue.resume(actor,Long.parseLong(p.id()),w.supplier(),w.id(),j.revision());assertThat(stockJob(j).status()).isEqualTo("CANCELLED");assertThat(stockJob(j).blocking()).isFalse();assertThat(refresh.claim("automatic")).isNotNull();
    }
    @Test void stockQueueNewRefreshAndInactiveRequesterInvalidateWaitingWork(){
        var p=stockProduct();var j=enqueueStock(p);start(p);assertThat(stockQueue.claim("manual")).isNull();assertThat(stockJob(j).status()).isEqualTo("STALE");
    }
    @Test void stockQueueUnknownInventoryMayBeSelectedButFailedInspectionMayNot(){
        var p=stockProduct();var j=enqueueStock(p);var w=stockQueue.claim("manual");var base=stockListing("6617877030",13,false,w.product().runId());
        var unknown=new SupplierResult(base.offer(),base.match(),"OPTIONS_UNKNOWN",List.of(),null,null,null,null,base.branch(),base.stockEvidence());stockQueue.finish("manual",w,unknown);
        var l=compare(p).groups().getFirst().listings().stream().filter(v->v.id().equals(j.supplierId())).findFirst().orElseThrow();assertThat(l.selectable()).isTrue();
        var next=stockQueue.enqueue(actor,Long.parseLong(p.id()),Long.parseLong(l.id()),new StockLookupInput(current(p).revision(),l.revision()));var w2=stockQueue.claim("manual");var failure=new SupplierResult(base.offer(),base.match(),"FAILED",List.of(),"재고 조회 시간 초과",null,null,null,base.branch(),new StockEvidence(w2.product().runId(),null,"1000008804",null,false));stockQueue.finish("manual",w2,failure);
        var failed=compare(p).groups().getFirst().listings().stream().filter(v->v.id().equals(j.supplierId())).findFirst().orElseThrow();assertThat(failed.selectable()).isFalse();assertThat(stockJob(next).status()).isEqualTo("FAILED");
    }
    @Test void stockQueueApisRequireRoleCsrfAndMatchingProductAndJob()throws Exception{
        var p=stockProduct();var l=skipped(p);var staffUser=account(UserRole.PRODUCT);var staff=login(staffUser);String path="/api/products/"+p.id()+"/suppliers/"+l.id()+"/stock-lookups";var body=Map.of("revision",current(p).revision(),"supplierRevision",l.revision());
        status(new Browser().post(path,body),401);status(login(account(UserRole.VIEWER)).post(path,body),403);status(staff.send("POST",path,body,null),403);
        var response=staff.post(path,body);status(response,200);String id=json.readTree(response.body()).path("data").path("id").asText();assertThat(id).isNotBlank();status(staff.get(path+"/"+id),200);
        status(staff.get("/api/products/"+p.id()+"/suppliers/999/stock-lookups/"+id),404);status(staff.send("DELETE",path+"/"+id,Map.of("revision",0),null),403);
        staff.csrf();status(staff.send("DELETE",path+"/"+id,Map.of("revision",9),staff.csrf),409);
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED',auth_version=auth_version+1 WHERE id=?",staffUser.getId());assertThat(stockQueue.claim("manual")).isNull();assertThat(jdbc.queryForObject("SELECT status FROM supplier_stock_lookup WHERE id=?",String.class,id)).isEqualTo("STALE");status(staff.get(path+"/"+id),401);
    }
    @Test void stockQueueConflictRevokesDependentGroupingAndOldRunCannotResume(){
        var p=create("HIBA113K2");finish(p,List.of(stockListing("13656623827",25,false,1),stockListing("6617877030",0,true,1),stockListing("6841687151",0,true,1)));
        var j=enqueueStock(p);var w=stockQueue.claim("manual");var old=w.previous();var wrong=new BranchInfo("대구점","CONFIRMED","API",null,new StoreEvidence("BRANCH","현대백화점","대구점","NAVER_DEPARTMENT","10001/other",Map.of("channelId","other")));
        stockQueue.finish("manual",w,new SupplierResult(old.offer(),old.match(),"CONFIRMED",List.of(new SourceOption("FREE","FREE",13L,"AVAILABLE")),null,null,null,null,wrong,new StockEvidence(w.product().runId(),null,"other",time.now(),true)));
        assertThat(storedListings(p).stream().filter(v->"GROUP_UNCONFIRMED".equals(v.result().state()))).hasSize(1);
        assertThat(compare(p).groups().stream().flatMap(g->g.listings().stream()).noneMatch(v->"GROUP_UNCONFIRMED".equals(v.result().state()))).isTrue();
        long run=Long.parseLong(start(p));refresh.control(actor,run,"pause");jdbc.update("UPDATE product_refresh_entry SET selection_snapshot=JSON_REMOVE(selection_snapshot,'$.stockPolicy') WHERE run_id=?",run);assertThatThrownBy(()->refresh.control(actor,run,"resume")).hasMessageContaining("이전 방식");
    }
    ProcurementProductView create(String code){return products.create(actor,new ProductEditRequest(null,null,code,ProductCodePolicy.suggested("LF_ACCESSORY",code),brand,null,null));}
    ProcurementProductView current(ProcurementProductView p){return products.product(Long.parseLong(p.id()));}
    VersionedId version(ProcurementProductView p){p=current(p);return new VersionedId(p.id(),p.revision());}
    String start(ProcurementProductView p){return refresh.start(actor,new RefreshInput("SELECTED",List.of(p.id())));}
    RefreshResult result(){return new RefreshResult("SUCCESS",10000L,"헤지스",0L,List.of(new SupplierResult(new Offer("nv","헤지스 ABCD6E123BK","헤지스","ABCD6E123BK","https://www.hazzys.com/product.do?PROD_CD=ABCD6E123BK",10000L,0L,ProcurementMall.HAZZYS,null),new CodeMatch("MATCHED","ABCD6E123BK","ABCD123","6E","BK","시즌 차이"),"CONFIRMED",List.of(new SourceOption("FREE","블랙 / FREE",2L,"AVAILABLE")),null)),time.now(),null);}
    void finish(ProcurementProductView p){start(p);var w=refresh.claim("worker");assertThat(w).isNotNull();refresh.finish("worker",w,result());assertThat(refresh.claim("worker")).isNull();}
    RefreshRunPage history(String from,String to,String status,boolean failed,int page,Long run){return refresh.runs(actor,new RefreshRunQuery(from,to,status,failed,page,20,run));}
    Map<String,Object> latestRun(){return history("","","",false,0,null).items().getFirst();}
    List<String> historyIds(RefreshRunPage page){return page.items().stream().map(r->r.get("id").toString()).toList();}
    long historyRun(long id,String status,String createdAt,long product,String entry){
        jdbc.update("INSERT INTO product_refresh_run(id,status,trigger_type,created_at) VALUES(?,?,'MANUAL',?)",id,status,LocalDateTime.parse(createdAt));
        jdbc.update("INSERT INTO product_refresh_entry(run_id,product_id,lookup_revision,query,product_code,code_type,status) VALUES(?,?,0,'q','CODE','GENERAL',?)",id,product,entry);return id;
    }
    @Test void refreshHistoryPagesByKoreanDateStatusAndFailuresWhileActiveRunStaysReachable()throws Exception{
        long product=Long.parseLong(create("ABCD6F123BK").id()),gone=Long.parseLong(create("ABCD6F124BK").id());
        historyRun(1,"COMPLETED","2026-09-20T23:59:59",product,"SUCCESS");
        historyRun(2,"COMPLETED","2026-09-21T00:00:00",product,"FAILED");
        historyRun(3,"CANCELLED","2026-09-22T12:00:00",product,"CANCELLED");
        historyRun(4,"COMPLETED","2026-09-22T13:00:00",gone,"FAILED");
        historyRun(5,"PAUSED","2026-09-10T09:00:00",product,"PENDING");
        jdbc.update("UPDATE catalog_product SET deleted_at=? WHERE id=?",time.value,gone);
        assertThat(historyIds(history("","","",false,0,null))).containsExactly("5","4","3","2","1");
        // 종료일은 그날 23:59:59까지, 다음 날 0시는 제외(created_at은 한국 시간).
        assertThat(historyIds(history("2026-09-20","2026-09-20","",false,0,null))).containsExactly("1");
        assertThat(historyIds(history("2026-09-21","","",false,0,null))).containsExactly("4","3","2");
        assertThat(historyIds(history("","","CANCELLED",false,0,null))).containsExactly("3");
        // 실패 재조회 대상과 같은 기준: 취소로 남은 항목은 포함, 삭제된 상품·진행 중 미조회 항목은 제외.
        var failed=history("","","",true,0,null);
        assertThat(historyIds(failed)).containsExactly("3","2");
        assertThat(failed.items()).allSatisfy(r->assertThat(((Number)r.get("retryable")).intValue()).isEqualTo(1));
        assertThat(((Number)history("2026-09-20","2026-09-20","",false,0,null).items().getFirst().get("retryable")).intValue()).isZero();
        // 진행 중 작업·선택 작업은 조회 조건 밖이어도 돌려준다.
        var outside=history("2026-09-20","2026-09-20","COMPLETED",false,0,1L);
        assertThat(outside.active().stream().map(r->r.get("id")).toList()).containsExactly("5");
        assertThat(outside.selected().get("id")).isEqualTo("1");
        assertThat(history("2026-09-21","2026-09-21","",false,0,5L).selected().get("status")).isEqualTo("PAUSED");
        assertThat(history("","","",false,0,999L).selected()).isNull();
        assertThat(outside.stockLookupBlock()).isNull();
        for(long id=100;id<121;id++)historyRun(id,"COMPLETED","2026-09-23T10:00:00",product,"SUCCESS");
        var second=history("2026-09-23","2026-09-23","",false,1,null);
        assertThat(second.totalElements()).isEqualTo(21);assertThat(second.totalPages()).isEqualTo(2);assertThat(historyIds(second)).containsExactly("100");
        assertThat(history("2026-09-24","2026-09-24","",false,0,null).items()).isEmpty();

        String path="/api/product-refresh";
        status(new Browser().get(path),401);status(login(account(UserRole.VIEWER)).get(path),403);
        var browser=login(account(UserRole.PRODUCT));
        var page=json.readTree(browser.get(path+"?from=2026-09-21&to=2026-09-22&status=COMPLETED&failed=true&run=5").body()).path("data");
        assertThat(page.path("items").size()).isEqualTo(1);assertThat(page.path("items").get(0).path("id").asText()).isEqualTo("2");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);assertThat(page.path("active").get(0).path("id").asText()).isEqualTo("5");
        assertThat(page.path("selected").path("id").asText()).isEqualTo("5");
        for(String bad:List.of("?size=30","?page=-1","?from=2026-9-1","?from=2026-09-23&to=2026-09-22","?status=DONE","?run=abc"))status(browser.get(path+bad),400);
    }
    @Autowired NaverSearchRecoveryService recovery;
    cc.ataglace.molebutter.procurement.internal.NaverSearchFailure noResponse(){return new cc.ataglace.molebutter.procurement.internal.NaverSearchFailure(cc.ataglace.molebutter.procurement.internal.NaverSearchFailure.Code.SEARCH_NO_REQUEST,"SORT",Map.of("requests",0));}
    @Test void searchRecoverySharesStreakAcrossLoginAndNoResponseAndNeverWritesFailedObservations(){
        var p=create("RECOVERY1");long run=Long.parseLong(start(p));int[] delays={5,30,60,120};
        for(int n=0;n<5;n++){
            var w=refresh.claim("worker");assertThat(w).isNotNull();long attempt=refresh.searchStarted("worker",w);
            Exception failure=n%2==0?noResponse():new cc.ataglace.molebutter.procurement.internal.NaverPriceSearch.SearchBlocked(cc.ataglace.molebutter.procurement.internal.NaverPriceSearch.BlockReason.LOGIN_REQUIRED,"safe");
            refresh.searchFailed("worker",w,attempt,failure);refresh.searchFailed("worker",w,attempt,failure);
            var status=refresh.status(actor,Long.parseLong(p.id()));assertThat(status.searchRetryCount()).isEqualTo(n+1);assertThat(status.status()).isEqualTo(n<4?"PENDING":"FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_value_observation",Integer.class)).isZero();
            assertThat(refresh.claim("other")).isNull();
            if(n<4){assertThat(status.nextRetryAt()).isEqualTo(time.value.plusMinutes(delays[n]));time.value=status.nextRetryAt();}
            else {assertThat(status.runStatus()).isEqualTo("BLOCKED");assertThat(recovery.gate().get("manualResumeRequired")).isEqualTo(true);}
        }
        assertThat(recovery.history(actor,run,0,"",null).totalElements()).isEqualTo(5);
        refresh.control(actor,run,"resume");assertThat(recovery.gated()).isFalse();assertThat(refresh.status(actor,Long.parseLong(p.id())).searchRetryCount()).isZero();
        time.value=time.value.plusSeconds(60);var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);assertThat(refresh.searchSucceeded("worker",w,a)).isTrue();assertThat(refresh.searchSucceeded("worker",w,a)).isFalse();
    }
    @Test void searchCooldownCannotBeBypassedByCancellingRunOrNewManualQueue(){
        var p=stockProduct();var listing=skipped(p);long run=Long.parseLong(start(current(p)));var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);
        refresh.searchFailed("worker",w,a,noResponse());refresh.control(actor,run,"cancel");
        assertThatThrownBy(()->stockQueue.enqueue(actor,Long.parseLong(p.id()),Long.parseLong(listing.id()),new StockLookupInput(current(p).revision(),listing.revision()))).hasMessageContaining("검색 대기");
        long next=Long.parseLong(start(current(p)));assertThat(refresh.claim("new-worker")).isNull();
        long version=((Number)recovery.gate().get("version")).longValue();assertThatThrownBy(()->refresh.resumeSearchGate(actor,version)).hasMessageContaining("예정 시각");
        time.value=time.value.plusMinutes(5);assertThat(refresh.claim("new-worker").runId()).isEqualTo(next);
    }
    @Test void successfulSearchResetsRecoveryBeforeInventoryAndPreservesReviewValuesDuringWait(){
        var p=create("RECOVERY2");deltaFinish(p,deltaListing(156450,100L));choose(p,byMall(p,ProcurementMall.LFMALL));
        var before=current(p).selectedSupplier();var summary=changeService.summary(Long.parseLong(p.id()));
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM product_value_observation",Long.class);
        start(current(p));var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);refresh.searchFailed("worker",w,a,noResponse());
        assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(before.referencePrice());
        assertThat(current(p).selectedSupplier().priceCheckedAt()).isEqualTo(before.priceCheckedAt());
        assertThat(changeService.summary(Long.parseLong(p.id())).reviewedAt()).isEqualTo(summary.reviewedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_value_observation",Long.class)).isEqualTo(count);
        time.value=time.value.plusMinutes(5);w=refresh.claim("worker");a=refresh.searchStarted("worker",w);assertThat(refresh.searchSucceeded("worker",w,a)).isTrue();
        assertThat(refresh.status(actor,Long.parseLong(p.id())).searchRetryCount()).isZero();assertThat(recovery.gate().get("untilAt")).isNull();
    }
    @Test void repeatedValidationStopsAfterOneRecheckAndExplicitResumeResetsSignature(){
        var p=create("RECOVERY3");long run=Long.parseLong(start(p));
        var failure=new cc.ataglace.molebutter.procurement.internal.NaverSearchFailure(cc.ataglace.molebutter.procurement.internal.NaverSearchFailure.Code.RESPONSE_MISMATCH,"SORT",Map.of("mismatches",List.of("SORT")));
        for(int i=0;i<2;i++){var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);refresh.searchFailed("worker",w,a,failure);time.value=time.value.plusMinutes(5);}
        assertThat(refresh.status(actor,Long.parseLong(p.id())).runStatus()).isEqualTo("BLOCKED");
        assertThat(recovery.gated()).isTrue();refresh.control(actor,run,"cancel");
        long version=((Number)recovery.gate().get("version")).longValue();assertThatThrownBy(()->refresh.resumeSearchGate(actor,version-1)).isInstanceOf(RuntimeException.class);
        refresh.resumeSearchGate(actor,version);assertThat(recovery.gated()).isFalse();assertThat(latestRun().get("status")).isEqualTo("CANCELLED");
    }
    @Test void cancelledSearchAndAbandonedAttemptDoNotPublishResultsOrResetGate(){
        var p=create("RECOVERY4");long run=Long.parseLong(start(p));var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);
        refresh.control(actor,run,"cancel");refresh.searchFailed("worker",w,a,noResponse());assertThat(latestRun().get("status")).isEqualTo("CANCELLED");assertThat(recovery.gated()).isTrue();
        time.value=time.value.plusMinutes(5);start(current(p));w=refresh.claim("dead-worker");a=refresh.searchStarted("dead-worker",w);
        time.value=time.value.plusMinutes(11);assertThat(refresh.claim("replacement")).isNull();
        assertThat(recovery.history(actor,w.runId(),0,"INTERRUPTED",null).totalElements()).isEqualTo(1);
        assertThat(refresh.searchSucceeded("dead-worker",w,a)).isFalse();
        time.value=time.value.plusMinutes(5);assertThat(refresh.claim("replacement")).isNotNull();
    }
    @Test void concurrentFailurePauseAndStaleOwnerCannotDuplicateOrPublishSearchResults()throws Exception{
        var p=create("RECOVERY6");long run=Long.parseLong(start(p));var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);
        refresh.control(actor,run,"pause");var pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->refresh.searchFailed("worker",w,a,noResponse()));var second=pool.submit(()->refresh.searchFailed("worker",w,a,noResponse()));first.get();second.get();}finally{pool.shutdownNow();}
        assertThat(refresh.status(actor,Long.parseLong(p.id())).runStatus()).isEqualTo("PAUSED");assertThat(refresh.status(actor,Long.parseLong(p.id())).searchRetryCount()).isEqualTo(1);
        assertThat(recovery.history(actor,run,0,"",null).totalElements()).isEqualTo(1);
        time.value=time.value.plusMinutes(5);assertThat(refresh.claim("another")).isNull();refresh.control(actor,run,"resume");
        var next=refresh.claim("another");long b=refresh.searchStarted("another",next);
        refresh.searchFailed("worker",w,a,noResponse());assertThat(refresh.searchSucceeded("worker",w,a)).isFalse();
        jdbc.update("UPDATE procurement_product SET lookup_revision=lookup_revision+1 WHERE product_id=?",p.id());assertThat(refresh.searchSucceeded("another",next,b)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history",Integer.class)).isZero();
    }
    @Test void searchDiagnosticsApiIsSafeAndGateResumeRequiresCsrfAndVersion()throws Exception{
        var p=create("RECOVERY5");long run=Long.parseLong(start(p));var w=refresh.claim("worker");long a=refresh.searchStarted("worker",w);refresh.searchFailed("worker",w,a,noResponse());
        var browser=login(account(UserRole.PRODUCT));
        var response=browser.get("/api/product-refresh/"+run+"/search-attempts");status(response,200);
        assertThat(response.body()).contains("SEARCH_NO_REQUEST","SORT").doesNotContain("owner_token","Cookie","Authorization");
        status(browser.send("POST","/api/product-refresh/search-gate/resume",Map.of("version",1),null),403);
        assertThat(browser.post("/api/product-refresh/search-gate/resume",Map.of("version",0)).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(recovery.history(actor,run,0,"LOGIN_REQUIRED",null).items()).isEmpty();
    }
    @Test void loginBackoffPersistsAndExhaustsThenManualResumeStartsNewCycle(){
        var p=create("ABCD6F123BK");long run=Long.parseLong(start(p));
        int[] delays={5,30,60,120};
        for(int i=0;i<5;i++){
            var w=refresh.claim("worker");assertThat(w).isNotNull();
            refresh.searchFinished("worker",w,false);
            var detected=time.value;
            refresh.blocked("worker",w,"LOGIN_REQUIRED","login");
            refresh.blocked("worker",w,"LOGIN_REQUIRED","duplicate");
            var status=refresh.status(actor,Long.parseLong(p.id()));
            assertThat(status.loginRetryCount()).isEqualTo(i+1);
            assertThat(status.blockReason()).isEqualTo("LOGIN_REQUIRED");
            assertThat(latestRun().get("loginRetryCount")).isEqualTo(i+1);
            assertThat(refresh.claim("other-process")).isNull();
            assertThatThrownBy(()->start(p)).hasMessageContaining("기존 최신화");
            if(i<4){
                assertThat(status.runStatus()).isEqualTo("RETRY_WAIT");
                assertThat(status.nextRetryAt()).isEqualTo(detected.plusMinutes(delays[i]));
                time.value=status.nextRetryAt().minusSeconds(1);assertThat(refresh.claim("other-process")).isNull();
                time.value=status.nextRetryAt();
            }else{
                assertThat(status.runStatus()).isEqualTo("BLOCKED");assertThat(status.nextRetryAt()).isNull();
                time.value=time.value.plusDays(1);assertThat(refresh.claim("other-process")).isNull();
                refresh.control(actor,run,"resume");
                var fresh=refresh.claim("other-process");assertThat(fresh).isNotNull();
                refresh.blocked("other-process",fresh,"LOGIN_REQUIRED","login");
                assertThat(refresh.status(actor,Long.parseLong(p.id())).nextRetryAt()).isEqualTo(time.value.plusMinutes(5));
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event WHERE target_id=?",Integer.class,run)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history",Integer.class)).isZero();
    }
    @Test void actualSearchCooldownSurvivesWorkerChangeAndCacheDoesNotWait(){
        var p=create("ABCD6F123BK");var same=create("ABCD6E123BK");var other=create("ABCD6F124BK");
        refresh.start(actor,new RefreshInput("SELECTED",List.of(p.id(),same.id(),other.id())));
        var w=refresh.claim("worker");assertThat(refresh.searchFinished("worker",w,true)).isTrue();
        refresh.cache(w,new SearchResult(List.of(),true,null));refresh.finish("worker",w,result());
        var cached=refresh.claim("replacement");assertThat(cached.productId()).isEqualTo(Long.parseLong(same.id()));
        assertThat(refresh.cached(cached)).isNotNull();refresh.finish("replacement",cached,result());
        assertThat(refresh.claim("replacement")).isNull();
        assertThat(refresh.status(actor,Long.parseLong(other.id())).nextSearchAt()).isEqualTo(time.value.plusSeconds(60));
        assertThat(latestRun().get("nextSearchAt")).isNotNull();
        time.value=time.value.plusSeconds(59);assertThat(refresh.claim("replacement")).isNull();
        time.value=time.value.plusSeconds(1);var next=refresh.claim("replacement");assertThat(next.productId()).isEqualTo(Long.parseLong(other.id()));
        refresh.searchFinished("replacement",next,true);time.value=time.value.plusSeconds(70);
        assertThat(refresh.claim("other-worker")).isNull(); // supplier lookup still owns the worker after the search interval
        refresh.finish("replacement",next,result());
        assertThat(refresh.claim("replacement")).isNull(); // finished job
        var newProduct=create("ABCD6F125BK");start(newProduct);assertThat(refresh.claim("replacement")).isNotNull();
    }
    @Test void successfulSearchResetsLoginStreakButOrdinaryRestrictionsDoNotAutoResume(){
        var p=create("ABCD6F123BK");start(p);var w=refresh.claim("worker");
        refresh.blocked("worker",w,"LOGIN_REQUIRED","login");time.value=time.value.plusMinutes(5);
        w=refresh.claim("worker");refresh.searchFinished("worker",w,true);
        assertThat(refresh.status(actor,Long.parseLong(p.id())).loginRetryCount()).isZero();
        refresh.blocked("worker",w,"LOGIN_REQUIRED","login");
        assertThat(refresh.status(actor,Long.parseLong(p.id())).nextRetryAt()).isEqualTo(time.value.plusMinutes(5));
        time.value=time.value.plusMinutes(5);w=refresh.claim("worker");refresh.blocked("worker",w,"HTTP 403");
        time.value=time.value.plusDays(1);assertThat(refresh.claim("worker")).isNull();
        assertThat(refresh.status(actor,Long.parseLong(p.id())).runStatus()).isEqualTo("BLOCKED");
    }
    @Test void pausingLoginWaitKeepsDeadlineAndCancellationWinsLateLogin(){
        var p=create("ABCD6F123BK");long run=Long.parseLong(start(p));var w=refresh.claim("worker");
        refresh.control(actor,run,"pause");refresh.blocked("worker",w,"LOGIN_REQUIRED","login");
        var status=refresh.status(actor,Long.parseLong(p.id()));assertThat(status.runStatus()).isEqualTo("PAUSED");
        assertThatThrownBy(()->refresh.control(actor,run,"resume")).hasMessageContaining("예정 시각");
        time.value=time.value.plusMinutes(5);assertThat(refresh.claim("worker")).isNull();refresh.control(actor,run,"resume");
        w=refresh.claim("worker");refresh.blocked("worker",w,"LOGIN_REQUIRED","login");
        refresh.control(actor,run,"pause");assertThatThrownBy(()->refresh.control(actor,run,"resume")).hasMessageContaining("예정 시각");
        time.value=time.value.plusMinutes(30);assertThat(refresh.claim("worker")).isNull();refresh.control(actor,run,"resume");
        w=refresh.claim("worker");refresh.control(actor,run,"cancel");refresh.blocked("worker",w,"LOGIN_REQUIRED","late login");
        time.value=time.value.plusDays(1);assertThat(refresh.claim("worker")).isNull();
        assertThat(refresh.status(actor,Long.parseLong(p.id())).runStatus()).isEqualTo("CANCELLED");
    }
    @Test void retryDeadlineHasSameKoreanLocalTimeInBothApisAndRejectsEarlyResume()throws Exception{
        var p=create("ABCD6F123BK");long run=Long.parseLong(start(p));var w=refresh.claim("worker");
        refresh.blocked("worker",w,"LOGIN_REQUIRED","login");
        var browser=login(account(UserRole.PRODUCT));
        var list=json.readTree(browser.get("/api/product-refresh").body()).path("data").path("items").get(0);
        var detail=json.readTree(browser.get("/api/products/"+p.id()+"/refresh-status").body()).path("data");
        assertThat(list.path("nextRetryAt").asText()).isEqualTo(detail.path("nextRetryAt").asText()).startsWith("2026-09-24T18:05");
        status(browser.post("/api/product-refresh/"+run+"/pause",Map.of()),200);
        assertThat(browser.post("/api/product-refresh/"+run+"/resume",Map.of()).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(refresh.status(actor,Long.parseLong(p.id())).runStatus()).isEqualTo("PAUSED");
    }
    @Test void explicitQueryIsRequiredAndDeprecatedModesCannotReplaceIt() throws Exception {
        var browser=login(account(UserRole.PRODUCT));
        status(browser.post("/api/products",Map.of("brandId",brand,"productCode","WCBA5F052BK","searchQuery","   ")),400);
        status(browser.post("/api/products",Map.of("brandId","999999999","productCode","WCBA5F052BK","searchQuery","WCBA052")),400);
        var p=products.create(actor,new ProductEditRequest(null,null," wcba5f052bk ","  닥스 가방 WCBA052  ",brand,"AUTO","LF_ACCESSORY"));
        assertThat(p.productCode()).isEqualTo("WCBA5F052BK");assertThat(p.searchQuery()).isEqualTo("닥스 가방 WCBA052");
        var edited=products.edit(actor,Long.parseLong(p.id()),new ProductEditRequest(p.revision(),null,"WCBA6F052BK",p.searchQuery(),"","AUTO","LF_ACCESSORY"));
        assertThat(edited.searchQuery()).isEqualTo(p.searchQuery());assertThat(edited.brandId()).isNull();assertThat(edited.lookupRevision()).isEqualTo(p.lookupRevision()+1);
        products.bulk(actor,new BulkEdit(List.of(version(edited)),null,null,brand));assertThat(current(p).searchQuery()).isEqualTo(p.searchQuery());assertThat(current(p).lookupRevision()).isEqualTo(edited.lookupRevision());
        status(browser.post("/api/products/"+p.id(),Map.of("revision",p.revision(),"brandId",brand,"productCode",p.productCode(),"searchQuery","stale")),409);
        jdbc.update("UPDATE procurement_product SET comparison_code='LEGACYONLY' WHERE product_id=?",p.id());
        assertThat(products.list(actor,"LEGACYONLY","ALL","",0).totalElements()).isZero();
        assertThat(products.list(actor,"닥스 가방","ALL","",0).totalElements()).isEqualTo(1);
    }
    @Test void excelInitialQueryOnlyChangesNewProductsAndInferencePreservesExistingAutoQuery(){
        var old=products.create(actor,new ProductEditRequest(null,null,"ABCD6F123BK","내 검색어","",null,null));
        jdbc.update("UPDATE procurement_product SET search_mode='AUTO' WHERE product_id=?",old.id());
        var imported=products.upload(actor,"query.xlsx",WorkbookFixture.create(3,Map.of("F4","ABCD6F123BK","H4","헤지스 가방","F5","ABCD6F124BK","H5","헤지스 가방","F6","SHIRT-1","H6","헤지스 셔츠")));
        assertThat(imported.created()).isEqualTo(2);assertThat(current(old).brandId()).isEqualTo(brand);assertThat(current(old).searchQuery()).isEqualTo("내 검색어");
        assertThat(products.list(actor,"ABCD6F124BK","ALL","",0).items().getFirst().searchQuery()).isEqualTo("ABCD124");
        assertThat(products.list(actor,"SHIRT-1","ALL","",0).items().getFirst().searchQuery()).isEqualTo("SHIRT-1");
        jdbc.update("UPDATE catalog_product SET brand_id=NULL WHERE id=?",old.id());
        products.inferBrands(actor,new BrandInferenceInput(List.of(version(old))));assertThat(current(old).searchQuery()).isEqualTo("내 검색어");
    }
    @Test void queryEditPreservesSelectionAndPreventsOldWorkOverwritingNewCriteria(){
        var p=create("ABCD6F123BK");finish(p);choose(p,byMall(p,ProcurementMall.HAZZYS));var selected=current(p).selectedSupplier();
        start(current(p));var work=refresh.claim("query-worker");var before=current(p);
        products.edit(actor,Long.parseLong(p.id()),new ProductEditRequest(before.revision(),null,p.productCode(),"변경된 검색어",brand,null,null));
        refresh.finish("query-worker",work,result());
        assertThat(current(p).latestResult()).isNull();assertThat(current(p).selectedSupplier().id()).isEqualTo(selected.id());assertThat(current(p).selectedSupplier().priceStatus()).isEqualTo("STALE");
        assertThat(products.history(actor,Long.parseLong(p.id()),0).totalElements()).isEqualTo(2);
        assertThat(compare(p).recommendations()).isEmpty();assertThat(current(p).searchQuery()).isEqualTo("변경된 검색어");
    }
    @Test void missingOrNullLookupPolicyCannotResumeButHistoryRemainsReadable(){
        var p=create("ABCD6F123BK");finish(p);choose(p,byMall(p,ProcurementMall.HAZZYS));
        for(String legacy:List.of("NULL","JSON_REMOVE(selection_snapshot,'$.naverPolicy')","JSON_SET(selection_snapshot,'$.naverPolicy',CAST('null' AS JSON))","JSON_REMOVE(selection_snapshot,'$.lookupPolicy')","JSON_SET(selection_snapshot,'$.lookupPolicy',CAST('null' AS JSON))")){
            long run=Long.parseLong(start(current(p)));
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(selection_snapshot,'$.lookupPolicy')) FROM product_refresh_entry WHERE run_id=?",String.class,run)).isEqualTo("SEARCH_QUERY");
            jdbc.update("UPDATE product_refresh_entry SET selection_snapshot="+legacy+" WHERE run_id=?",run);
            assertThat(refresh.claim("old-worker")).isNull();assertThat(compare(p).recommendationStatus().state()).isEqualTo("REFRESH_REQUIRED");
            assertThatThrownBy(()->refresh.control(actor,run,"resume")).hasMessageContaining("취소 후 다시 실행");
            assertThat(current(p).selectedSupplier()).isNotNull();products.detail(actor,Long.parseLong(p.id()));
            refresh.control(actor,run,"cancel");
        }
        start(current(p));assertThat(refresh.claim("new-worker")).isNotNull();
    }
    SupplierResult searchResult(SupplierResult raw){return new SupplierResult(raw.offer(),new CodeMatch("SEARCH_RESULT",null,null,null,null,null),raw.state(),raw.options(),raw.message(),null,null,"다른 브랜드",raw.branch());}
    @Test void searchResultsWithoutCodesCanBeSelectedRecommendedAndRefreshed(){
        var p=create("ABCD6F123BK");
        finish(p,List.of(searchResult(listing(ProcurementMall.LFMALL,"chosen",10000,"","CONFIRMED"))));
        var original=byMall(p,ProcurementMall.LFMALL);assertThat(original.selectable()).isTrue();choose(p,original);
        preferred.deleteMall(actor,ProcurementMall.HI_THEHYUNDAI,preferred.get(actor).revision());
        finish(p,List.of(searchResult(listing(ProcurementMall.LFMALL,"chosen",10000,"","CONFIRMED")),searchResult(listing(ProcurementMall.HI_THEHYUNDAI,"cheaper",9000,"목동점","CONFIRMED")),searchResult(listing(ProcurementMall.HI_THEHYUNDAI,"near",9001,"목동점","CONFIRMED"))));
        var suggestions=compare(p).recommendations().stream().flatMap(g->g.listings().stream()).toList();assertThat(suggestions).hasSize(1);assertThat(suggestions.getFirst().recommendationSaving()).isEqualTo(1000);
        assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(10000);assertThat(current(p).selectedSupplier().inventoryState()).isEqualTo("AVAILABLE");
        choose(p,suggestions.getFirst());assertThat(compare(p).recommendations()).isEmpty();assertThat(preferred.get(actor).mallAllowed(ProcurementMall.HI_THEHYUNDAI)).isFalse();
        finish(p,List.of(searchResult(listing(ProcurementMall.HI_THEHYUNDAI,"cheaper",8500,"목동점","CONFIRMED"))));assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(8500);
    }
    @Test void preRecommendationJsonRemainsReadableWithoutRewritingHistory()throws Exception {
        var p=create("ABCD6F123BK");finish(p);
        var original=current(p).latestResult();
        jdbc.update("UPDATE procurement_product SET latest_result=JSON_REMOVE(latest_result,'$.recommendationLimited'),last_good_result=JSON_REMOVE(last_good_result,'$.recommendationLimited') WHERE product_id=?",p.id());
        jdbc.update("UPDATE product_refresh_entry SET result=JSON_REMOVE(result,'$.recommendationLimited'),selection_snapshot=NULL WHERE product_id=?",p.id());
        jdbc.update("UPDATE product_lookup_history SET payload=JSON_REMOVE(payload,'$.recommendationLimited') WHERE product_id=?",p.id());
        String before=jdbc.queryForObject("SELECT latest_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",String.class,p.id());
        String historyBefore=jdbc.queryForObject("SELECT payload FROM product_lookup_history WHERE product_id=?",String.class,p.id());
        String run=jdbc.queryForObject("SELECT CAST(run_id AS CHAR) FROM product_refresh_entry WHERE product_id=?",String.class,p.id());
        var browser=login(account(UserRole.PRODUCT));
        status(browser.get("/api/products"),200);
        var detail=browser.get("/api/products/"+p.id());status(detail,200);
        var data=json.readTree(detail.body()).path("data");
        assertThat(data.path("product").path("latestResult").path("recommendationLimited").asBoolean()).isFalse();
        assertThat(data.path("lastGoodResult").path("recommendationLimited").asBoolean()).isFalse();
        status(browser.get("/api/products/"+p.id()+"/history"),200);
        status(browser.get("/api/product-refresh/"+run),200);
        assertThat(current(p).latestResult()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT latest_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",String.class,p.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT payload FROM product_lookup_history WHERE product_id=?",String.class,p.id())).isEqualTo(historyBefore);
    }
    @Test void legacySmartstoreSelectionIsPreservedButCannotBeSelectedOrManuallyBypassed()throws Exception {
        var p=create("WINDOW42");finish(p,List.of(identified(ProcurementMall.NAVER_SMART_STORE,"42","롯데백화점","본점","NAVER_DEPARTMENT","10002/1")));
        var old=byMall(p,ProcurementMall.NAVER_SMART_STORE);choose(p,old);
        long history=jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier_change",Long.class);
        String raw=jdbc.queryForObject("SELECT observation FROM product_supplier WHERE id=?",String.class,old.id());
        var stored=(tools.jackson.databind.node.ObjectNode)json.readTree(raw);
        var offer=(tools.jackson.databind.node.ObjectNode)stored.path("offer");
        offer.remove("naverChannel");offer.put("url","https://smartstore.naver.com/lotte/products/42");
        jdbc.update("UPDATE product_supplier SET observation=?,url=? WHERE id=?",json.writeValueAsString(stored),offer.path("url").asText(),old.id());
        var c=compare(p);
        assertThat(c.groups()).isEmpty();assertThat(c.recommendations()).isEmpty();assertThat(c.selected().id()).isEqualTo(old.id());
        assertThat(c.selected().referencePrice()).isEqualTo(old.referencePrice());assertThat(c.selected().priceCheckedAt()).isEqualTo(old.priceCheckedAt());
        assertThat(c.selected().priceStatus()).isEqualTo("UNSUPPORTED_CHANNEL");assertThat(c.selected().selectable()).isFalse();assertThat(c.selected().current()).isFalse();
        // A new run must ignore the selected ordinary store and defer recommendations.
        start(p);var w=refresh.claim("channel");var calls=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new cc.ataglace.molebutter.procurement.internal.SupplierProductGateway(){
            public String validateUrl(ProcurementMall m,String url){return url;}
            public List<SourceOption> options(ProcurementMall m,String id,String url){calls.incrementAndGet();throw new AssertionError();}
        };
        var result=new SupplierLookupService(gateway,time).lookup(w,new SearchResult(List.of(json.treeToValue(offer,Offer.class)),true,null),()->true);
        refresh.finish("channel",w,result);refresh.claim("channel");
        assertThat(calls.get()).isZero();assertThat(compare(p).recommendationStatus().state()).isEqualTo("PRICE_UNCONFIRMED");
        assertThatThrownBy(()->choose(p,old)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(old.id()),new AssignmentInput(old.revision(),old.store().id()))).hasMessageContaining("쇼핑윈도");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier_change",Long.class)).isEqualTo(history);
        assertThat(json.readTree(jdbc.queryForObject("SELECT observation FROM product_supplier WHERE id=?",String.class,old.id()))).isEqualTo(stored);
        assertThatThrownBy(()->officialStores.preview(actor,"https://smartstore.naver.com/lotte/products/42")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"SELLER","임의 판매자","smartstore.naver.com/lotte"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void naverInventoryRefreshPersistsOptionsAndPreservesSelectionOnStockFailure()throws Exception {
        String id="12610379894",url="https://shopping.naver.com/window-products/brandfashion/"+id;
        try(var in=getClass().getResourceAsStream("/product/naver-inventory-"+id+".json")){brandGateway.payload=new String(in.readAllBytes());}
        var preview=officialStores.preview(actor,url);
        var official=officialStores.register(actor,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","헤지스",null,null,url,preview.channelUid(),preview.preferenceRevision()));
        preferred.saveMall(actor,ProcurementMall.NAVER_SMART_STORE,new MallPreferenceInput(preferred.get(actor).revision(),"STORES",List.of(official.id()),List.of(),true));
        var p=create("HIWA6E450BK");var offer=new Offer("NV","HIWA6E450BK","헤지스",id,url,94000L,0L,ProcurementMall.NAVER_SMART_STORE,null,new NaverChannel(NaverChannelType.WINDOW,"BRAND_FASHION"));
        var search=new SearchResult(List.of(offer),true,null);var lookup=new SupplierLookupService(brandGateway,time);
        start(p);var w=refresh.claim("naver-test");var result=lookup.lookup(w,search,()->true);
        refresh.finish("naver-test",w,result);refresh.claim("naver-test");
        var c=compare(p);assertThat(c.pending()).isEmpty();assertThat(c.groups()).hasSize(1);
        var listing=c.groups().getFirst().listings().getFirst();assertThat(listing.result().options()).containsExactly(new SourceOption("53129032179","FREE",3L,"AVAILABLE"));assertThat(listing.selectable()).isTrue();choose(p,listing);
        brandGateway.payload=brandGateway.payload.replace("\"stockQuantity\": 3","\"stockQuantity\": 0");
        start(p);w=refresh.claim("naver-test");result=lookup.lookup(w,search,()->true);refresh.finish("naver-test",w,result);refresh.claim("naver-test");
        assertThat(compare(p).selected().id()).isEqualTo(listing.id());assertThat(compare(p).selected().inventoryState()).isEqualTo("SOLD_OUT");
        var failed=new SupplierResult(offer,listing.result().match(),"FAILED",List.of(),"재고 조회 실패",null,null,null,listing.result().branch());
        finish(p,List.of(failed));assertThat(compare(p).selected().id()).isEqualTo(listing.id());assertThat(compare(p).selected().inventoryState()).isEqualTo("FAILED");assertThat(compare(p).selected().referencePrice()).isEqualTo(94000);
    }
    @Test void officialBrandRegistrationPreservesObservedStoreAndSelectionAndRequiresEvidence()throws Exception {
        String url="https://brand.naver.com/daks/products/13197489089";
        try(var in=getClass().getResourceAsStream("/product/naver-brand-store.json")){brandGateway.payload=new String(in.readAllBytes());}
        var preview=officialStores.preview(actor,url);
        assertThat(preferred.get(actor).stores()).isEmpty(); // 확인/취소는 저장하지 않는다.
        var p=create("WCBA5F052BK");var d=brandGateway.inspect(ProcurementMall.NAVER_SMART_STORE,preview.productId(),url);
        var offer=new Offer("NV1","WCBA5E052BK","닥스 DAKS",preview.productId(),url,94000L,0L,ProcurementMall.NAVER_SMART_STORE,null,new NaverChannel(NaverChannelType.WINDOW,"BRAND_FASHION"));
        var observation=new SupplierResult(offer,new CodeMatch("MATCHED","WCBA5E052BK","WCBA052","5E","BK","시즌 차이"),"DEFERRED",List.of(),null,d.title(),d.modelCode(),d.brand(),SupplierBranch.resolve(offer,d));
        finish(p,List.of(observation));var old=compare(p).groups().getFirst().listings().getFirst();choose(p,old);
        String originalStore=old.store().id(),originalListing=old.id();
        var before=preferred.get(actor);
        var input=new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","닥스 공식몰",null,null,url,preview.channelUid(),before.revision());
        var official=officialStores.register(actor,input);
        assertThat(official.id()).isEqualTo(originalStore);assertThat(official.kind()).isEqualTo("BRAND_STORE");
        assertThat(current(p).selectedSupplier().id()).isEqualTo(originalListing);
        assertThat(preferred.get(actor).rules()).isEqualTo(before.rules());
        assertThat(compare(p).groups().getFirst().listings().getFirst().selectable()).isTrue();
        assertThatThrownBy(()->officialStores.register(actor,input)).isInstanceOf(IllegalStateException.class);
        var again=officialStores.register(actor,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","다른 표시명",null,null,url,preview.channelUid(),preferred.get(actor).revision()));
        assertThat(again.id()).isEqualTo(originalStore);assertThat(again.name()).isEqualTo("닥스 공식몰");
        var branch=preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRANCH","천호점",null,"현대백화점"));
        preferred.saveMall(actor,ProcurementMall.NAVER_SMART_STORE,new MallPreferenceInput(preferred.get(actor).revision(),"STORES",List.of(official.id(),branch.id()),List.of(),true));
        assertThat(preferred.get(actor).groups().stream().filter(g->g.mall()==ProcurementMall.NAVER_SMART_STORE).findFirst().orElseThrow().storeIds()).containsExactlyInAnyOrder(official.id(),branch.id());
        preferred.saveStore(actor,Long.valueOf(official.id()),new StoreInput(official.revision(),ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","닥스 이름 변경",null));
        finish(p,List.of(observation));assertThat(current(p).selectedSupplier().store().id()).isEqualTo(originalStore);
        var staffUser=account(UserRole.PRODUCT);var forAssignment=compare(p).selected();supplierService.assign(staffUser.getId(),Long.valueOf(p.id()),Long.valueOf(originalListing),new AssignmentInput(forAssignment.revision(),official.id()));
        assertThat(compare(p).selected().manual()).isTrue();
        assertThat(current(p).selectedSupplier().inventoryState()).isEqualTo("UNCONFIRMED");assertThat(current(p).selectedSupplier().result().state()).isEqualTo("DEFERRED");
        var otherBranch=new BranchInfo("닥스 DAKS","CONFIRMED","META","닥스 DAKS",new StoreEvidence("SELLER",null,"닥스 DAKS","NAVER_CHANNEL","other-channel"));
        var other=new SupplierResult(offer,observation.match(),"DEFERRED",List.of(),null,d.title(),d.modelCode(),d.brand(),otherBranch);
        finish(p,List.of(other));var changed=compare(p).selected();
        assertThat(changed.selectable()).isFalse();assertThat(changed.id()).isEqualTo(originalListing);
        assertThatThrownBy(()->supplierService.assign(actor,Long.valueOf(p.id()),Long.valueOf(originalListing),new AssignmentInput(changed.revision(),official.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","우회 등록",null))).isInstanceOf(IllegalArgumentException.class);
        long rev=preferred.get(actor).revision();int size=preferred.get(actor).stores().size();
        brandGateway.payload=brandGateway.payload.replace("2sWE1WHVrXUFoJpa77gZb","new-channel");
        assertThatThrownBy(()->officialStores.register(actor,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","위조",null,null,url,preview.channelUid(),rev))).isInstanceOf(IllegalArgumentException.class);
        assertThat(preferred.get(actor).revision()).isEqualTo(rev);assertThat(preferred.get(actor).stores()).hasSize(size);
    }
    @Test void officialRegistrationRollsBackAndConcurrentRegistrationCreatesOnlyOneStore()throws Exception {
        String url="https://brand.naver.com/daks/products/13197489089";
        try(var in=getClass().getResourceAsStream("/product/naver-brand-store.json")){brandGateway.payload=new String(in.readAllBytes());}
        var preview=officialStores.preview(actor,url);
        var input=new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","닥스 DAKS",null,null,url,preview.channelUid(),preview.preferenceRevision());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{preferred.registerBrandStore(actor,input,preview);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(preferred.get(actor).stores()).isEmpty();assertThat(preferred.get(actor).revision()).isEqualTo(preview.preferenceRevision());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store_identity",Long.class)).isZero();
        try(var executor=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)tasks.add(executor.submit(()->{gate.await();try{officialStores.register(actor,input);return true;}catch(IllegalStateException expected){return false;}}));
            gate.countDown();int success=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))success++;
            assertThat(success).isEqualTo(1);
        }
        assertThat(preferred.get(actor).stores()).hasSize(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store_identity",Long.class)).isEqualTo(1);
    }
    @Test void officialRegistrationApiRejectsStaffMissingCsrfInvalidIdsAndStaleVersion()throws Exception {
        String url="https://brand.naver.com/daks/products/13197489089";
        try(var in=getClass().getResourceAsStream("/product/naver-brand-store.json")){brandGateway.payload=new String(in.readAllBytes());}
        var adminUser=users.findByEmail("admin@example.com").orElseThrow();
        var admin=login(adminUser);var staff=login(account(UserRole.PRODUCT));staff.csrf();
        status(admin.send("POST","/api/settings/supplier-stores/channel-preview",new ChannelRequest(url),null),403);
        status(staff.send("POST","/api/settings/supplier-stores/channel-preview",new ChannelRequest(url),staff.csrf),403);
        admin.csrf();var response=admin.send("POST","/api/settings/supplier-stores/channel-preview",new ChannelRequest(url),admin.csrf);status(response,200);
        var preview=json.readTree(response.body()).path("data");assertThat(preferred.get(actor).stores()).isEmpty();
        var input=new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE","닥스 DAKS",null,null,url,preview.path("channelUid").asText(),preview.path("preferenceRevision").asLong());
        status(staff.send("POST","/api/settings/supplier-stores",input,staff.csrf),403);
        status(admin.send("POST","/api/settings/supplier-stores",input,null),403);
        admin.csrf();status(admin.send("POST","/api/settings/supplier-stores",input,admin.csrf),200);
        assertThat(preferred.get(actor).stores()).hasSize(1);
        status(admin.post("/api/settings/supplier-stores",input),409);
        status(admin.post("/api/settings/supplier-stores/channel-preview",new ChannelRequest(url.replace("13197489089","999"))),400);
        status(admin.post("/api/settings/supplier-stores/channel-preview",new ChannelRequest("https://localhost/")),400);
        assertThat(preferred.get(actor).stores()).hasSize(1);
    }
    @Test void lotteCompanyPreferenceIsAtomicReusedAndRestricted()throws Exception {
        var draft=new StoreInput(null,ProcurementMall.LOTTE_ON,"COMPANY","주식회사 LF",null,null);
        long revision=preferred.get(actor).revision();
        assertThatThrownBy(()->preferred.saveMall(actor,ProcurementMall.LOTTE_ON,new MallPreferenceInput(revision,"STORES",List.of(),List.of(draft,new StoreInput(null,ProcurementMall.LOTTE_ON,"COMPANY","다른 업체",null,null)),true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(preferred.get(actor).revision()).isEqualTo(revision);assertThat(preferred.get(actor).stores()).noneMatch(st->st.kind().equals("COMPANY"));
        var saved=preferred.saveMall(actor,ProcurementMall.LOTTE_ON,new MallPreferenceInput(revision,"STORES",List.of(),List.of(draft,draft),true));
        var lf=saved.stores().stream().filter(st->st.kind().equals("COMPANY")).findFirst().orElseThrow();
        assertThat(lf.identityKey()).isEqualTo("company:lf");assertThat(saved.groups().stream().filter(g->g.mall()==ProcurementMall.LOTTE_ON).findFirst().orElseThrow().storeIds()).containsExactly(lf.id());
        assertThatThrownBy(()->preferred.saveMall(actor,ProcurementMall.LOTTE_ON,new MallPreferenceInput(revision,"ALL",List.of(),List.of(),true))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->preferred.saveStore(actor,Long.valueOf(lf.id()),new StoreInput(lf.revision(),ProcurementMall.LOTTE_ON,"COMPANY","주식회사 LF",null,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.NAVER_SMART_STORE,"COMPANY","주식회사 LF",null,null))).isInstanceOf(IllegalArgumentException.class);
        var next=preferred.saveMall(actor,ProcurementMall.LOTTE_ON,new MallPreferenceInput(saved.revision(),"STORES",List.of(),List.of(draft),true));assertThat(next.stores()).filteredOn(st->st.kind().equals("COMPANY")).hasSize(1);
        var adminUser=users.findByEmail("admin@example.com").orElseThrow();
        var admin=login(adminUser);var staff=login(account(UserRole.PRODUCT));{
            var input=new MallPreferenceInput(next.revision(),"STORES",List.of(lf.id()),List.of(),true);
            status(admin.send("PUT","/api/settings/preferred-suppliers/LOTTE_ON",input,null),403);
            staff.csrf();status(staff.send("PUT","/api/settings/preferred-suppliers/LOTTE_ON",input,staff.csrf),403);
            var view=json.readTree(admin.get("/api/settings/preferred-suppliers").body()).path("data");assertThat(view.path("companyChoices").size()).isEqualTo(1);
        }
    }
    @Test void lotteCompanyGroupingSelectionAndContradictoryManualAssignment()throws Exception {
        var draft=new StoreInput(null,ProcurementMall.LOTTE_ON,"COMPANY","주식회사 LF",null,null);
        preferred.saveMall(actor,ProcurementMall.LOTTE_ON,new MallPreferenceInput(preferred.get(actor).revision(),"STORES",List.of(),List.of(draft),true));
        var p=create("WCBA5F052BK");String raw;try(var in=getClass().getResourceAsStream("/product/lotte-lf.json")){raw=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var parser=new cc.ataglace.molebutter.procurement.internal.MallOptionParser(json);var d=parser.details(ProcurementMall.LOTTE_ON,raw,"LO2630710241_2630710242");
        var observations=new ArrayList<SupplierResult>();
        for(int i=0;i<2;i++){
            var offer=new Offer("NV"+i,"WCBA5E052BK",i==0?"롯데ON":"롯데백화점","LO2630710241_2630710242","https://www.lotteon.com/p/product/LO2630710241?sitmNo=LO2630710241_2630710242",134000L+i*6000,0L,ProcurementMall.LOTTE_ON,null);
            observations.add(new SupplierResult(offer,new CodeMatch("MATCHED","WCBA5E052BK","WCBA052","5E","BK","시즌 차이"),"CONFIRMED",d.options(),null,d.title(),d.modelCode(),d.brand(),SupplierBranch.resolve(offer,d)));
        }
        finish(p,observations);var c=compare(p);assertThat(c.pending()).isEmpty();assertThat(c.groups()).hasSize(1);assertThat(c.groups().getFirst().listings()).hasSize(2);
        var chosen=c.groups().getFirst().listings().getLast();assertThat(chosen.selectable()).isTrue();
        supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(current(p).revision(),chosen.id()));
        finish(p,observations);assertThat(compare(p).selected().id()).isEqualTo(chosen.id());assertThat(compare(p).selected().referencePrice()).isEqualTo(140000L);
        var changed=parser.details(ProcurementMall.LOTTE_ON,raw.replace("주식회사 LF","다른 회사").replace("LO10004813","OTHER"),"LO2630710241_2630710242");var o=observations.getFirst().offer();
        finish(p,List.of(new SupplierResult(o,observations.getFirst().match(),"CONFIRMED",changed.options(),null,changed.title(),changed.modelCode(),changed.brand(),SupplierBranch.resolve(o,changed))));
        var other=storedListings(p).stream().filter(l->!l.preferred()).findFirst().orElseThrow();var company=preferred.get(actor).stores().stream().filter(st->st.kind().equals("COMPANY")).findFirst().orElseThrow();
        assertThatThrownBy(()->supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(other.id()),new AssignmentInput(other.revision(),company.id()))).hasMessageContaining("LF로 지정");
        assertThat(compare(p).selected().id()).isEqualTo(chosen.id());
    }
    @Test void deprecatedFieldsNeverOverwriteExplicitQuery(){
        var p=create("ABCD6F123BK");assertThat(p.searchQuery()).isEqualTo("ABCD123");assertThat(p.searchQuery()).isEqualTo("ABCD123");
        settings.saveBrand(actor,Long.valueOf(brand),new NamedSettingInput("이름 변경",0L));assertThat(current(p).brandKey()).isEqualTo("HAZZYS");assertThat(current(p).lookupRevision()).isEqualTo(p.lookupRevision());
        p=current(p);var edited=products.edit(actor,Long.parseLong(p.id()),new ProductEditRequest(p.revision(),null,p.productCode(),p.searchQuery(),brand,"AUTO","GENERAL"));assertThat(edited.comparisonCode()).isEmpty();assertThat(edited.searchQuery()).isEqualTo("ABCD123");
        products.bulk(actor,new BulkEdit(List.of(version(edited)),null,null,brand));assertThat(current(edited).codeType()).isEqualTo("GENERAL");assertThat(current(edited).comparisonCode()).isEmpty();
    }
    @Test void registrationReadsOnlyCodesGroupsRowsAndRetainsManualSettings(){
        var bytes=WorkbookFixture.create(5,Map.of("F4","ABCD6F123BK","F5","ABCD6F123BK","F6","ABCD6E123BK","F7","ABCD6F124Y2","H4","헤지스 가방","H5","헤지스 가방","H6","헤지스 가방","P4","100","C4",""));
        var r=products.upload(actor,"sample.xlsx",bytes);assertThat(r.created()).isEqualTo(3);assertThat(r.duplicates()).isEqualTo(1);assertThat(r.excluded()).isEqualTo(1);assertThat(r.brandsAssigned()).isEqualTo(2);
        var p=products.list(actor,"ABCD6F123BK","ALL","",0).items().getFirst();assertThat(p.searchQuery()).isEqualTo("ABCD123");
        products.edit(actor,Long.parseLong(p.id()),new ProductEditRequest(p.revision(),null,p.productCode(),"직접 검색어",brand,"MANUAL","LF_ACCESSORY"));
        var again=products.upload(actor,"again.xlsx",bytes);assertThat(again.created()).isZero();assertThat(again.existing()).isEqualTo(3);assertThat(current(p).searchQuery()).isEqualTo("직접 검색어");
        assertThat(products.list(actor,"","ALL","",0).totalElements()).isEqualTo(3);
    }
    @Test void brandInferenceKeepsAmbiguousNamesUnassigned(){var bytes=WorkbookFixture.create(2,Map.of("F4","ABCD6F123BK","F5","ABCD6F123BK","H4","헤지스 가방","H5","닥스 가방"));settings.saveBrand(actor,null,new NamedSettingInput("닥스",null));products.upload(actor,"x",bytes);var p=products.list(actor,"","ALL","",0).items().getFirst();assertThat(p.brandId()).isNull();assertThat(p.searchQuery()).isEqualTo(p.productCode());}
    @Test void refreshDoesNotRequireLinksAndKeepsRawOptions(){var p=create("ABCD6F123BK");finish(p);var current=current(p);assertThat(current.latestResult().searchPrice()).isEqualTo(10000);assertThat(current.latestResult().suppliers().getFirst().options().getFirst().stock()).isEqualTo(2);assertThat(products.history(actor,Long.parseLong(p.id()),0).totalElements()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier",Long.class)).isEqualTo(1);}
    @Test void progressEndpointTracksItemCompletionBeforeWholeRunAndRejectsStaleCriteria()throws Exception {
        var p=create("ABCD6F123BK");long id=Long.parseLong(p.id());
        var staff=login(account(UserRole.PRODUCT));String path="/api/products/"+id+"/refresh-status";
        status(staff.get(path),200);assertThat(refresh.status(actor,id).status()).isEqualTo("NOT_CHECKED");
        var other=create("ABCD6F124BK");long run=Long.parseLong(refresh.start(actor,new RefreshInput("SELECTED",List.of(p.id(),other.id()))));
        assertThat(refresh.status(actor,id).status()).isEqualTo("PENDING");
        var w=refresh.claim("w");assertThat(refresh.status(actor,id).status()).isEqualTo("CHECKING");
        refresh.blocked("w",w,"접속 제한");assertThat(refresh.status(actor,id).runStatus()).isEqualTo("BLOCKED");
        refresh.control(actor,run,"resume");refresh.control(actor,run,"pause");assertThat(refresh.status(actor,id).runStatus()).isEqualTo("PAUSED");
        refresh.control(actor,run,"resume");w=refresh.claim("w");refresh.finish("w",w,result());
        var progress=refresh.status(actor,id);assertThat(progress.status()).isEqualTo("SUCCESS");assertThat(progress.runStatus()).isEqualTo("RUNNING");
        assertThat(json.readTree(staff.get(path).body()).path("data").path("productId").asText()).isEqualTo(p.id());
        assertThat(jdbc.queryForObject("SELECT latest_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",String.class,id)).doesNotContain("itemPrice","additionalPrice","total");
        products.edit(actor,id,new ProductEditRequest(current(p).revision(),null,"ABCD6F125BK",p.searchQuery(),brand,"AUTO",null));assertThat(refresh.status(actor,id).status()).isEqualTo("NOT_CHECKED");
        status(staff.get("/api/products/1/refresh-status"),404);
        status(new Browser().get(path),401);status(login(account(UserRole.VIEWER)).get(path),403);
    }
    @Test void managementAndBrandRenameDoNotInvalidatePricesAndSelectedRefreshIsAllowed(){var p=create("ABCD6F123BK");finish(p);products.bulk(actor,new BulkEdit(List.of(version(p)),null,false,null));settings.saveBrand(actor,Long.valueOf(brand),new NamedSettingInput("새 이름",0L));assertThat(current(p).latestResult()).isNotNull();assertThat(current(p).lookupRevision()).isEqualTo(p.lookupRevision());assertThatThrownBy(()->refresh.start(actor,new RefreshInput("ALL_MANAGED",List.of()))).hasMessageContaining("최신화할 상품");assertThat(start(current(p))).isNotBlank();}
    @Test void staleIdentityRejectsOldResultWithoutOverwritingCurrentState(){var p=create("ABCD6F123BK");start(p);var w=refresh.claim("w");products.edit(actor,Long.parseLong(p.id()),new ProductEditRequest(p.revision(),null,"ABCD6F124Y2",p.searchQuery(),brand,"AUTO",null));refresh.finish("w",w,result());assertThat(current(p).latestStatus()).isEqualTo("NOT_CHECKED");assertThat(current(p).latestResult()).isNull();assertThat(jdbc.queryForObject("SELECT status FROM product_refresh_entry",String.class)).isEqualTo("STALE");}
    @Test void sameSearchIsCachedByRunAndLeaseTakeoverFencesOldWorker(){var p=create("ABCD6F123BK");var p2=create("ABCD6E123BK");refresh.start(actor,new RefreshInput("SELECTED",List.of(p.id(),p2.id())));var w=refresh.claim("old");assertThat(refresh.claim("new")).isNull();refresh.cache(w,new SearchResult(List.of(),true,null));time.value=time.value.plusMinutes(11);var replacement=refresh.claim("new");assertThat(replacement).isEqualTo(w);refresh.finish("old",w,result());assertThat(current(p).latestResult()).isNull();assertThat(refresh.cached(replacement)).isNotNull();refresh.finish("new",replacement,result());var next=refresh.claim("new");assertThat(next.query()).isEqualTo(w.query());assertThat(refresh.cached(next)).isNotNull();}
    @Test void pauseBlockResumeCancelAndRetryPreserveHistory(){var p=create("ABCD6F123BK");long id=Long.parseLong(start(p));var w=refresh.claim("w");refresh.blocked("w",w,"접속 제한");assertThat(refresh.claim("w")).isNull();refresh.control(actor,id,"resume");w=refresh.claim("w");refresh.control(actor,id,"pause");refresh.finish("w",w,result());assertThat(refresh.claim("w")).isNull();refresh.control(actor,id,"resume");assertThat(refresh.claim("w")).isNull();long next=Long.parseLong(start(p));refresh.control(actor,next,"cancel");assertThat(current(p).latestResult()).isNull();assertThat(products.detail(actor,Long.parseLong(p.id())).get("lastGoodResult")).isNotNull();assertThat(refresh.retry(actor,next)).isNotBlank();}
    @Test void concurrentStartsAndStaleWritesAreRejected()throws Exception {var p=create("ABCD6F123BK");try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{gate.await();try{start(p);return true;}catch(IllegalStateException e){return false;}}));gate.countDown();assertThat(tasks.get(0).get()^tasks.get(1).get()).isTrue();}products.bulk(actor,new BulkEdit(List.of(version(p)),null,false,null));assertThatThrownBy(()->products.bulk(actor,new BulkEdit(List.of(new VersionedId(p.id(),p.revision())),null,true,null))).isInstanceOf(IllegalStateException.class);}
    @Test void deletesHideProductsButPreserveLookupHistory(){var p=create("ABCD6F123BK");long run=Long.parseLong(start(p));assertThatThrownBy(()->products.delete(actor,new DeleteProducts(List.of(version(p))))).hasMessageContaining("완료하거나 취소");refresh.control(actor,run,"cancel");finish(p);products.delete(actor,new DeleteProducts(List.of(version(p))));assertThat(products.list(actor,"","ALL","",0).items()).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history",Long.class)).isEqualTo(1);}
    @Test void mergingLegacyDuplicatesPreservesSourcesAndHistory(){var p=create("ABCD6F123BK");finish(p);long copy=Long.parseLong(p.id())+100; jdbc.update("INSERT INTO catalog_product(id,product_code,created_at,updated_at) VALUES(?,?,NOW(6),NOW(6))",copy,p.productCode());jdbc.update("INSERT INTO procurement_product(product_id,search_query) VALUES(?,?)",copy,p.searchQuery());jdbc.update("INSERT INTO product_supplier(id,product_id,mall,mall_product_id,naver_product_id,url) VALUES(1,?,'LFMALL','P2','NV2','https://www.lfmall.co.kr/app/product/P2')",copy);p=current(p);var merged=products.merge(actor,Long.parseLong(p.id()),new MergeInput(List.of(version(p),new VersionedId(Long.toString(copy),0L)),null,p.productCode(),p.searchQuery(),true,brand,"AUTO","LF_ACCESSORY"));assertThat(products.list(actor,"","ALL","",0).totalElements()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier WHERE product_id=?",Long.class,merged.id())).isEqualTo(2);assertThat(products.history(actor,Long.parseLong(p.id()),0).totalElements()).isEqualTo(1);}
    @Test void paginationAndBrandDeletionRemainValid(){for(int i=0;i<105;i++)create("PAGE-"+i);assertThat(products.list(actor,"PAGE","ALL","",0).items()).hasSize(20);assertThat(products.list(actor,"PAGE","ALL","",1,100).items()).hasSize(5);assertThatThrownBy(()->products.list(actor,"","ALL","",0,5000)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->settings.deleteBrand(actor,Long.parseLong(brand),0L)).hasMessageContaining("사용");}
    @Test void listSendsPriceSummaryWhileDetailKeepsAllOptions(){var p=create("ABCD6F123BK");finish(p);var listed=products.list(actor,"","ALL","",0).items().getFirst();assertThat(listed.latestResult().searchPrice()).isEqualTo(10000);assertThat(listed.latestResult().suppliers()).isEmpty();assertThat(current(p).latestResult().suppliers()).hasSize(1);}
    @Test void cancellationReleasesWorkerAndRetryExcludesDeletedProducts(){var p=create("ABCD6F123BK");var p2=create("ABCD6F124BK");long run=Long.parseLong(refresh.start(actor,new RefreshInput("SELECTED",List.of(p.id(),p2.id()))));var w=refresh.claim("w");refresh.control(actor,run,"cancel");assertThat(refresh.heartbeat("w",w)).isFalse();products.delete(actor,new DeleteProducts(List.of(version(p))));long next=Long.parseLong(refresh.retry(actor,run));assertThat(refresh.items(actor,next,0).items()).hasSize(1);assertThat(refresh.claim("next").productId()).isEqualTo(Long.parseLong(p2.id()));}
    @Test void httpPermissionsCsrfUploadAndRemovedRoutes()throws Exception {
        status(new Browser().get("/api/products"),401);var viewer=login(account(UserRole.VIEWER));status(viewer.get("/api/products"),403);status(viewer.get("/products"),403);
        var user=account(UserRole.PRODUCT);var staff=login(user);status(staff.get("/api/products"),200);status(staff.get("/api/settings/brands"),200);status(staff.get("/products"),200);status(staff.get("/settings"),200);
        status(staff.post("/api/settings/brands",Map.of("name","권한 없음")),403);
        status(staff.send("POST","/api/products",Map.of("productCode","X"),null),403);
        status(staff.upload(WorkbookFixture.create(1,Map.of("F4","ABCD6F123BK","H4","헤지스 가방")),true),200);
        status(staff.post("/api/products/code-preview",Map.of("brandId",brand,"productCode","ABCD6F123BK","searchMode","AUTO")),200);
        assertThat(staff.get("/api/product-pricing/imports").statusCode()).isBetween(400,499);
        assertThat(staff.get("/api/settings/sales-channels").statusCode()).isBetween(400,499);
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED',auth_version=auth_version+1 WHERE id=?",user.getId());status(staff.get("/api/products"),401);
    }

    SupplierResult listing(ProcurementMall mall,String id,long price,String branch,String state){return new SupplierResult(new Offer("NV"+id,"헤지스 ABCD6E123BK",mall.getDisplayName(),id,"https://www.lfmall.co.kr/app/product/"+id,price,0L,mall,null,mall==ProcurementMall.NAVER_SMART_STORE?new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"):null),new CodeMatch("MATCHED","ABCD6E123BK","ABCD123","6E","BK","시즌 차이"),state,state.equals("FAILED")?List.of():List.of(new SourceOption("ONE","FREE",state.equals("SOLD_OUT")?0L:2L,state.equals("SOLD_OUT")?"SOLD_OUT":"AVAILABLE")),null,null,null,null,new BranchInfo(branch,branch.isBlank()?"UNKNOWN":"CONFIRMED","SEARCH_TITLE",branch));}
    void finish(ProcurementProductView p,List<SupplierResult> results){start(p);var w=refresh.claim("w");refresh.finish("w",w,SupplierLookupService.summarize(results,true,time.now()));refresh.claim("w");}
    Comparison compare(ProcurementProductView p){var c=supplierService.comparison(actor,Long.parseLong(p.id()));assertThat(c.pending()).isEmpty();assertThat(c.excluded()).isEmpty();return c;}
    // Hidden historical records remain inspectable in storage, not in the comparison API.
    List<Listing> storedListings(ProcurementProductView p){return jdbc.queryForList("SELECT id FROM product_supplier WHERE product_id=? AND merged_into IS NULL ORDER BY id",Long.class,p.id()).stream().map(id->(Listing)org.springframework.test.util.ReflectionTestUtils.invokeMethod((Object)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(supplierService),"listing",Long.parseLong(p.id()),id)).toList();}
    List<Listing> hiddenReview(ProcurementProductView p){return storedListings(p).stream().filter(Listing::requiresReview).toList();}
    Listing byMall(ProcurementProductView p,ProcurementMall mall){var c=compare(p);return java.util.stream.Stream.concat(c.groups().stream().flatMap(g->g.listings().stream()),c.pending().stream()).filter(l->l.mall()==mall).findFirst().orElseThrow();}
    void choose(ProcurementProductView p,Listing l){supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(current(p).revision(),l.id()));}
    @Test void recommendationsAreNonPreferredExactThresholdAndOneOffSelection()throws Exception {
        preferred.deleteMall(actor,ProcurementMall.HI_THEHYUNDAI,preferred.get(actor).revision());
        var p=create("ABCD6F123BK");var base=listing(ProcurementMall.LFMALL,"base",94000,"","CONFIRMED");
        var cheap=listing(ProcurementMall.HI_THEHYUNDAI,"cheap",93000,"대구점","CONFIRMED");
        var tooClose=listing(ProcurementMall.HI_THEHYUNDAI,"close",93001,"대구점","CONFIRMED");
        var preferredCheap=listing(ProcurementMall.HAZZYS,"preferred",80000,"","CONFIRMED");
        var unknown=listing(ProcurementMall.HI_THEHYUNDAI,"unknown",50000,"","CONFIRMED");
        var sold=listing(ProcurementMall.HI_THEHYUNDAI,"sold",50000,"대구점","CONFIRMED");sold=new SupplierResult(sold.offer(),sold.match(),"CONFIRMED",List.of(new SourceOption("FREE","FREE",0L,"SOLD_OUT")),null,null,null,null,sold.branch());
        var partial=new SupplierResult(listing(ProcurementMall.HI_THEHYUNDAI,"partial",92000,"대구점","CONFIRMED").offer(),sold.match(),"OPTIONS_PARTIAL",sold.options(),null,null,null,null,sold.branch());
        var rows=List.of(base,cheap,tooClose,preferredCheap,unknown,sold,partial);
        finish(p,rows);assertThat(compare(p).recommendationStatus().state()).isEqualTo("NO_SELECTION");assertThat(compare(p).recommendations()).isEmpty();
        choose(p,byMall(p,ProcurementMall.LFMALL));finish(p,rows);
        var c=compare(p);var recs=c.recommendations().stream().flatMap(g->g.listings().stream()).toList();
        assertThat(recs).extracting(l->l.result().offer().mallProductId()).containsExactly("partial","cheap");assertThat(recs).allMatch(Listing::selectable);assertThat(recs.getLast().recommendationSaving()).isEqualTo(1000);
        assertThat(c.groups()).flatExtracting(Group::listings).noneMatch(l->!l.preferred());
        var snapshot=preferred.get(actor);var staff=login(account(UserRole.PRODUCT));String route="/api/products/"+p.id()+"/selection";
        var input=new SelectionInput(current(p).revision(),recs.getLast().id());status(staff.send("POST",route,input,null),403);status(staff.post(route,input),200);
        assertThat(current(p).selectedSupplier().id()).isEqualTo(recs.getLast().id());assertThat(preferred.get(actor)).isEqualTo(snapshot);assertThat(compare(p).recommendations()).isEmpty();assertThat(compare(p).recommendationStatus().state()).isEqualTo("SELECTION_CHANGED");
        status(staff.post(route,input),409);
        finish(p,rows);assertThat(compare(p).selected().referencePrice()).isEqualTo(93000);assertThat(compare(p).recommendations()).flatExtracting(Group::listings).extracting(Listing::id).doesNotContain(recs.getLast().id());
        finish(p,List.of(base,preferredCheap));assertThat(compare(p).recommendationStatus().state()).isEqualTo("PRICE_UNCONFIRMED");assertThat(compare(p).selected().referencePrice()).isEqualTo(93000);assertThat(compare(p).recommendations()).isEmpty();
    }
    @Test void snapshotPreservesChoiceAndPreferencesAcrossPauseAndConcurrentChanges(){
        preferred.deleteMall(actor,ProcurementMall.HI_THEHYUNDAI,preferred.get(actor).revision());var p=create("ABCD6F123BK");
        var base=listing(ProcurementMall.LFMALL,"base",94000,"","CONFIRMED");var other=listing(ProcurementMall.LFMALL,"other",95000,"","CONFIRMED");var cheap=listing(ProcurementMall.HI_THEHYUNDAI,"cheap",92000,"대구점","FAILED");
        finish(p,List.of(base,other));var choices=compare(p).groups().getFirst().listings();choose(p,choices.getFirst());
        long run=Long.parseLong(start(p));refresh.control(actor,run,"pause");refresh.control(actor,run,"resume");var work=refresh.claim("snapshot");
        assertThat(work.selectionBasis().supplierId()).isEqualTo(choices.getFirst().id());assertThat(work.preferences().mallAllowed(ProcurementMall.HI_THEHYUNDAI)).isFalse();
        choose(p,choices.getLast());refresh.finish("snapshot",work,SupplierLookupService.summarize(List.of(base,other,cheap),true,time.now()));refresh.claim("snapshot");
        assertThat(compare(p).selected().id()).isEqualTo(choices.getLast().id());assertThat(compare(p).recommendationStatus().state()).isEqualTo("SELECTION_CHANGED");assertThat(compare(p).recommendations()).isEmpty();
        finish(p,List.of(base,other,cheap));assertThat(compare(p).recommendations()).hasSize(1);var candidate=compare(p).recommendations().getFirst().listings().getFirst();assertThat(candidate.inventoryState()).isEqualTo("FAILED");
        preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(preferred.get(actor).revision(),"ALL",List.of(),List.of(),true));
        assertThat(compare(p).recommendations()).isEmpty();assertThat(compare(p).groups()).flatExtracting(Group::listings).extracting(Listing::id).contains(candidate.id());
        jdbc.update("UPDATE product_refresh_entry SET selection_snapshot=NULL WHERE product_id=?",p.id());assertThat(compare(p).recommendationStatus().state()).isEqualTo("REFRESH_REQUIRED");assertThat(compare(p).recommendations()).isEmpty();
    }
    @Test void scheduleSettingsSaveThroughHttpPersistsAndRetainsValidation() throws Exception {
        String route="/api/product-refresh/settings";
        var admin=login(users.findById(actor).orElseThrow());
        var disabled=new ScheduleSettings(0,false,"19:30");
        status(admin.post(route,disabled),200);
        assertThat(products.schedule(actor)).isEqualTo(new ScheduleSettings(1,false,"19:30"));
        assertThat(jdbc.queryForObject("SELECT schedule_enabled FROM procurement_settings WHERE id=1",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT schedule_time FROM procurement_settings WHERE id=1",String.class)).isEqualTo("19:30");

        status(admin.post(route,disabled),409);
        status(admin.post(route,new ScheduleSettings(1,true,"25:00")),400);
        status(login(account(UserRole.PRODUCT)).post(route,new ScheduleSettings(1,true,"08:15")),403);
        assertThat(products.schedule(actor)).isEqualTo(new ScheduleSettings(1,false,"19:30"));

        status(admin.post(route,new ScheduleSettings(1,true,"08:15")),200);
        assertThat(products.schedule(actor)).isEqualTo(new ScheduleSettings(2,true,"08:15"));
        assertThat(jdbc.queryForObject("SELECT schedule_enabled FROM procurement_settings WHERE id=1",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT schedule_time FROM procurement_settings WHERE id=1",String.class)).isEqualTo("08:15");
    }
    @Test void noPreferencesBlocksManualAndScheduledWork(){jdbc.update("DELETE FROM supplier_preference");var p=create("ABCD6F123BK");assertThatThrownBy(()->start(p)).hasMessageContaining("선호 매입처");refresh.schedule();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run",Long.class)).isZero();}
    @Test void selectionUsesSpecificListingNotLowestAndPreservesSoldOutChoice(){var p=create("ABCD6F123BK");var lf=listing(ProcurementMall.LFMALL,"lf",94000,"온라인점","CONFIRMED");var hi=listing(ProcurementMall.HI_THEHYUNDAI,"hi",93450,"목동점","CONFIRMED");finish(p,List.of(lf,hi));assertThat(current(p).selectedSupplier()).isNull();choose(p,byMall(p,ProcurementMall.LFMALL));assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(94000);assertThat(compare(p).groups().getFirst().mall()).isEqualTo(ProcurementMall.HI_THEHYUNDAI);String selected=current(p).selectedSupplier().id();
        finish(p,List.of(listing(ProcurementMall.LFMALL,"lf",95000,"온라인점","SOLD_OUT"),listing(ProcurementMall.HI_THEHYUNDAI,"hi",100,"목동점","CONFIRMED")));assertThat(current(p).selectedSupplier().id()).isEqualTo(selected);assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(95000);assertThat(current(p).selectedSupplier().inventoryState()).isEqualTo("SOLD_OUT");assertThat(current(p).selectedSupplier().url()).endsWith("lf");}
    List<Long> shownPrices(Comparison c){return c.groups().stream().flatMap(g->g.listings().stream()).map(l->l.result().offer().price()).toList();}
    @Test void groupsAndListingsFollowPriceRegardlessOfSelectionOrPendingRefresh(){
        var p=create("ABCD6F123BK");
        finish(p,List.of(listing(ProcurementMall.LFMALL,"lf",300,"온라인점","CONFIRMED"),listing(ProcurementMall.HAZZYS,"hz",100,"온라인점","CONFIRMED"),listing(ProcurementMall.HI_THEHYUNDAI,"hi-b",250,"목동점","CONFIRMED"),listing(ProcurementMall.HI_THEHYUNDAI,"hi-a",200,"목동점","CONFIRMED")));
        choose(p,byMall(p,ProcurementMall.LFMALL));
        assertThat(shownPrices(compare(p))).containsExactly(100L,200L,250L,300L);
        // 새 최신화가 대기 중이면 이번 조회 가격(minPrice)이 비어도 마지막 확인 가격으로 정렬한다.
        start(p);var pending=compare(p);
        assertThat(pending.groups()).allSatisfy(g->assertThat(g.minPrice()).isNull());
        assertThat(shownPrices(pending)).containsExactly(100L,200L,250L,300L);
    }
    SupplierResult pictured(SupplierResult s,String image){var o=s.offer();return new SupplierResult(new Offer(o.naverProductId(),o.title(),o.mallName(),o.mallProductId(),o.url(),o.price(),o.deliveryFee(),o.mall(),image,o.naverChannel(),o.searchStore()),s.match(),s.state(),s.options(),s.message(),s.sourceTitle(),s.sourceModelCode(),s.sourceBrand(),s.branch(),s.stockEvidence());}
    String listedImage(ProcurementProductView p){return products.list(actor,"","ALL","",0).items().stream().filter(v->v.id().equals(p.id())).findFirst().orElseThrow().imageUrl();}
    @Test void representativeImageFollowsSelectedListingOtherwiseFirstSearchResult(){
        var p=create("ABCD6F123BK");String a="https://img.test/a.jpg",b="https://img.test/b.jpg";
        var results=List.of(pictured(listing(ProcurementMall.LFMALL,"a",100,"온라인점","CONFIRMED"),a),pictured(listing(ProcurementMall.LFMALL,"b",200,"온라인점","CONFIRMED"),b));
        // 최신화는 마지막이 아니라 첫 판매글(검색 1순위) 사진을 대표로 저장한다.
        finish(p,results);assertThat(current(p).imageUrl()).isEqualTo(a);assertThat(listedImage(p)).isEqualTo(a);
        var second=compare(p).groups().getFirst().listings().stream().filter(l->b.equals(l.imageUrl())).findFirst().orElseThrow();
        choose(p,second);assertThat(current(p).imageUrl()).isEqualTo(b);assertThat(listedImage(p)).isEqualTo(b);
        // 선정 중에는 다음 최신화도 대표 사진을 덮어쓰지 못한다.
        finish(p,results);assertThat(current(p).imageUrl()).isEqualTo(b);
        supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(current(p).revision(),null));
        assertThat(current(p).imageUrl()).isEqualTo(a);assertThat(listedImage(p)).isEqualTo(a);
    }
    @Test void groupsRetainEveryListingAndNeverSumInventory(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"목동점","CONFIRMED"),listing(ProcurementMall.HI_THEHYUNDAI,"two",100,"목동점","CONFIRMED"),listing(ProcurementMall.HI_THEHYUNDAI,"three",200,"목동점","CONFIRMED")));var group=compare(p).groups().getFirst();assertThat(group.listings()).hasSize(3);assertThat(group.minPrice()).isEqualTo(100);assertThat(group.maxPrice()).isEqualTo(200);assertThat(group.listings()).allSatisfy(l->assertThat(l.result().options().getFirst().stock()).isEqualTo(2));}
    @Test void unknownStoresStaySeparateAndManualAssignmentSurvivesConflictingRefresh(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"","CONFIRMED"),listing(ProcurementMall.HI_THEHYUNDAI,"two",200,"","CONFIRMED")));assertThat(hiddenReview(p)).hasSize(2);assertThat(compare(p).groups()).isEmpty();var first=hiddenReview(p).getFirst();assertThatThrownBy(()->choose(p,first)).hasMessageContaining("선호 매입처 또는 현재 유효한 추천");var branch=preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","목동점",null));supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(first.id()),new AssignmentInput(first.revision(),branch.id()));choose(p,compare(p).groups().getFirst().listings().getFirst());
        finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",300,"천호점","CONFIRMED")));var pinned=current(p).selectedSupplier();assertThat(pinned.store().id()).isEqualTo(branch.id());assertThat(pinned.manual()).isTrue();assertThat(pinned.conflict()).isTrue();assertThat(hiddenReview(p)).hasSize(1);assertThatThrownBy(()->supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(first.id()),new AssignmentInput(first.revision(),null))).isInstanceOf(IllegalStateException.class);}
    @Test void stockFailureDoesNotInvalidateFreshPriceAndMissingSearchRetainsReferenceOnly(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"one",94000,"온라인점","CONFIRMED")));choose(p,byMall(p,ProcurementMall.LFMALL));time.value=time.value.plusHours(1);finish(p,List.of(listing(ProcurementMall.LFMALL,"one",95000,"온라인점","FAILED")));var chosen=current(p).selectedSupplier();assertThat(chosen.referencePrice()).isEqualTo(95000);assertThat(chosen.priceStatus()).isEqualTo("CONFIRMED");assertThat(chosen.inventoryState()).isEqualTo("FAILED");var checked=chosen.priceCheckedAt();time.value=time.value.plusHours(1);finish(p,List.of());chosen=current(p).selectedSupplier();assertThat(chosen.priceStatus()).isEqualTo("MISSING");assertThat(chosen.referencePrice()).isEqualTo(95000);assertThat(chosen.priceCheckedAt()).isEqualTo(checked);assertThat(chosen.current()).isFalse();}
    @Test void preferenceSnapshotIsStableAndExcludedSelectionDoesNotDisappear(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"one",94000,"온라인점","CONFIRMED")));choose(p,byMall(p,ProcurementMall.LFMALL));start(p);var w=refresh.claim("w");var rule=preferred.get(actor).rules().stream().filter(r->r.mall()==ProcurementMall.LFMALL).findFirst().orElseThrow();preferred.deleteRule(actor,Long.parseLong(rule.id()),rule.revision());assertThat(w.preferences().mallAllowed(ProcurementMall.LFMALL)).isTrue();assertThat(current(p).selectedSupplier().preferred()).isFalse();refresh.finish("w",w,SupplierLookupService.summarize(List.of(listing(ProcurementMall.LFMALL,"one",96000,"온라인점","CONFIRMED")),true,time.now()));refresh.claim("w");assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(96000);assertThat(current(p).selectedSupplier().preferred()).isFalse();assertThat(compare(p).groups()).isEmpty();assertThat(compare(p).selected()).isNotNull();}
    @Test void selectionMadeDuringRefreshAndStaleDoubleClickAreSafe()throws Exception {var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"a",100,"온라인점","CONFIRMED"),listing(ProcurementMall.LFMALL,"b",200,"온라인점","CONFIRMED")));var listings=compare(p).groups().getFirst().listings();choose(p,listings.getFirst());start(p);var w=refresh.claim("w");long rev=current(p).revision();choose(p,listings.getLast());assertThatThrownBy(()->supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(rev,listings.getFirst().id()))).isInstanceOf(IllegalStateException.class);refresh.finish("w",w,SupplierLookupService.summarize(List.of(listing(ProcurementMall.LFMALL,"a",50,"온라인점","CONFIRMED"),listing(ProcurementMall.LFMALL,"b",300,"온라인점","CONFIRMED")),true,time.now()));assertThat(current(p).selectedSupplier().id()).isEqualTo(listings.getLast().id());assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(300);}
    @Test void branchRenameKeepsIdentityAndPreferencesAndLookupRevision(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"목동점","CONFIRMED")));var source=byMall(p,ProcurementMall.HI_THEHYUNDAI);choose(p,source);var branch=source.store();preferred.saveStore(actor,Long.parseLong(branch.id()),new StoreInput(branch.revision(),branch.mall(),branch.kind(),"현대 목동",null));finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"목동점","CONFIRMED")));assertThat(current(p).selectedSupplier().store().id()).isEqualTo(branch.id());assertThat(current(p).selectedSupplier().store().name()).isEqualTo("현대 목동");assertThat(current(p).lookupRevision()).isZero();assertThatThrownBy(()->preferred.deleteStore(actor,Long.parseLong(branch.id()),1L)).hasMessageContaining("사용 중");}
    @Test void selectionAndStoreApisEnforcePermissionsCsrfAndOwnership()throws Exception {var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"a",100,"온라인점","CONFIRMED")));String route="/api/products/"+p.id()+"/selection";var staff=login(account(UserRole.PRODUCT));var adminUser=account(UserRole.ADMIN);var admin=login(adminUser);var body=Map.of("revision",current(p).revision(),"supplierId",byMall(p,ProcurementMall.LFMALL).id());status(new Browser().get("/api/settings/preferred-suppliers"),401);status(login(account(UserRole.VIEWER)).get("/api/settings/preferred-suppliers"),403);status(staff.get("/api/settings/preferred-suppliers"),200);status(staff.post("/api/settings/preferred-suppliers",Map.of("mall","LFMALL")),403);status(staff.send("POST",route,body,null),403);status(staff.post(route,body),200);status(admin.post(route,body),409);var other=create("ABCD6F124BK");status(staff.post("/api/products/"+other.id()+"/selection",Map.of("revision",other.revision(),"supplierId",byMall(p,ProcurementMall.LFMALL).id())),404);jdbc.update("UPDATE `user` SET user_status='SUSPENDED',auth_version=auth_version+1 WHERE id=?",adminUser.getId());status(admin.get("/api/settings/preferred-suppliers"),401);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier_change WHERE change_type='SELECT'",Long.class)).isEqualTo(1);jdbc.update("UPDATE `user` SET user_role='PRODUCT' WHERE id=?",adminUser.getId());}

    @Test void concurrentSelectionsOnlyAcceptOneVersionAndClearIsAudited()throws Exception {var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"one",100,"온라인점","CONFIRMED"),listing(ProcurementMall.LFMALL,"two",200,"온라인점","CONFIRMED")));var listings=compare(p).groups().getFirst().listings();long revision=current(p).revision();try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var tasks=listings.stream().map(l->pool.submit(()->{gate.await();try{supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(revision,l.id()));return true;}catch(IllegalStateException e){return false;}})).toList();gate.countDown();assertThat(tasks.get(0).get()^tasks.get(1).get()).isTrue();}supplierService.select(actor,Long.parseLong(p.id()),new SelectionInput(current(p).revision(),null));assertThat(current(p).selectedSupplier()).isNull();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier_change WHERE change_type='SELECT'",Long.class)).isEqualTo(2);}
    @Test void mergeRequiresExplicitSelectionAndPreservesBothListings(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"one",100,"온라인점","CONFIRMED")));choose(p,byMall(p,ProcurementMall.LFMALL));long copy=Long.parseLong(p.id())+100; jdbc.update("INSERT INTO catalog_product(id,brand_id,product_code,created_at,updated_at) VALUES(?,?,?,NOW(6),NOW(6))",copy,brand,p.productCode());jdbc.update("INSERT INTO procurement_product(product_id,search_query,code_type) VALUES(?,?,'LF_ACCESSORY')",copy,p.searchQuery());var other=products.product(copy);finish(other,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"two",200,"목동점","CONFIRMED")));choose(other,byMall(other,ProcurementMall.HI_THEHYUNDAI));var historyCount=changeService.history(actor,Long.parseLong(p.id()),"","ALL",0).totalElements()+changeService.history(actor,Long.parseLong(other.id()),"","ALL",0).totalElements();var versions=List.of(version(p),version(other));var noChoice=new MergeInput(versions,null,p.productCode(),p.searchQuery(),true,brand,"AUTO","LF_ACCESSORY");assertThatThrownBy(()->products.merge(actor,Long.parseLong(p.id()),noChoice)).hasMessageContaining("유지할 판매글");String choice=current(other).selectedSupplier().id();products.merge(actor,Long.parseLong(p.id()),new MergeInput(versions,null,p.productCode(),p.searchQuery(),true,brand,"AUTO","LF_ACCESSORY",choice));assertThat(current(p).selectedSupplier().id()).isEqualTo(choice);assertThat(changeService.history(actor,Long.parseLong(p.id()),"","ALL",0).totalElements()).isEqualTo(historyCount);assertThat(changeService.history(actor,Long.parseLong(p.id()),"","ALL",0).items()).anyMatch(h->h.originProductId().equals(other.id()));assertThat(changeService.summary(Long.parseLong(p.id())).version()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier WHERE product_id=?",Long.class,p.id())).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier_selection",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier_change",Long.class)).isEqualTo(3);}

    @Test void transientMissingBranchKeepsKnownStoreButConflictingEvidenceNeedsReview(){var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"목동점","CONFIRMED")));choose(p,byMall(p,ProcurementMall.HI_THEHYUNDAI));String branch=current(p).selectedSupplier().store().id();finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",120,"","FAILED")));assertThat(current(p).selectedSupplier().store().id()).isEqualTo(branch);assertThat(current(p).selectedSupplier().priceStatus()).isEqualTo("CONFIRMED");var conflict=listing(ProcurementMall.HI_THEHYUNDAI,"one",130,"","CONFIRMED");conflict=new SupplierResult(conflict.offer(),conflict.match(),conflict.state(),conflict.options(),null,null,null,null,new BranchInfo("","CONFLICT","TITLE","목동점 / 천호점"));finish(p,List.of(conflict));assertThat(compare(p).groups()).isEmpty();assertThat(hiddenReview(p)).hasSize(1);assertThat(current(p).selectedSupplier().id()).isNotNull();assertThatThrownBy(()->choose(p,hiddenReview(p).getFirst())).hasMessageContaining("선호 매입처 또는 현재 유효한 추천");}
    @Test void manualStoreDoesNotResolveUncertainProductCode(){preferred.saveMall(actor,ProcurementMall.LFMALL,new MallPreferenceInput(preferred.get(actor).revision(),"ALL",List.of(),List.of(),true));var p=create("ABCD6F123BK");var raw=listing(ProcurementMall.LFMALL,"one",100,"","CONFIRMED");var review=new SupplierResult(raw.offer(),new CodeMatch("REVIEW","","","","","상품코드 확인 필요"),raw.state(),raw.options(),null);finish(p,List.of(review));var store=preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.LFMALL,"ONLINE","LF몰 온라인",null));var l=hiddenReview(p).getFirst();supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(l.id()),new AssignmentInput(l.revision(),store.id()));assertThatThrownBy(()->choose(p,hiddenReview(p).getFirst())).hasMessageContaining("선호 매입처 또는 현재 유효한 추천");assertThat(compare(p).groups()).isEmpty();}
    SupplierResult identified(ProcurementMall mall,String id,String retailer,String branch,String namespace,String external){
        var old=listing(mall,id,94000,branch,"CONFIRMED");
        return new SupplierResult(old.offer(),old.match(),mall==ProcurementMall.NAVER_SMART_STORE?"DEFERRED":old.state(),mall==ProcurementMall.NAVER_SMART_STORE?List.of():old.options(),null,null,null,null,new BranchInfo(branch,"CONFIRMED","META",retailer+" "+branch,new StoreEvidence("BRANCH",retailer,branch,namespace,external)));
    }
    @Test void externalStoreIdentityPreservesSelectionAndDisplayRename(){
        var p=create("ABCD6F123BK");finish(p,List.of(identified(ProcurementMall.HI_THEHYUNDAI,"hi","현대백화점","신촌점","HI_STORE","270")));
        var listing=byMall(p,ProcurementMall.HI_THEHYUNDAI);choose(p,listing);var original=listing.store();assertThat(original.identities()).hasSize(1);
        preferred.saveStore(actor,Long.parseLong(original.id()),new StoreInput(original.revision(),original.mall(),original.kind(),"자주 사는 매장",null,"현대백화점"));
        finish(p,List.of(identified(ProcurementMall.HI_THEHYUNDAI,"hi","현대백화점","신촌점 이름 변경","HI_STORE","270")));
        var selected=current(p).selectedSupplier();assertThat(selected.store().id()).isEqualTo(original.id());assertThat(selected.store().name()).isEqualTo("자주 사는 매장");assertThat(selected.storeStatus()).isEqualTo("CONFIRMED");assertThat(selected.id()).isEqualTo(listing.id());
    }
    @Test void naverRetailersWithIdenticalBranchNamesAreDifferentStores(){
        var p=create("ABCD6F123BK");finish(p,List.of(identified(ProcurementMall.NAVER_SMART_STORE,"a","현대백화점","본점","NAVER_DEPARTMENT","10001/1"),identified(ProcurementMall.NAVER_SMART_STORE,"b","롯데백화점","본점","NAVER_DEPARTMENT","10002/1")));
        assertThat(compare(p).groups()).hasSize(2);assertThat(compare(p).groups()).extracting(g->g.store().retailer()).containsExactlyInAnyOrder("현대백화점","롯데백화점");assertThat(compare(p).groups()).allSatisfy(g->assertThat(g.listings().getFirst().inventoryState()).isEqualTo("UNCONFIRMED"));
    }
    @Test void legacyStoreBindingRequiresAdminAndPreservesIdWithVersionCheck()throws Exception {
        jdbc.update("INSERT INTO supplier_store(id,mall,kind,name,identity_key,aliases,created_at,updated_at) VALUES(77,'NAVER_SMART_STORE','BRANCH','천호점','branch:천호점',JSON_ARRAY('천호점'),NOW(6),NOW(6))");
        var p=create("ABCD6F123BK");finish(p,List.of(identified(ProcurementMall.NAVER_SMART_STORE,"a","현대백화점","천호점","NAVER_DEPARTMENT","10001/10001006")));
        assertThat(compare(p).groups()).isEmpty();var c=preferred.identityCandidates(actor).getFirst();
        var body=Map.of("revision",0,"supplierId",c.supplierId(),"observedRunId",c.observedRunId());var staff=login(account(UserRole.PRODUCT));var adminAccount=account(UserRole.ADMIN);var admin=login(adminAccount);String url="/api/settings/supplier-stores/77/identity";
        status(staff.post(url,body),403);status(admin.send("POST",url,body,null),403);status(admin.post(url,body),200);status(admin.post(url,body),409);jdbc.update("UPDATE `user` SET user_role='PRODUCT' WHERE id=?",adminAccount.getId());
        finish(p,List.of(identified(ProcurementMall.NAVER_SMART_STORE,"a","현대백화점","천호점","NAVER_DEPARTMENT","10001/10001006")));
        assertThat(compare(p).groups().getFirst().store().id()).isEqualTo("77");assertThat(preferred.identityCandidates(actor)).isEmpty();
    }
    @Test void contradictoryExternalIdentityDoesNotOverwriteSelectedStore(){
        var p=create("ABCD6F123BK");finish(p,List.of(identified(ProcurementMall.NAVER_SMART_STORE,"a","현대백화점","본점","NAVER_DEPARTMENT","10001/1")));var original=byMall(p,ProcurementMall.NAVER_SMART_STORE);choose(p,original);
        finish(p,List.of(identified(ProcurementMall.NAVER_SMART_STORE,"a","롯데백화점","본점","NAVER_DEPARTMENT","10001/1")));
        var selected=current(p).selectedSupplier();assertThat(selected.id()).isEqualTo(original.id());assertThat(selected.store().id()).isEqualTo(original.store().id());assertThat(selected.conflict()).isTrue();assertThat(hiddenReview(p)).hasSize(1);assertThat(preferred.get(actor).stores()).hasSize(1);
    }
    @Test void missingBranchIsHistoricalAndCannotBeSelectedUntilConfirmed(){
        var p=create("ABCD6F123BK");finish(p,List.of(identified(ProcurementMall.HI_THEHYUNDAI,"a","현대백화점","신촌점","HI_STORE","270")));choose(p,byMall(p,ProcurementMall.HI_THEHYUNDAI));String chosen=current(p).selectedSupplier().id();
        finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"a",95000,"","FAILED")));var l=current(p).selectedSupplier();assertThat(l.storeStatus()).isEqualTo("HISTORICAL");assertThat(l.id()).isEqualTo(chosen);assertThat(l.referencePrice()).isEqualTo(95000);assertThat(hiddenReview(p)).hasSize(1);assertThatThrownBy(()->choose(p,l)).hasMessageContaining("선호 매입처 또는 현재 유효한 추천");
    }
    @Test void configuredPreferredMallsFlowThroughLookupPersistenceAndComparison(){
        jdbc.update("DELETE FROM supplier_preference");
        for(var mall:List.of(ProcurementMall.LFMALL,ProcurementMall.HAZZYS,ProcurementMall.LOTTE_IMALL))preferred.saveRule(actor,null,new RuleInput(null,mall,null));
        for(var mall:List.of(ProcurementMall.HI_THEHYUNDAI,ProcurementMall.NAVER_SMART_STORE))for(String name:List.of("목동점","천호점","신촌점")){
            var st=preferred.saveStore(actor,null,new StoreInput(null,mall,"BRANCH",name,null,"현대백화점"));
            preferred.saveRule(actor,null,new RuleInput(null,mall,st.id()));
        }
        for(var mall:List.of(ProcurementMall.LOTTE_ON,ProcurementMall.NAVER_SMART_STORE)){
            var st=preferred.saveStore(actor,null,new StoreInput(null,mall,"BRANCH","본점",null,"롯데백화점"));
            preferred.saveRule(actor,null,new RuleInput(null,mall,st.id()));
        }
        var offers=new ArrayList<Offer>();var details=new HashMap<String,SourceDetails>();
        for(var mall:ProcurementMall.values()){
            String url=switch(mall){
                case LFMALL->"https://www.lfmall.co.kr/app/product/";
                case HAZZYS->"https://www.hazzys.com/product.do?PROD_CD=";
                case LOTTE_IMALL->"https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no=";
                case LOTTE_ON->"https://www.lotteon.com/p/product?sitmNo=";
                case HI_THEHYUNDAI->"https://hi.thehyundai.com/product/";
                case HMALL->"https://www.hmall.com/p/pda/itemPtc.do?slitmCd=";
                case NAVER_SMART_STORE->"https://shopping.naver.com/window-products/department/";
            };
            int count=Set.of(ProcurementMall.LOTTE_ON,ProcurementMall.HI_THEHYUNDAI,ProcurementMall.NAVER_SMART_STORE).contains(mall)?3:1;
            for(int n=0;n<count;n++){
                String id=mall==ProcurementMall.NAVER_SMART_STORE?Integer.toString(420+n):mall.name()+n;String retailer=mall==ProcurementMall.LOTTE_ON?"롯데백화점":"현대백화점";
                String branch=mall==ProcurementMall.LOTTE_ON?"본점":"천호점";
                StoreEvidence evidence=n==2?null:new StoreEvidence("BRANCH",retailer,n==1?"부산점":branch,"TEST_STORE",id);
                offers.add(new Offer(id,"ABCD123",mall.getDisplayName(),id,url+id,94000L+n,0L,mall,"https://example.com/"+id+".jpg"));
                details.put(id,new SourceDetails("ABCD123","","",List.of(new SourceOption("FREE","FREE",2L,"AVAILABLE")),null,evidence,true,mall==ProcurementMall.NAVER_SMART_STORE?new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"):null));
            }
        }
        var calls=new ArrayList<ProcurementMall>();
        var gateway=new cc.ataglace.molebutter.procurement.internal.SupplierProductGateway(){
            public String validateUrl(ProcurementMall mall,String url){return url;}
            public List<SourceOption> options(ProcurementMall mall,String id,String url){throw new AssertionError();}
            public SourceDetails inspect(ProcurementMall mall,String id,String url){calls.add(mall);return details.get(id);}
        };
        var p=create("ABCD6F123BK");start(p);var work=refresh.claim("w");
        var result=new SupplierLookupService(gateway,time).lookup(work,new SearchResult(offers,true,null),()->refresh.heartbeat("w",work));
        refresh.finish("w",work,result);refresh.claim("w");
        assertThat(calls).doesNotContain(ProcurementMall.HMALL);
        assertThat(result.suppliers()).hasSize(9).noneMatch(r->"부산점".equals(r.branch().name()));
        assertThat(compare(p).groups()).hasSize(6);assertThat(hiddenReview(p)).hasSize(3);assertThat(compare(p).excluded()).isEmpty();
        var unknown=hiddenReview(p).stream().filter(l->l.mall()==ProcurementMall.NAVER_SMART_STORE).findFirst().orElseThrow();
        assertThat(unknown.store()).isNull();assertThat(unknown.inventoryState()).isEqualTo("AVAILABLE");assertThat(unknown.selectable()).isFalse();
        assertThat(preferred.get(actor).stores()).noneMatch(st->st.kind().equals("SELLER"));
        choose(p,byMall(p,ProcurementMall.HI_THEHYUNDAI));assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(94000);
    }


    @Test void branchlessListingsAreGroupedSelectableAndDoNotChangePriceOrSelection()throws Exception {
        var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.LFMALL,"a",176210,"","CONFIRMED"),listing(ProcurementMall.LFMALL,"b",225070,"","SOLD_OUT"),listing(ProcurementMall.HAZZYS,"c",200000,"","CONFIRMED")));
        var c=compare(p);assertThat(c.pending()).isEmpty();assertThat(c.groups()).hasSize(2);
        var lf=c.groups().stream().filter(g->g.mall()==ProcurementMall.LFMALL).findFirst().orElseThrow();
        assertThat(lf.store()).isNull();assertThat(lf.id()).isEqualTo("mall:LFMALL");assertThat(lf.listings()).hasSize(2).allMatch(Listing::selectable);
        assertThat(lf.listings()).allMatch(l->!l.branchRequired()&&l.storeStatus().equals("NOT_REQUIRED"));
        choose(p,lf.listings().getLast());String selected=current(p).selectedSupplier().id();var checked=current(p).selectedSupplier().priceCheckedAt();long lookupRev=current(p).lookupRevision();
        var response=login(account(UserRole.PRODUCT)).get("/api/products/"+p.id());status(response,200);
        var selectedJson=json.readTree(response.body()).path("data").path("comparison").path("selected");
        assertThat(selectedJson.path("selectable").asBoolean()).isTrue();assertThat(selectedJson.path("branchRequired").asBoolean()).isFalse();assertThat(selectedJson.path("selectionUnavailableReason").isNull()).isTrue();
        preferred.saveMall(actor,ProcurementMall.LFMALL,new MallPreferenceInput(preferred.get(actor).revision(),"ALL",List.of(),List.of(),true));
        assertThat(hiddenReview(p)).hasSize(2);assertThat(current(p).selectedSupplier().id()).isEqualTo(selected);
        assertThat(current(p).selectedSupplier().selectable()).isFalse();
        assertThatThrownBy(()->choose(p,current(p).selectedSupplier())).hasMessageContaining("선호 매입처 또는 현재 유효한 추천");
        preferred.saveMall(actor,ProcurementMall.LFMALL,new MallPreferenceInput(preferred.get(actor).revision(),"ALL",List.of(),List.of(),false));
        assertThat(hiddenReview(p)).isEmpty();assertThat(current(p).selectedSupplier().id()).isEqualTo(selected);
        assertThat(current(p).selectedSupplier().referencePrice()).isEqualTo(225070);assertThat(current(p).selectedSupplier().priceCheckedAt()).isEqualTo(checked);assertThat(current(p).lookupRevision()).isEqualTo(lookupRev);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history",Long.class)).isEqualTo(1);
    }
    @Test void branchPolicyPreservesManualAssignmentsSnapshotsAndRulesUntilSaved(){
        var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"a",100,"목동점","CONFIRMED"),listing(ProcurementMall.HI_THEHYUNDAI,"b",200,"","CONFIRMED")));
        var store=compare(p).groups().getFirst().store();var pending=hiddenReview(p).getFirst();
        supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(pending.id()),new AssignmentInput(pending.revision(),store.id()));
        preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(preferred.get(actor).revision(),"STORES",List.of(store.id()),List.of(),true));
        start(p);var w=refresh.claim("w");long revision=preferred.get(actor).revision();
        preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(revision,"ALL",List.of(),List.of(),false));
        assertThat(w.preferences().branchRequired(ProcurementMall.HI_THEHYUNDAI)).isTrue();
        assertThat(store().branchRequired(ProcurementMall.HI_THEHYUNDAI)).isFalse();
        assertThat(store().groups().stream().filter(g->g.mall()==ProcurementMall.HI_THEHYUNDAI).findFirst().orElseThrow().scope()).isEqualTo("ALL");
        assertThatThrownBy(()->preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(revision,"ALL",List.of(),List.of(),true))).isInstanceOf(IllegalStateException.class);
        assertThat(compare(p).groups().getFirst().listings()).anyMatch(l->l.manual()&&l.store().id().equals(store.id()));
        assertThatThrownBy(()->preferred.saveRule(actor,null,new RuleInput(null,ProcurementMall.HI_THEHYUNDAI,store.id()))).hasMessageContaining("지점 구분");
        preferred.deleteMall(actor,ProcurementMall.HI_THEHYUNDAI,preferred.get(actor).revision());assertThat(store().branchRequired(ProcurementMall.HI_THEHYUNDAI)).isFalse();
        preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(preferred.get(actor).revision(),"ALL",List.of(),List.of()));assertThat(store().branchRequired(ProcurementMall.HI_THEHYUNDAI)).isFalse();
        refresh.finish("w",w,result());refresh.claim("w");start(p);assertThat(refresh.claim("next").preferences().branchRequired(ProcurementMall.HI_THEHYUNDAI)).isFalse();
    }
    Preferences store(){return preferred.get(actor);}
    @Test void branchPolicyMutationRequiresAdminCsrfAndRollsBackOnInvalidStores()throws Exception {
        var adminUser=account(UserRole.ADMIN);try {
        var admin=login(adminUser);var staff=login(account(UserRole.PRODUCT));String url="/api/settings/preferred-suppliers/LFMALL";
        var input=new MallPreferenceInput(preferred.get(actor).revision(),"ALL",List.of(),List.of(),true);
        staff.csrf();status(staff.send("PUT",url,input,staff.csrf),403);status(admin.send("PUT",url,input,null),403);assertThat(store().branchRequired(ProcurementMall.LFMALL)).isFalse();
        admin.csrf();status(admin.send("PUT",url,input,admin.csrf),200);assertThat(store().branchRequired(ProcurementMall.LFMALL)).isTrue();
        var invalid=new MallPreferenceInput(store().revision(),"STORES",List.of("999999"),List.of(),false);
        assertThatThrownBy(()->preferred.saveMall(actor,ProcurementMall.LFMALL,invalid)).isInstanceOf(IllegalArgumentException.class);assertThat(store().branchRequired(ProcurementMall.LFMALL)).isTrue();
        }finally{jdbc.update("UPDATE `user` SET user_role='PRODUCT' WHERE id=?",adminUser.getId());}
    }
    User account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.com").name("상품 테스트").passwordHash(encoder.encode("Product123!")).role(role).status(UserStatus.ACTIVE).build());}
    @Test void mallPreferencesSaveAtomicallyAndPreserveStoreIdentities(){
        var initial=preferred.get(actor);var draft=new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","목동점",null,null);
        var saved=preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(initial.revision(),"STORES",List.of(),List.of(draft,draft)));
        var group=saved.groups().stream().filter(g->g.mall()==ProcurementMall.HI_THEHYUNDAI).findFirst().orElseThrow();
        assertThat(group.scope()).isEqualTo("STORES");assertThat(group.storeIds()).hasSize(1);
        var id=group.storeIds().getFirst();assertThat(saved.stores()).filteredOn(st->st.id().equals(id)).first().extracting(Store::retailer).isEqualTo("현대백화점");
        assertThatThrownBy(()->preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(initial.revision(),"ALL",List.of(),List.of()))).isInstanceOf(IllegalStateException.class);
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store",Long.class);
        assertThatThrownBy(()->preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(saved.revision(),"STORES",List.of(id),List.of(new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","천호점",null),new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"SELLER","잘못된 판매자","x"))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store",Long.class)).isEqualTo(before);
        assertThat(preferred.get(actor).revision()).isEqualTo(saved.revision());
        assertThatThrownBy(()->preferred.saveRule(actor,null,new RuleInput(null,ProcurementMall.HI_THEHYUNDAI,null))).hasMessageContaining("함께 저장");
        var all=preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(saved.revision(),"ALL",List.of(),List.of()));
        assertThat(all.rules().stream().filter(r->r.mall()==ProcurementMall.HI_THEHYUNDAI).toList()).hasSize(1);
        assertThat(all.stores()).anyMatch(st->st.id().equals(id));
        var removed=preferred.deleteMall(actor,ProcurementMall.HI_THEHYUNDAI,all.revision());assertThat(removed.mallAllowed(ProcurementMall.HI_THEHYUNDAI)).isFalse();assertThat(removed.stores()).anyMatch(st->st.id().equals(id));
    }
    @Test void concurrentMallChangesAcceptOneRevision()throws Exception {
        long revision=preferred.get(actor).revision();
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(String branch:List.of("목동점","천호점"))tasks.add(pool.submit(()->{gate.await();try{
                preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(revision,"STORES",List.of(),List.of(new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH",branch,null))));return true;
            }catch(IllegalStateException ex){return false;}}));
            gate.countDown();assertThat(tasks.get(0).get()^tasks.get(1).get()).isTrue();
        }
        assertThat(preferred.get(actor).stores()).hasSize(1);
        assertThat(preferred.get(actor).groups().stream().filter(g->g.mall()==ProcurementMall.HI_THEHYUNDAI).findFirst().orElseThrow().storeIds()).hasSize(1);
    }
    @Test void groupedPreferencesShowLegacyAllAndSnapshotStillRoundTrips(){
        var branch=preferred.saveStore(actor,null,new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","목동점",null));
        jdbc.update("INSERT INTO supplier_preference(id,mall,store_id,scope_key) VALUES(?,?,?,?)",ProductStore.id(),ProcurementMall.HI_THEHYUNDAI.name(),branch.id(),branch.id());
        var p=preferred.get(actor);assertThat(p.groups().stream().filter(g->g.mall()==ProcurementMall.HI_THEHYUNDAI).findFirst().orElseThrow().scope()).isEqualTo("ALL");
        var decoded=json.readValue(json.writeValueAsString(p),Preferences.class);assertThat(decoded).isEqualTo(p);
        var clean=preferred.saveMall(actor,ProcurementMall.HI_THEHYUNDAI,new MallPreferenceInput(p.revision(),"STORES",List.of(branch.id()),List.of()));
        assertThat(clean.rules().stream().filter(r->r.mall()==ProcurementMall.HI_THEHYUNDAI).toList()).hasSize(1);
    }
    @Test void inlineStoreAssignmentIsAtomicAndPreferenceAdditionIsOptional(){
        var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"","CONFIRMED")));
        var l=hiddenReview(p).getFirst();var prefs=preferred.get(actor);preferred.deleteMall(actor,ProcurementMall.HI_THEHYUNDAI,prefs.revision());
        var draft=new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","목동점",null);
        var assigned=supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(l.id()),new AssignmentInput(l.revision(),null,draft,false,preferred.get(actor).revision()));
        assertThat(preferred.get(actor).mallAllowed(ProcurementMall.HI_THEHYUNDAI)).isFalse();assertThat(assigned.selected()).isNull();
        var current=storedListings(p).getFirst();long count=jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store",Long.class);
        assertThatThrownBy(()->supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(l.id()),new AssignmentInput(l.revision(),null,new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","천호점",null),true,preferred.get(actor).revision()))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_store",Long.class)).isEqualTo(count);
        var result=supplierService.assign(actor,Long.parseLong(p.id()),Long.parseLong(l.id()),new AssignmentInput(current.revision(),current.store().id(),null,true,preferred.get(actor).revision()));
        assertThat(result.groups()).hasSize(1);assertThat(result.groups().getFirst().listings().getFirst().manual()).isTrue();assertThat(result.selected()).isNull();
    }
    @Test void groupedApiAndInlineRegistrationEnforceCsrfAndAdmin()throws Exception{
        var p=create("ABCD6F123BK");finish(p,List.of(listing(ProcurementMall.HI_THEHYUNDAI,"one",100,"","CONFIRMED")));var l=hiddenReview(p).getFirst();
        var adminUser=account(UserRole.ADMIN);try{
            var admin=login(adminUser);var staff=login(account(UserRole.PRODUCT));
            var input=new MallPreferenceInput(preferred.get(actor).revision(),"STORES",List.of(),List.of(new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","목동점",null)));
            var route="/api/settings/preferred-suppliers/HI_THEHYUNDAI";
            status(admin.send("PUT",route,input,null),403);admin.csrf();status(admin.send("PUT",route,input,admin.csrf),200);
            staff.csrf();status(staff.send("PUT",route,input,staff.csrf),403);status(staff.send("DELETE",route,Map.of("revision",preferred.get(actor).revision()),staff.csrf),403);
            String assign="/api/products/"+p.id()+"/suppliers/"+l.id()+"/store";
            status(staff.post(assign,new AssignmentInput(l.revision(),null,new StoreInput(null,ProcurementMall.HI_THEHYUNDAI,"BRANCH","천호점",null),false,preferred.get(actor).revision())),403);
            var branch=preferred.get(actor).stores().getFirst();
            status(staff.post(assign,new AssignmentInput(l.revision(),branch.id(),null,true,preferred.get(actor).revision())),403);
            status(staff.post(assign,new AssignmentInput(l.revision(),branch.id())),200);
            admin.csrf();status(admin.send("PUT",route,input,admin.csrf),409);
            var response=admin.get("/api/settings/preferred-suppliers");assertThat(json.readTree(response.body()).path("data").path("groups").isArray()).isTrue();
        }finally{jdbc.update("UPDATE `user` SET user_role='PRODUCT' WHERE id=?",adminUser.getId());}
    }

    void notificationTransaction(Runnable work){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->work.run());}
    User notificationUser(UserRole role){return users.saveAndFlush(User.builder().name("알림 검증").email("notice-"+UUID.randomUUID()+"@example.com").passwordHash(encoder.encode("Product123!")).role(role).status(UserStatus.ACTIVE).build());}
    void notice(String key,Long recipient){notifications.publish(key,"TEST","INFO","검증 결과","처리 완료","PRODUCTS",null,List.of(recipient));}
    @Test void notificationsAreAtomicDeduplicatedAndOnlyExplicitlyDismissed(){
        assertThatThrownBy(()->notificationTransaction(()->{notice("rollback",actor);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.summary(actor).count()).isZero();
        notificationTransaction(()->notice("one",actor));notificationTransaction(()->notice("one",actor));
        var first=notifications.list(actor,null,20).items().getFirst();
        assertThat(notifications.summary(actor).count()).isEqualTo(1);assertThat(notifications.target(actor,Long.parseLong(first.id())).url()).isEqualTo("/products");
        assertThat(notifications.summary(actor).count()).isEqualTo(1);
        notifications.dismiss(actor,Long.parseLong(first.id()));notifications.dismiss(actor,Long.parseLong(first.id()));notificationTransaction(()->notice("one",actor));
        assertThat(notifications.summary(actor).count()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event",Long.class)).isEqualTo(1);
    }
    @Test void notificationCursorRemainsStableWhenNewEventsArrive(){
        for(int i=0;i<25;i++){String key="page-"+i;notificationTransaction(()->notice(key,actor));}
        var first=notifications.list(actor,null,20);assertThat(first.items()).hasSize(20);assertThat(first.nextCursor()).isNotNull();
        notificationTransaction(()->notice("new",actor));
        var second=notifications.list(actor,first.nextCursor(),20);assertThat(second.items()).hasSize(5);assertThat(second.nextCursor()).isNull();
        var ids=new HashSet<String>();first.items().forEach(n->ids.add(n.id()));second.items().forEach(n->assertThat(ids.add(n.id())).isTrue());assertThat(ids).hasSize(25);
        assertThat(notifications.summary(actor).count()).isEqualTo(26);
    }
    @Test void scheduledNoticesHaveIndependentRecipientsAndManualRunsNotifyOnlyInitiator(){
        var staff=notificationUser(UserRole.PRODUCT);var viewer=notificationUser(UserRole.VIEWER);var p=create("ABCD6F123BK");
        refresh.schedule();var w=refresh.claim("notify");refresh.finish("notify",w,result());refresh.claim("notify");
        assertThat(notifications.summary(actor).count()).isEqualTo(1);assertThat(notifications.summary(staff.getId()).count()).isEqualTo(1);assertThat(notifications.summary(viewer.getId()).count()).isZero();
        var n=notifications.list(staff.getId(),null,20).items().getFirst();notifications.dismiss(staff.getId(),Long.parseLong(n.id()));assertThat(notifications.summary(actor).count()).isEqualTo(1);
        start(p);w=refresh.claim("notify");refresh.blocked("notify",w,"[쇼핑몰 재고 조회 제한] 롯데홈쇼핑 · HTTP 403.");refresh.blocked("notify",w,"duplicate");
        assertThat(notifications.summary(actor).count()).isEqualTo(2);assertThat(notifications.summary(staff.getId()).count()).isZero();
        refresh.control(actor,w.runId(),"resume");w=refresh.claim("notify");refresh.blocked("notify",w,"blocked again");assertThat(notifications.summary(actor).count()).isEqualTo(3);
        refresh.control(actor,w.runId(),"cancel");assertThat(notifications.summary(actor).count()).isEqualTo(4);
    }
    @Test void notificationPermissionsCsrfDeletedTargetsAndRevocation()throws Exception {
        var staff=notificationUser(UserRole.PRODUCT);var other=notificationUser(UserRole.PRODUCT);var p=create("ABCD6F123BK");
        notificationTransaction(()->notifications.publish("product-notice","TEST","INFO","상품 결과","비공개 내용","PRODUCT",Long.parseLong(p.id()),List.of(staff.getId())));
        String id=notifications.summary(staff.getId()).latestId();var browser=login(staff);var stranger=login(other);
        status(stranger.get("/api/notifications/"+id+"/target"),404);status(new Browser().get("/api/notifications/summary"),401);
        browser.csrf();status(browser.send("DELETE","/api/notifications/"+id,null,null),403);
        stranger.csrf();status(stranger.send("DELETE","/api/notifications/"+id,null,stranger.csrf),404);
        assertThat(notifications.target(staff.getId(),Long.parseLong(id)).url()).contains(p.id());
        products.delete(actor,new DeleteProducts(List.of(version(p))));assertThat(notifications.target(staff.getId(),Long.parseLong(id)).url()).isNull();
        jdbc.update("UPDATE `user` SET user_role='VIEWER' WHERE id=?",staff.getId());
        var masked=notifications.list(staff.getId(),null,20).items().getFirst();assertThat(masked.title()).isEqualTo("접근 권한 없음");assertThat(masked.message()).isEmpty();
        notifications.dismiss(staff.getId(),Long.parseLong(id));assertThat(notifications.summary(staff.getId()).count()).isZero();
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",staff.getId());assertThatThrownBy(()->notifications.summary(staff.getId())).isInstanceOf(cc.ataglace.molebutter.common.api.BusinessException.class);
    }
    @Test void mutationFailureIsSavedAfterRollbackButValidationAndNotificationErrorsAreNot()throws Exception {
        var staff=notificationUser(UserRole.PRODUCT);var browser=login(staff);var p=create("ABCD6F123BK");browser.operationId=UUID.randomUUID().toString();
        var invalid=new ProductEditRequest(999L,null,p.productCode(),null,brand,"AUTO",null);
        status(browser.post("/api/products/"+p.id(),invalid),409);status(browser.post("/api/products/"+p.id(),invalid),409);
        assertThat(notifications.summary(staff.getId()).count()).isEqualTo(1);assertThat(current(p).revision()).isEqualTo(p.revision());
        status(browser.get("/api/notifications?size=0"),400);status(browser.post("/api/products",Map.of("productCode","")),400);
        assertThat(notifications.summary(staff.getId()).count()).isEqualTo(1);
    }
    @Test void importAndCorrectionEventsUseBusinessTransactionsAndCorrectTargets(){
        products.upload(actor,"test.xlsx",WorkbookFixture.create(2,Map.of("F4","ABCD6F123BK","F5","ABCD6F123BK")));
        var imported=notifications.list(actor,null,20).items().getFirst();assertThat(imported.type()).isEqualTo("PRODUCT_IMPORT");assertThat(imported.message()).contains("신규 1","중복 1");assertThat(notifications.target(actor,Long.parseLong(imported.id())).url()).isEqualTo("/products#import-panel");
        var owner=notificationUser(UserRole.VIEWER);var date=LocalDate.of(2025,1,2);
        var request=new cc.ataglace.molebutter.attendance.api.AttendanceDtos.CorrectionRequest(date,null,null,date.atTime(9,0),date.atTime(18,0),List.of(),"누락 보완");
        var c=attendance.requestCorrection(owner.getId(),request);assertThat(notifications.summary(owner.getId()).count()).isEqualTo(1);
        attendance.review(actor,Long.parseLong(c.id()),true,new cc.ataglace.molebutter.attendance.api.AttendanceDtos.ReviewRequest(c.revision(),null));
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(2);
        String ownerNotice=notifications.summary(owner.getId()).latestId(),adminNotice=notifications.summary(actor).latestId();
        assertThat(notifications.target(owner.getId(),Long.parseLong(ownerNotice)).url()).isEqualTo("/attendance?correction="+c.id());
        assertThat(notifications.target(actor,Long.parseLong(adminNotice)).url()).isEqualTo("/attendance-manage?correction="+c.id());
        var cancelled=attendance.requestCorrection(owner.getId(),new cc.ataglace.molebutter.attendance.api.AttendanceDtos.CorrectionRequest(date.plusDays(1),null,null,date.plusDays(1).atTime(9,0),date.plusDays(1).atTime(18,0),List.of(),"누락"));
        attendance.cancel(owner.getId(),Long.parseLong(cancelled.id()),new cc.ataglace.molebutter.attendance.api.AttendanceDtos.ReviewRequest(cancelled.revision(),null));
        assertThat(notifications.list(owner.getId(),null,20).items().getFirst().type()).isEqualTo("CORRECTION_CANCELLED");
    }
    @Test void correctionFailureIsSavedAfterRollbackAndDeduplicatedButValidationIsNot()throws Exception{
        var owner=notificationUser(UserRole.VIEWER);var date=LocalDate.of(2025,2,2);
        var c=attendance.requestCorrection(owner.getId(),new cc.ataglace.molebutter.attendance.api.AttendanceDtos.CorrectionRequest(date,null,null,date.atTime(9,0),date.atTime(18,0),List.of(),"누락"));
        var browser=login(owner);browser.operationId=UUID.randomUUID().toString();
        String route="/api/attendance/corrections/"+c.id()+"/cancel";
        var invalid=new cc.ataglace.molebutter.attendance.api.AttendanceDtos.ReviewRequest(999L,null);
        status(browser.post(route,invalid),409);status(browser.post(route,invalid),409);
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(2);
        assertThat(notifications.list(owner.getId(),null,20).items()).filteredOn(n->n.type().equals("OPERATION_FAILED")).hasSize(1);
        status(browser.get("/api/notifications?size=0"),400);status(browser.post(route,Map.of("revision",-1)),400);
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(2);
        attendance.cancel(owner.getId(),Long.parseLong(c.id()),new cc.ataglace.molebutter.attendance.api.AttendanceDtos.ReviewRequest(c.revision(),null));
        assertThat(notifications.list(owner.getId(),null,20).items().getFirst().type()).isEqualTo("CORRECTION_CANCELLED");
    }
    Browser login(User u)throws Exception{String password=u.getEmail().equals("admin@example.com")?"IntegrationAdmin123!":"Product123!";Browser b=new Browser();status(b.post("/api/auth/signin",Map.of("email",u.getEmail(),"password",password)),200);b.csrf=null;return b;}
    void status(HttpResponse<String> r,int expected){assertThat(r.statusCode()).withFailMessage("HTTP %s: %s",r.statusCode(),r.body()).isEqualTo(expected);}
    class Browser {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final Map<String, String> cookies = new HashMap<>();
        String csrf;String operationId;
        void csrf() throws Exception {
            var response = get("/api/auth/csrf");
            status(response, 200);
            csrf = json.readTree(response.body()).path("data").path("token").asText();
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> post(String path, Object body) throws Exception {
            if (csrf == null) csrf();
            var response = send("POST", path, body, csrf);
            if (response.statusCode() == 403 && json.readTree(response.body()).path("code").asText()
                    .equals("INVALID_CSRF_TOKEN")) {
                csrf();
                return send("POST", path, body, csrf);
            }
            return response;
        }
        HttpResponse<String> send(String method, String path, Object body, String csrfHeader) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            if (!cookies.isEmpty()) request.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            if (csrfHeader != null) request.header("X-XSRF-TOKEN", csrfHeader);
            if(operationId!=null)request.header("X-Operation-Id",operationId);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String value : response.headers().allValues("set-cookie")) {
                for (HttpCookie cookie : HttpCookie.parse(value)) {
                    if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                    else cookies.put(cookie.getName(), cookie.getValue());
                }
            }
            return response;
        }

        HttpResponse<String> upload(byte[] data,boolean token)throws Exception {
            if(token)csrf();String boundary="testBoundary123";
            var bytes=new java.io.ByteArrayOutputStream();bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").getBytes());bytes.write(data);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes());
            var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/products/imports")).header("Content-Type","multipart/form-data; boundary="+boundary).header("Cookie",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            if(token)r.header("X-XSRF-TOKEN",csrf);return client.send(r.POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
