package cc.ataglace.molebutter.app.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ataglace.molebutter.storage.api.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductImageUploadReservationsTest {
    private static final String PRODUCT = "HIHO861W2";
    private static final String KEY = "product-image-upload-reservations/HIHO861W2.json";
    private static final String FIRST = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND = "00000000-0000-0000-0000-000000000002";
    private static final String GENERATION = "10000000-0000-0000-0000-000000000001";
    private static final String SECOND_GENERATION = "10000000-0000-0000-0000-000000000002";
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private ProductImageUploadReservations reservations;

    @BeforeEach void setup() {
        reservations = new ProductImageUploadReservations(storage);
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.empty());
    }

    @Test void acquiresMissingProductUsingConditionalCreateInPrivateArea() {
        reservations.acquire(PRODUCT, FIRST, GENERATION);
        var content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).create(eq(StorageArea.PRIVATE), eq(KEY), content.capture(), eq("application/json"),
                argThat(metadata -> validClaimMetadata(metadata, FIRST, "held")));
        assertThat(new String(content.getValue(), StandardCharsets.UTF_8))
                .startsWith("{\"requestId\":\"" + FIRST + "\",\"state\":\"held\",\"generation\":\"")
                .matches(".*\\\"generation\\\":\\\"[0-9a-f-]{36}\\\"}");
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
        verify(storage, never()).delete(any(), any());
    }

    @Test void heldClaimBlocksOtherAndSameRequestIdsWithoutWriting() {
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "held", "held-tag")));
        for (String requestId : List.of(FIRST, SECOND)) {
            assertThatThrownBy(() -> reservations.acquire(PRODUCT, requestId, GENERATION))
                    .isInstanceOf(ProductImageUploadFailure.class)
                    .satisfies(error -> assertThat(((ProductImageUploadFailure) error).kind())
                            .isEqualTo(ProductImageUploadFailure.Kind.DUPLICATE));
        }
        verify(storage, never()).create(any(), any(), any(), any(), any());
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test void unknownOrIncompleteClaimIsNotTreatedAsReleased() {
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(
                new ObjectMetadata(StorageArea.PRIVATE, KEY, 1, "application/json", "tag", null, Map.of())));
        assertThatThrownBy(() -> reservations.acquire(PRODUCT, FIRST, GENERATION)).isInstanceOf(ProductImageUploadFailure.class);
        verify(storage, never()).create(any(), any(), any(), any(), any());
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test void releasedClaimIsReacquiredByETagConditionalReplacement() {
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "released", "released-tag")));
        reservations.acquire(PRODUCT, SECOND, SECOND_GENERATION);
        verify(storage).replace(eq(StorageArea.PRIVATE), eq(KEY), eq("released-tag"), any(), eq("application/json"),
                argThat(metadata -> validClaimMetadata(metadata, SECOND, "held")
                        && !GENERATION.equals(metadata.get("generation"))));
        verify(storage, never()).create(any(), any(), any(), any(), any());
    }

    @Test void releasedClaimCannotBeReacquiredWithItsPreviousRequestId() {
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "released", "released-tag")));
        assertThatThrownBy(() -> reservations.acquire(PRODUCT, FIRST, GENERATION))
                .isInstanceOfSatisfying(ProductImageUploadFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ProductImageUploadFailure.Kind.DUPLICATE));
        verify(storage, never()).create(any(), any(), any(), any(), any());
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test void ownershipRequiresExactStateRequestIdAndCallerGeneration() {
        assertThat(reservations.heldBy(PRODUCT, FIRST, GENERATION)).isFalse();
        assertThat(reservations.releasedBy(PRODUCT, FIRST, GENERATION)).isFalse();
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "held", "tag")));
        assertThat(reservations.heldBy(PRODUCT, FIRST, GENERATION)).isTrue();
        assertThat(reservations.heldBy(PRODUCT, SECOND, SECOND_GENERATION)).isFalse();
        assertThat(reservations.heldBy(PRODUCT, FIRST, SECOND_GENERATION)).isFalse();
        assertThat(reservations.releasedBy(PRODUCT, FIRST, GENERATION)).isFalse();
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "released", "tag")));
        assertThat(reservations.heldBy(PRODUCT, FIRST, GENERATION)).isFalse();
        assertThat(reservations.releasedBy(PRODUCT, FIRST, GENERATION)).isTrue();
        assertThat(reservations.releasedBy(PRODUCT, SECOND, SECOND_GENERATION)).isFalse();
        assertThat(reservations.releasedBy(PRODUCT, FIRST, SECOND_GENERATION)).isFalse();
    }

    @Test void releasesOnlyOwnHeldClaimWithConditionalReplacementAndNeverDeletes() {
        reservations.release(PRODUCT, FIRST, GENERATION);
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(SECOND, "held", "other-tag")));
        reservations.release(PRODUCT, FIRST, GENERATION);
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "released", "released-tag")));
        reservations.release(PRODUCT, FIRST, GENERATION);
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "held", "own-tag")));
        reservations.release(PRODUCT, FIRST, GENERATION);
        var content = ArgumentCaptor.forClass(byte[].class);
        verify(storage).replace(eq(StorageArea.PRIVATE), eq(KEY), eq("own-tag"), content.capture(),
                eq("application/json"), eq(Map.of("request-id", FIRST, "state", "released", "generation", GENERATION)));
        assertThat(new String(content.getValue(), StandardCharsets.UTF_8))
                .contains("\"state\":\"released\"").contains("\"generation\":\"" + GENERATION + "\"");
        verify(storage, never()).delete(any(), any());
    }

    @Test void readFailuresRemainStorageFailuresForAllOperations() {
        var unavailable = new StorageException(StorageException.Code.UNAVAILABLE);
        when(storage.head(StorageArea.PRIVATE, KEY)).thenThrow(unavailable);
        assertThatThrownBy(() -> reservations.acquire(PRODUCT, FIRST, GENERATION)).isSameAs(unavailable);
        assertThatThrownBy(() -> reservations.heldBy(PRODUCT, FIRST, GENERATION)).isSameAs(unavailable);
        assertThatThrownBy(() -> reservations.releasedBy(PRODUCT, FIRST, GENERATION)).isSameAs(unavailable);
        assertThatThrownBy(() -> reservations.release(PRODUCT, FIRST, GENERATION)).isSameAs(unavailable);
        verify(storage, never()).create(any(), any(), any(), any(), any());
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test void uncertainWritesAreNotConvertedToDuplicatesOrRetried() {
        var uncertain = new StorageException(StorageException.Code.WRITE_UNCERTAIN);
        when(storage.create(any(), any(), any(), any(), any())).thenThrow(uncertain);
        assertThatThrownBy(() -> reservations.acquire(PRODUCT, FIRST, GENERATION)).isSameAs(uncertain);
        verify(storage, times(1)).create(any(), any(), any(), any(), any());
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "released", "tag")));
        when(storage.replace(any(), any(), any(), any(), any(), any())).thenThrow(uncertain);
        assertThatThrownBy(() -> reservations.acquire(PRODUCT, SECOND, SECOND_GENERATION)).isSameAs(uncertain);
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "held", "tag")));
        assertThatThrownBy(() -> reservations.release(PRODUCT, FIRST, GENERATION)).isSameAs(uncertain);
    }

    @Test void concurrentInstancesPermitOnlyOneCreateOrReleasedClaimReplacement() throws Exception {
        for (boolean released : List.of(false, true)) {
            ObjectStorage shared = mock(ObjectStorage.class);
            ObjectMetadata initial = released ? claim(FIRST, "released", "initial-tag") : null;
            AtomicReference<ObjectMetadata> object = new AtomicReference<>(initial);
            CyclicBarrier bothObserved = new CyclicBarrier(2);
            when(shared.head(StorageArea.PRIVATE, KEY)).thenAnswer(call -> {
                ObjectMetadata observed = object.get();
                bothObserved.await(5, TimeUnit.SECONDS);
                return Optional.ofNullable(observed);
            });
            when(shared.create(eq(StorageArea.PRIVATE), eq(KEY), any(), eq("application/json"), anyMap()))
                    .thenAnswer(call -> {
                        Map<String, String> metadata = call.getArgument(4);
                        ObjectMetadata next = claim(metadata.get("request-id"), "held", UUID.randomUUID().toString());
                        if (!object.compareAndSet(null, next)) throw new StorageException(StorageException.Code.CONFLICT);
                        return next;
                    });
            when(shared.replace(eq(StorageArea.PRIVATE), eq(KEY), anyString(), any(), eq("application/json"), anyMap()))
                    .thenAnswer(call -> {
                        ObjectMetadata observed = object.get();
                        if (!call.<String>getArgument(2).equals(observed.eTag())) {
                            throw new StorageException(StorageException.Code.CONFLICT);
                        }
                        Map<String, String> metadata = call.getArgument(5);
                        ObjectMetadata next = claim(metadata.get("request-id"), "held", UUID.randomUUID().toString());
                        if (!object.compareAndSet(observed, next)) throw new StorageException(StorageException.Code.CONFLICT);
                        return next;
                    });
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                var one = executor.submit(() -> acquire(new ProductImageUploadReservations(shared), FIRST));
                var two = executor.submit(() -> acquire(new ProductImageUploadReservations(shared), SECOND));
                assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }
            assertThat(object.get().metadata().get("state")).isEqualTo("held");
            assertThat(object.get().metadata().get("request-id")).isIn(FIRST, SECOND);
            verify(shared, never()).delete(any(), any());
        }
    }

    @Test void staleReleaseCannotReplaceNextOwner() {
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(claim(FIRST, "held", "old-tag")));
        when(storage.replace(eq(StorageArea.PRIVATE), eq(KEY), eq("old-tag"), any(), any(), any()))
                .thenThrow(new StorageException(StorageException.Code.CONFLICT));
        assertThatThrownBy(() -> reservations.release(PRODUCT, FIRST, GENERATION)).isInstanceOf(StorageException.class)
                .satisfies(error -> assertThat(((StorageException) error).code()).isEqualTo(StorageException.Code.CONFLICT));
        verify(storage, times(1)).replace(any(), any(), any(), any(), any(), any());
        verify(storage, never()).delete(any(), any());
    }

    @Test void delayedReleaseCannotOverwriteNextRequestAfterReleaseWithContentBasedETags() throws Exception {
        ObjectStorage shared = mock(ObjectStorage.class);
        AtomicReference<ObjectMetadata> object = new AtomicReference<>();
        AtomicReference<DelayedReplacement> delayed = new AtomicReference<>();
        AtomicBoolean delayFirstRelease = new AtomicBoolean(true);
        when(shared.head(StorageArea.PRIVATE, KEY)).thenAnswer(call -> Optional.ofNullable(object.get()));
        when(shared.create(eq(StorageArea.PRIVATE), eq(KEY), any(), eq("application/json"), anyMap()))
                .thenAnswer(call -> {
                    byte[] body = call.getArgument(2);
                    Map<String, String> metadata = call.getArgument(4);
                    ObjectMetadata next = stored(body, metadata);
                    if (!object.compareAndSet(null, next)) throw new StorageException(StorageException.Code.CONFLICT);
                    return next;
                });
        when(shared.replace(eq(StorageArea.PRIVATE), eq(KEY), anyString(), any(), eq("application/json"), anyMap()))
                .thenAnswer(call -> {
                    String expectedETag = call.getArgument(2);
                    byte[] body = call.getArgument(3);
                    Map<String, String> metadata = call.getArgument(5);
                    if ("released".equals(metadata.get("state")) && delayFirstRelease.compareAndSet(true, false)) {
                        delayed.set(new DelayedReplacement(expectedETag, body, metadata));
                        throw new StorageException(StorageException.Code.WRITE_UNCERTAIN);
                    }
                    return conditionalReplace(object, expectedETag, body, metadata);
                });
        var one = new ProductImageUploadReservations(shared);
        one.acquire(PRODUCT, FIRST, GENERATION);
        ObjectMetadata originalHeld = object.get();
        assertThatThrownBy(() -> one.release(PRODUCT, FIRST, GENERATION)).isInstanceOf(StorageException.class);
        assertThat(object.get()).isSameAs(originalHeld);
        // Result reconciliation releases the old claim while its first release is still in transit.
        one.release(PRODUCT, FIRST, GENERATION);
        assertThat(object.get().metadata().get("state")).isEqualTo("released");
        // Reusing the old ID is forbidden; a no-file retry uses a new ID and a fresh generation.
        var restarted = new ProductImageUploadReservations(shared);
        assertThatThrownBy(() -> restarted.acquire(PRODUCT, FIRST, GENERATION)).isInstanceOf(ProductImageUploadFailure.class);
        restarted.acquire(PRODUCT, SECOND, SECOND_GENERATION);
        ObjectMetadata newHeld = object.get();
        assertThat(newHeld.metadata().get("generation")).isNotEqualTo(originalHeld.metadata().get("generation"));
        assertThat(newHeld.eTag()).isNotEqualTo(originalHeld.eTag());
        DelayedReplacement late = delayed.get();
        assertThatThrownBy(() -> conditionalReplace(object, late.expectedETag(), late.body(), late.metadata()))
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(StorageException.Code.CONFLICT));
        assertThat(object.get()).isSameAs(newHeld);
        assertThat(restarted.heldBy(PRODUCT, SECOND, SECOND_GENERATION)).isTrue();
        assertThatThrownBy(() -> new ProductImageUploadReservations(shared).acquire(PRODUCT, FIRST, GENERATION))
                .isInstanceOf(ProductImageUploadFailure.class);
    }

    @Test void legacyClaimWithoutGenerationCannotBeOwnedOrAutomaticallyReleased() {
        when(storage.head(StorageArea.PRIVATE, KEY)).thenReturn(Optional.of(
                new ObjectMetadata(StorageArea.PRIVATE, KEY, 1, "application/json", "legacy-tag", null,
                        Map.of("request-id", FIRST, "state", "held"))));
        reservations.release(PRODUCT, FIRST, GENERATION);
        assertThat(reservations.heldBy(PRODUCT, FIRST, GENERATION)).isFalse();
        verify(storage, never()).replace(any(), any(), any(), any(), any(), any());
    }

    @Test void delayedHeadCannotReleaseSameRequestWithNewGenerationAfterOwnershipCycle() throws Exception {
        ObjectStorage shared = mock(ObjectStorage.class);
        AtomicReference<ObjectMetadata> object = new AtomicReference<>();
        AtomicBoolean delayNextHead = new AtomicBoolean();
        CountDownLatch oldHeadStarted = new CountDownLatch(1);
        CountDownLatch oldHeadCanReturn = new CountDownLatch(1);
        when(shared.head(StorageArea.PRIVATE, KEY)).thenAnswer(call -> {
            if (delayNextHead.compareAndSet(true, false)) {
                oldHeadStarted.countDown();
                if (!oldHeadCanReturn.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Delayed HEAD timeout");
            }
            return Optional.ofNullable(object.get());
        });
        when(shared.create(eq(StorageArea.PRIVATE), eq(KEY), any(), eq("application/json"), anyMap()))
                .thenAnswer(call -> {
                    byte[] body = call.getArgument(2);
                    Map<String, String> metadata = call.getArgument(4);
                    ObjectMetadata next = stored(body, metadata);
                    if (!object.compareAndSet(null, next)) throw new StorageException(StorageException.Code.CONFLICT);
                    return next;
                });
        when(shared.replace(eq(StorageArea.PRIVATE), eq(KEY), anyString(), any(), eq("application/json"), anyMap()))
                .thenAnswer(call -> conditionalReplace(object, call.getArgument(2), call.getArgument(3), call.getArgument(5)));
        var firstOwner = new ProductImageUploadReservations(shared);
        var anotherInstance = new ProductImageUploadReservations(shared);
        firstOwner.acquire(PRODUCT, FIRST, GENERATION);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            delayNextHead.set(true);
            Future<?> oldRelease = executor.submit(() -> firstOwner.release(PRODUCT, FIRST, GENERATION));
            assertThat(oldHeadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // Reconciliation releases A; B retries without writing files and then releases its claim.
            anotherInstance.release(PRODUCT, FIRST, GENERATION);
            anotherInstance.acquire(PRODUCT, SECOND, SECOND_GENERATION);
            anotherInstance.release(PRODUCT, SECOND, SECOND_GENERATION);
            // A's immutable request ID can appear again after B, but now owns a different generation.
            String newGeneration = "10000000-0000-0000-0000-000000000003";
            anotherInstance.acquire(PRODUCT, FIRST, newGeneration);
            ObjectMetadata newOwner = object.get();
            oldHeadCanReturn.countDown();
            oldRelease.get(5, TimeUnit.SECONDS);
            assertThat(object.get()).isSameAs(newOwner);
            assertThat(anotherInstance.heldBy(PRODUCT, FIRST, newGeneration)).isTrue();
            assertThat(firstOwner.heldBy(PRODUCT, FIRST, GENERATION)).isFalse();
            verify(shared, times(4)).replace(any(), any(), any(), any(), any(), any());
        } finally {
            oldHeadCanReturn.countDown();
        }
    }

    private static boolean acquire(ProductImageUploadReservations reservations, String requestId) {
        try {
            reservations.acquire(PRODUCT, requestId, GENERATION);
            return true;
        } catch (ProductImageUploadFailure failure) {
            assertThat(failure.kind()).isEqualTo(ProductImageUploadFailure.Kind.DUPLICATE);
            return false;
        }
    }

    private static ObjectMetadata claim(String owner, String state, String eTag) {
        return new ObjectMetadata(StorageArea.PRIVATE, KEY, 1, "application/json", eTag, null,
                Map.of("request-id", owner, "state", state, "generation", GENERATION));
    }

    private static boolean validClaimMetadata(Map<String, String> metadata, String owner, String state) {
        return owner.equals(metadata.get("request-id")) && state.equals(metadata.get("state"))
                && metadata.getOrDefault("generation", "").matches("[0-9a-f-]{36}");
    }

    private static ObjectMetadata stored(byte[] body, Map<String, String> metadata) throws Exception {
        // Single-object ETags may repeat for identical bytes; they are not a monotonic version counter.
        String eTag = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body));
        return new ObjectMetadata(StorageArea.PRIVATE, KEY, body.length, "application/json", eTag, null, metadata);
    }

    private static ObjectMetadata conditionalReplace(AtomicReference<ObjectMetadata> object, String expectedETag,
            byte[] body, Map<String, String> metadata) throws Exception {
        ObjectMetadata before = object.get();
        if (before == null || !expectedETag.equals(before.eTag())) throw new StorageException(StorageException.Code.CONFLICT);
        ObjectMetadata next = stored(body, metadata);
        if (!object.compareAndSet(before, next)) throw new StorageException(StorageException.Code.CONFLICT);
        return next;
    }

    private record DelayedReplacement(String expectedETag, byte[] body, Map<String, String> metadata) { }
}
