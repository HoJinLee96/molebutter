package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Set;

/** Installed capabilities, independent of whether a live account is configured. */
public final class MarketplaceChannels {
    public enum Provider { COUPANG, NAVER, ESM, LOTTEON, ELEVENST }
    public record Channel(String id, String label, Provider provider, boolean commonDraft,
            boolean productRead, boolean productCreate, boolean productUpdate, boolean orderRead) {}
    private static final List<Channel> CHANNELS = List.of(
        new Channel("COUPANG", "쿠팡", Provider.COUPANG, true, true, true, true, true),
        new Channel("NAVER", "스마트스토어", Provider.NAVER, true, true, true, true, false),
        new Channel("GMARKET", "G마켓", Provider.ESM, true, false, false, false, false),
        new Channel("AUCTION", "옥션", Provider.ESM, true, false, false, false, false),
        new Channel("LOTTEON", "롯데ON", Provider.LOTTEON, false, false, false, false, false),
        new Channel("ELEVENST", "11번가", Provider.ELEVENST, false, false, false, false, false));
    private MarketplaceChannels() {}
    public static List<Channel> all() { return CHANNELS; }
    public static Set<String> ids() { return CHANNELS.stream().map(Channel::id).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    public static Channel find(String id) { return CHANNELS.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown marketplace")); }
}
