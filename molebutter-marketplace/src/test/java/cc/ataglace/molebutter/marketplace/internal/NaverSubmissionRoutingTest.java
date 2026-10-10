package cc.ataglace.molebutter.marketplace.internal;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSubmissionRoutingTest {
    final BusinessAccess access=mock(BusinessAccess.class);
    final MarketplaceDrafts drafts=mock(MarketplaceDrafts.class);
    final DraftStore draftStore=mock(DraftStore.class);
    final SubmissionStore store=mock(SubmissionStore.class);
    final DefaultMarketplaceEditing editing=mock(DefaultMarketplaceEditing.class);
    final MarketplaceWriteGateway coupang=mock(MarketplaceWriteGateway.class),naver=mock(MarketplaceWriteGateway.class);
    final String account=DefaultMarketplaceSubmissions.account("NAVER:app:SELF");
    final PlatformTransactionManager manager=new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}protected void doRollback(DefaultTransactionStatus s){}
    };
    DefaultMarketplaceSubmissions service;
    @BeforeEach void setup(){when(coupang.market()).thenReturn("COUPANG");when(naver.market()).thenReturn("NAVER");when(naver.accountKey()).thenReturn(account);when(naver.projectChanges(any(),any())).thenAnswer(i->i.getArgument(0));when(store.byKey(anyString())).thenReturn(null);when(store.byPreview(anyLong())).thenReturn(null);service=new DefaultMarketplaceSubmissions(access,drafts,draftStore,store,editing,List.of(coupang,naver),manager,"vendor",false);clearInvocations(coupang,naver);}
    @AfterEach void close(){service.close();}
    MarketplaceDrafts.Document document(){
        var input=new NaverEditor.Input(Map.of("originProduct.name","원상품"),"NONE",List.of(),List.of(),List.of(),"");
        var naver=new MarketplaceDrafts.Naver("","SALE","NEW","00","","",List.of(),List.of(),null,false,"ON",input);
        return new MarketplaceDrafts.Document("10",2L,new MarketplaceDrafts.Common("","원상품","","","","","","","","",""),List.of(),MarketplaceDrafts.StockMode.OPTION,"",List.of(),new MarketplaceDrafts.Media(List.of(),List.of()),null,List.of(MarketplaceDrafts.Market.NAVER),Map.of(MarketplaceDrafts.Market.NAVER,new MarketplaceDrafts.MarketConfig("",null,null,naver,null)));
    }
    MarketplaceWriteGateway.Mapping mapping(){return new MarketplaceWriteGateway.Mapping(account,"123",List.of(),"456");}
    MarketplaceWriteGateway.Prepared prepared(){var d=document();return new MarketplaceWriteGateway.Prepared(account,mapping(),List.of(new MarketplaceWriteGateway.Step("product",MarketplaceWriteGateway.Type.PRODUCT,null,"PUT","/v2/products/origin-products/123","","{}","{}","{}")),List.of(),Instant.now(),List.of(),new MarketplaceWriteGateway.EditIntent(d,List.of()),"NAVER");}
    SubmissionStore.Job job(String action){var p=prepared();return new SubmissionStore.Job(30,10,2,1L,"owner",p,p.steps().getFirst(),action,mapping(),new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.UNKNOWN,mapping(),"INTERRUPTED",null,Instant.now()),40);}
    @Test void prepareSelectsNaverWriterAndPinsNativeAssetsWithMarketPreserved(){
        var d=document();var session=new MarketplaceEditing.Session("20","10",2,Instant.now().plusSeconds(600).toString(),List.of(new MarketplaceEditing.Target("NAVER","UPDATE","READY",null,null,d)));when(editing.require(1L,"20",true)).thenReturn(new EditingStore.Stored(1L,account,mapping(),session,false));when(draftStore.find(10,false)).thenReturn(d);
        when(naver.prepareSelected(eq(1L),eq(d),eq(mapping()),eq(false),eq(d),eq(List.of()))).thenReturn(prepared());
        var result=service.prepare(1L,new MarketplaceEditing.PrepareRequest("10",2L,"20",false,List.of(new MarketplaceEditing.TargetChanges("NAVER",List.of()))));
        assertThat(result.executable()).isTrue();assertThat(result.targets().getFirst().market()).isEqualTo("NAVER");preparedSnapshotFrom(result);
        verifyNoInteractions(coupang);verify(drafts,never()).validate(any(),any());verify(naver,never()).execute(any(),any(),any(),any());
    }
    // A capture helper keeps timestamp identity out of the comparison with the prepared object.
    private MarketplaceWriteGateway.Prepared preparedSnapshotFrom(MarketplaceSubmissions.Preview preview){var capture=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Prepared.class);verify(store).completePreview(anyLong(),capture.capture(),eq(preview));assertThat(capture.getValue().market()).isEqualTo("NAVER");return capture.getValue();}
    @Test void workerRoutesNativeWriteAndReconcileToNaverOnly(){
        var write=job("WRITE");when(store.claim(anyString())).thenReturn(write);when(store.owns(write)).thenReturn(true);var expected=new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.CONFIRMED,mapping(),"SUCCESS",null,Instant.now());when(naver.execute(eq(1L),eq(write.prepared()),eq(write.step()),eq(mapping()),any())).thenAnswer(i->{java.util.function.Consumer<MarketplaceWriteGateway.Step> record=i.getArgument(4);record.accept(write.step());return expected;});
        service.runPending();verify(store).recordRequest(write,write.step());verify(store).finish(write,expected);verifyNoInteractions(coupang);
        var reconcile=job("RECONCILE");when(store.claim(anyString())).thenReturn(reconcile);when(store.owns(reconcile)).thenReturn(true);when(naver.reconcile(1L,reconcile.prepared(),reconcile.step(),reconcile.previous())).thenReturn(expected);service.runPending();verify(naver).reconcile(1L,reconcile.prepared(),reconcile.step(),reconcile.previous());verify(store).finish(reconcile,expected);verifyNoInteractions(coupang);
    }
    @Test void changedNaverAccountBlocksDispatch(){
        var job=job("WRITE");when(store.claim(anyString())).thenReturn(job);when(naver.accountKey()).thenReturn(DefaultMarketplaceSubmissions.account("OTHER"));service.runPending();var result=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store).finish(eq(job),result.capture());assertThat(result.getValue().state()).isEqualTo(MarketplaceWriteGateway.State.FAILED);assertThat(result.getValue().code()).isEqualTo("ACCOUNT_CHANGED");verify(naver,never()).execute(any(),any(),any(),any(),any());verifyNoInteractions(coupang);
    }
    @Test void legacyPreparedAndMappingJsonKeepCoupangDefaults(){
        var legacy="{\"accountKey\":\"account\",\"mapping\":{\"accountKey\":\"account\",\"sellerProductId\":\"123\",\"options\":[]},\"steps\":[],\"changes\":[],\"preparedAt\":\"2026-10-08T00:00:00Z\",\"expectedSkus\":[]}";
        var restored=new ObjectMapper().readValue(legacy,MarketplaceWriteGateway.Prepared.class);assertThat(restored.market()).isEqualTo("COUPANG");assertThat(restored.mapping().channelProductId()).isNull();
        assertThat(SubmissionStore.publicMessage("FAILED","BASELINE_CHANGED","NAVER")).contains("스마트스토어").doesNotContain("쿠팡");assertThat(SubmissionStore.publicMessage("FAILED","BASELINE_CHANGED")).contains("쿠팡");
        assertThat(SubmissionStore.publicMessage("FAILED","HTTP_429","NAVER")).contains("호출 제한");
        assertThat(SubmissionStore.publicMessage("FAILED","HTTP_401","NAVER")).contains("설정·권한");
        assertThat(SubmissionStore.publicMessage("FAILED","ALREADY_REGISTERED","NAVER")).contains("이미 등록된 상품");
    }
    @Test void mappingLookupsKeepSameProductNumbersSeparatedByMarketAndPreserveChannelId(){
        var db=mock(JdbcTemplate.class);var json=new ObjectMapper();var persistence=new SubmissionStore(db,json);
        when(db.queryForList(anyString(),eq(Long.class),eq("COUPANG"),eq(account),eq("123"))).thenReturn(List.of(10L));
        when(db.queryForList(anyString(),eq(Long.class),eq("NAVER"),eq(account),eq("123"))).thenReturn(List.of(11L));
        assertThat(persistence.mappedDraft("COUPANG",account,"123")).isEqualTo(10L);assertThat(persistence.mappedDraft("NAVER",account,"123")).isEqualTo(11L);
        when(db.queryForList(anyString(),eq(String.class),eq(11L),eq("NAVER"),eq(account))).thenReturn(List.of(json.writeValueAsString(mapping())));
        assertThat(persistence.mapping(11,"NAVER",account,false).channelProductId()).isEqualTo("456");
        verify(db).queryForList(contains("market=?"),eq(Long.class),eq("NAVER"),eq(account),eq("123"));
    }
    @Test void primaryGatewayOverridesOnlyItsMarketWithoutChangingNaverRouting(){
        var replacement=mock(MarketplaceWriteGateway.class);when(replacement.market()).thenReturn("COUPANG");
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();var defaultDefinition=new org.springframework.beans.factory.support.RootBeanDefinition(MarketplaceWriteGateway.class);var primaryDefinition=new org.springframework.beans.factory.support.RootBeanDefinition(MarketplaceWriteGateway.class);primaryDefinition.setPrimary(true);beans.registerBeanDefinition("defaultCoupang",defaultDefinition);beans.registerBeanDefinition("fakeCoupang",primaryDefinition);
        var supplied=new LinkedHashMap<String,MarketplaceWriteGateway>();supplied.put("defaultCoupang",coupang);supplied.put("fakeCoupang",replacement);supplied.put("naver",naver);
        assertThat(DefaultMarketplaceSubmissions.selectGateways(supplied,beans)).containsExactly(replacement,naver);
        primaryDefinition.setPrimary(false);assertThatThrownBy(()->DefaultMarketplaceSubmissions.selectGateways(supplied,beans)).isInstanceOf(IllegalStateException.class);
    }
    @Test void importedDraftSummaryKeepsLegacyConstructorsAndExposesTrustedReentryIds()throws Exception{
        var old=new MarketplaceDrafts.Summary("10",2,"SKU","이름",List.of(MarketplaceDrafts.Market.NAVER),true,"2026-10-09T00:00:00","COMMON");
        assertThat(old.importMarket()).isNull();assertThat(old.externalProductId()).isNull();
        assertThat(new MarketplaceDrafts.Summary("10",2,"SKU","이름",List.of(),false,"2026-10-09T00:00:00").editorKind()).isEqualTo("COMMON");
        var json=new ObjectMapper();var db=mock(JdbcTemplate.class);var row=mock(java.sql.ResultSet.class);
        when(row.getString(1)).thenReturn("10");when(row.getLong(2)).thenReturn(2L);when(row.getString(3)).thenReturn("SKU");when(row.getString(4)).thenReturn("이름");when(row.getString(5)).thenReturn(json.writeValueAsString(document()));when(row.getString(6)).thenReturn("NAVER");when(row.getTimestamp(7)).thenReturn(java.sql.Timestamp.valueOf("2026-10-09 00:00:00"));when(row.getString(8)).thenReturn("COMMON");when(row.getString(9)).thenReturn("123");when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(db.query(contains("import_product_id"),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<MarketplaceDrafts.Summary>>any(),any(Object[].class))).thenAnswer(i->{org.springframework.jdbc.core.RowMapper<MarketplaceDrafts.Summary> mapper=i.getArgument(1);return List.of(mapper.mapRow(row,0));});
        var result=new DraftStore(db,json).list("",0,20).items().getFirst();assertThat(result.imported()).isTrue();assertThat(result.importMarket()).isEqualTo("NAVER");assertThat(result.externalProductId()).isEqualTo("123");
    }
}
