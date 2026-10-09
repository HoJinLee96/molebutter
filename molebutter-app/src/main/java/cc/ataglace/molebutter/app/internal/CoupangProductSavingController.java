package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplaces/coupang/products/{productId}")
public final class CoupangProductSavingController {
    private final CoupangProductSaving saving;
    public CoupangProductSavingController(CoupangProductSaving saving){this.saving=saving;}
    @GetMapping("/edit-observation")
    public ApiResponse<CoupangProductSaving.Observation> observe(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId,
            @RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){return ApiResponse.success(saving.observe(actor.userId(),productId,requestId));}
    @PostMapping("/save-preparation")
    public ApiResponse<MarketplaceSubmissions.Preview> prepare(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId,
            @RequestBody CoupangProductSaving.Prepare input){return ApiResponse.success(saving.prepare(actor.userId(),productId,input));}
    @ExceptionHandler(MarketplaceEditingFailure.class)
    public ResponseEntity<ApiResponse<Void>> editing(MarketplaceEditingFailure failure){var status=failure.kind()==MarketplaceEditingFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_EDITING_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceSubmissionFailure.class)
    public ResponseEntity<ApiResponse<Void>> submission(MarketplaceSubmissionFailure failure){var status=switch(failure.kind()){case NOT_FOUND->HttpStatus.NOT_FOUND;case INVALID->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.CONFLICT;};return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_SUBMISSION_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceFailure.class)
    public ResponseEntity<ApiResponse<Void>> external(MarketplaceFailure failure){var status=switch(failure.kind()){case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;case BUSY,CANCELLED->HttpStatus.CONFLICT;case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;default->HttpStatus.BAD_GATEWAY;};return ResponseEntity.status(status).body(ApiResponse.error(status,"COUPANG_"+failure.kind(),failure.getMessage()));}
}
