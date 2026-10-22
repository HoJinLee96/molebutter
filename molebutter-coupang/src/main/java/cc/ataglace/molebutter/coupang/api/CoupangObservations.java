package cc.ataglace.molebutter.coupang.api;

/** Server-only validated observations. Raw seller data is never returned in a public DTO. */
public interface CoupangObservations {
    record Read(String account, Long actor, long order, String kind, String productId, String optionId,
                String raw, CoupangCatalog.Product summary, CoupangCatalog.ProductDetail detail,
                CoupangCatalog.CurrentInventory current, String error) {}
    void record(Read read);
}
