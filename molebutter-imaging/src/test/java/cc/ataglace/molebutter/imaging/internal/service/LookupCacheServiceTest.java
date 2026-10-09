package cc.ataglace.molebutter.imaging.internal.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.awt.image.BufferedImage;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.client.ImageDownloadClient;

class LookupCacheServiceTest {
    private ProductLookupDto product(String code,String brand){return new ProductLookupDto(code,brand,brand,"상품",null,null,List.of(),List.of(),Map.of(),"FREE",null,Map.of(),false,null,null,null);}
    @Test void normalizesKeysCachesAndExpiresAtBoundary(){
        var products=mock(ProductLookupService.class);var images=mock(ImageDownloadClient.class);var clock=new GeneratedImageStoreTest.MutableClock();
        when(products.lookup("BAG1","DAKS")).thenReturn(product("BAG1","DAKS"));var cache=new LookupCacheService(products,images,Duration.ofMinutes(10),clock);
        assertThat(cache.product(" bag1 ",null)).isSameAs(cache.product("BAG1","DAKS"));verify(products,times(1)).lookup("BAG1","DAKS");
        clock.now=clock.now.plusSeconds(600);cache.product("BAG1","DAKS");verify(products,times(2)).lookup("BAG1","DAKS");
    }
    @Test void explicitLookupRefreshesWhilePreviewReuseUsesLatestSnapshot(){
        var products=mock(ProductLookupService.class);var images=mock(ImageDownloadClient.class);
        var first=product("BAG1","DAKS");var second=product("BAG1","DAKS");
        when(products.lookup("BAG1","DAKS")).thenReturn(first,second);var cache=new LookupCacheService(products,images,Duration.ofMinutes(10));
        assertThat(cache.refreshProduct("BAG1","DAKS")).isSameAs(first);
        assertThat(cache.refreshProduct("BAG1","DAKS")).isSameAs(second);
        assertThat(cache.product("BAG1","DAKS")).isSameAs(second);verify(products,times(2)).lookup("BAG1","DAKS");
    }
    @Test void boundsProductAndDecodedImageCaches(){
        var products=mock(ProductLookupService.class);var images=mock(ImageDownloadClient.class);when(products.lookup(anyString(),anyString())).thenAnswer(call->product(call.getArgument(0),call.getArgument(1)));
        when(images.downloadBaseImage(anyString(),anyString())).thenAnswer(call->new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB));var cache=new LookupCacheService(products,images,Duration.ofMinutes(10));
        cache.product("BAG1","DAKS");for(int i=0;i<50;i++)cache.product("CODE"+i,"DAKS");cache.product("BAG1","DAKS");verify(products,times(2)).lookup("BAG1","DAKS");
        cache.baseImage("one","BAG1");cache.baseImage("two","BAG1");cache.baseImage("three","BAG1");cache.baseImage("one","BAG1");verify(images,times(2)).downloadBaseImage("one","BAG1");
    }
    @Test void cacheHitsAndCleanupAreNotBlockedBySupplierRequest() throws Exception {
        var products=mock(ProductLookupService.class);var images=mock(ImageDownloadClient.class);var hit=product("HIT1","DAKS");when(products.lookup("HIT1","DAKS")).thenReturn(hit);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(products.lookup("MISS","DAKS")).thenAnswer(call->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException();return product("MISS","DAKS");});
        var cache=new LookupCacheService(products,images,Duration.ofMinutes(10));cache.product("HIT1","DAKS");var workers=Executors.newFixedThreadPool(2);
        try {
            var pending=workers.submit(()->cache.product("MISS","DAKS"));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(workers.submit(()->{cache.cleanup();return cache.product("HIT1","DAKS");}).get(1,TimeUnit.SECONDS)).isSameAs(hit);
            release.countDown();assertThat(pending.get(1,TimeUnit.SECONDS).productCode()).isEqualTo("MISS");
        } finally {release.countDown();workers.shutdownNow();}
    }
}
