package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplace-orders")
public final class MarketplaceOrderController {
    private final MarketplaceOrders orders;
    private final MarketplaceOrderCollections collections;
    public MarketplaceOrderController(MarketplaceOrders orders, MarketplaceOrderCollections collections) {
        this.orders=orders;this.collections=collections;
    }
    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping
    public ApiResponse<MarketplaceOrders.OrderPage> list(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue="") String market,@RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String query,@RequestParam(required=false) String dateFrom,
            @RequestParam(required=false) String dateTo,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="") String claimType,
            @RequestParam(defaultValue="ORDERED") String dateBasis) {
        return ApiResponse.success(orders.orders(actor.userId(),new MarketplaceOrders.Search(market,status,query,dateFrom,dateTo,claimType,dateBasis),page,size));
    }
    @GetMapping("/{market}/{orderId}/detail")
    public ApiResponse<MarketplaceOrders.Detail> detail(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String market,@PathVariable String orderId) {
        return ApiResponse.success(orders.detail(actor.userId(),market,orderId));
    }
    @GetMapping("/collections")
    public ApiResponse<List<MarketplaceOrderCollections.Job>> history(@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(collections.list(actor.userId()));
    }
    @PostMapping("/collections")
    public ApiResponse<MarketplaceOrderCollections.Job> start(@AuthenticationPrincipal UserPrincipal actor,
            @RequestBody MarketplaceOrderCollections.Request request) {
        return ApiResponse.success(collections.start(actor.userId(),request));
    }
    @GetMapping("/collections/{id}")
    public ApiResponse<MarketplaceOrderCollections.Job> progress(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id) {
        return ApiResponse.success(collections.get(actor.userId(),id));
    }
    @PostMapping("/collections/{id}/cancel")
    public ApiResponse<MarketplaceOrderCollections.Job> cancel(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id) {
        return ApiResponse.success(collections.cancel(actor.userId(),id));
    }
    @PostMapping("/collections/{id}/retry")
    public ApiResponse<MarketplaceOrderCollections.Job> retry(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id) {
        return ApiResponse.success(collections.retry(actor.userId(),id));
    }
    @ExceptionHandler(MarketplaceFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(MarketplaceFailure failure) {
        var status=switch(failure.kind()) {
            case CONFIGURATION -> HttpStatus.SERVICE_UNAVAILABLE;
            case BUSY,CANCELLED -> HttpStatus.CONFLICT;
            case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiResponse.error(status,"MARKETPLACE_ORDER_"+failure.kind(),failure.getMessage()));
    }
}
