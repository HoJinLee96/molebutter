package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplaces")
public final class MarketplaceEditingController {
    private final MarketplaceEditing editing;
    public MarketplaceEditingController(MarketplaceEditing editing){this.editing=editing;}
    record RefreshRequest(String market) {}
    @PostMapping("/drafts/{id}/edit-sessions")
    public ApiResponse<MarketplaceEditing.Session> start(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(editing.start(actor.userId(),id));}
    @PutMapping("/edit-sessions/{id}/reference")
    public ApiResponse<MarketplaceEditing.Session> save(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody MarketplaceDrafts.Document input){return ApiResponse.success(editing.saveReference(actor.userId(),id,input));}
    @PostMapping("/edit-sessions/{id}/refresh")
    public ApiResponse<MarketplaceEditing.Session> refresh(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody RefreshRequest input){return ApiResponse.success(editing.refresh(actor.userId(),id,input.market()));}
    @ExceptionHandler(MarketplaceEditingFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(MarketplaceEditingFailure failure){
        var status=failure.kind()==MarketplaceEditingFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_EDITING_"+failure.kind(),failure.getMessage()));
    }
}
