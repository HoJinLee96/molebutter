package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Map;

/** Bounded app inputs. Remote identifiers and source responses are never accepted here. */
public interface PartnerEditor {
    record Input(Map<String,Object> fields,List<Option> options,List<Image> images,String description,
                 String noticeType,List<MarketplaceDrafts.Attribute> notices,List<Certification> certifications) {}
    record Option(String id,String name,String sku,String price,String quantity,List<MarketplaceDrafts.Attribute> attributes,Boolean usable) {}
    record Image(String id,String assetId,String url,boolean representative,int order) {}
    record Certification(String type,String number,String agency) {}
    record Field(String key,String label,String section,String type,boolean required,List<String> choices) {}
    record Schema(List<Field> fields,int maxOptions,int maxImages,String warning) {}
    record Identity(String optionId,String remoteId) {}
    record Observation(Input input,String productId,Map<String,String> siteProducts,List<Identity> identities,
                       List<String> readonlyFields,String warning) {}
    record Product(String id,String siteProductId,String name,String status,String price,String quantity) {}
    record ProductPage(List<Product> items,int page,boolean hasNext) {}
}
