package cc.ataglace.molebutter.app.internal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.coupang.api.*;
@RestController
@RequestMapping("/api/coupang/products/{product}/bulk-edit")
public final class CoupangBulkEditController {
    private final CoupangOptionEdits edits;
    public CoupangBulkEditController(CoupangOptionEdits edits){this.edits=edits;}
    public record GroupContext(CoupangWorkingCopies.Entry entry,CoupangRegisteredProducts.Detail registry,CoupangPricing.Settings settings,java.util.List<CoupangOptionEdits.Context> options) {}
    @PostMapping("/context")
    public ApiResponse<GroupContext> context(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String product,@RequestBody CoupangOptionEdits.BulkOpen input){
        var contexts=edits.stored(actor.userId(),product,input);var first=contexts.getFirst();
        // Send the shared product/session document once, rather than once per option.
        var options=contexts.stream().map(c->new CoupangOptionEdits.Context(null,c.optionId(),c.kind(),c.currentValue(),c.pendingValue(),null,c.source(),null,c.calculation(),c.currentInventory())).toList();
        return ApiResponse.success(new GroupContext(first.entry(),first.registry(),first.settings(),options));
    }
    @PostMapping("/options/{vendor}/prepare") @OperationAudit("COUPANG_OPTION_EDIT_PREPARE")
    public ApiResponse<CoupangOptionEdits.Result> prepare(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String product,@PathVariable String vendor,@RequestBody CoupangOptionEdits.Prepare input){return ApiResponse.success(edits.prepareStored(actor.userId(),product,vendor,input));}
}
