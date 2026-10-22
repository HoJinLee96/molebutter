package cc.ataglace.molebutter.app.internal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.coupang.api.*;
@RestController
@RequestMapping("/api/coupang/products/{product}/options/{vendor}/quick-edit")
public final class CoupangQuickEditController {
    private final CoupangOptionEdits edits;
    private final CoupangRegisteredProducts products;
    public CoupangQuickEditController(CoupangOptionEdits edits,CoupangRegisteredProducts products){this.edits=edits;this.products=products;}
    @GetMapping("/history") public ApiResponse<cc.ataglace.molebutter.common.api.PageResponse<CoupangRegisteredProducts.OptionEvent>> history(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String product,@PathVariable String vendor,@RequestParam String kind,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(products.optionHistory(actor.userId(),product,vendor,kind,page,size));}
    @GetMapping public ApiResponse<CoupangOptionEdits.Context> open(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String product,@PathVariable String vendor,@RequestParam String kind,@RequestParam String internalProductId){return ApiResponse.success(edits.open(actor.userId(),product,vendor,kind,internalProductId));}
    @PostMapping("/prepare") @OperationAudit("COUPANG_OPTION_EDIT_PREPARE")
    public ApiResponse<CoupangOptionEdits.Result> prepare(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String product,@PathVariable String vendor,@RequestBody CoupangOptionEdits.Prepare input){return ApiResponse.success(edits.prepare(actor.userId(),product,vendor,input));}
}
