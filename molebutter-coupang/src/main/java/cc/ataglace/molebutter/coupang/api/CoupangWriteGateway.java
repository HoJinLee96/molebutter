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
        CREATE, PRODUCT, DELIVERY, ORIGINAL_PRICE, PRICE, STOCK, APPROVAL
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
    record EditIntent(Document observed, List<CoupangEditing.Change> changes) {
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
    record Result(State state, Mapping mapping, String code, String message, Instant attemptedAt, String requestJson) {
        public Result(State state, Mapping mapping, String code, String message, Instant attemptedAt) {
            this(state, mapping, code, message, attemptedAt, null);
        }
    }
    Prepared prepare(Long actor, Document document, Mapping mapping, Document observed,
            List<CoupangEditing.Change> changes);
    Result execute(Long actor, Prepared prepared, Step step, Mapping current,
            java.util.function.Consumer<Step> beforeDispatch);
    Result reconcile(Long actor, Prepared prepared, Step step, Result previous);
}
