package cc.ataglace.molebutter.imaging.internal.service;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.service.GeneratedImageStore.ImageKind;

class GeneratedImageStoreTest {
    private ProductLookupDto product(String code, String brand) {
        return new ProductLookupDto(code,brand,brand,"상품",null,null,List.of(),List.of(),Map.of(),"FREE",null,Map.of(),false,null,null,null);
    }
    @Test void keepsIndependentDefensiveSnapshots() {
        GeneratedImageStore store = new GeneratedImageStore(); var product = product("BAG1","DAKS");
        byte[] png = {1,2,3}; String first = store.put(1L, product, png); png[0]=9;
        String second = store.put(1L, product, png); store.get(1L, first, product)[1]=99;
        assertThat(first).isNotEqualTo(second);
        assertThat(store.get(1L, first, product)).containsExactly((byte)1,(byte)2,(byte)3);
        assertThat(store.get(1L, second, product)).containsExactly((byte)9,(byte)2,(byte)3);
    }
    @Test void storesExplicitImageKindsAndDefaultsLegacyPutToSize() {
        GeneratedImageStore store = new GeneratedImageStore();
        var product = product("BAG1", "DAKS");
        byte[] samePng = {1, 2, 3};
        String legacySize = store.put(1L, product, samePng);
        String explicitSize = store.put(1L, product, samePng, ImageKind.SIZE);
        String notice = store.put(1L, product, samePng, ImageKind.NOTICE);

        assertThat(store.getStored(1L, legacySize, product).kind()).isEqualTo(ImageKind.SIZE);
        assertThat(store.getStored(1L, explicitSize, product).kind()).isEqualTo(ImageKind.SIZE);
        assertThat(store.getStored(1L, notice, product).kind()).isEqualTo(ImageKind.NOTICE);
        assertThat(store.getStored(1L, notice, product).png()).isEqualTo(samePng);
        assertThat(store.get(1L, notice, product)).isEqualTo(samePng);
    }
    @Test void storedImageBytesRemainDefensiveForBothKinds() {
        GeneratedImageStore store = new GeneratedImageStore();
        var product = product("BAG1", "DAKS");
        for (ImageKind kind : ImageKind.values()) {
            byte[] source = {1, 2, 3};
            String id = store.put(1L, product, source, kind);
            source[0] = 9;
            var stored = store.getStored(1L, id, product);
            stored.png()[1] = 9;
            store.get(1L, id, product)[2] = 9;

            assertThat(stored.kind()).isEqualTo(kind);
            assertThat(stored.png()).containsExactly((byte)1, (byte)2, (byte)3);
            assertThat(store.getStored(1L, id, product).png()).containsExactly((byte)1, (byte)2, (byte)3);
        }
    }
    @Test void storedMetadataRequiresOwnerAndMatchingProductAndBrandForBothKinds() {
        GeneratedImageStore store = new GeneratedImageStore();
        var product = product("BAG1", "DAKS");
        for (ImageKind kind : ImageKind.values()) {
            String id = store.put(1L, product, new byte[]{1}, kind);
            assertThatThrownBy(() -> store.getStored(2L, id, product))
                    .isInstanceOfSatisfying(ImagingFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND));
            assertThatThrownBy(() -> store.getStored(1L, UUID.randomUUID().toString(), product))
                    .isInstanceOfSatisfying(ImagingFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND));
            assertThatThrownBy(() -> store.getStored(1L, id, product("BAG2", "DAKS")))
                    .isInstanceOfSatisfying(ImagingFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.INVALID_INPUT));
            assertThatThrownBy(() -> store.getStored(1L, id, product("BAG1", "LQT")))
                    .isInstanceOfSatisfying(ImagingFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.INVALID_INPUT));
        }
    }
    @Test void enforcesActorProductAndBrandOwnership() {
        GeneratedImageStore store = new GeneratedImageStore(); var product = product("BAG1","DAKS");
        String id=store.put(1L,product,new byte[]{1});
        assertThatThrownBy(() -> store.get(2L,id,product)).isInstanceOf(ImagingFailure.class).extracting(e -> ((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND);
        assertThatThrownBy(() -> store.get(1L,id,product("BAG2","DAKS"))).hasMessageContaining("다른 상품");
        assertThatThrownBy(() -> store.get(1L,id,product("BAG1","LQT"))).hasMessageContaining("다른 상품");
    }
    @Test void expiresOnReadAtTtlBoundaryAndScheduledCleanup() {
        MutableClock clock=new MutableClock(); GeneratedImageStore store=new GeneratedImageStore(clock);
        var product=product("BAG1","DAKS"); String id=store.put(1L,product,new byte[]{1});
        String notice=store.put(1L,product,new byte[]{2},ImageKind.NOTICE);
        clock.now=clock.now.plus(GeneratedImageStore.TTL);
        assertThatThrownBy(() -> store.get(1L,id,product)).hasMessageContaining("다시 추가");
        assertThatThrownBy(() -> store.getStored(1L,id,product)).isInstanceOfSatisfying(ImagingFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND));
        assertThatThrownBy(() -> store.getStored(1L,notice,product)).isInstanceOfSatisfying(ImagingFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND));
    }
    @Test void evictsOnlyOwnersOldestAtPerActorLimitAndBoundsGlobalStore() {
        GeneratedImageStore store=new GeneratedImageStore();var product=product("BAG1","DAKS");
        String other=store.put(2L,product,new byte[]{7});String oldest=store.put(1L,product,new byte[]{1});
        for(int i=0;i<16;i++)store.put(1L,product,new byte[]{2});
        assertThatThrownBy(() -> store.get(1L,oldest,product)).isInstanceOf(ImagingFailure.class);
        assertThat(store.get(2L,other,product)).containsExactly((byte)7);
        for(int i=0;i<15;i++)store.put(3L,product,new byte[]{3});
        assertThatThrownBy(() -> store.put(4L,product,new byte[]{4})).extracting(e -> ((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.BUSY);
    }
    @Test void rejectedGenerationPreservesSnapshotsAndSameSizeRetryCanReplaceOldest() {
        GeneratedImageStore store = new GeneratedImageStore();
        var product = product("BAG1", "DAKS");
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < GeneratedImageStore.MAX_ACTOR_ENTRIES; i++) {
            existing.add(store.put(1L, product, new byte[64 * 1024], i % 2 == 0 ? ImageKind.SIZE : ImageKind.NOTICE));
        }
        String other = store.put(2L, product, new byte[8 * 1024 * 1024]);
        store.put(3L, product, new byte[7 * 1024 * 1024]);

        assertThatThrownBy(() -> store.put(1L, product, new byte[128 * 1024]))
                .isInstanceOfSatisfying(ImagingFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.BUSY));
        for (int i = 0; i < existing.size(); i++) {
            var stored = store.getStored(1L, existing.get(i), product);
            assertThat(stored.png()).hasSize(64 * 1024);
            assertThat(stored.kind()).isEqualTo(i % 2 == 0 ? ImageKind.SIZE : ImageKind.NOTICE);
        }
        assertThat(store.get(2L, other, product)).hasSize(8 * 1024 * 1024);

        String replacement = store.put(1L, product, new byte[64 * 1024]);
        assertThat(store.get(1L, replacement, product)).hasSize(64 * 1024);
        assertThatThrownBy(() -> store.get(1L, existing.getFirst(), product))
                .isInstanceOfSatisfying(ImagingFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND));
        for (String id : existing.subList(1, existing.size())) {
            assertThat(store.get(1L, id, product)).hasSize(64 * 1024);
        }
    }
    @Test void globalRejectionPreservesAllCandidatesWhenActorByteLimitRequiresSeveralEvictions() {
        GeneratedImageStore store = new GeneratedImageStore();
        var product = product("BAG1", "DAKS");
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < 4; i++) existing.add(store.put(1L, product, new byte[1024 * 1024]));
        store.put(2L, product, new byte[6 * 1024 * 1024]);
        store.put(3L, product, new byte[6 * 1024 * 1024]);

        assertThatThrownBy(() -> store.put(1L, product, new byte[6 * 1024 * 1024]))
                .isInstanceOfSatisfying(ImagingFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.BUSY));
        for (String id : existing) assertThat(store.get(1L, id, product)).hasSize(1024 * 1024);
    }
    @Test void successfulGenerationAppliesSeveralOwnerEvictionsTogetherAtByteLimit() {
        GeneratedImageStore store = new GeneratedImageStore();
        var product = product("BAG1", "DAKS");
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < 4; i++) existing.add(store.put(1L, product, new byte[2 * 1024 * 1024]));
        String other = store.put(2L, product, new byte[8 * 1024 * 1024]);

        String replacement = store.put(1L, product, new byte[4 * 1024 * 1024]);
        assertThat(store.get(1L, replacement, product)).hasSize(4 * 1024 * 1024);
        for (String id : existing.subList(0, 2)) {
            assertThatThrownBy(() -> store.get(1L, id, product)).isInstanceOfSatisfying(ImagingFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND));
        }
        for (String id : existing.subList(2, 4)) assertThat(store.get(1L, id, product)).hasSize(2 * 1024 * 1024);
        assertThat(store.get(2L, other, product)).hasSize(8 * 1024 * 1024);
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-08T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
    }
}
