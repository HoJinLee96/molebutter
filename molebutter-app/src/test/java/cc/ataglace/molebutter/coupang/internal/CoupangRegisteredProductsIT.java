package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoupangRegisteredProductsIT {
    static JdbcTemplate db;static DataSourceTransactionManager manager;
    final ObjectMapper json=new ObjectMapper();final String account="c".repeat(64);
    RegisteredProductStore store;WorkingCopyStore copies;DefaultCoupangRegisteredProducts service;
    CoupangCatalog catalog;CoupangProductSource source;SubmissionStore submissions;
    CoupangRecommendationSources recommendationSources;
    @BeforeAll static void database(){String base=System.getenv("MOLEBUTTER_TEST_DB_URL");Assumptions.assumeTrue(base!=null);
        String url=base.replace("/molebutter_test?","/molebutter_products_test?");
        Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").load().migrate();
        var ds=new DriverManagerDataSource(url,"test_migrator","isolated-test-migration-password");db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
    }
    @BeforeEach void setup(){
        for(String table:List.of("coupang_collection_candidate","coupang_collection_recent","coupang_product_asset_pin","coupang_product_read","coupang_product_snapshot","coupang_product_change","coupang_product_collection_item","coupang_product_collection","coupang_product_intent_base","coupang_registered_option","coupang_registered_product","coupang_product_working_copy","coupang_submission_account"))db.update("DELETE FROM "+table);
        store=new RegisteredProductStore(db,json,manager);copies=new WorkingCopyStore(db,json);catalog=mock(CoupangCatalog.class,withSettings().extraInterfaces(RecentProductCandidates.class));source=mock(CoupangProductSource.class);submissions=mock(SubmissionStore.class);
        var writer=mock(CoupangWriteGateway.class);when(writer.accountKey()).thenReturn(account);
        recommendationSources=mock(CoupangRecommendationSources.class);when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of());
        service=new DefaultCoupangRegisteredProducts(store,mock(BusinessAccess.class),catalog,source,writer,copies,submissions,mock(DefaultCoupangWorkingCopies.class),mock(DefaultCoupangSubmissions.class),mock(CoupangCollectionNotifications.class),new CoupangRecommendations(store,new DefaultCoupangPricing(db,mock(BusinessAccess.class)),recommendationSources),false);
        when(source.project(anyString(),anyMap(),anyMap())).thenAnswer(call->{var raw=json.readTree(call.getArgument(0,String.class));Map<String,CoupangCatalog.CurrentInventory> current=call.getArgument(1);Map<String,String> errors=call.getArgument(2);
            var options=new ArrayList<CoupangEditor.EditOption>();for(var o:raw.path("items")){String id=o.path("vendorItemId").asString();options.add(new CoupangEditor.EditOption(o.path("sellerProductItemId").asString(),id,"옵션",current.get(id),errors.get(id),true,List.of(),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("salePrice","100000"),new CoupangCatalog.Field("maximumBuyCount","9")),List.of()));}
            return new CoupangEditor.EditorDocument(new CoupangEditor.Basic(raw.path("sellerProductId").asString(),null,"테스트 상품","테스트 상품","모델","브랜드",null,"1","APPROVED"),new CoupangEditor.Limits(true,true,true),options,List.of(),List.of(),List.of());});
    }
    @AfterEach void close(){service.close();}
    CoupangCatalog.Product product(String id){return new CoupangCatalog.Product(id,"테스트 상품 "+id,"브랜드","APPROVED",Long.toString(Long.parseLong(id)+100000),"2026-10-16T10:00:00");}
    void summary(String id){var p=product(id);store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"SUMMARY",id,null,json.writeValueAsString(p),p,null,null,null));}
    void detail(String id,long price,long stock){var p=product(id);var items=new ArrayList<CoupangCatalog.ProductOption>();var rawItems=json.createArrayNode();
        for(int n=0;n<2;n++){String sid=id+"0"+n,vid=id+"1"+n;
            var raw=json.createObjectNode();raw.put("sellerProductItemId",sid);raw.put("vendorItemId",vid);raw.put("itemName","옵션 "+n);raw.put("externalVendorSku",n==0?"HJWA908BK":"OTHER");raw.put("outboundShippingTimeDay",n+1);raw.put("futureField","preserved");rawItems.add(raw);
            items.add(new CoupangCatalog.ProductOption(sid,vid,"옵션 "+n,null,null,List.of(new CoupangCatalog.Attribute("색상",n==0?"블랙":"화이트","EXPOSED")),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku",n==0?"HJWA908BK":"OTHER")),List.of()));
        }
        var raw=json.createObjectNode();raw.put("sellerProductId",id);raw.put("vendorId","A-fake");raw.put("sellerProductName",p.sellerProductName());raw.put("statusName","APPROVED");raw.set("items",rawItems);
        var d=new CoupangCatalog.ProductDetail(p,"노출","모델",null,"1",null,null,null,items,List.of(),List.of());
        store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"DETAIL",id,null,json.writeValueAsString(raw),p,d,null,null));
        for(var o:items){var c=new CoupangCatalog.CurrentInventory(o.vendorItemId(),price,stock,true);store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"INVENTORY",id,o.vendorItemId(),json.writeValueAsString(c),null,null,c,null));}
    }
    Search all(){return new Search(null,null,null,null,null,null,null,null,null);}
    void emptyLists(){when(catalog.products(anyLong(),eq(100),anyString(),any())).thenReturn(new CoupangCatalog.ProductPage(List.of(),"",false));}
    void complete(String id){detail(id,100000,70);when(catalog.product(1L,id)).thenAnswer(c->{detail(id,100000,70);return store.detail(store.find(account,id,false));});}
    RecentProductCandidates port(){return (RecentProductCandidates)catalog;}
    RecentProductCandidates.Candidate candidate(String id,String timestamp){return candidate(id,timestamp,"APPROVED");}
    RecentProductCandidates.Candidate candidate(String id,String timestamp,String status){var p=new CoupangCatalog.Product(id,"상품 "+id,"브랜드",status,null,timestamp);return new RecentProductCandidates.Candidate(p,json.writeValueAsString(p),BusinessIds.next(),java.time.Instant.now().toString());}
    Collect recent(String from,String to,int count){return new Collect(List.of(),"RECENT",from,to,count);}
    void collectDetails(){when(catalog.product(anyLong(),anyString())).thenAnswer(call->{String id=call.getArgument(1);detail(id,100000,70);return store.detail(store.find(account,id,false));});}
    @Test void recentFinishesBoundaryDayAndOnlyPublishesTheNewestNWithNumericTies(){
        detail("999",100000,70);long oldVersion=store.find(account,"999",false).row().version();
        when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(candidate("2","2026-10-16T10:00:00"),candidate("10","2026-10-16T10:00:00")),"next",true));
        when(port().candidates(1L,"2026-10-16","next")).thenAnswer(call->{assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_product",Long.class)).isEqualTo(1);return new RecentProductCandidates.Page(List.of(candidate("20","2026-10-16T11:00:00")),"",false);});collectDetails();
        var job=service.collect(1L,recent("2026-10-10","2026-10-16",2));service.runPending();var result=service.collection(1L,job.id());
        assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.listComplete()).isFalse();assertThat(result.discovered()).isEqualTo(2);assertThat(result.recent().apiPages()).isEqualTo(2);assertThat(result.recent().candidates()).isEqualTo(3);assertThat(result.recent().selected()).isEqualTo(2);
        assertThat(db.queryForList("SELECT seller_product_id FROM coupang_collection_candidate WHERE selected_rank IS NOT NULL ORDER BY selected_rank",String.class)).containsExactly("20","10");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_product",Long.class)).isEqualTo(3);assertThat(store.find(account,"999",false).row().version()).isEqualTo(oldVersion);assertThat(store.find(account,"999",false).row().deletionCandidate()).isFalse();
        verify(catalog,never()).products(anyLong(),anyInt(),anyString(),any());verify(catalog,never()).product(1L,"2");verify(port(),never()).candidates(eq(1L),eq("2026-10-15"),anyString());
    }
    @Test void recentCheckpointSurvivesCancellationAndResumeKeepsTheOriginalCutoff(){
        when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(candidate("1","2026-10-16T10:00:00")),"p2",true));
        when(port().candidates(1L,"2026-10-16","p2")).thenAnswer(call->{throw new CoupangFailure(CoupangFailure.Kind.RATE_LIMIT);});
        var job=service.collect(1L,recent("2026-10-15","2026-10-16",1));service.runPending();var paused=service.collection(1L,job.id());assertThat(paused.status()).isEqualTo("PAUSED");assertThat(paused.recent().apiPages()).isEqualTo(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_product",Long.class)).isZero();
        service.resume(1L,job.id());service.cancel(1L,job.id());assertThat(service.collection(1L,job.id()).status()).isEqualTo("CANCELLED");
        doReturn(new RecentProductCandidates.Page(List.of(candidate("3","2026-10-16T11:00:00")),"",false)).when(port()).candidates(1L,"2026-10-16","p2");collectDetails();service.resume(1L,job.id());service.runPending();
        var done=service.collection(1L,job.id());assertThat(done.status()).isEqualTo("SUCCEEDED");assertThat(done.recent().cutoffAt()).isEqualTo(paused.recent().cutoffAt());assertThat(done.completed()).isEqualTo(1);verify(port(),times(1)).candidates(1L,"2026-10-16","");verify(catalog).product(1L,"3");
    }
    @Test void recentCancelDuringResponseDoesNotCommitTheUncheckpointedPage(){
        when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(candidate("1","2026-10-16T10:00:00")),"p2",true));
        final String[] jobId={null};when(port().candidates(1L,"2026-10-16","p2")).thenAnswer(call->{service.cancel(1L,jobId[0]);return new RecentProductCandidates.Page(List.of(candidate("2","2026-10-16T11:00:00")),"",false);});
        jobId[0]=service.collect(1L,recent("2026-10-16","2026-10-16",1)).id();service.runPending();var stopped=service.collection(1L,jobId[0]);assertThat(stopped.status()).isEqualTo("CANCELLED");assertThat(stopped.recent().apiPages()).isEqualTo(1);assertThat(stopped.recent().candidates()).isEqualTo(1);assertThat(stopped.recent().selected()).isZero();
        doReturn(new RecentProductCandidates.Page(List.of(candidate("2","2026-10-16T11:00:00")),"",false)).when(port()).candidates(1L,"2026-10-16","p2");collectDetails();service.resume(1L,jobId[0]);service.runPending();verify(catalog).product(1L,"2");
    }
    @Test void recentExpiredLeaseAndPermissionFailureKeepTheSameCheckpoint(){
        when(port().candidates(1L,"2026-10-16","")).thenThrow(new CoupangFailure(CoupangFailure.Kind.PERMISSION));var job=service.collect(1L,recent("2026-10-16","2026-10-16",1));service.runPending();assertThat(service.collection(1L,job.id()).error()).isEqualTo("PERMISSION");
        db.update("UPDATE coupang_product_collection SET status='RUNNING',lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND,lease_owner='previous-process' WHERE id=?",Long.parseLong(job.id()));service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("INTERRUPTED");
        doReturn(new RecentProductCandidates.Page(List.of(),"",false)).when(port()).candidates(1L,"2026-10-16","");service.resume(1L,job.id());service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("SUCCEEDED");assertThat(service.collection(1L,job.id()).recent().selected()).isZero();
    }
    @Test void recentEmptyDaysAndFewerThanRequestedFinishNormally(){
        when(port().candidates(anyLong(),anyString(),anyString())).thenAnswer(call->new RecentProductCandidates.Page(call.getArgument(1).equals("2026-10-15")?List.of(candidate("1","2026-10-15T10:00:00")):List.of(),"",false));collectDetails();
        var job=service.collect(1L,recent("2026-10-14","2026-10-16",100));service.runPending();var done=service.collection(1L,job.id());assertThat(done.status()).isEqualTo("SUCCEEDED");assertThat(done.recent().apiPages()).isEqualTo(3);assertThat(done.completed()).isEqualTo(1);
    }
    @Test void recentZeroResultsAndDeletedOrAfterCutoffNeverPublishProducts(){
        when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(candidate("1","2026-10-16T10:00:00","DELETED"),candidate("2","2026-10-16T11:00:00")),"",false));
        var job=service.collect(1L,recent("2026-10-16","2026-10-16",100));db.update("UPDATE coupang_collection_recent SET cutoff_at='2026-10-16T10:30:00' WHERE job_id=?",Long.parseLong(job.id()));service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("SUCCEEDED");assertThat(service.collection(1L,job.id()).discovered()).isZero();verify(catalog,never()).product(anyLong(),anyString());assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_product",Long.class)).isZero();
    }
    @Test void recentRejectsInvalidDatesAndTokenLoopsWithoutAdvancingTheCheckpoint(){
        for(String scenario:List.of("missing","wrong-date","loop","conflict")){
            reset(port());var first=candidate("1","2026-10-16T10:00:00");
            when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(first),"p2",true));
            var bad=scenario.equals("missing")?candidate("2",null):scenario.equals("wrong-date")?candidate("2","2026-10-15T10:00:00"):scenario.equals("conflict")?candidate("1","2026-10-16T11:00:00"):first;
            when(port().candidates(1L,"2026-10-16","p2")).thenReturn(new RecentProductCandidates.Page(List.of(bad),scenario.equals("loop")?"p2":"",scenario.equals("loop")));
            var job=service.collect(1L,recent("2026-10-16","2026-10-16",1));service.runPending();var failed=service.collection(1L,job.id());assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.error()).startsWith("RECENT_");assertThat(failed.recent().apiPages()).isEqualTo(1);assertThat(failed.recent().selected()).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_product",Long.class)).isZero();
        }
    }
    @Test void recentDuplicateIdDoesNotConsumeTwoSlotsAndFailedDetailDoesNotBackfill(){
        var first=candidate("1","2026-10-16T10:00:00");when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(first,first,candidate("2","2026-10-16T09:00:00")),"",false));when(catalog.product(1L,"1")).thenThrow(new CoupangFailure(CoupangFailure.Kind.REJECTED));
        var job=service.collect(1L,recent("2026-10-16","2026-10-16",1));service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("PARTIAL");assertThat(service.collection(1L,job.id()).failed()).isEqualTo(1);verify(catalog,never()).product(1L,"2");
        doAnswer(call->{detail("1",100000,1);return store.detail(store.find(account,"1",false));}).when(catalog).product(1L,"1");service.resume(1L,job.id());service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("SUCCEEDED");verify(port(),times(1)).candidates(1L,"2026-10-16","");
    }
    @Test void recentSelectedSummaryCannotOverwriteANewerObservation(){
        var old=candidate("1","2026-10-16T10:00:00");summary("1");var newer=store.find(account,"1",false).row().version();
        when(port().candidates(1L,"2026-10-16","")).thenReturn(new RecentProductCandidates.Page(List.of(old),"",false));when(catalog.product(1L,"1")).thenThrow(new CoupangFailure(CoupangFailure.Kind.RATE_LIMIT));var job=service.collect(1L,recent("2026-10-16","2026-10-16",1));service.runPending();assertThat(store.find(account,"1",false).row().version()).isEqualTo(newer);
    }
    @Test void listHydratesTwentyOrHundredOptionsWithConstantQueryCountAndNoDetailPayloads() {
        var evidence=new CoupangRecommendationSources.Source("41",115170L,true,"2026-10-16T10:00:00",
            List.of(new CoupangRecommendationSources.Stock("s1","FREE",70,"AVAILABLE")),1);
        when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of("HJWA908BK",evidence,"OTHER",evidence));
        for(int n=1;n<=55;n++)detail(Integer.toString(n),150000,0);
        var original=db.getDataSource();var statements=new ArrayList<String>();
        db.setDataSource(new DelegatingDataSource(original) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection=super.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement"))statements.add((String)args[0]);
                    if(method.getName().equals("createStatement"))statements.add("<statement>");
                    try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }
        });
        try {
            for(String group:List.of("ALL","RECOMMENDED")) {
                var query=new Search(null,null,null,null,null,null,null,null,null,group,"CREATED_DESC");
                statements.clear();long begin=System.nanoTime();var small=service.list(1L,query,false,0,20);long smallTime=System.nanoTime()-begin;int smallCount=statements.size();
                statements.clear();begin=System.nanoTime();var large=service.list(1L,query,false,0,100);long largeTime=System.nanoTime()-begin;int largeCount=statements.size();
                assertThat(small.items()).hasSize(20);assertThat(large.items()).hasSize(100);assertThat(large.counts()).isEqualTo(small.counts());
                assertThat(small.items()).isEqualTo(large.items().subList(0,20));
                assertThat(largeCount).isEqualTo(smallCount).isLessThanOrEqualTo(6);
                assertThat(statements).noneMatch(sql->sql.contains("SELECT * FROM coupang_registered_product")||sql.contains("raw_detail_json"));
                assertThat(large.totalElements()).isEqualTo(110);assertThat(large.counts().recommended()).isEqualTo(110);
                // The old mapper's full product read must agree with the lightweight projection.
                for(var row:large.items()) {
                    var old=store.find(account,row.product().sellerProductId(),false).row();
                    assertThat(row.product()).isEqualTo(old.product());assertThat(row.version()).isEqualTo(old.version());
                    assertThat(row.lastSuccessAt()).isEqualTo(old.lastSuccessAt());assertThat(row.lastAttemptAt()).isEqualTo(old.lastAttemptAt());
                    assertThat(row.error()).isEqualTo(old.error());assertThat(row.detailCollected()).isEqualTo(old.detailCollected());
                    assertThat(row.deleted()).isEqualTo(old.deleted());assertThat(row.deletionCandidate()).isEqualTo(old.deletionCandidate());
                }
                System.out.printf("LIST_COST group=%s rows=20/%s SQL=%s/%s timeMs=%.2f/%.2f%n",group,large.items().size(),smallCount,largeCount,smallTime/1e6,largeTime/1e6);
            }
        } finally {db.setDataSource(original);}
        verifyNoInteractions(catalog);
    }
    @Test void threeHundredProductsAreCollectedOnceWithSeparateOptionsAndSearchesAreLocal(){
        when(catalog.products(anyLong(),eq(100),anyString(),any())).thenAnswer(call->{var search=call.getArgument(3,CoupangCatalog.ProductSearch.class);if(search.status()!=null)return new CoupangCatalog.ProductPage(List.of(),"",false);
            int page=call.getArgument(2,String.class).isEmpty()?0:Integer.parseInt(call.getArgument(2,String.class));var products=new ArrayList<CoupangCatalog.Product>();
            for(int n=page*100+1;n<=Math.min(300,(page+1)*100);n++){String id=Integer.toString(n);summary(id);products.add(product(id));}
            return new CoupangCatalog.ProductPage(products,page<2?Integer.toString(page+1):"",page<2);});
        when(catalog.product(anyLong(),anyString())).thenAnswer(call->{String id=call.getArgument(1);detail(id,100000,70);return store.detail(store.find(account,id,false));});
        var job=service.collect(1L,new Collect(List.of()));service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_product",Long.class)).isEqualTo(300);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_registered_option",Long.class)).isEqualTo(600);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_working_copy",Long.class)).isZero();
        long versions=db.queryForObject("SELECT COUNT(*) FROM coupang_product_snapshot",Long.class);
        service.collect(1L,new Collect(List.of()));service.runPending();assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_snapshot",Long.class)).isEqualTo(versions);
        clearInvocations(catalog);
        var found=service.list(1L,new Search(null,"브랜드","테스트","908",70L,"EQ","ON_SALE",1,"LE"),false,0,20);
        assertThat(found.productCount()).isEqualTo(300);assertThat(found.totalElements()).isEqualTo(300);
        assertThat(service.list(1L,new Search("100001",null,null,null,null,null,null,null,null),false,0,20).totalElements()).isEqualTo(2);
        assertThat(service.list(1L,new Search(null,null,null,"908",71L,"GE",null,null,null),false,0,20).totalElements()).isZero();
        assertThat(service.brands(1L)).containsExactly("브랜드");service.get(1L,"1");service.history(1L,"1",0,20);verifyNoInteractions(catalog);
    }
    @Test void optionLinksReadTheCurrentAccountsExposureIdWithoutExternalCallsOrGuessing(){
        detail("1",100000,10);
        db.update("UPDATE coupang_registered_product SET product_json=JSON_SET(product_json,'$.productId','9550561343') WHERE seller_product_id='1'");
        db.update("UPDATE coupang_registered_option SET vendor_item_id='96040603660' WHERE seller_product_item_id='100'");
        assertThat(service.optionProductUrl(1L,"96040603660")).isEqualTo("https://www.coupang.com/vp/products/9550561343?vendorItemId=96040603660");
        assertThatThrownBy(()->service.optionProductUrl(1L,"missing")).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->service.optionProductUrl(1L,"999")).isInstanceOf(InputValidationFailure.class);
        detail("2",100000,10);db.update("UPDATE coupang_registered_option SET vendor_item_id='96040603660' WHERE seller_product_item_id='200'");
        assertThatThrownBy(()->service.optionProductUrl(1L,"96040603660")).isInstanceOf(InputValidationFailure.class);
        db.update("UPDATE coupang_registered_product SET account_key=? WHERE seller_product_id='2'","d".repeat(64));
        assertThat(service.optionProductUrl(1L,"96040603660")).contains("/9550561343?vendorItemId=");
        db.update("UPDATE coupang_registered_product SET product_json=JSON_REMOVE(product_json,'$.productId') WHERE seller_product_id='1'");
        assertThatThrownBy(()->service.optionProductUrl(1L,"96040603660")).isInstanceOf(InputValidationFailure.class);
        verifyNoInteractions(catalog,source,recommendationSources);
    }
    @Test void recommendationsCountWholeAccountBeforeSearchAndPagingAndReevaluateWithoutExternalCalls(){
        var evidence=new CoupangRecommendationSources.Source("41",115170L,true,"2026-10-16T10:00:00",List.of(new CoupangRecommendationSources.Stock("s1","FREE",70,"AVAILABLE")),1);
        when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of("HJWA908BK",evidence,"OTHER",evidence));
        for(int n=1;n<=12;n++)detail(Integer.toString(n),150000,0);
        db.update("UPDATE coupang_registered_product SET created_at_external=CONCAT('2026-10-',LPAD(seller_product_id,2,'0'),'T10:00:00')");
        db.update("UPDATE coupang_registered_option SET on_sale=FALSE WHERE seller_product_item_id='101'");
        db.update("UPDATE coupang_registered_product SET deleted=TRUE WHERE seller_product_id='2'");
        var query=new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_DESC");
        var first=service.list(1L,query,false,0,10);assertThat(first.counts().recommended()).isEqualTo(21);assertThat(first.totalElements()).isEqualTo(21);assertThat(first.totalPages()).isEqualTo(3);assertThat(first.items()).hasSize(10);
        assertThat(first.items().getFirst().product().sellerProductId()).isEqualTo("12");assertThat(first.items().getFirst().recommendation().reasons()).containsExactly("PRICE_HIGH","RESTOCK");
        var last=service.list(1L,query,false,2,10);assertThat(last.items()).hasSize(1);assertThat(last.items().getFirst().product().sellerProductId()).isEqualTo("1");
        var searched=service.list(1L,new Search(null,"브랜드",null,"HJWA",null,null,null,null,null,"RECOMMENDED","CREATED_ASC"),false,0,10);
        assertThat(searched.counts().recommended()).isEqualTo(21);assertThat(searched.totalElements()).isEqualTo(11);assertThat(searched.items().getFirst().product().sellerProductId()).isEqualTo("1");
        // Resolve just one option. Counts shrink and unrelated option recommendations remain.
        db.update("UPDATE coupang_registered_option SET current_price=147000,current_stock=1 WHERE seller_product_item_id='1200'");
        assertThat(service.list(1L,query,false,0,10).counts().recommended()).isEqualTo(20);
        when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of());assertThat(service.list(1L,query,false,0,10).totalElements()).isZero();
        when(recommendationSources.byCodes(anyLong(),anySet())).thenThrow(new IllegalStateException("source read failed"));
        assertThatThrownBy(()->service.list(1L,query,false,0,10)).hasMessage("source read failed");
        verifyNoInteractions(catalog);verify(recommendationSources,atLeastOnce()).byCodes(1L,Set.of("HJWA908BK","OTHER"));
    }
    @Test void recommendationTypesHaveGlobalOverlappingCountsAndFilterBeforePaging(){
        var evidence=new CoupangRecommendationSources.Source("41",115170L,true,"2026-10-16T10:00:00",List.of(new CoupangRecommendationSources.Stock("s1","FREE",70,"AVAILABLE")),1);
        when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of("HJWA908BK",evidence,"OTHER",evidence));
        for(int n=1;n<=15;n++)detail(Integer.toString(n),n<=5?150000:n<=10?100000:143000,n>5&&n<=10?50:0);
        db.update("UPDATE coupang_registered_product SET created_at_external=CONCAT('2026-10-',LPAD(seller_product_id,2,'0'),'T10:00:00')");
        var query=new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_DESC","RESTOCK");
        var first=service.list(1L,query,false,0,10);assertThat(first.counts().recommended()).isEqualTo(30);assertThat(first.counts().recommendationReasons()).isEqualTo(new RecommendationCounts(10,10,20));
        assertThat(first.totalElements()).isEqualTo(20);assertThat(first.totalPages()).isEqualTo(2);assertThat(first.items().getFirst().product().sellerProductId()).isEqualTo("15");
        assertThat(first.items()).allMatch(row->row.recommendation().reasons().contains("RESTOCK"));
        var last=service.list(1L,query,false,1,10);assertThat(last.items()).hasSize(10);assertThat(last.items().getFirst().product().sellerProductId()).isEqualTo("5");
        var search=service.list(1L,new Search(null,null,null,"HJWA",null,null,null,null,null,"RECOMMENDED","CREATED_ASC","RESTOCK"),false,0,10);
        assertThat(search.totalElements()).isEqualTo(10);assertThat(search.items().getFirst().product().sellerProductId()).isEqualTo("1");assertThat(search.counts()).isEqualTo(first.counts());
        var priceQuery=new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_ASC","PRICE_HIGH");
        assertThat(service.list(1L,priceQuery,false,0,10).items()).hasSize(10).allMatch(row->row.recommendation().reasons().containsAll(List.of("PRICE_HIGH","RESTOCK")));
        db.update("UPDATE coupang_registered_option SET current_price=147000 WHERE seller_product_item_id='100'");
        var changed=service.list(1L,priceQuery,false,0,10);assertThat(changed.totalElements()).isEqualTo(9);assertThat(changed.counts().recommended()).isEqualTo(30);assertThat(changed.counts().recommendationReasons()).isEqualTo(new RecommendationCounts(9,10,20));
        db.update("UPDATE coupang_registered_option SET current_stock=1 WHERE seller_product_item_id='1500'");
        assertThat(service.list(1L,query,false,0,10).counts().recommended()).isEqualTo(29);
        assertThat(service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_ASC","NEGATIVE_MARGIN"),false,0,10).totalElements()).isEqualTo(10);
        assertThatThrownBy(()->service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"ALL","CREATED_DESC","RESTOCK"),false,0,10)).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_DESC","BAD"),false,0,10)).isInstanceOf(InputValidationFailure.class);
        verifyNoInteractions(catalog,source);
    }
    @Test void missingListingCountsAcrossTheAccountBeforeFilteringAndLeavesAfterStockZero() {
        var missing=new CoupangRecommendationSources.Source("41",46820L,true,null,List.of(new CoupangRecommendationSources.Stock("old","FREE",1,"AVAILABLE")),1,"SELECTED_MISSING",true,"2026-10-20T10:00:00",0L,0,"SELECTED_LISTING_MISSING");
        when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of("HJWA908BK",missing));
        for(int n=1;n<=15;n++)detail(Integer.toString(n),62000,49);
        var query=new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_ASC","MARK_SOLD_OUT");
        var first=service.list(1L,query,false,0,10);assertThat(first.totalElements()).isEqualTo(15);assertThat(first.counts().recommendationReasons().markSoldOut()).isEqualTo(15);assertThat(first.items()).hasSize(10);
        assertThat(first.items()).allSatisfy(row->{assertThat(row.recommendation().stockProposal().quantity()).isZero();assertThat(row.recommendation().reasons()).containsExactly("MARK_SOLD_OUT");assertThat(row.recommendation().availabilityReason()).isEqualTo("SELECTED_LISTING_MISSING");});
        var searched=service.list(1L,new Search("1",null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_ASC","MARK_SOLD_OUT"),false,0,10);
        assertThat(searched.counts().recommended()).isEqualTo(15);
        assertThat(service.list(1L,all(),false,0,10).items()).anyMatch(row->row.recommendation()!=null&&"SELECTED_LISTING_MISSING".equals(row.recommendation().availabilityReason()));
        db.update("UPDATE coupang_registered_option SET current_stock=0 WHERE seller_product_item_id='100'");
        assertThat(service.list(1L,query,false,0,10).counts().recommendationReasons().markSoldOut()).isEqualTo(14);
        verifyNoInteractions(catalog);
    }
    @Test void quickHistoryFiltersBeforePagingAndDoesNotCallExternalApisOrWriteRows(){
        summary("1");detail("1",100000,70);
        for(int n=1;n<=25;n++) {
            for(String vendor:List.of("110","111")) {
                var current=new CoupangCatalog.CurrentInventory(vendor,100000L+n*1000,70L,true);
                store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"INVENTORY","1",vendor,json.writeValueAsString(current),null,null,current,null));
            }
        }
        long before=db.queryForObject("SELECT COUNT(*) FROM coupang_product_change",Long.class);
        var first=service.optionHistory(1L,"1","110","PRICE",0,20);var last=service.optionHistory(1L,"1","110","PRICE",1,20);
        assertThat(first.totalElements()).isEqualTo(26);assertThat(first.totalPages()).isEqualTo(2);assertThat(first.items()).hasSize(20);assertThat(last.items()).hasSize(6);
        assertThat(first.items().getFirst().after()).isEqualTo(125000);assertThat(last.items().getLast().after()).isEqualTo(100000);
        assertThat(first.items()).extracting(OptionEvent::id).doesNotContainAnyElementsOf(last.items().stream().map(OptionEvent::id).toList());
        assertThat(service.optionHistory(1L,"1","110","STOCK",0,20).totalElements()).isEqualTo(1);
        assertThatThrownBy(()->service.optionHistory(1L,"1","999","PRICE",0,20)).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->service.optionHistory(1L,"1","110","UNKNOWN",0,20)).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->service.optionHistory(1L,"1","110","PRICE",-1,20)).isInstanceOf(InputValidationFailure.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM coupang_product_change",Long.class)).isEqualTo(before);verifyNoInteractions(catalog,source);
    }
    @Test void failuresRetainValuesAndRetryOnlyFailedItems(){summary("1");summary("2");detail("1",100000,70);detail("2",100000,70);var reads=new AtomicInteger();
        when(catalog.product(1L,"1")).thenAnswer(c->{reads.incrementAndGet();detail("1",110000,65);return store.detail(store.find(account,"1",false));});
        when(catalog.product(1L,"2")).thenThrow(new CoupangFailure(CoupangFailure.Kind.UPSTREAM));
        var job=service.collect(1L,new Collect(List.of("1","2")));service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("PARTIAL");
        assertThat(service.get(1L,"2").detail().items().getFirst().current().amountInStock()).isEqualTo(70);assertThat(service.get(1L,"2").state().deleted()).isFalse();
        doAnswer(c->{detail("2",120000,60);return store.detail(store.find(account,"2",false));}).when(catalog).product(1L,"2");service.resume(1L,job.id());service.runPending();
        assertThat(service.collection(1L,job.id()).status()).isEqualTo("SUCCEEDED");assertThat(reads).hasValue(1);
    }
    @Test void onlyCompleteFullCollectionWithExplicitAbsenceCreatesCandidate(){summary("1");detail("1",100000,70);emptyLists();when(catalog.product(1L,"1")).thenThrow(new CoupangFailure(CoupangFailure.Kind.UPSTREAM));
        var job=service.collect(1L,new Collect(List.of()));service.runPending();assertThat(service.get(1L,"1").state().deletionCandidate()).isFalse();
        doThrow(new CoupangFailure(CoupangFailure.Kind.PRODUCT_ABSENT)).when(catalog).product(1L,"1");service.collect(1L,new Collect(List.of("1")));service.runPending();assertThat(service.get(1L,"1").state().deletionCandidate()).isFalse();
        service.collect(1L,new Collect(List.of()));service.runPending();var candidate=service.get(1L,"1");assertThat(candidate.state().deletionCandidate()).isTrue();assertThat(candidate.state().deleted()).isFalse();
        assertThatThrownBy(()->service.confirmDeletion(1L,"1",new DeleteConfirmation(candidate.state().version()-1,true))).isInstanceOf(InputValidationFailure.class);
        var deleted=service.confirmDeletion(1L,"1",new DeleteConfirmation(candidate.state().version(),true));assertThat(deleted.state().deleted()).isTrue();assertThat(service.list(1L,all(),false,0,20).totalElements()).isZero();
        assertThat(service.list(1L,all(),true,0,20).totalElements()).isEqualTo(2);detail("1",100000,70);assertThat(service.get(1L,"1").state().deleted()).isFalse();
    }
    @Test void interruptedAndInvalidPaginationNeverInferDeletion(){summary("1");detail("1",100000,70);
        when(catalog.products(anyLong(),eq(100),anyString(),any())).thenReturn(new CoupangCatalog.ProductPage(List.of(),"repeat",true));
        var job=service.collect(1L,new Collect(List.of()));service.runPending();assertThat(service.collection(1L,job.id()).status()).isEqualTo("PAUSED");assertThat(service.get(1L,"1").state().deletionCandidate()).isFalse();
        var queued=service.collect(1L,new Collect(List.of("1")));assertThat(service.cancel(1L,queued.id()).status()).isEqualTo("CANCELLED");assertThat(service.get(1L,"1").state().deleted()).isFalse();
    }
    @Test void observationsAreVersionedWithoutDuplicatesAndOldResponsesCannotOverwrite(){summary("1");detail("1",100000,70);long before=service.get(1L,"1").state().version();long order=BusinessIds.next();
        var newer=new CoupangCatalog.CurrentInventory("110",130000L,9L,false);
        store.record(new CoupangObservations.Read(account,1L,order,"INVENTORY","1","110",json.writeValueAsString(newer),null,null,newer,null));
        var old=new CoupangCatalog.CurrentInventory("110",120000L,2L,true);store.record(new CoupangObservations.Read(account,1L,order-1,"INVENTORY","1","110",json.writeValueAsString(old),null,null,old,null));
        assertThat(service.get(1L,"1").detail().items().getFirst().current()).isEqualTo(newer);
        store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"INVENTORY_ERROR","1","110",null,null,null,null,"TIMEOUT"));assertThat(service.get(1L,"1").detail().items().getFirst().current()).isEqualTo(newer);
        assertThat(service.get(1L,"1").state().version()).isGreaterThan(before);
        assertThat(RegisteredProductStore.canonical(json.readTree("{\"a\":1,\"b\":null}"))).isEqualTo(RegisteredProductStore.canonical(json.readTree("{\"b\":null,\"a\":1}")));
    }
    @Test void firstEditBaselineSurvivesRefreshAndConflictChoicesAreVersionChecked(){summary("1");detail("1",100000,70);
        var mapping=new CoupangWriteGateway.Mapping(account,"1",List.of(new CoupangWriteGateway.OptionMapping("00000000-0000-4000-8000-000000000001","100","110"),new CoupangWriteGateway.OptionMapping("00000000-0000-4000-8000-000000000002","101","111")));
        var editor=source.project(store.find(account,"1",false).raw(),Map.of("110",new CoupangCatalog.CurrentInventory("110",100000L,70L,true),"111",new CoupangCatalog.CurrentInventory("111",100000L,70L,true)),Map.of());
        var baseline=CoupangInputs.from(editor,mapping);var change=new CoupangEditing.Change("options.registration.salePrice","00000000-0000-4000-8000-000000000001","120000");
        var copy=store.tx.execute(t->{var c=copies.insert(1L,account,"1",CoupangInputs.apply(json,baseline,List.of(change)),List.of(change),mapping,editor);store.saved(c,baseline,1L);return c;});
        db.update("DELETE FROM coupang_product_intent_base WHERE working_copy_id=?",Long.parseLong(copy.id()));
        var missing=service.get(1L,"1");assertThat(missing.conflicts().getFirst().state()).isEqualTo("BASELINE_MISSING");
        service.resolve(1L,"1",new Resolve(missing.state().version(),missing.revision(),missing.conflicts().getFirst().key(),"LOCAL"));
        detail("1",130000,70);var conflict=service.get(1L,"1");assertThat(conflict.conflicts().getFirst().state()).isEqualTo("CONFLICT");assertThat(conflict.conflicts().getFirst().baseline().toString()).contains("100000");
        assertThatThrownBy(()->service.resolve(1L,"1",new Resolve(conflict.state().version()-1,conflict.revision(),conflict.conflicts().getFirst().key(),"COUPANG"))).isInstanceOf(InputValidationFailure.class);
        var chosen=service.resolve(1L,"1",new Resolve(conflict.state().version(),conflict.revision(),conflict.conflicts().getFirst().key(),"LOCAL"));assertThat(chosen.conflicts().getFirst().state()).isEqualTo("KEEP_LOCAL");
        detail("1",140000,70);assertThat(service.get(1L,"1").conflicts().getFirst().state()).isEqualTo("CONFLICT");
        var current=service.get(1L,"1");service.resolve(1L,"1",new Resolve(current.state().version(),current.revision(),current.conflicts().getFirst().key(),"COUPANG"));assertThat(service.get(1L,"1").conflicts()).isEmpty();
    }

    void inventory(String product,String option,long stock,boolean onSale){
        var current=new CoupangCatalog.CurrentInventory(option,100000L,stock,onSale);
        store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"INVENTORY",product,option,json.writeValueAsString(current),null,null,current,null));
    }
    @Test void registrationDateSortsBeforePaginationWithUnknownDatesLastAndStableOptions(){
        for(String product:List.of("1","2","3","4","5","6"))summary(product);
        detail("2",100000,1);
        db.update("UPDATE coupang_registered_product SET created_at_external=CASE seller_product_id WHEN '1' THEN '2026-10-17T10:00:00' WHEN '2' THEN '2026-10-01T09:00:00' WHEN '3' THEN '2026-10-17T10:00:00' WHEN '4' THEN NULL WHEN '5' THEN '' ELSE '   ' END");
        var descending=service.list(1L,all(),false,0,10);
        assertThat(descending.items()).extracting(row->row.product().sellerProductId()).containsExactly("3","1","2","2","6","5","4");
        assertThat(descending.items().subList(2,4)).extracting(row->row.option().sellerProductItemId()).containsExactly("200","201");
        var ascending=new Search(null,null,null,null,null,null,null,null,null,"ALL","CREATED_ASC");
        assertThat(service.list(1L,ascending,false,0,10).items()).extracting(row->row.product().sellerProductId()).containsExactly("2","2","3","1","6","5","4");
        for(int n=7;n<=12;n++)summary(Integer.toString(n));
        assertThat(service.list(1L,all(),false,1,10).items()).extracting(row->row.product().sellerProductId()).containsExactly("6","5","4");
        assertThat(service.list(1L,new Search("2",null,null,null,null,null,null,null,null,"ALL","CREATED_ASC"),false,0,10).totalElements()).isEqualTo(2);
        assertThatThrownBy(()->service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"ALL","bad"),false,0,10)).isInstanceOf(InputValidationFailure.class);
        verifyNoInteractions(catalog);
    }

    @Test void cardsCountAllAccountOptionRowsIndependentlyOfSearchAndUseSameOptionForConditions(){
        summary("1");detail("1",100000,1);inventory("1","111",0,true);
        summary("2");detail("2",100000,0);inventory("2","210",0,false);inventory("2","211",99,false);
        summary("3");detail("3",100000,1);
        db.update("UPDATE coupang_registered_option SET current_json=NULL,current_stock=NULL,on_sale=NULL WHERE product_id=(SELECT id FROM coupang_registered_product WHERE seller_product_id='3')");
        summary("4");summary("5");detail("5",100000,1);db.update("UPDATE coupang_registered_product SET deleted=TRUE WHERE seller_product_id='5'");
        db.update("UPDATE coupang_registered_option SET active=FALSE WHERE seller_product_item_id='301'");
        store.record(new CoupangObservations.Read("d".repeat(64),1L,BusinessIds.next(),"SUMMARY","6",null,json.writeValueAsString(product("6")),product("6"),null,null,null));
        store.record(new CoupangObservations.Read(account,1L,BusinessIds.next(),"INVENTORY_ERROR","1","110",null,null,null,null,"TIMEOUT"));
        var expected=new Counts(6,1,1,2,2);
        assertThat(service.list(1L,all(),false,0,20).counts()).isEqualTo(expected);
        assertThat(service.list(1L,new Search(null,null,null,"908",null,null,null,null,null,"SELLING"),false,0,20).totalElements()).isEqualTo(1);
        assertThat(service.list(1L,new Search(null,null,null,"OTHER",null,null,null,null,null,"SELLING"),false,0,20).totalElements()).isZero();
        assertThat(service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"SOLD_OUT"),false,0,20).totalElements()).isEqualTo(1);
        assertThat(service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"STOPPED"),false,0,20).totalElements()).isEqualTo(2);
        var empty=service.list(1L,new Search(null,null,"없는 이름",null,null,null,null,null,null),false,0,20);
        assertThat(empty.totalElements()).isZero();assertThat(empty.counts()).isEqualTo(expected);
        assertThat(service.list(1L,all(),true,0,20).counts()).isEqualTo(expected);
        assertThat(service.list(1L,all(),true,0,20).totalElements()).isEqualTo(2);
        assertThatThrownBy(()->service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"bad"),false,0,20)).isInstanceOf(InputValidationFailure.class);
        verifyNoInteractions(catalog);
    }

    @Test void unavailableSupplierCountsBothStockReasonsBeforePagingAndNeverShowsPriceReasons(){
        var zero=new CoupangRecommendationSources.Source("41",115170L,true,null,List.of(),1,"UNAVAILABLE",true,"2026-10-15T10:00:00",0L,0);
        var owned=new CoupangRecommendationSources.Source("42",115170L,true,null,List.of(),1,"UNAVAILABLE",false,null,5L,1);
        when(recommendationSources.byCodes(anyLong(),anySet())).thenReturn(Map.of("HJWA908BK",zero,"OTHER",owned));
        for(int n=1;n<=12;n++)detail(Integer.toString(n),100000,50);
        var mark=service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_DESC","MARK_SOLD_OUT"),false,0,10);
        assertThat(mark.totalElements()).isEqualTo(12);assertThat(mark.counts().recommendationReasons()).isEqualTo(new RecommendationCounts(0,0,0,12,12));assertThat(mark.counts().recommended()).isEqualTo(24);
        assertThat(mark.items()).allSatisfy(r->{assertThat(r.recommendation().reasons()).containsExactly("MARK_SOLD_OUT");assertThat(r.recommendation().stockProposal().quantity()).isZero();});
        var stock=service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_ASC","OWNED_STOCK_MISMATCH"),false,1,10);assertThat(stock.items()).hasSize(2).allSatisfy(r->assertThat(r.recommendation().stockProposal().selectionRequired()).isTrue());
        db.update("UPDATE coupang_registered_option SET current_stock=0 WHERE sku='HJWA908BK'");
        var resolved=service.list(1L,new Search(null,null,null,null,null,null,null,null,null,"RECOMMENDED","CREATED_DESC","MARK_SOLD_OUT"),false,0,10);assertThat(resolved.totalElements()).isZero();assertThat(resolved.counts().recommended()).isEqualTo(12);
        verifyNoInteractions(catalog,source);
    }
}
