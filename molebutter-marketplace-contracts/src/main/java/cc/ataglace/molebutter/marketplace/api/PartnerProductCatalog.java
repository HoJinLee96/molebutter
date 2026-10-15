package cc.ataglace.molebutter.marketplace.api;

/** 상품 조회 계약. 조회 조건은 외부 요청의 임의 필드로 전달하지 않는다. */
public interface PartnerProductCatalog {
    record Query(String keyword, String status, int page, int size) {
        public Query {
            if (page < 1 || size < 1 || size > 500)
                throw PartnerFormSupport.invalid("조회 페이지와 개수를 확인해 주세요.");
            keyword = MarketplaceDocuments.text(keyword, 200);
            status = MarketplaceDocuments.text(status, 100);
        }
    }
    PartnerEditor.ProductPage products(Long actor, Query query);
    PartnerEditor.Observation editor(Long actor, String productId);
}
