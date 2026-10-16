package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.coupang.api.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupang/orders")
public final class CoupangOrderController {
    private final CoupangOrders orders;
    private final CoupangOrderCollections collections;
    public CoupangOrderController(CoupangOrders orders, CoupangOrderCollections collections) {
        this.orders = orders;
        this.collections = collections;
    }
    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
    @GetMapping
    public ApiResponse<CoupangOrders.OrderPage> list(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String claimType,
            @RequestParam(defaultValue = "ORDERED") String dateBasis) {
        return ApiResponse.success(orders.orders(actor.userId(),
                new CoupangOrders.Search(status, query, dateFrom, dateTo, claimType, dateBasis), page, size));
    }
    @GetMapping("/{orderId}/detail")
    public ApiResponse<CoupangOrders.Detail> detail(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String orderId) {
        return ApiResponse.success(orders.detail(actor.userId(), orderId));
    }
    @GetMapping("/collections")
    public ApiResponse<List<CoupangOrderCollections.Job>> history(@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.success(collections.list(actor.userId()));
    }
    @PostMapping("/collections")
    @OperationAudit("COUPANG_COLLECTION_START")
    public ApiResponse<CoupangOrderCollections.Job> start(@AuthenticationPrincipal UserPrincipal actor,
            @RequestBody CoupangOrderCollections.Request request) {
        return ApiResponse.success(collections.start(actor.userId(), request));
    }
    @GetMapping("/collections/{id}")
    public ApiResponse<CoupangOrderCollections.Job> progress(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(collections.get(actor.userId(), id));
    }
    @PostMapping("/collections/{id}/cancel")
    @OperationAudit("COUPANG_COLLECTION_CANCEL")
    public ApiResponse<CoupangOrderCollections.Job> cancel(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(collections.cancel(actor.userId(), id));
    }
    @PostMapping("/collections/{id}/retry")
    @OperationAudit("COUPANG_COLLECTION_RETRY")
    public ApiResponse<CoupangOrderCollections.Job> retry(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id) {
        return ApiResponse.success(collections.retry(actor.userId(), id));
    }
}
