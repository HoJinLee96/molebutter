package cc.ataglace.molebutter.marketplace.api;

/** Existing-product saves are based on a server-owned expiring observation. */
public interface NaverProductSaving {
    Observation observe(Long actor,String originProductNo);
    MarketplaceSubmissions.Preview prepare(Long actor,String originProductNo,Prepare input);
    record Observation(String token,String expiresAt,NaverEditor.EditorDocument document,String draftId) {}
    record Prepare(String token,NaverEditor.Input input) {}
}
