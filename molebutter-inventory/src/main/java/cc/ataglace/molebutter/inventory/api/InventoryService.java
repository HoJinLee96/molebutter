package cc.ataglace.molebutter.inventory.api;
import cc.ataglace.molebutter.inventory.api.InventoryDtos.*;
import java.util.*;
import cc.ataglace.molebutter.common.api.PageResponse;

public interface InventoryService {
    boolean admin(Long actor);
    PageResponse<Map<String,Object>> items(Long actor,String q,String productId,String state,int page,int size);
    PageResponse<Map<String,Object>> items(Long actor,String q,String productId,String groupProductId,String state,int page,int size);
    Map<String,Object> item(Long actor,long item);
    PageResponse<Map<String,Object>> purchases(Long actor,String q,int page,int size);
    PageResponse<Map<String,Object>> purchases(Long actor,String q,String brandId,int page,int size);
    Map<String,Object> purchase(Long actor,long purchase);
    PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,int page,int size);
    PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,String groupProductId,int page,int size);
    PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,String groupProductId,String brandId,int page,int size);
    PageResponse<Map<String,Object>> stockProducts(Long actor,String q,String productId,int page,int size);
    PageResponse<Map<String,Object>> stockProducts(Long actor,String q,String productId,String brandId,int page,int size);
    Map<String,Object> stockTotals(Long actor,String q,String productId,String brandId);
    Map<String,Object> stockProduct(Long actor,long product);
    List<Map<String,Object>> summaries(Long actor,List<String> productIds);
    Map<String,Object> create(Long actor,String requestId,PurchaseInput input);
    Map<String,Object> editPurchase(Long actor,long order,PurchaseEdit x);
    Map<String,Object> deletePurchase(Long actor,long order,String requestId,PurchaseDelete x);
    Map<String,Object> editItem(Long actor,long item,ItemEdit x);
    Map<String,Object> move(Long actor,long item,String requestId,MovementInput input);
    Map<String,Object> refund(Long actor,long movement,RefundInput x);
}
