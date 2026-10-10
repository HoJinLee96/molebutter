package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import tools.jackson.databind.JsonNode;

/** Authorized edit values only; never include upstream credentials or raw envelopes. */
public final class MarketplaceEditConflict extends InputValidationFailure {
    private final List<MarketplaceWriteGateway.Change> differences;
    public MarketplaceEditConflict(String path,JsonNode observed,JsonNode current,JsonNode wanted){
        super("쿠팡의 선택한 항목이 변경되었습니다. 조회 당시 값·현재 값·입력 값을 확인하고 다시 조회해 주세요: "+path);
        differences=List.of(new MarketplaceWriteGateway.Change(path+" · 조회 당시 → 현재",show(observed),show(current)),
            new MarketplaceWriteGateway.Change(path+" · 현재 → 입력",show(current),show(wanted)));
    }
    public List<MarketplaceWriteGateway.Change> differences(){return differences;}
    private static String show(JsonNode value){return value==null?"":value.isString()?value.asString():value.toString();}
}
