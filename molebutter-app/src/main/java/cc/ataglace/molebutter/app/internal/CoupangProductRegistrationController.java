package cc.ataglace.molebutter.app.internal;

import java.util.Set;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplaces/coupang/product-registrations/drafts")
public final class CoupangProductRegistrationController {
    private final CoupangProductRegistrations registrations;private final ObjectMapper json;
    public CoupangProductRegistrationController(CoupangProductRegistrations registrations,ObjectMapper json){this.registrations=registrations;this.json=json;}
    @PostMapping public ApiResponse<CoupangProductRegistrations.Draft> create(@AuthenticationPrincipal UserPrincipal actor,@RequestBody JsonNode body){check(body);return ApiResponse.success(registrations.create(actor.userId(),json.treeToValue(body,CoupangProductRegistrations.Input.class)));}
    @GetMapping("/{id}") public ApiResponse<CoupangProductRegistrations.Draft> get(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(registrations.get(actor.userId(),id));}
    @PutMapping("/{id}") public ApiResponse<CoupangProductRegistrations.Draft> save(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody JsonNode body){check(body);return ApiResponse.success(registrations.save(actor.userId(),id,json.treeToValue(body,CoupangProductRegistrations.Save.class)));}
    @PostMapping("/{id}/prepare") public ApiResponse<MarketplaceSubmissions.Preview> prepare(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody CoupangProductRegistrations.Prepare body){return ApiResponse.success(registrations.prepare(actor.userId(),id,body));}
    private static void check(JsonNode value){
        if(value.isObject())for(var entry:value.properties()){if(Set.of("sellerProductId","sellerProductItemId","vendorItemId","productId","source","current","currentChanges","statusName","limits").contains(entry.getKey()))throw new InputValidationFailure("신규 입력에 쿠팡 식별자나 원본 응답을 포함할 수 없습니다.");check(entry.getValue());}
        else if(value.isArray())for(var item:value)check(item);
    }
    @ExceptionHandler(MarketplaceDraftFailure.class) public ResponseEntity<ApiResponse<Void>> draftFailure(MarketplaceDraftFailure failure){var status=failure.kind()==MarketplaceDraftFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceEditingFailure.class) public ResponseEntity<ApiResponse<Void>> editingFailure(MarketplaceEditingFailure failure){var status=failure.kind()==MarketplaceEditingFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_EDITING_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceSubmissionFailure.class) public ResponseEntity<ApiResponse<Void>> submissionFailure(MarketplaceSubmissionFailure failure){var status=switch(failure.kind()){case NOT_FOUND->HttpStatus.NOT_FOUND;case INVALID->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.CONFLICT;};return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_SUBMISSION_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceFailure.class) public ResponseEntity<ApiResponse<Void>> externalFailure(MarketplaceFailure failure){var status=switch(failure.kind()){case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;case BUSY,CANCELLED->HttpStatus.CONFLICT;case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;default->HttpStatus.BAD_GATEWAY;};return ResponseEntity.status(status).body(ApiResponse.error(status,"COUPANG_"+failure.kind(),failure.getMessage()));}
}
