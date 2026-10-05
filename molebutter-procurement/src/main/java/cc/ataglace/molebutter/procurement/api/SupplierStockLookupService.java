package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierRefreshService;
import java.util.*;
import org.springframework.transaction.annotation.*;




public interface SupplierStockLookupService {
    public record Work(long id,long actor,SupplierRefreshService.Work product,long supplier,Offer offer,SupplierResult previous) {}
    StockLookupJob get(Long actor,long product,long supplier,long id);
    StockLookupJob enqueue(Long actor,long product,long supplier,StockLookupInput input);
    Work claim(String owner);
    void finish(String owner,Work w,SupplierResult result);
    void blocked(String owner,Work w,String message);
    StockLookupJob cancel(Long actor,long product,long supplier,long id,Long revision);
    StockLookupJob resume(Long actor,long product,long supplier,long id,Long revision);
}
