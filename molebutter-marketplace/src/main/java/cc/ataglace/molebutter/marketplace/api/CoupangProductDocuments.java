package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Map;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;
import cc.ataglace.molebutter.media.api.ImageAssets;

/** Compatibility input mapping port; its wire rules are implemented by the Coupang provider. */
public interface CoupangProductDocuments extends MarketplaceProductAdapter {
    Document importObserved(CoupangEditor.EditorDocument source);
    Document project(Document reference, Mapping mapping, CoupangEditor.EditorDocument source);
    Document registration(CoupangProductRegistrations.Input input);
    CoupangProductRegistrations.Input registrationInput(Document document);
    List<MarketplaceEditing.Change> expand(Document document, List<MarketplaceEditing.Change> changes);
    void requireBrand(Long actor, Document before, Document after);
    void protectNew(Document document);
    void protectImport(Document before, Document after);
    void validate(Long actor,Document document,MarketConfig config,String requestId,
                  Map<String,ImageAssets.Asset> images,List<Issue> errors,List<Issue> unverified);
}
