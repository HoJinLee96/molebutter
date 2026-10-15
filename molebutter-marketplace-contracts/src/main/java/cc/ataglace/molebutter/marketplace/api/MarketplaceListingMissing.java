package cc.ataglace.molebutter.marketplace.api;

import cc.ataglace.molebutter.common.api.InputValidationFailure;

/** Only explicit provider evidence; generic HTTP/errors never establish deletion. */
public final class MarketplaceListingMissing extends InputValidationFailure {
    private final String reason;
    public MarketplaceListingMissing(String reason){super("마켓에서 연결 상품의 삭제 또는 미존재를 확인했습니다. 기존 연결에 수정 전송할 수 없습니다.");this.reason=reason;}
    public String reason(){return reason;}
}
