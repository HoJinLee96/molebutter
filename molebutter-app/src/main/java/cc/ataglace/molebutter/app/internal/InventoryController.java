package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.inventory.api.InventoryDtos.*;


import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cc.ataglace.molebutter.app.internal.ApiResponse;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.app.internal.OperationAudit;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.inventory.api.InventoryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventory;
    @GetMapping("/stock-products")
    public ApiResponse<PageResponse<Map<String,Object>>> stockProducts(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String productId,@RequestParam(defaultValue="") String brandId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return ApiResponse.success(inventory.stockProducts(u.userId(),q,productId,brandId,page,size));}
    @GetMapping("/stock-totals")
    public ApiResponse<Map<String,Object>> stockTotals(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String productId,@RequestParam(defaultValue="") String brandId){return ApiResponse.success(inventory.stockTotals(u.userId(),q,productId,brandId));}
    @GetMapping("/stock-products/{productId}")
    public ApiResponse<Map<String,Object>> stockProduct(@AuthenticationPrincipal UserPrincipal u,@PathVariable long productId){return ApiResponse.success(inventory.stockProduct(u.userId(),productId));}
    @GetMapping("/items")
    public ApiResponse<PageResponse<Map<String,Object>>> items(@AuthenticationPrincipal UserPrincipal u,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String productId,
            @RequestParam(defaultValue="") String groupProductId,@RequestParam(defaultValue="ALL") String state,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(inventory.items(u.userId(),q,productId,groupProductId,state,page,size));
    }
    @GetMapping("/items/{id}") public ApiResponse<Map<String,Object>> item(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){return ApiResponse.success(inventory.item(u.userId(),id));}
    @GetMapping("/summaries") public ApiResponse<List<Map<String,Object>>> summaries(@AuthenticationPrincipal UserPrincipal u,@RequestParam List<String> productIds){return ApiResponse.success(inventory.summaries(u.userId(),productIds));}
    @GetMapping("/purchases") public ApiResponse<PageResponse<Map<String,Object>>> purchases(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String brandId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(inventory.purchases(u.userId(),q,brandId,page,size));}
    @GetMapping("/purchases/{id}") public ApiResponse<Map<String,Object>> purchase(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){return ApiResponse.success(inventory.purchase(u.userId(),id));}
    @PostMapping("/purchases") @OperationAudit(value="INVENTORY_PURCHASE_CREATE",targetType="INVENTORY")
    public ApiResponse<Map<String,Object>> create(@AuthenticationPrincipal UserPrincipal u,@RequestHeader("X-Operation-Id") String request,@RequestBody PurchaseInput input){return ApiResponse.created(inventory.create(u.userId(),request,input));}
    @PostMapping("/purchases/{id}") @OperationAudit(value="INVENTORY_PURCHASE_EDIT",targetType="INVENTORY_PURCHASE",targetIdPathVariable="id")
    public ApiResponse<Map<String,Object>> editPurchase(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody PurchaseEdit input){return ApiResponse.success(inventory.editPurchase(u.userId(),id,input));}
    @PostMapping("/purchases/{id}/delete") @OperationAudit(value="INVENTORY_PURCHASE_DELETE",targetType="INVENTORY_PURCHASE",targetIdPathVariable="id")
    public ApiResponse<Map<String,Object>> deletePurchase(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestHeader("X-Operation-Id") String request,@RequestBody PurchaseDelete input){return ApiResponse.success(inventory.deletePurchase(u.userId(),id,request,input));}
    @PostMapping("/items/{id}") @OperationAudit(value="INVENTORY_ITEM_EDIT",targetType="INVENTORY_ITEM",targetIdPathVariable="id")
    public ApiResponse<Map<String,Object>> editItem(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody ItemEdit input){return ApiResponse.success(inventory.editItem(u.userId(),id,input));}
    @PostMapping("/items/{id}/movements") @OperationAudit(value="INVENTORY_MOVEMENT",targetType="INVENTORY_ITEM",targetIdPathVariable="id")
    public ApiResponse<Map<String,Object>> movement(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestHeader("X-Operation-Id") String request,@RequestBody MovementInput input){return ApiResponse.success(inventory.move(u.userId(),id,request,input));}
    @GetMapping("/movements") public ApiResponse<PageResponse<Map<String,Object>>> movements(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="") String itemId,@RequestParam(defaultValue="") String productId,@RequestParam(defaultValue="") String groupProductId,@RequestParam(defaultValue="") String brandId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(inventory.movements(u.userId(),itemId,productId,groupProductId,brandId,page,size));}
    @PostMapping("/movements/{id}/refund") @OperationAudit(value="INVENTORY_REFUND",targetType="INVENTORY_MOVEMENT",targetIdPathVariable="id")
    public ApiResponse<Map<String,Object>> refund(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody RefundInput input){return ApiResponse.success(inventory.refund(u.userId(),id,input));}
}
