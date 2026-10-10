package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplaces/submissions")
public class MarketplaceSubmissionController {
    private final MarketplaceSubmissions submissions;
    public MarketplaceSubmissionController(MarketplaceSubmissions submissions){this.submissions=submissions;}
    record ExecuteRequest(String idempotencyKey) {}
    @PostMapping("/prepare")
    public ApiResponse<MarketplaceSubmissions.Preview> prepare(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceEditing.PrepareRequest input){
        return ApiResponse.success(submissions.prepare(actor.userId(),input));
    }
    @PostMapping("/{previewId}/execute")
    public ApiResponse<MarketplaceSubmissions.Execution> execute(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String previewId,@RequestBody ExecuteRequest input){
        return ApiResponse.success(submissions.execute(actor.userId(),previewId,input.idempotencyKey()));
    }
    @GetMapping("/{id}")
    public ApiResponse<MarketplaceSubmissions.Execution> get(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(submissions.get(actor.userId(),id));}
    @GetMapping
    public ApiResponse<PageResponse<MarketplaceSubmissions.Execution>> list(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String draftId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.success(submissions.list(actor.userId(),draftId,page,size));
    }
    @PostMapping("/{id}/retry")
    public ApiResponse<MarketplaceSubmissions.Execution> retry(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(submissions.retry(actor.userId(),id));}
    @PostMapping("/{id}/reconcile")
    public ApiResponse<MarketplaceSubmissions.Execution> reconcile(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(submissions.reconcile(actor.userId(),id));}
    @PostMapping("/{id}/revise")
    public ApiResponse<MarketplaceSubmissions.Execution> revise(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(submissions.revise(actor.userId(),id));}
    @ExceptionHandler(MarketplaceSubmissionFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(MarketplaceSubmissionFailure failure){
        var status=switch(failure.kind()){case NOT_FOUND->HttpStatus.NOT_FOUND;case INVALID->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.CONFLICT;};
        return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_SUBMISSION_"+failure.kind(),failure.getMessage()));
    }
    @ExceptionHandler(MarketplaceEditingFailure.class)
    public ResponseEntity<ApiResponse<Void>> editingFailure(MarketplaceEditingFailure failure){
        var status=failure.kind()==MarketplaceEditingFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_EDITING_"+failure.kind(),failure.getMessage()));
    }
    @ExceptionHandler(MarketplaceFailure.class)
    public ResponseEntity<ApiResponse<Void>> externalReadFailure(MarketplaceFailure failure){
        var status=switch(failure.kind()){case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;case BUSY,CANCELLED->HttpStatus.CONFLICT;case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;default->HttpStatus.BAD_GATEWAY;};
        return ResponseEntity.status(status).body(ApiResponse.error(status,"COUPANG_"+failure.kind(),failure.getMessage()));
    }
}
