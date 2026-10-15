package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplaces/editor")
public final class MarketplaceProductEditorController {
    private final MarketplaceProductEditor editor;
    public MarketplaceProductEditorController(MarketplaceProductEditor editor){this.editor=editor;}
    @PostMapping("/validate")
    public ApiResponse<MarketplaceProductEditor.Validation> validate(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceProductEditor.Validate input){return ApiResponse.success(editor.validate(actor.userId(),input));}
    @GetMapping("/entry")
    public ApiResponse<MarketplaceProductEditor.Entry> open(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam String mode,@RequestParam(required=false) String market,@RequestParam(required=false) String productId,@RequestParam(required=false) String draftId){
        return ApiResponse.success(editor.open(actor.userId(),mode,market,productId,draftId));
    }
    @GetMapping("/candidates")
    public ApiResponse<cc.ataglace.molebutter.common.api.PageResponse<MarketplaceDrafts.Summary>> candidates(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(editor.candidates(actor.userId(),query,page,size));}
    @PostMapping("/drafts")
    public ApiResponse<MarketplaceEditing.DraftSaveResult> save(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceProductEditor.Save input){return ApiResponse.success(editor.save(actor.userId(),input));}
    record Refresh(String token) {}
    @PostMapping("/refresh")
    public ApiResponse<MarketplaceProductEditor.Entry> refresh(@AuthenticationPrincipal UserPrincipal actor,@RequestBody Refresh input){return ApiResponse.success(editor.refresh(actor.userId(),input.token()));}
    @PostMapping("/connect")
    public ApiResponse<MarketplaceProductEditor.Entry> connect(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceProductEditor.Link input){return ApiResponse.success(editor.connect(actor.userId(),input));}
    @PostMapping("/resolve")
    public ApiResponse<MarketplaceEditing.ResolveResult> resolve(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceEditing.Resolve input){return ApiResponse.success(editor.resolve(actor.userId(),input));}
    @PostMapping("/recreate")
    public ApiResponse<MarketplaceProductEditor.Entry> recreate(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceProductEditor.Recreate input){return ApiResponse.success(editor.recreate(actor.userId(),input));}
    @ExceptionHandler(MarketplaceEditingFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(MarketplaceEditingFailure f){var status=f.kind()==MarketplaceEditingFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_EDITING_"+f.kind(),f.getMessage()));}
}
