package cc.ataglace.molebutter.catalog.internal;

import cc.ataglace.molebutter.catalog.api.CatalogQueries;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service @lombok.RequiredArgsConstructor
public class JdbcCatalogQueries implements CatalogQueries {
    private final JdbcTemplate jdbc;
    public List<Product> products(Collection<Long> ids){
        if(ids.isEmpty())return List.of();
        return jdbc.query("SELECT p.id,p.product_code,p.brand_id,COALESCE(b.name,'') brand,p.revision,p.deleted_at,p.merged_into FROM catalog_product p LEFT JOIN product_brand b ON b.id=p.brand_id WHERE p.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",(r,n)->new Product(r.getString("id"),r.getString("product_code"),r.getString("brand_id"),r.getString("brand"),r.getLong("revision"),r.getTimestamp("deleted_at")!=null,r.getString("merged_into")),ids.toArray());
    }
}
