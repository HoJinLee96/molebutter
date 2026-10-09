package cc.ataglace.molebutter.marketplace.api;

import java.time.Instant;
import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing;

/** Durable server-only execution snapshots. Payloads must never be returned to the browser. */
public interface MarketplaceWriteGateway {
    /** Legacy snapshots without a market retain their historical Coupang interpretation. */
    default String market(){return "COUPANG";}
    default String accountKey(){return null;}
    enum Type { CREATE, PRODUCT, DELIVERY, ORIGINAL_PRICE, PRICE, STOCK }
    enum State { CONFIRMED, ACCEPTED, FAILED, UNKNOWN }
    record OptionMapping(String optionId, String sellerProductItemId, String vendorItemId) {}
    record Mapping(String accountKey, String sellerProductId, List<OptionMapping> options, String channelProductId) {
        public Mapping(String accountKey,String sellerProductId,List<OptionMapping> options){this(accountKey,sellerProductId,options,null);}
    }
    record Change(String path, String before, String after) {}
    record Step(String id, Type type, String optionId, String method, String path, String query,
                String bodyJson, String baselineJson, String expectedJson) {}
    record EditIntent(Document observed, List<MarketplaceEditing.Change> changes) {}
    record Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes,
                    Instant preparedAt, List<String> expectedSkus, EditIntent editIntent, String market, Integer schemaVersion) {
        public Prepared { market=market==null?"COUPANG":market; schemaVersion=schemaVersion==null||schemaVersion==0?1:schemaVersion; }
        public Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes,
                        Instant preparedAt, List<String> expectedSkus, EditIntent editIntent, String market) {
            this(accountKey,mapping,steps,changes,preparedAt,expectedSkus,editIntent,market,1);
        }
        public Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes,
                        Instant preparedAt, List<String> expectedSkus, EditIntent editIntent) {
            this(accountKey,mapping,steps,changes,preparedAt,expectedSkus,editIntent,"COUPANG");
        }
        public Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes,
                        Instant preparedAt, List<String> expectedSkus) {
            this(accountKey,mapping,steps,changes,preparedAt,expectedSkus,null,"COUPANG");
        }
    }
    record Result(State state, Mapping mapping, String code, String message, Instant attemptedAt, String requestJson) {
        public Result(State state, Mapping mapping, String code, String message, Instant attemptedAt){this(state,mapping,code,message,attemptedAt,null);}
    }
    /** Apply authorized field edits for core asset pinning and common draft validation. */
    default Document projectChanges(Document document,List<MarketplaceEditing.Change> changes) {
        if(changes==null||changes.isEmpty())return document;
        throw new cc.ataglace.molebutter.common.api.InputValidationFailure("마켓 변경 매핑을 확인해 주세요.");
    }
    default boolean validateCommonDraft(){return false;}
    Prepared prepare(Long actor, Document document, Mapping mapping, boolean requested);
    Prepared prepareSelected(Long actor, Document reference, Mapping mapping, boolean requested,
                             Document observed, List<MarketplaceEditing.Change> changes);
    Result execute(Long actor, Prepared prepared, Step step, Mapping current);
    default Result execute(Long actor,Prepared prepared,Step step,Mapping current,java.util.function.Consumer<Step> beforeDispatch){
        beforeDispatch.accept(step);return execute(actor,prepared,step,current);
    }
    Result reconcile(Long actor, Prepared prepared, Step step, Result previous);
}
