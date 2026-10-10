package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.storage.api.ObjectMetadata;
import cc.ataglace.molebutter.storage.api.ObjectStorage;
import cc.ataglace.molebutter.storage.api.StorageArea;
import cc.ataglace.molebutter.storage.api.StorageException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** Durable, conditional product claims; released records remain to protect subsequent owners. */
final class ProductImageUploadReservations {
    private static final String CONTENT_TYPE = "application/json";
    private static final String HELD = "held";
    private static final String RELEASED = "released";
    private final ObjectStorage storage;

    ProductImageUploadReservations(ObjectStorage storage) {
        this.storage = storage;
    }

    void acquire(String productCode, String requestId, String generation) {
        String key = key(productCode);
        Optional<ObjectMetadata> existing = storage.head(StorageArea.PRIVATE, key);
        if (existing.isPresent() && (!RELEASED.equals(existing.get().metadata().get("state"))
                || requestId.equals(existing.get().metadata().get("request-id")))) {
            throw duplicate();
        }
        try {
            if (existing.isEmpty()) {
                storage.create(StorageArea.PRIVATE, key, body(requestId, HELD, generation), CONTENT_TYPE,
                        metadata(requestId, HELD, generation));
            } else {
                storage.replace(StorageArea.PRIVATE, key, existing.get().eTag(), body(requestId, HELD, generation),
                        CONTENT_TYPE, metadata(requestId, HELD, generation));
            }
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.CONFLICT) throw duplicate();
            throw failure;
        }
    }

    boolean heldBy(String productCode, String requestId, String generation) {
        return storage.head(StorageArea.PRIVATE, key(productCode))
                .filter(value -> owned(value, requestId, generation, HELD)).isPresent();
    }

    boolean releasedBy(String productCode, String requestId, String generation) {
        return storage.head(StorageArea.PRIVATE, key(productCode))
                .filter(value -> owned(value, requestId, generation, RELEASED)).isPresent();
    }

    void release(String productCode, String requestId, String generation) {
        String key = key(productCode);
        Optional<ObjectMetadata> existing = storage.head(StorageArea.PRIVATE, key);
        if (existing.isEmpty() || !owned(existing.get(), requestId, generation, HELD)) return;
        // Deleting could complete after another request acquires the product. CAS preserves that owner.
        storage.replace(StorageArea.PRIVATE, key, existing.get().eTag(), body(requestId, RELEASED, generation),
                CONTENT_TYPE, metadata(requestId, RELEASED, generation));
    }

    private static boolean owned(ObjectMetadata value, String requestId, String generation, String state) {
        return state.equals(value.metadata().get("state")) && requestId.equals(value.metadata().get("request-id"))
                && generation.equals(value.metadata().get("generation"));
    }

    private static Map<String, String> metadata(String requestId, String state, String generation) {
        return Map.of("request-id", requestId, "state", state, "generation", generation);
    }

    private static byte[] body(String requestId, String state, String generation) {
        // Request and generation are UUIDs; state is an internal constant.
        return ("{\"requestId\":\"" + requestId + "\",\"state\":\"" + state
                + "\",\"generation\":\"" + generation + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String key(String productCode) {
        return "product-image-upload-reservations/" + productCode + ".json";
    }

    private static ProductImageUploadFailure duplicate() {
        return new ProductImageUploadFailure(ProductImageUploadFailure.Kind.DUPLICATE,
                "이미 업로드되었거나 업로드 중인 상품입니다. 다른 상품코드를 입력해주세요.");
    }
}
