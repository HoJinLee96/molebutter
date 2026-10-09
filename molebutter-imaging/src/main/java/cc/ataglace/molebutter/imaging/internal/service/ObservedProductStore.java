package cc.ataglace.molebutter.imaging.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;

/** The immutable supplier data actually shown to an actor. Actions never refresh these source indexes. */
@Service
public class ObservedProductStore {
    static final Duration TTL = Duration.ofHours(4);
    static final int MAX_ENTRIES = 64;
    static final int MAX_ACTOR_ENTRIES = 4;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private final Map<Key, Entry> observations = new LinkedHashMap<>();
    private final Clock clock;
    public ObservedProductStore() { this(Clock.systemUTC()); }
    ObservedProductStore(Clock clock) { this.clock = clock; }
    public synchronized void put(Long actor, ProductLookupDto product) {
        ImagingInputs.actor(actor); cleanup();
        long size = estimatedBytes(product);
        if (size > MAX_BYTES) throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "상품 조회 정보가 너무 큽니다.");
        Key key = key(actor, product.productCode(), product.brandCode());
        Entry existing = observations.get(key);
        var evicted = new ArrayList<Key>();
        long actorEntries = actorCount(actor) - (existing == null ? 0 : 1);
        for (Key candidate : observations.keySet()) {
            if (actorEntries < MAX_ACTOR_ENTRIES) break;
            if (actor.equals(candidate.actor()) && !candidate.equals(key)) {
                evicted.add(candidate); actorEntries--;
            }
        }
        long afterBytes = totalBytes() - (existing == null ? 0 : existing.bytes()) + size;
        for (Key candidate : evicted) afterBytes -= observations.get(candidate).bytes();
        int afterEntries = observations.size() - (existing == null ? 0 : 1) - evicted.size() + 1;
        if (afterEntries > MAX_ENTRIES || afterBytes > MAX_BYTES) {
            throw new ImagingFailure(ImagingFailure.Kind.BUSY, "상품 조회 보관 공간이 가득 찼습니다. 잠시 후 다시 시도해주세요.");
        }
        observations.remove(key);
        evicted.forEach(observations::remove);
        observations.put(key, new Entry(product, clock.instant(), size));
    }
    public synchronized ProductLookupDto get(Long actor, String code, String brand) {
        ImagingInputs.actor(actor); ImagingInputs.product(code, brand); cleanup();
        Entry entry = observations.get(key(actor, code, brand));
        if (entry == null) throw new ImagingFailure(ImagingFailure.Kind.NOT_FOUND, "상품 조회 정보가 만료되었습니다. 상품을 다시 조회해주세요.");
        return entry.product();
    }
    private Key key(Long actor, String code, String brand) {
        return new Key(actor, code.trim().toUpperCase(Locale.ROOT),
                brand == null || brand.isBlank() ? "DAKS" : brand.trim().toUpperCase(Locale.ROOT));
    }
    @Scheduled(fixedDelayString = "${imaging.cleanup-delay-ms:60000}")
    public synchronized void cleanup() {
        Instant cutoff = clock.instant().minus(TTL);
        observations.values().removeIf(e -> !e.at().isAfter(cutoff));
    }
    private long actorCount(Long actor) { return observations.keySet().stream().filter(k -> actor.equals(k.actor())).count(); }
    private long totalBytes() { return observations.values().stream().mapToLong(Entry::bytes).sum(); }
    private static long textBytes(String text) { return text == null ? 0 : 48L + 2L * text.length(); }
    private static long estimatedBytes(ProductLookupDto p) {
        long bytes = 1024 + textBytes(p.productCode()) + textBytes(p.brandCode()) + textBytes(p.brandName()) + textBytes(p.productName())
                + textBytes(p.sizeLabel()) + textBytes(p.sizeDescription()) + textBytes(p.sizeGuideTypeName()) + textBytes(p.sizeGuideTemplateKey());
        if (p.sizeDimensions() != null) bytes += textBytes(p.sizeDimensions().width()) + textBytes(p.sizeDimensions().depth()) + textBytes(p.sizeDimensions().height());
        for (String url : p.imageUrls()) bytes += 32 + textBytes(url);
        for (String category : p.categoryNames()) bytes += 32 + textBytes(category);
        for (Map.Entry<String, String> field : p.notificationFields().entrySet()) bytes += 64 + textBytes(field.getKey()) + textBytes(field.getValue());
        for (Map.Entry<String, String> field : p.sizeMeasurements().entrySet()) bytes += 64 + textBytes(field.getKey()) + textBytes(field.getValue());
        return bytes;
    }
    private record Key(Long actor, String code, String brand) { }
    private record Entry(ProductLookupDto product, Instant at, long bytes) { }
}
