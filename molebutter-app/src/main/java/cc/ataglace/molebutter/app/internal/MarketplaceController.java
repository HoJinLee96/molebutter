package cc.ataglace.molebutter.app.internal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
@RestController
@RequestMapping("/api/marketplaces/coupang")
public final class MarketplaceController {
    private final CoupangCatalog catalog;
    private final CoupangEditor editor;
    private final CoupangRequests requests;
    private final CoupangBrands brands;
    private final CoupangShippingPlaces shippingPlaces;
    public MarketplaceController(CoupangCatalog catalog,CoupangEditor editor,CoupangRequests requests,CoupangBrands brands,CoupangShippingPlaces shippingPlaces){this.catalog=catalog;this.editor=editor;this.requests=requests;this.brands=brands;this.shippingPlaces=shippingPlaces;}
    @GetMapping("/shipping-places/outbound/{code}")
    public ApiResponse<CoupangShippingPlaces.Page> outboundPlace(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String code,@RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){return ApiResponse.success(shippingPlaces.outbound(actor.userId(),code,requestId));}
    @GetMapping("/shipping-places/{kind}")
    public ApiResponse<CoupangShippingPlaces.Page> shippingPlaces(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String kind,@RequestParam(defaultValue="1") int page,@RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){
        if(!kind.equals("outbound")&&!kind.equals("return"))throw new cc.ataglace.molebutter.common.api.InputValidationFailure("주소록 종류를 확인해 주세요.");
        return ApiResponse.success(shippingPlaces.list(actor.userId(),kind.equals("return"),page,requestId));
    }
    @GetMapping("/brands")
    public ApiResponse<CoupangBrands.Page> brands(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String brandName,@RequestParam(defaultValue="1") int page,@RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){return ApiResponse.success(brands.search(actor.userId(),brandName,page,requestId));}
    @GetMapping("/products")
    public ApiResponse<CoupangCatalog.ProductPage> products(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue="10") int maxPerPage,@RequestParam(defaultValue="") String nextToken,
            @RequestParam(required=false) String sellerProductId,@RequestParam(required=false) String sellerProductName,
            @RequestParam(required=false) String status,@RequestParam(required=false) String createdAt,
            @RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){
        return ApiResponse.success(catalog.products(actor.userId(),maxPerPage,nextToken,new CoupangCatalog.ProductSearch(sellerProductId,sellerProductName,status,createdAt),requestId));
    }
    @GetMapping("/products/{sellerProductId}")
    public ApiResponse<CoupangCatalog.ProductDetail> product(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String sellerProductId,@RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){
        return ApiResponse.success(catalog.product(actor.userId(),sellerProductId,requestId));
    }
    @GetMapping("/products/{sellerProductId}/edit-data")
    public ApiResponse<CoupangEditor.EditorDocument> edit(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String sellerProductId,@RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){return ApiResponse.success(editor.edit(actor.userId(),sellerProductId,requestId));}
    @GetMapping("/categories/{categoryCode}/rules")
    public ApiResponse<CoupangEditor.CategoryRules> category(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String categoryCode,@RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){return ApiResponse.success(editor.category(actor.userId(),categoryCode,requestId));}
    @PostMapping("/requests/{requestId}/cancel")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String requestId){requests.cancel(actor.userId(),requestId);return ApiResponse.success(null);}
    @ExceptionHandler(MarketplaceFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(MarketplaceFailure failure){
        HttpStatus status=switch(failure.kind()){
            case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;
            case BUSY,CANCELLED->HttpStatus.CONFLICT;
            case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;
            default->HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(status,"COUPANG_"+failure.kind().name(),failure.getMessage()));
    }
}
