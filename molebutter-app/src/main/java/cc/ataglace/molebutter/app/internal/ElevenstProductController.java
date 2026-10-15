package cc.ataglace.molebutter.app.internal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.*;
@RestController
@RequestMapping("/api/marketplaces/elevenst")
final class ElevenstProductController {
 private final ElevenstProducts products;ElevenstProductController(ElevenstProducts products){this.products=products;}
 @GetMapping("/products") ApiResponse<PartnerEditor.ProductPage> list(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false)String productId,@RequestParam(required=false)String name,@RequestParam(required=false)String status,@RequestParam(required=false)String dateType,@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.success(products.list(actor.userId(),new ElevenstProducts.Query(productId,name,status,dateType,from,to,page,size)));}
 @GetMapping("/products/{productId}") ApiResponse<PartnerEditor.Observation> get(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String productId){return ApiResponse.success(products.get(actor.userId(),productId));}
 @GetMapping("/categories") ApiResponse<java.util.List<ElevenstProducts.Choice>> categories(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.categories(actor.userId()));}
 @GetMapping("/addressbooks") ApiResponse<java.util.List<ElevenstProducts.Address>> addresses(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String kind){return ApiResponse.success(products.addresses(actor.userId(),kind));}
 @GetMapping("/shipping-templates") ApiResponse<java.util.List<ElevenstProducts.Choice>> templates(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.shippingTemplates(actor.userId()));}
 @GetMapping("/brands") ApiResponse<ElevenstProducts.BrandPage> brands(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String name,@RequestParam(defaultValue="1")int page){return ApiResponse.success(products.brands(actor.userId(),name,page));}
 @GetMapping("/delivery-companies") ApiResponse<java.util.List<ElevenstProducts.Choice>> deliveryCompanies(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.deliveryCompanies(actor.userId()));}
 @GetMapping("/origins") ApiResponse<java.util.List<ElevenstProducts.Choice>> origins(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.origins(actor.userId()));}
 @GetMapping("/schema") ApiResponse<PartnerEditor.Schema> schema(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(products.schema(actor.userId()));}
 @ExceptionHandler(cc.ataglace.molebutter.common.api.InputValidationFailure.class)
 org.springframework.http.ResponseEntity<ApiResponse<Void>> invalid(cc.ataglace.molebutter.common.api.InputValidationFailure failure){return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.error(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_INPUT",failure.getMessage()));}
 @ExceptionHandler(MarketplaceFailure.class)
 org.springframework.http.ResponseEntity<ApiResponse<Void>> external(MarketplaceFailure failure){var status=switch(failure.kind()){case CONFIGURATION->org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;case RATE_LIMIT->org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;case TIMEOUT->org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;default->org.springframework.http.HttpStatus.BAD_GATEWAY;};return org.springframework.http.ResponseEntity.status(status).body(ApiResponse.error(status,"ELEVENST_"+failure.kind(),failure.getMessage()));}
}
