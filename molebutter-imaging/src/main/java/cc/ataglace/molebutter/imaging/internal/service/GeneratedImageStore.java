package cc.ataglace.molebutter.imaging.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;

/** Owned PNG snapshots with aggregate and per-actor limits. */
@Service
public class GeneratedImageStore {
    public enum ImageKind { SIZE, NOTICE }

    /** Type and PNG are one owned snapshot; copies keep stored bytes immutable. */
    public record StoredImage(byte[] png, ImageKind kind) {
        public StoredImage { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
    static final int MAX_ENTRIES = 32;
    static final int MAX_ACTOR_ENTRIES = 16;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final long MAX_ACTOR_BYTES = 8L * 1024 * 1024;
    static final Duration TTL = Duration.ofHours(4);
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Clock clock;
    public GeneratedImageStore() { this(Clock.systemUTC()); }
    GeneratedImageStore(Clock clock) { this.clock = clock; }

    public synchronized String put(Long actor, ProductLookupDto product, byte[] png) {
        return put(actor, product, png, ImageKind.SIZE);
    }

    public synchronized String put(Long actor, ProductLookupDto product, byte[] png, ImageKind kind) {
        if (actor == null || actor <= 0 || png == null || png.length == 0 || png.length > MAX_ACTOR_BYTES || kind == null) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "생성 이미지가 올바르지 않습니다.");
        }
        cleanup();
        while (actorCount(actor) >= MAX_ACTOR_ENTRIES || actorBytes(actor) + png.length > MAX_ACTOR_BYTES) {
            String oldest = entries.entrySet().stream().filter(e -> actor.equals(e.getValue().actor()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            entries.remove(oldest);
        }
        if (entries.size() >= MAX_ENTRIES || totalBytes() + png.length > MAX_BYTES) {
            throw new ImagingFailure(ImagingFailure.Kind.BUSY, "생성 이미지 보관 공간이 가득 찼습니다. 잠시 후 다시 시도해주세요.");
        }
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(actor, product.productCode(), product.brandCode(), png.clone(), kind, clock.instant()));
        return id;
    }

    public synchronized byte[] get(Long actor, String id, ProductLookupDto product) {
        return getStored(actor, id, product).png();
    }

    public synchronized StoredImage getStored(Long actor, String id, ProductLookupDto product) {
        cleanup();
        Entry entry = entries.get(id);
        if (entry == null || !Objects.equals(entry.actor(), actor)) {
            throw new ImagingFailure(ImagingFailure.Kind.NOT_FOUND, "추가한 이미지를 찾을 수 없습니다. 다시 추가해주세요.");
        }
        if (!Objects.equals(entry.productCode(), product.productCode()) || !Objects.equals(entry.brandCode(), product.brandCode())) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "다른 상품의 생성 이미지는 다운로드할 수 없습니다.");
        }
        return new StoredImage(entry.png(), entry.kind());
    }

    @Scheduled(fixedDelayString = "${imaging.cleanup-delay-ms:60000}")
    public synchronized void cleanup() {
        Instant cutoff = clock.instant().minus(TTL);
        entries.values().removeIf(entry -> !entry.createdAt().isAfter(cutoff));
    }
    private long actorCount(Long actor) { return entries.values().stream().filter(e -> actor.equals(e.actor())).count(); }
    private long actorBytes(Long actor) { return entries.values().stream().filter(e -> actor.equals(e.actor())).mapToLong(e -> e.png().length).sum(); }
    private long totalBytes() { return entries.values().stream().mapToLong(e -> e.png().length).sum(); }
    private record Entry(Long actor, String productCode, String brandCode, byte[] png, ImageKind kind, Instant createdAt) { }
}
