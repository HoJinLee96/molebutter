package cc.ataglace.molebutter.catalog.api;

import java.util.*;
public interface CatalogQueries {
    record Product(String id,String productCode,String brandId,String brand,long revision,boolean deleted,String mergedInto) {}
    List<Product> products(Collection<Long> ids);
}
