package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.common.api.PageResponse;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/coupang/working-copies")
public final class CoupangWorkingCopyController {
    private final CoupangWorkingCopies copies;
    public CoupangWorkingCopyController(CoupangWorkingCopies copies) {
        this.copies = copies;
    }
    @GetMapping
    public ApiResponse<PageResponse<CoupangWorkingCopies.Summary>> list(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue = "") String query, @RequestParam(required=false) String mode,@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(copies.list(actor.userId(), query, mode, page, size));
    }
    @GetMapping("/entry")
    public ApiResponse<CoupangWorkingCopies.Entry> entry(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(required = false) String productId) {
        return ApiResponse.success(copies.open(actor.userId(), productId, null));
    }
    @GetMapping("/{id}")
    public ApiResponse<CoupangWorkingCopies.Entry> get(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id, @RequestParam(defaultValue="false") boolean observed) {
        return ApiResponse.success(observed ? copies.openObserved(actor.userId(), id) : copies.open(actor.userId(), null, id));
    }
    @PostMapping
    @OperationAudit("COUPANG_WORKING_COPY_CREATE")
    public ApiResponse<CoupangWorkingCopies.Entry> create(@AuthenticationPrincipal UserPrincipal actor,
            @RequestBody CoupangWorkingCopies.Save input) {
        return ApiResponse.success(copies.save(actor.userId(), null, input));
    }
    @PutMapping("/{id}")
    @OperationAudit("COUPANG_WORKING_COPY_SAVE")
    public ApiResponse<CoupangWorkingCopies.Entry> save(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable String id, @RequestBody CoupangWorkingCopies.Save input) {
        return ApiResponse.success(copies.save(actor.userId(), id, input));
    }
}
