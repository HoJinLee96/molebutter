package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;

/** Compatibility editor mapping port; Naver contracts remain in the Naver provider. */
public interface NaverProductDocuments extends MarketplaceProductAdapter {
    default void validateInput(NaverEditor.Input input,boolean create) {}
    default void validateSettings(Long actor,Document document,boolean create,List<MarketplaceEditing.Change> changes) {}
    NaverEditor.Input normalize(NaverEditor.Input input);
    Document document(String id,Long revision,NaverEditor.Input input);
    NaverEditor.Input input(Document document);
    Document project(Document reference,NaverEditor.EditorDocument source,Mapping mapping);
    List<MarketplaceEditing.Change> diff(NaverEditor.Input observed,NaverEditor.Input edited);
}
