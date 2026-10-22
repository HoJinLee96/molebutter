package cc.ataglace.molebutter.coupang.api;

import java.time.Instant;
import java.util.List;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.Document;

/**
 * Durable server-only execution snapshots. Payloads must never be returned to
 * the browser.
 */
public interface CoupangWriteGateway {
    String accountKey();
    enum Type {
        CREATE, PRODUCT, DELIVERY, ORIGINAL_PRICE, PRICE, STOCK, SALE_STOP, SALE_RESUME, DELETE, APPROVAL
    }
    enum State {
        CONFIRMED, ACCEPTED, FAILED, UNKNOWN
    }
    record OptionMapping(String optionId, String sellerProductItemId, String vendorItemId) {
    }
    record Mapping(String accountKey, String sellerProductId, List<OptionMapping> options) {
    }
    record Change(String path, String before, String after) {
    }
    record Step(String id, Type type, String optionId, String method, String path, String query, String bodyJson,
            String baselineJson, String expectedJson) {
    }
    record EditIntent(Document observed, List<CoupangEditing.Change> changes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CoupangOptionEdits.Basis quickEditBasis) {
        public EditIntent(Document observed,List<CoupangEditing.Change> changes){this(observed,changes,null);}
    }
    record Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes, Instant preparedAt,
            List<String> expectedSkus, EditIntent editIntent, Integer schemaVersion, Long listingVersion) {
        public Prepared {
            schemaVersion = schemaVersion == null || schemaVersion == 0 ? 1 : schemaVersion;
            listingVersion = listingVersion == null ? 1L : listingVersion;
        }
        public Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes, Instant at,
                List<String> skus, EditIntent intent, Integer version) {
            this(accountKey, mapping, steps, changes, at, skus, intent, version, 1L);
        }
        public Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes, Instant at,
                List<String> skus, EditIntent intent) {
            this(accountKey, mapping, steps, changes, at, skus, intent, 4, 1L);
        }
        public Prepared(String accountKey, Mapping mapping, List<Step> steps, List<Change> changes, Instant at,
                List<String> skus) {
            this(accountKey, mapping, steps, changes, at, skus, null, 4, 1L);
        }
        public boolean supported() {
            return schemaVersion == 1 || schemaVersion == 2 || schemaVersion == 3 || schemaVersion == 4;
        }
        public Prepared withListingVersion(long version) {
            return new Prepared(accountKey, mapping, steps, changes, preparedAt, expectedSkus, editIntent,
                    schemaVersion, version);
        }
    }
    record Result(State state, Mapping mapping, String code, String message, Instant attemptedAt, String requestJson,
            String rejectionDetails) {
        public Result(State state, Mapping mapping, String code, String message, Instant attemptedAt, String requestJson) {
            this(state, mapping, code, message, attemptedAt, requestJson, null);
        }
        public Result(State state, Mapping mapping, String code, String message, Instant attemptedAt) {
            this(state, mapping, code, message, attemptedAt, null);
        }
    }
    Prepared prepare(Long actor, Document document, Mapping mapping, Document observed,
            List<CoupangEditing.Change> changes);
    /** Server-owned DB observation only; execution must still re-read Coupang immediately before dispatch. */
    default Prepared prepareStoredOption(Long actor,Document document,Mapping mapping,Document observed,List<CoupangEditing.Change> changes) {
        throw new cc.ataglace.molebutter.common.api.InputValidationFailure("지원하지 않는 저장 관측 준비입니다.");
    }
    default Prepared prepareAction(Long actor, Mapping mapping, Document observed, String type, String vendorItemId) {
        throw new cc.ataglace.molebutter.common.api.InputValidationFailure("지원하지 않는 상품 작업입니다.");
    }
    Result execute(Long actor, Prepared prepared, Step step, Mapping current,
            java.util.function.Consumer<Step> beforeDispatch);
    Result reconcile(Long actor, Prepared prepared, Step step, Result previous);
}
