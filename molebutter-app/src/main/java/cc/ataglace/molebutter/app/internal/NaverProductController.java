package cc.ataglace.molebutter.app.internal;

import java.util.Map;
import java.util.Set;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplaces/naver")
public final class NaverProductController {
    private final NaverCatalog catalog;
    private final NaverProductSaving saving;
    private final NaverProductRegistrations registrations;
    private final ObjectMapper json;
    public NaverProductController(NaverCatalog catalog,NaverProductSaving saving,NaverProductRegistrations registrations,ObjectMapper json){this.catalog=catalog;this.saving=saving;this.registrations=registrations;this.json=json;}
    @GetMapping("/products")
    public ApiResponse<NaverCatalog.ProductPage> products(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(defaultValue="1") Integer page,@RequestParam(defaultValue="20") Integer size,@RequestParam(required=false) String keyword,@RequestParam(required=false) String sellerManagementCode){return ApiResponse.success(catalog.products(actor.userId(),new NaverCatalog.Search(page,size,keyword,sellerManagementCode)));}
    @GetMapping("/metadata/{kind}")
    public ApiResponse<Object> metadata(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String kind,@RequestParam Map<String,String> query){return ApiResponse.success(catalog.metadata(actor.userId(),kind,query));}
    @GetMapping("/products/{id}/observation")
    public ApiResponse<NaverProductSaving.Observation> observe(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(saving.observe(actor.userId(),id));}
    @PostMapping("/products/{id}/prepare")
    public ApiResponse<MarketplaceSubmissions.Preview> prepare(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody NaverProductSaving.Prepare input){return ApiResponse.success(saving.prepare(actor.userId(),id,input));}
    @PostMapping("/product-registrations/drafts")
    public ApiResponse<NaverProductRegistrations.Draft> create(@AuthenticationPrincipal UserPrincipal actor,@RequestBody JsonNode input){checkInput(input);return ApiResponse.success(registrations.create(actor.userId(),json.treeToValue(input,NaverEditor.Input.class)));}
    @GetMapping("/product-registrations/drafts/{id}")
    public ApiResponse<NaverProductRegistrations.Draft> draft(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){return ApiResponse.success(registrations.get(actor.userId(),id));}
    @PutMapping("/product-registrations/drafts/{id}")
    public ApiResponse<NaverProductRegistrations.Draft> save(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody JsonNode input){checkKeys(input,Set.of("revision","input"));checkInput(input.path("input"));return ApiResponse.success(registrations.save(actor.userId(),id,json.treeToValue(input,NaverProductRegistrations.Save.class)));}
    @PostMapping("/product-registrations/drafts/{id}/prepare")
    public ApiResponse<MarketplaceSubmissions.Preview> prepareDraft(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody NaverProductRegistrations.Prepare input){return ApiResponse.success(registrations.prepare(actor.userId(),id,input));}
    private static void checkInput(JsonNode input){
        checkKeys(input,Set.of("fields","optionMode","optionNames","options","images","description"));
        for(var option:input.path("options"))checkKeys(option,Set.of("id","values","price","stockQuantity","sellerManagerCode","usable"));
        for(var image:input.path("images"))checkKeys(image,Set.of("id","assetId","url","representative","order"));
    }
    private static void checkKeys(JsonNode input,Set<String> allowed){if(!input.isObject())throw new InputValidationFailure("스마트스토어 입력 형식을 확인해 주세요.");for(var property:input.properties())if(!allowed.contains(property.getKey()))throw new InputValidationFailure("신규 입력에 외부 상품·옵션 번호나 원본 응답을 포함할 수 없습니다.");}
    @ExceptionHandler(InputValidationFailure.class)
    public ResponseEntity<ApiResponse<Void>> inputFailure(InputValidationFailure failure){return ResponseEntity.badRequest().body(ApiResponse.error(HttpStatus.BAD_REQUEST,"INVALID_INPUT",failure.getMessage()));}
    @ExceptionHandler(MarketplaceDraftFailure.class)
    public ResponseEntity<ApiResponse<Void>> draftFailure(MarketplaceDraftFailure failure){var status=failure.kind()==MarketplaceDraftFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceEditingFailure.class)
    public ResponseEntity<ApiResponse<Void>> editingFailure(MarketplaceEditingFailure failure){var status=failure.kind()==MarketplaceEditingFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_EDITING_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceSubmissionFailure.class)
    public ResponseEntity<ApiResponse<Void>> submissionFailure(MarketplaceSubmissionFailure failure){var status=switch(failure.kind()){case NOT_FOUND->HttpStatus.NOT_FOUND;case INVALID->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.CONFLICT;};return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_SUBMISSION_"+failure.kind(),failure.getMessage()));}
    @ExceptionHandler(MarketplaceFailure.class)
    public ResponseEntity<ApiResponse<Void>> externalFailure(MarketplaceFailure failure){var status=switch(failure.kind()){case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;case BUSY,CANCELLED->HttpStatus.CONFLICT;case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;default->HttpStatus.BAD_GATEWAY;};return ResponseEntity.status(status).body(ApiResponse.error(status,"NAVER_"+failure.kind(),failure.getMessage()));}
}
