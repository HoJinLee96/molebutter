package cc.ataglace.molebutter.imaging.internal.service;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.internal.client.ImageDownloadClient;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;

/** Shared public supplier data only. Private generated snapshots never enter this cache. */
@Service
public class LookupCacheService {
    private static final int MAX_PRODUCT_ENTRIES = 50;
    private static final int MAX_BASE_IMAGE_ENTRIES = 2;
    private final ProductLookupService productLookupService;
    private final ImageDownloadClient imageDownloadClient;
    private final Duration ttl;
    private final Clock clock;
    private final Semaphore productSlots = new Semaphore(2);
    private final Semaphore imageSlots = new Semaphore(1);
    private final Map<String, CacheEntry<ProductLookupDto>> products = new LinkedHashMap<>();
    private final Map<String, CacheEntry<BufferedImage>> baseImages = new LinkedHashMap<>();
    @Autowired
    public LookupCacheService(ProductLookupService products, ImageDownloadClient images,
            @Value("${imaging.lookup.cache.ttl:10m}") Duration ttl) {
        this(products, images, ttl, Clock.systemUTC());
    }
    LookupCacheService(ProductLookupService products, ImageDownloadClient images, Duration ttl, Clock clock) {
        this.productLookupService = products; this.imageDownloadClient = images; this.ttl = ttl; this.clock = clock;
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("Invalid imaging cache TTL");
    }
    public ProductLookupDto product(String productCode, String brandCode) {
        return lookup(productCode, brandCode, false);
    }
    /** Explicit user lookup refreshes supplier data; editor requests reuse the snapshot. */
    public ProductLookupDto refreshProduct(String productCode, String brandCode) {
        return lookup(productCode, brandCode, true);
    }
    private ProductLookupDto lookup(String productCode, String brandCode, boolean refresh) {
        String code = productCode == null ? "" : productCode.trim().toUpperCase(Locale.ROOT);
        String brand = brandCode == null || brandCode.isBlank() ? "DAKS" : brandCode.trim().toUpperCase(Locale.ROOT);
        String key = code + "|" + brand;
        synchronized (this) {
            cleanup();
            CacheEntry<ProductLookupDto> cached = products.get(key);
            if (cached != null && !refresh) return cached.value();
        }
        if (!productSlots.tryAcquire()) throw new ImagingFailure(ImagingFailure.Kind.BUSY, "상품을 조회 중입니다. 잠시 후 다시 시도해주세요.");
        try {
            ProductLookupDto product = productLookupService.lookup(code, brand);
            synchronized (this) {
                CacheEntry<ProductLookupDto> cached = products.get(key);
                if (cached != null && !refresh) return cached.value();
                products.remove(key);
                put(products, key, product, MAX_PRODUCT_ENTRIES);
            }
            return product;
        } finally { productSlots.release(); }
    }
    public BufferedImage baseImage(String url, String productCode) {
        synchronized (this) {
            cleanup();
            CacheEntry<BufferedImage> cached = baseImages.get(url);
            if (cached != null) return cached.value();
        }
        if (!imageSlots.tryAcquire()) throw new ImagingFailure(ImagingFailure.Kind.BUSY, "사진을 조회 중입니다. 잠시 후 다시 시도해주세요.");
        try {
            BufferedImage image = imageDownloadClient.downloadBaseImage(url, productCode);
            synchronized (this) { put(baseImages, url, image, MAX_BASE_IMAGE_ENTRIES); }
            return image;
        } finally { imageSlots.release(); }
    }
    private <T> void put(Map<String, CacheEntry<T>> map, String key, T value, int limit) {
        if (map.size() >= limit) map.remove(map.keySet().iterator().next());
        map.put(key, new CacheEntry<>(value, clock.instant()));
    }
    @Scheduled(fixedDelayString = "${imaging.cleanup-delay-ms:60000}")
    public synchronized void cleanup() {
        Instant cutoff = clock.instant().minus(ttl);
        products.values().removeIf(e -> !e.storedAt().isAfter(cutoff));
        baseImages.values().removeIf(e -> !e.storedAt().isAfter(cutoff));
    }
    private record CacheEntry<T>(T value, Instant storedAt) { }
}
