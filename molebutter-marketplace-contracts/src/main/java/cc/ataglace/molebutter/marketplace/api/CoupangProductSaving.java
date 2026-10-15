package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** Standalone product form: trusted observation plus explicit typed changes, never a browser source body. */
public interface CoupangProductSaving {
    Observation observe(Long actor,String productId,String requestId);
    MarketplaceSubmissions.Preview prepare(Long actor,String productId,Prepare input);
    record Observation(String token,String expiresAt,CoupangEditor.EditorDocument document,String draftId) {}
    record Change(String path,String sellerProductItemId,Object value) {}
    record Prepare(String token,List<Change> changes) {}
}
