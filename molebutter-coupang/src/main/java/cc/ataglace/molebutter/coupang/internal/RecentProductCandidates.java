package cc.ataglace.molebutter.coupang.internal;

import java.util.List;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog.Product;

/** Internal listing port. Fetching candidates never publishes registered-product observations. */
public interface RecentProductCandidates {
    record Candidate(Product product, String raw, long order, String observedAt) {}
    record Page(List<Candidate> items, String nextToken, boolean hasNext) {
        public Page { items=List.copyOf(items); }
    }
    Page candidates(Long actor, String date, String token);
}
