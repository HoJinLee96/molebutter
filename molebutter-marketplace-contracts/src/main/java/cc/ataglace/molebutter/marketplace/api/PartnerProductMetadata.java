package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** 등록 입력에 필요한 선택값 조회. 계정 정보와 외부 응답 전문은 노출하지 않는다. */
public interface PartnerProductMetadata {
    enum Kind { CATEGORY, ATTRIBUTE, NOTICE, CERTIFICATION, ADDRESS, SHIPPING_PLACE, SHIPPING_POLICY, DISPATCH_POLICY, DELIVERY_COMPANY, ORIGIN, OPTION_POLICY }
    record Query(Kind kind, String parentId, String categoryId, String keyword, int page) {
        public Query {
            if (kind == null || page < 1) throw PartnerFormSupport.invalid("조회 항목과 페이지를 확인해 주세요.");
            parentId = MarketplaceDocuments.text(parentId, 200);
            categoryId = MarketplaceDocuments.text(categoryId, 200);
            keyword = MarketplaceDocuments.text(keyword, 200);
        }
    }
    record Choice(String id, String label, String parentId, boolean selectable) {}
    record Page(List<Choice> items, int page, boolean hasNext, String warning) {
        public Page { items = List.copyOf(items); }
    }
    Page metadata(Long actor, Query query);
}
