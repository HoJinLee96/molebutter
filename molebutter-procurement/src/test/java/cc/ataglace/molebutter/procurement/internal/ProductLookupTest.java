package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductCodePolicy;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.procurement.internal.SupplierProductGateway;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupFailure;
import cc.ataglace.molebutter.procurement.internal.NaverPriceSearch;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupService;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierRefreshService;

import cc.ataglace.molebutter.common.api.BusinessTime;
class ProductLookupTest {
    @Test void failuresRecordContextAndSafeReasonWithoutRawResponse(){
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(SupplierLookupService.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            var failure=new SupplierLookupFailure(SupplierLookupFailure.Code.TIMEOUT,"FETCH",null,new java.net.http.HttpTimeoutException("secret cookie"));
            var gateway=new Gateway(){@Override public SourceDetails inspect(ProcurementMall m,String id,String url){throw failure;}};
            var r=new SupplierLookupService(gateway,new BusinessTime()).lookup(work,new SearchResult(List.of(offer("one","",10)),true,null),()->true);
            assertThat(r.suppliers().getFirst().message()).isEqualTo("재고 조회 시간 초과");
            assertThat(appender.list).hasSize(1);String line=appender.list.getFirst().getFormattedMessage();
            assertThat(line).contains("runId=1","productId=2","mall=HAZZYS","supplierProductId=one","stage=FETCH","code=TIMEOUT","exception=HttpTimeoutException").doesNotContain("secret","cookie");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            assertThat(SupplierLookupFailure.classify(new IllegalArgumentException("internal secret")).getMessage()).isEqualTo("재고 조회 처리 오류");
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void excludedUnknownSupplierDoesNotMakeHealthyLookupPartial(){
        var prefs=new cc.ataglace.molebutter.procurement.api.SupplierDtos.Preferences(1,List.of(),List.of(
            new cc.ataglace.molebutter.procurement.api.SupplierDtos.Rule("1",ProcurementMall.HAZZYS,null,0),
            new cc.ataglace.molebutter.procurement.api.SupplierDtos.Rule("2",ProcurementMall.LOTTE_ON,null,0)),Map.of(ProcurementMall.HAZZYS,false));
        var task=new DefaultSupplierRefreshService.Work(1,2,0,"query","code","GENERAL",null,prefs,Map.of(),null);
        var lotte=new Offer("NV2","가방","롯데ON","LE123_1","https://www.lotteon.com/p/product/LE123?sitmNo=LE123_1",200L,0L,ProcurementMall.LOTTE_ON,null);
        var gateway=new Gateway();
        gateway.values.put("one",new SourceDetails("","","",List.of(new SourceOption("1","FREE",15L,"AVAILABLE"))));
        gateway.values.put("LE123_1",new SourceDetails("","","",List.of(new SourceOption("1","FREE",3L,"AVAILABLE"))));
        var lookup=new SupplierLookupService(gateway,new BusinessTime());
        var result=lookup.lookup(task,new SearchResult(List.of(offer("one","",100),lotte),true,null),()->true);
        assertThat(result.status()).isEqualTo("SUCCESS");assertThat(result.message()).isNull();
        assertThat(result.suppliers()).hasSize(2); // Hidden evidence remains available in history.
        assertThat(lookup.lookup(task,new SearchResult(List.of(lotte),true,null),()->true).status()).isEqualTo("SOLD_OUT");
        assertThat(lookup.lookup(task,new SearchResult(List.of(offer("one","",100),lotte),false,null),()->true).status()).isEqualTo("PARTIAL");
    }
    @Test void branchConflictIsExcludedFromSummaryButStockProblemsAndFailuresRemain(){
        var prefs=new cc.ataglace.molebutter.procurement.api.SupplierDtos.Preferences(1,List.of(),List.of(),Map.of(ProcurementMall.HAZZYS,false));
        var code=new CodeMatch("SEARCH_RESULT",null,null,null,null,null);
        var lotte=new Offer("NV2","가방","롯데ON","LE123_1","https://www.lotteon.com/p/product/LE123",200L,0L,ProcurementMall.LOTTE_ON,null);
        var conflict=new SupplierResult(lotte,code,"CONFIRMED",List.of(new SourceOption("1","FREE",3L,"AVAILABLE")),null,null,null,null,
            new BranchInfo(null,"CONFLICT","지점 표기 불일치","기흥아울렛점 / 프리미엄아울렛기흥점",null));
        var healthy=new SupplierResult(offer("one","",100),code,"CONFIRMED",List.of(new SourceOption("1","FREE",15L,"AVAILABLE")),null);
        var now=java.time.LocalDateTime.now();
        assertThat(SupplierLookupService.summarize(List.of(healthy,conflict),true,now,prefs,Map.of()).status()).isEqualTo("SUCCESS");
        var sold=new SupplierResult(offer("one","",100),code,"CONFIRMED",List.of(new SourceOption("1","FREE",0L,"SOLD_OUT")),null);
        assertThat(SupplierLookupService.summarize(List.of(sold,conflict),true,now,prefs,Map.of()).status()).isEqualTo("SOLD_OUT");
        var unknown=new SupplierResult(offer("unknown","",150),code,"CONFIRMED",List.of(new SourceOption("1","FREE",null,"STOCK_UNKNOWN")),null);
        var failed=new SupplierResult(lotte,code,"FAILED",List.of(),"재고 조회 시간 초과");
        for(var issue:List.of(unknown,failed))assertThat(SupplierLookupService.summarize(List.of(healthy,conflict,issue),true,now,prefs,Map.of()).status()).isEqualTo("PARTIAL");
    }
    final DefaultSupplierRefreshService.Work work=new DefaultSupplierRefreshService.Work(1,2,0,"ABCD123","ABCD6F123BK","LF_ACCESSORY","HAZZYS");
    Offer offer(String id,String title,long price){return new Offer(id,title,"헤지스",id,"https://www.hazzys.com/product.do?PROD_CD="+id,price,0L,ProcurementMall.HAZZYS,null);}
    static class Gateway implements SupplierProductGateway {
        final List<String> calls=new ArrayList<>();final Map<String,SourceDetails> values=new HashMap<>();boolean blocked;
        public String validateUrl(ProcurementMall mall,String url){return url;}
        public List<SourceOption> options(ProcurementMall mall,String id,String url){return inspect(mall,id,url).options();}
        public SourceDetails inspect(ProcurementMall mall,String id,String url){calls.add(id);if(blocked)throw new NaverPriceSearch.SearchBlocked("차단");if(!values.containsKey(id))throw new IllegalStateException("통신 실패");return values.get(id);}
    }
    List<SourceOption> options(){return List.of(new SourceOption("1","BLACK / M",3L,"AVAILABLE"),new SourceOption("2","블랙 / FREE",null,"STOCK_UNKNOWN"));}
    @Test void ordinarySmartStoreIsExcludedWithoutRequestOrFailure(){
        var gateway=new Gateway();var offer=new Offer("nv","헤지스 ABCD123","스마트스토어","1234","https://smartstore.naver.com/hazzys/products/1234",9000L,3000L,ProcurementMall.NAVER_SMART_STORE,null);
        var r=new SupplierLookupService(gateway,new BusinessTime()).lookup(work,new SearchResult(List.of(offer),true,null),()->true);
        assertThat(gateway.calls).isEmpty();assertThat(r.searchPrice()).isNull();assertThat(r.suppliers()).isEmpty();assertThat(r.message()).isEqualTo("대상 판매글 없음");assertThat(r.status()).isEqualTo("SOLD_OUT");
    }
    @Test void searchResultsKeepRawOptionsRegardlessOfDifferentTitleCodes(){var gateway=new Gateway();gateway.values.put("one",new SourceDetails("헤지스 가방 ABCD123BK","","",options()));var result=new SupplierLookupService(gateway,new BusinessTime()).lookup(work,new SearchResult(List.of(offer("one","헤지스 ABCD6E123BK",9000),offer("two","헤지스 ABCD6F124Y2",1)),true,null),()->true);assertThat(gateway.calls).containsExactly("one","two");assertThat(result.searchPrice()).isEqualTo(1);assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.suppliers().getFirst().options()).hasSize(2);assertThat(result.suppliers().getFirst().options().getFirst().stock()).isEqualTo(3);assertThat(result.suppliers().getFirst().options().getLast().stock()).isNull();}
    @Test void missingOrDifferentModelAndBrandDoNotBlockSearchResults(){var gateway=new Gateway();gateway.values.put("one",new SourceDetails("헤지스 가방","ABCD6E123BK","헤지스",options()));gateway.values.put("two",new SourceDetails("헤지스 다른 상품","ABCD6F124Y2","다른 브랜드",options()));var r=new SupplierLookupService(gateway,new BusinessTime()).lookup(work,new SearchResult(List.of(offer("one","헤지스 가방",12000),offer("two","헤지스 ABCD123",100)),true,null),()->true);assertThat(r.searchPrice()).isEqualTo(100);assertThat(r.suppliers()).allMatch(v->v.accepted()&&v.match().state().equals("SEARCH_RESULT"));}
    @Test void failedSupplierDoesNotBecomeSoldOutAndRestrictionStopsWork(){var gateway=new Gateway();gateway.values.put("sold",new SourceDetails("","","",List.of(new SourceOption("1","FREE",0L,"SOLD_OUT"))));var lookup=new SupplierLookupService(gateway,new BusinessTime());var search=new SearchResult(List.of(offer("sold","ABCD123",10000),offer("failed","ABCD123",9000)),true,null);var r=lookup.lookup(work,search,()->true);assertThat(r.status()).isEqualTo("PARTIAL");assertThat(r.suppliers().getLast().state()).isEqualTo("FAILED");gateway.blocked=true;assertThatThrownBy(()->lookup.lookup(work,search,()->true)).isInstanceOf(NaverPriceSearch.SearchBlocked.class);}
    @Test void supplierModelTakesDisplayPriorityOverSearchTitle(){var gateway=new Gateway();gateway.values.put("one",new SourceDetails("매입처 상품명","ABCD6E123Y2","헤지스",options()));var r=new SupplierLookupService(gateway,new BusinessTime()).lookup(work,new SearchResult(List.of(offer("one","헤지스 ABCD6F123BK",10000)),true,null),()->true);var supplier=r.suppliers().getFirst();assertThat(supplier.match().originalCode()).isEqualTo("ABCD6E123Y2");assertThat(supplier.match().message()).isNull();assertThat(supplier.match().state()).isEqualTo("SEARCH_RESULT");assertThat(supplier.sourceModelCode()).isEqualTo("ABCD6E123Y2");assertThat(supplier.sourceTitle()).isEqualTo("매입처 상품명");}
    @Test void excelInitialQueryShortensOnlySupportedAccessoryPatterns(){
        assertThat(ProductCodePolicy.suggested("LF_ACCESSORY"," abcd6f003y2 ")).isEqualTo("ABCD003");
        assertThat(ProductCodePolicy.suggested("GENERAL","ABCD6F123BK")).isEqualTo("ABCD6F123BK");
        assertThat(ProductCodePolicy.suggested("LF_ACCESSORY","ABCD6S123BK")).isEqualTo("ABCD6S123BK");
        assertThat(ProductCodePolicy.brandKey("헤지스")).isEqualTo("HAZZYS");
        assertThat(ProductCodePolicy.brandKey("다른 브랜드")).isEmpty();
    }
}
