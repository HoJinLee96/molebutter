package cc.ataglace.molebutter.app.internal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.coupang.api.*;
@RestController
@RequestMapping("/api/coupang/products")
public final class CoupangRegisteredProductController {
    private final CoupangRegisteredProducts products;
    public CoupangRegisteredProductController(CoupangRegisteredProducts products){this.products=products;}
    @GetMapping public ApiResponse<CoupangRegisteredProducts.Listing> list(@AuthenticationPrincipal UserPrincipal actor,
        @RequestParam(defaultValue="") String id,@RequestParam(defaultValue="") String brand,
        @RequestParam(defaultValue="") String name,@RequestParam(defaultValue="") String sku,
        @RequestParam(required=false) Long stock,@RequestParam(defaultValue="EQ") String stockComparison,
        @RequestParam(defaultValue="") String saleStatus,@RequestParam(required=false) Integer shippingDays,
        @RequestParam(defaultValue="EQ") String shippingComparison,@RequestParam(defaultValue="ALL") String group,@RequestParam(defaultValue="false") boolean deleted,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(defaultValue="CREATED_DESC") String sort,@RequestParam(defaultValue="ALL") String reason) {
        return ApiResponse.success(products.list(actor.userId(),new CoupangRegisteredProducts.Search(id,brand,name,sku,stock,stockComparison,saleStatus,shippingDays,shippingComparison,group,sort,reason),deleted,page,size));
    }
    @GetMapping("/options/{vendorItemId}/open")
    public org.springframework.http.ResponseEntity<Void> optionProduct(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String vendorItemId) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
            .location(java.net.URI.create(products.optionProductUrl(actor.userId(),vendorItemId))).build();
    }
    @GetMapping("/brands") public ApiResponse<java.util.List<String>> brands(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.brands(actor.userId()));}
    @GetMapping("/{productId}") public ApiResponse<CoupangRegisteredProducts.Detail> get(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId){return ApiResponse.success(products.get(actor.userId(),productId));}
    @GetMapping("/{productId}/history") public ApiResponse<PageResponse<CoupangRegisteredProducts.Event>> history(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(products.history(actor.userId(),productId,page,size));}
    @PostMapping("/collections") @OperationAudit("COUPANG_PRODUCT_COLLECTION_START")
    public ApiResponse<CoupangRegisteredProducts.Collection> collect(@AuthenticationPrincipal UserPrincipal actor,@RequestBody CoupangRegisteredProducts.Collect input){return ApiResponse.success(products.collect(actor.userId(),input));}
    @GetMapping("/collections/latest") public ApiResponse<CoupangRegisteredProducts.Collection> latest(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.latestCollection(actor.userId()));}
    @GetMapping("/collections/{id}") public ApiResponse<CoupangRegisteredProducts.Collection> collection(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(products.collection(actor.userId(),id));}
    @PostMapping("/collections/{id}/cancel") @OperationAudit("COUPANG_PRODUCT_COLLECTION_CANCEL")
    public ApiResponse<CoupangRegisteredProducts.Collection> cancel(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(products.cancel(actor.userId(),id));}
    @PostMapping("/collections/{id}/resume") @OperationAudit("COUPANG_PRODUCT_COLLECTION_RESUME")
    public ApiResponse<CoupangRegisteredProducts.Collection> resume(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(products.resume(actor.userId(),id));}
    @PostMapping("/{productId}/conflicts/resolve") @OperationAudit("COUPANG_PRODUCT_CONFLICT_RESOLVE")
    public ApiResponse<CoupangRegisteredProducts.Detail> resolve(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId,@RequestBody CoupangRegisteredProducts.Resolve input){return ApiResponse.success(products.resolve(actor.userId(),productId,input));}
    @PostMapping("/{productId}/deletion-confirmation") @OperationAudit("COUPANG_PRODUCT_DELETION_CONFIRM")
    public ApiResponse<CoupangRegisteredProducts.Detail> deletion(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId,@RequestBody CoupangRegisteredProducts.DeleteConfirmation input){return ApiResponse.success(products.confirmDeletion(actor.userId(),productId,input));}
    @PostMapping("/{productId}/actions/prepare") @OperationAudit("COUPANG_PRODUCT_ACTION_PREPARE")
    public ApiResponse<CoupangSubmissions.Preview> action(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId,@RequestBody CoupangRegisteredProducts.Action input){return ApiResponse.success(products.prepareAction(actor.userId(),productId,input));}
}
