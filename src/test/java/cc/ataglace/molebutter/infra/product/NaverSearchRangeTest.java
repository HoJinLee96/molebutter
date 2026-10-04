package cc.ataglace.molebutter.infra.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

class NaverSearchRangeTest {
    NaverSearchPayload.Captured page(int n,boolean end) {
        var offer=new Offer("nv"+n,"bag","LF몰","p"+n,"https://www.lfmall.co.kr/product/"+n,100L,0L,ProcurementMall.LFMALL,null);
        return new NaverSearchPayload.Captured(new SearchResult(List.of(offer),end,null),80,1000L,false);
    }
    @Test void firstPageEndAndDefaultRangeHaveIdenticalSuccessMetadata() {
        var early=NaverPriceSearch.collectPages(page(1,true),3,(n,size)->{throw new AssertionError("No next page");});
        var calls=new ArrayList<Integer>();
        var bounded=NaverPriceSearch.collectPages(page(1,false),3,(n,size)->{calls.add(n);assertThat(size).isEqualTo(80);return page(n,false);});
        assertThat(calls).containsExactly(2,3);
        assertThat(bounded.offers()).hasSize(3);
        for(var result:List.of(early,bounded)) {
            assertThat(result.complete()).isTrue();
            assertThat(result.completionReason()).isEqualTo("COMPLETED");
            assertThat(result.message()).isNull();
        }
    }
    @Test void emptyResultsAreNormalAndPageErrorsStillPropagate() {
        var empty=new NaverSearchPayload.Captured(new SearchResult(List.of(),true,null),80,0L,false);
        assertThat(NaverPriceSearch.collectPages(empty,3,(n,size)->{throw new AssertionError();}).offers()).isEmpty();
        for(var error:List.of(new IllegalStateException("Invalid response"),new NaverPriceSearch.SearchBlocked("Restricted"))) {
            assertThatThrownBy(()->NaverPriceSearch.collectPages(page(1,false),3,(n,size)->{throw error;})).isSameAs(error);
        }
    }
}
