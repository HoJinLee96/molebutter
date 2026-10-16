package cc.ataglace.molebutter.coupang.api;

import cc.ataglace.molebutter.common.api.InputValidationFailure;

/** Only explicit provider evidence; generic HTTP/errors never establish deletion. */
public final class CoupangListingMissing extends InputValidationFailure {
    private final String reason;
    public CoupangListingMissing(String reason){super("쿠팡에서 연결 상품의 삭제 또는 미존재를 확인했습니다. 기존 연결에 수정 전송할 수 없습니다.");this.reason=reason;}
    public String reason(){return reason;}
}
