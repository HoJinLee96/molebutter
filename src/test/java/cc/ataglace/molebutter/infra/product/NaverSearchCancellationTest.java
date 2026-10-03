package cc.ataglace.molebutter.infra.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.SearchResult;

class NaverSearchCancellationTest {
    @Test void cancellationBeforeExecutionAllowsTheNextSearch()throws Exception {
        var search=new NaverPriceSearch(new ObjectMapper());
        var executor=(ExecutorService)ReflectionTestUtils.getField(search,"executor");
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        executor.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
        try {
            for(int i=0;i<2;i++) {
                Thread.currentThread().interrupt();
                try{assertThatThrownBy(()->search.search("test",Set.of(),1)).hasMessage("검색이 중단되었습니다.");}
                finally{Thread.interrupted();}
                assertThat(busy(search).get()).isFalse();
            }
            // Cancelled queued tasks must not release a later task's lock when dequeued.
            busy(search).set(true);release.countDown();executor.submit(()->{}).get(2,TimeUnit.SECONDS);
            assertThat(busy(search).get()).isTrue();
        }finally{release.countDown();search.close();}
    }

    @Test void runningCancellationKeepsLockUntilBrowserWorkActuallyExits()throws Exception {
        var search=new NaverPriceSearch(new ObjectMapper());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        busy(search).set(true);
        var task=search.new BrowserTask(()->{
            entered.countDown();
            boolean finished=false;
            while(!finished)try{release.await();finished=true;}catch(InterruptedException ignored){/* simulate browser cleanup */}
            return new SearchResult(List.of(),true,null);
        });
        var runner=new Thread(task);
        try {
            runner.start();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(task.cancel(true)).isTrue();assertThat(task.isDone()).isTrue();assertThat(busy(search).get()).isTrue();
            assertThatThrownBy(()->search.search("next",Set.of(),1)).isInstanceOfSatisfying(NaverSearchFailure.class,e->assertThat(e.code()).isEqualTo(NaverSearchFailure.Code.BROWSER_UNAVAILABLE));
            release.countDown();runner.join(2000);assertThat(runner.isAlive()).isFalse();assertThat(busy(search).get()).isFalse();
        }finally{release.countDown();runner.join(2000);search.close();}
    }

    @Test void submissionRejectionReleasesTheLock() {
        var search=new NaverPriceSearch(new ObjectMapper());
        ((ExecutorService)ReflectionTestUtils.getField(search,"executor")).shutdown();
        try {
            assertThatThrownBy(()->search.search("test",Set.of(),1)).isInstanceOfSatisfying(NaverSearchFailure.class,e->assertThat(e.code()).isEqualTo(NaverSearchFailure.Code.BROWSER_UNAVAILABLE));
            assertThat(busy(search).get()).isFalse();
        }finally{search.close();}
    }
    private AtomicBoolean busy(NaverPriceSearch search){return (AtomicBoolean)ReflectionTestUtils.getField(search,"browserBusy");}
}
