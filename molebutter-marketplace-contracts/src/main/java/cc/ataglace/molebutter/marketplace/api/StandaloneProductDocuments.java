package cc.ataglace.molebutter.marketplace.api;
import java.util.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
/** Provider extension for standalone observations; raw responses stay on the server. */
public interface StandaloneProductDocuments extends MarketplaceProductAdapter {
 record Observed(Document document,MarketplaceWriteGateway.Mapping mapping) {}
 Observed standalone(Long actor,String productId);
 void validate(Long actor,Document document,boolean create);
 List<cc.ataglace.molebutter.media.api.ImageInspection.Input> images(Document document,Set<String> existing);
 default Document commonReference(Document document){return document;}
}
