package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;

import cc.ataglace.molebutter.common.api.BusinessTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;


import cc.ataglace.molebutter.procurement.internal.NaverPriceSearch;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupService;
import cc.ataglace.molebutter.procurement.internal.SupplierRefreshWorker;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierRefreshService;
import cc.ataglace.molebutter.procurement.internal.DefaultSupplierStockLookupService;

class ProductRefreshWorkerTest {
    @Test void scheduledFailureHidesExceptionDetailsAndAllowsTheNextPoll() {
        String secret="SYNTHETIC_SECRET_NOT_FOR_LOGS";
        var runs=mock(DefaultSupplierRefreshService.class);var lookup=mock(SupplierLookupService.class);
        var search=mock(NaverPriceSearch.class);var queue=mock(DefaultSupplierStockLookupService.class);
        when(queue.claim(anyString())).thenThrow(new RuntimeException(secret,new RuntimeException(secret))).thenReturn(null);
        var worker=new SupplierRefreshWorker(runs,lookup,search,new BusinessTime(),queue);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"enabled",true);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(SupplierRefreshWorker.class);
        var originalLevel=logger.getLevel();
        var output=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        output.start();logger.addAppender(output);
        try {
            logger.setLevel(ch.qos.logback.classic.Level.WARN);
            worker.tick();
            verifyNoInteractions(runs,lookup,search);
            assertThat(output.list).isNotEmpty().allSatisfy(event->{
                assertThat(event.getFormattedMessage()).contains("RuntimeException").doesNotContain(secret);
                assertThat(event.getThrowableProxy()).isNull();
            });
            worker.tick();
            verify(runs).schedule();verify(runs).claim(anyString());
        } finally {logger.detachAppender(output);output.stop();logger.setLevel(originalLevel);}
    }
    @Test void searchesSavedQueryAndCachesNonPreferredCandidatesForOtherProductsInSameRun(){
        var runs=mock(DefaultSupplierRefreshService.class);var lookup=mock(SupplierLookupService.class);var search=mock(NaverPriceSearch.class);
        var prefs=new Preferences(1,List.of(),List.of(new Rule("1",ProcurementMall.LFMALL,null,0)),Map.of(ProcurementMall.LFMALL,false));
        var work=new DefaultSupplierRefreshService.Work(1,2,0,"직접 입력한 검색어","OTHER-CODE","GENERAL","HAZZYS",prefs,Map.of());
        var wanted=new Offer("1","상품코드 없음","LF몰","LF1","https://www.lfmall.co.kr/app/product/LF1",10000L,0L,ProcurementMall.LFMALL,null);
        var other=new Offer("2","[현대백화점 목동점] 다른 브랜드","더현대Hi","123","https://hi.thehyundai.com/product/123",8000L,0L,ProcurementMall.HI_THEHYUNDAI,null);
        when(runs.heartbeat(anyString(),eq(work))).thenReturn(true);
        when(runs.searchStarted(anyString(),eq(work))).thenReturn(10L);
        when(runs.searchSucceeded(anyString(),eq(work),eq(10L))).thenReturn(true);
        when(search.search(work.query(),Set.of(),3)).thenReturn(new SearchResult(List.of(wanted,other),false,"최대 3페이지 조회","PAGE_LIMIT"));
        new SupplierRefreshWorker(runs,lookup,search,new BusinessTime(),mock(DefaultSupplierStockLookupService.class)).process(work);
        var capture=ArgumentCaptor.forClass(SearchResult.class);verify(runs).cache(eq(work),capture.capture());
        assertThat(capture.getValue().offers()).extracting(Offer::mall).containsExactly(ProcurementMall.LFMALL,ProcurementMall.HI_THEHYUNDAI);
        assertThat(capture.getValue().completionReason()).isEqualTo("COMPLETED");assertThat(capture.getValue().complete()).isTrue();assertThat(capture.getValue().message()).isNull();
        verify(search).search("직접 입력한 검색어",Set.of(),3);
        verify(lookup).lookup(eq(work),eq(capture.getValue()),any(),anyString());
    }
    @Test void recordsSearchCompletionBeforeStockLookupAndDoesNotCountCacheHits(){
        var runs=mock(DefaultSupplierRefreshService.class);var lookup=mock(SupplierLookupService.class);var search=mock(NaverPriceSearch.class);
        var work=new DefaultSupplierRefreshService.Work(1,2,0,"query","code","GENERAL",null);
        when(runs.heartbeat(anyString(),eq(work))).thenReturn(true);
        when(runs.searchStarted(anyString(),eq(work))).thenReturn(10L);
        when(runs.searchSucceeded(anyString(),eq(work),eq(10L))).thenReturn(true);
        var found=new SearchResult(List.of(),false,null,"PAGE_LIMIT");when(search.search("query",Set.of(),3)).thenReturn(found);
        var worker=new SupplierRefreshWorker(runs,lookup,search,new BusinessTime(),mock(DefaultSupplierStockLookupService.class));worker.process(work);
        var order=inOrder(search,runs,lookup);order.verify(search).search("query",Set.of(),3);
        order.verify(runs).searchSucceeded(anyString(),eq(work),eq(10L));order.verify(runs).cache(eq(work),any());order.verify(lookup).lookup(eq(work),any(),any(),anyString());
        clearInvocations(runs,lookup,search);when(runs.cached(work)).thenReturn(found);worker.process(work);
        verify(search,never()).search(anyString(),anySet(),anyInt());verify(runs,never()).searchSucceeded(anyString(),any(),anyLong());
    }
    @Test void loginFailureIsTypedAndNeverRecordedAsCompletedProduct(){
        var runs=mock(DefaultSupplierRefreshService.class);var lookup=mock(SupplierLookupService.class);var search=mock(NaverPriceSearch.class);
        var work=new DefaultSupplierRefreshService.Work(1,2,0,"query","code","GENERAL",null);
        when(runs.heartbeat(anyString(),eq(work))).thenReturn(true);
        when(runs.searchStarted(anyString(),eq(work))).thenReturn(10L);
        when(search.search(anyString(),anySet(),anyInt())).thenThrow(new NaverPriceSearch.SearchBlocked(NaverPriceSearch.BlockReason.LOGIN_REQUIRED,"login"));
        new SupplierRefreshWorker(runs,lookup,search,new BusinessTime(),mock(DefaultSupplierStockLookupService.class)).process(work);
        verify(runs).searchFailed(anyString(),eq(work),eq(10L),isA(NaverPriceSearch.SearchBlocked.class));
        verify(runs,never()).finish(anyString(),any(),any());verifyNoInteractions(lookup);
    }
    @Test void manualQueueRunsAtProductBoundaryWithoutSearchingAndInvalidatesCache(){
        var runs=mock(DefaultSupplierRefreshService.class);var lookup=mock(SupplierLookupService.class);var search=mock(NaverPriceSearch.class);var queue=mock(DefaultSupplierStockLookupService.class);
        var product=new DefaultSupplierRefreshService.Work(1,2,0,"query","code","GENERAL",null);var offer=new Offer("nv","title","네이버","42","https://shopping.naver.com/window-products/department/42",10L,0L,ProcurementMall.NAVER_SMART_STORE,null);
        var result=new SupplierResult(offer,new CodeMatch("SEARCH_RESULT",null,null,null,null,null),"OPTIONS_UNKNOWN",List.of(),null);
        var manual=new DefaultSupplierStockLookupService.Work(3,4,product,5,offer,result);when(queue.claim(anyString())).thenReturn(manual,null);when(lookup.inspectFresh(product,offer)).thenReturn(result);
        var worker=new SupplierRefreshWorker(runs,lookup,search,new BusinessTime(),queue);org.springframework.test.util.ReflectionTestUtils.setField(worker,"enabled",true);worker.tick();
        verify(queue).finish(anyString(),eq(manual),eq(result));verify(lookup).invalidate(ProcurementMall.NAVER_SMART_STORE,"42");verifyNoInteractions(runs,search);worker.tick();verify(runs).claim(anyString());verifyNoInteractions(search);
    }
    @Test void manualRestrictionStopsQueueWithoutRecordingSuccess(){
        var runs=mock(DefaultSupplierRefreshService.class);var lookup=mock(SupplierLookupService.class);var search=mock(NaverPriceSearch.class);var queue=mock(DefaultSupplierStockLookupService.class);
        var product=new DefaultSupplierRefreshService.Work(1,2,0,"query","code","GENERAL",null);var offer=new Offer("nv","title","네이버","42","https://shopping.naver.com/window-products/department/42",10L,0L,ProcurementMall.NAVER_SMART_STORE,null);
        var previous=new SupplierResult(offer,new CodeMatch("SEARCH_RESULT",null,null,null,null,null),"SKIPPED_SAME_STORE",List.of(),null);var manual=new DefaultSupplierStockLookupService.Work(3,4,product,5,offer,previous);
        when(lookup.inspectFresh(product,offer)).thenThrow(new NaverPriceSearch.SearchBlocked("HTTP 429"));new SupplierRefreshWorker(runs,lookup,search,new BusinessTime(),queue).processStock(manual);
        verify(queue).blocked(anyString(),eq(manual),eq("HTTP 429"));verify(queue,never()).finish(anyString(),any(),any());verifyNoInteractions(runs,search);
    }
}
