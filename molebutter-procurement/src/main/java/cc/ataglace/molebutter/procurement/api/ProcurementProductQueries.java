package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import java.util.*;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;


public interface ProcurementProductQueries {
    PageResponse<CatalogProduct> list(Long actor, String q, String mode, String status, int page);
    PageResponse<CatalogProduct> list(Long actor, String q, String mode, String status, int page, int size);
    PageResponse<CatalogProduct> list(Long actor, String q, String mode, String status, int page, int size,
            String change);
    cc.ataglace.molebutter.procurement.api.ChangeDtos.Counts changeCounts(Long actor, String q, String mode,
            String status);
    CatalogProduct product(long id);
    Map<String, Object> detail(Long actor, long id);
    PageResponse<Map<String, Object>> history(Long actor, long id, int page);
    List<String> brands(Long actor);
    List<CatalogProduct> duplicates(Long actor, long id);
    void validateSupplierReference(long product,long supplier);
}
