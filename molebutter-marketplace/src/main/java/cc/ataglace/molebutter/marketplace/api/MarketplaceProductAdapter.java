package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.Change;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;

/** Provider-owned mapping boundary. Observations and remote bodies stay on the server. */
public interface MarketplaceProductAdapter {
    String market();
    String accountKey();
    boolean supports(Document document);
    default Document normalize(Document document) { return document; }
    Document newDocument(Document reference);
    Document observe(Long actor, Document reference, Mapping mapping);
    Document apply(Document document, List<Change> changes);
    default Mapping legacyMapping(Document document, String accountKey) { return null; }
}
