package cc.ataglace.molebutter.marketplace.api;
import java.util.List;
/** Brand lookup only; does not register or modify a product. */
public interface CoupangBrands {
    Page search(Long actor,String name,int page,String requestId);
    void requireSelection(Long actor,String id,String name);
    record Brand(String brandId,String brandName,boolean isUIDRequired,List<String> allowedUIDTypes) {}
    record Page(List<Brand> items,int page,long totalCount,boolean hasNext) {}
}
