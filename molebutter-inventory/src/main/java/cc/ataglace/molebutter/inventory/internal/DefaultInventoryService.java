package cc.ataglace.molebutter.inventory.internal;
import cc.ataglace.molebutter.inventory.api.InventoryDtos.*;
import cc.ataglace.molebutter.inventory.internal.InventoryMovementService;
import cc.ataglace.molebutter.inventory.internal.InventoryQueryService;
import cc.ataglace.molebutter.inventory.internal.InventoryPurchaseService;

import java.util.*;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.common.api.PageResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DefaultInventoryService implements cc.ataglace.molebutter.inventory.api.InventoryService {
    private final InventoryQueryService queries;
    private final InventoryPurchaseService purchases;
    private final InventoryMovementService movements;
    public boolean admin(Long actor) { return queries.admin(actor); }
    public PageResponse<Map<String,Object>> items(Long actor,String q,String productId,String state,int page,int size) { return queries.items(actor, q, productId, state, page, size); }
    public PageResponse<Map<String,Object>> items(Long actor,String q,String productId,String groupProductId,String state,int page,int size) { return queries.items(actor, q, productId, groupProductId, state, page, size); }
    public Map<String,Object> item(Long actor,long item) { return queries.item(actor, item); }
    public PageResponse<Map<String,Object>> purchases(Long actor,String q,int page,int size) { return queries.purchases(actor, q, page, size); }
    public PageResponse<Map<String,Object>> purchases(Long actor,String q,String brandId,int page,int size) { return queries.purchases(actor, q, brandId, page, size); }
    public Map<String,Object> purchase(Long actor,long purchase) { return queries.purchase(actor, purchase); }
    public PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,int page,int size) { return queries.movements(actor, itemId, productId, page, size); }
    public PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,String groupProductId,int page,int size) { return queries.movements(actor, itemId, productId, groupProductId, page, size); }
    public PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,String groupProductId,String brandId,int page,int size) { return queries.movements(actor, itemId, productId, groupProductId, brandId, page, size); }
    public PageResponse<Map<String,Object>> stockProducts(Long actor,String q,String productId,int page,int size) { return queries.stockProducts(actor, q, productId, page, size); }
    public PageResponse<Map<String,Object>> stockProducts(Long actor,String q,String productId,String brandId,int page,int size) { return queries.stockProducts(actor, q, productId, brandId, page, size); }
    public Map<String,Object> stockTotals(Long actor,String q,String productId,String brandId) { return queries.stockTotals(actor, q, productId, brandId); }
    public Map<String,Object> stockProduct(Long actor,long product) { return queries.stockProduct(actor, product); }
    public List<Map<String,Object>> summaries(Long actor,List<String> productIds) { return queries.summaries(actor, productIds); }
    public Map<String,Object> create(Long actor,String requestId,PurchaseInput input) { return purchases.create(actor, requestId, input); }
    public Map<String,Object> editPurchase(Long actor,long order,PurchaseEdit x) { return purchases.editPurchase(actor, order, x); }
    public Map<String,Object> deletePurchase(Long actor,long order,String requestId,PurchaseDelete x) { return purchases.deletePurchase(actor, order, requestId, x); }
    public Map<String,Object> editItem(Long actor,long item,ItemEdit x) { return purchases.editItem(actor, item, x); }
    public Map<String,Object> move(Long actor,long item,String requestId,MovementInput input) { return movements.move(actor, item, requestId, input); }
    public Map<String,Object> refund(Long actor,long movement,RefundInput x) { return movements.refund(actor, movement, x); }
}
