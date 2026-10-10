package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Map;

/** SmartStore product and category metadata queries. */
public interface NaverCatalog {
    ProductPage products(Long actor,Search input);
    NaverEditor.EditorDocument editor(Long actor,String originProductNo);
    Object metadata(Long actor,String kind,Map<String,String> query);
    record Search(Integer page,Integer size,String keyword,String sellerManagementCode) {}
    record Product(String originProductNo,String channelProductNo,String groupProductNo,String name,
                   String statusType,String displayStatusType,Long salePrice,Long stockQuantity) {}
    record ProductPage(List<Product> products,int page,int totalPages,boolean hasNext) {}
}
