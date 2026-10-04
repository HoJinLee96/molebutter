package cc.ataglace.molebutter.service.product;

import java.util.*;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.infra.product.NaverPriceSearch;
import cc.ataglace.molebutter.infra.product.NaverChannelPolicy;
import cc.ataglace.molebutter.infra.product.ProductSourceMetadata;

/** 사용자 선택용 후보만 분류한다. 최신화 작업의 원본 검색 결과와 저장된 스냅샷은 유지한다. */
@Service
@RequiredArgsConstructor
public class ProductCandidateSearch {
    private final NaverPriceSearch search;

    public CandidateSearchResult search(String query) {
        SearchResult result=search.search(query,Set.of(),3);
        return classify(result);
    }
    public static CandidateSearchResult classify(SearchResult result) {
        List<Offer> offers=new ArrayList<>(), needsReview=new ArrayList<>();
        Set<String> excluded=new LinkedHashSet<>();
        for(Offer offer:result.offers()) {
            ProcurementMall mall=ProductSourceMetadata.mall(offer.url());
            if(mall!=null) {
                String productId=ProductSourceMetadata.productId(mall,offer.url(),offer.mallProductId());
                Offer resolved=new Offer(offer.naverProductId(),offer.title(),offer.mallName(),productId,offer.url(),
                    offer.price(),offer.deliveryFee(),mall,offer.imageUrl(),offer.naverChannel(),offer.searchStore());
                if(!NaverChannelPolicy.probeAllowed(resolved))continue;
                (productId.isBlank()?needsReview:offers).add(resolved);
            } else if(ProductSourceMetadata.isSupportedName(offer.mallName())) {
                needsReview.add(new Offer(offer.naverProductId(),offer.title(),offer.mallName(),offer.mallProductId(),
                    offer.url(),offer.price(),offer.deliveryFee(),null,offer.imageUrl()));
            } else {
                excluded.add(offer.mallName()==null||offer.mallName().isBlank()?"이름 미확인":offer.mallName().trim());
            }
        }
        return new CandidateSearchResult(List.copyOf(offers),List.copyOf(needsReview),List.copyOf(excluded),result.complete(),result.message(),result.completionReason());
    }
}
