package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.infra.product.NaverPriceSearch;
import cc.ataglace.molebutter.service.product.ProductCandidateSearch;

class ProductCandidateSearchTest {
    @Test void separatesReadyCandidatesUnresolvedSupportedMallsAndUnsupportedNames() {
        var search=mock(NaverPriceSearch.class);
        var raw=new SearchResult(List.of(
            offer("1","판매점 별칭","http://www.lfmall.co.kr/app/product/P123",""),
            offer("2","LF몰","https://www.lfmall.co.kr/unknown",""),
            offer("3","헤지스","https://ad.example/redirect","P456"),
            offer("4","스마트스토어","",""),
            offer("5","하프클럽","http://www.halfclub.com/product/1","1"),
            offer("6","하프클럽","http://www.halfclub.com/product/2","2"),
            offer("7","GSSHOP","https://cr.shopping.naver.com/adcr?nvMid=7","1"),
            offer("8","헤지스 할인판매점","https://unrelated.example/1","1")
        ),false,"조회 범위 제한");
        when(search.search("HIBA311",Set.of(),3)).thenReturn(raw);
        var result=new ProductCandidateSearch(search).search("HIBA311");
        assertThat(result.offers()).extracting(Offer::naverProductId).containsExactly("1");
        assertThat(result.offers().getFirst().mall()).isEqualTo(Mall.LFMALL);
        assertThat(result.offers().getFirst().mallProductId()).isEqualTo("P123");
        assertThat(result.needsReview()).extracting(Offer::naverProductId).containsExactly("2","3","4");
        assertThat(result.needsReview().get(1).mall()).isNull(); // 이름만으로 자동 옵션 조회를 허용하지 않는다.
        assertThat(result.excludedMalls()).containsExactly("하프클럽","GSSHOP","헤지스 할인판매점");
        assertThat(result.complete()).isFalse();assertThat(result.message()).isEqualTo("조회 범위 제한");
        assertThat(raw.offers()).hasSize(8); // 최신화용 검색 결과는 필터로 손실되지 않는다.
    }

    @Test void emptyAndUnsupportedOnlySearchesRemainDistinct() {
        var search=mock(NaverPriceSearch.class);
        when(search.search("empty",Set.of(),3)).thenReturn(new SearchResult(List.of(),true,null));
        when(search.search("unsupported",Set.of(),3)).thenReturn(new SearchResult(List.of(offer("1","NS홈쇼핑","http://www.nsmall.com/1","1")),true,null));
        var service=new ProductCandidateSearch(search);
        assertThat(service.search("empty").excludedMalls()).isEmpty();
        var excluded=service.search("unsupported");
        assertThat(excluded.offers()).isEmpty();assertThat(excluded.needsReview()).isEmpty();
        assertThat(excluded.excludedMalls()).containsExactly("NS홈쇼핑");
    }

    private Offer offer(String id,String name,String url,String productId) {
        return new Offer(id,"상품 "+id,name,productId,url,10000L,0L);
    }
}
