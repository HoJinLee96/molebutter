package cc.ataglace.molebutter.imaging.internal.service;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;

class ObservedProductStoreTest {
    private ProductLookupDto product(String code,String url){return new ProductLookupDto(code,"DAKS","닥스","상품",null,null,List.of(url),List.of(),Map.of(),"FREE",null,Map.of(),false,null,null,null);}
    @Test void isolatesActorsAndKeepsObservedImmutableSourceOrder(){
        var store=new ObservedProductStore();var first=product("BAG1","https://nimg.lfmall.co.kr/old.jpg");var updated=product("BAG1","https://nimg.lfmall.co.kr/new.jpg");
        store.put(1L,first);store.put(2L,updated);
        assertThat(store.get(1L,"bag1",null)).isSameAs(first);assertThat(store.get(2L,"BAG1","DAKS")).isSameAs(updated);
        assertThatThrownBy(()->store.get(3L,"BAG1","DAKS")).isInstanceOf(ImagingFailure.class);
    }
    @Test void expirationRequiresFreshLookupInsteadOfSilentlyFetchingSupplier(){
        var clock=new GeneratedImageStoreTest.MutableClock();var store=new ObservedProductStore(clock);store.put(1L,product("BAG1","old"));
        clock.now=clock.now.plus(ObservedProductStore.TTL);assertThatThrownBy(()->store.get(1L,"BAG1","DAKS")).hasMessageContaining("다시 조회");
    }
    @Test void boundsActorCountWithoutEvictingOtherActorSnapshot(){
        var store=new ObservedProductStore();store.put(2L,product("OTHER","other"));store.put(1L,product("FIRST","first"));
        for(int i=0;i<4;i++)store.put(1L,product("BAG"+i,"url"+i));
        assertThatThrownBy(()->store.get(1L,"FIRST","DAKS")).isInstanceOf(ImagingFailure.class);assertThat(store.get(2L,"OTHER","DAKS").imageUrls()).containsExactly("other");
    }
    @Test void boundsGlobalObservationCount(){
        var store=new ObservedProductStore();for(long actor=1;actor<=64;actor++)store.put(actor,product("BAG1","url"));
        assertThatThrownBy(()->store.put(65L,product("BAG1","url"))).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.BUSY);
    }
}
