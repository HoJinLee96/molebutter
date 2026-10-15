package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** 화면 입력과 공통 초안 사이의 순수 변환 계약. 외부 요청 실행은 담당하지 않는다. */
public interface PartnerProductInputs {
    PartnerEditor.Schema schema();
    PartnerEditor.Input input(MarketplaceDrafts.Document document);
    MarketplaceDrafts.Document withInput(MarketplaceDrafts.Document document, PartnerEditor.Input input);
    MarketplaceDrafts.Document document(PartnerEditor.Input input, List<MarketplaceDrafts.Market> selected);
    MarketplaceWriteGateway.Mapping mapping(PartnerEditor.Observation observation);
    List<MarketplaceEditing.Change> diff(PartnerEditor.Input before, PartnerEditor.Input after);
}
