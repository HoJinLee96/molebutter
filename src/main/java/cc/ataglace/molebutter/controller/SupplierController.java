package cc.ataglace.molebutter.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cc.ataglace.molebutter.dto.ApiResponse;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.infra.web.OperationAudit;
import cc.ataglace.molebutter.security.UserPrincipal;
import cc.ataglace.molebutter.service.product.*;
import lombok.RequiredArgsConstructor;

@RestController @RequiredArgsConstructor
public class SupplierController {
    private final SupplierPreferenceService preferences;
    private final ProductSupplierService suppliers;
    private final OfficialBrandStoreService officialStores;
    private final SupplierStockLookupService stockLookups;
    @PostMapping("/api/settings/supplier-stores/channel-preview")
    public ApiResponse<ChannelPreview> previewChannel(@AuthenticationPrincipal UserPrincipal user,@RequestBody ChannelRequest input){return ApiResponse.success(officialStores.preview(user.userId(),input.productUrl()));}
    public record Revision(Long revision) {}
    @GetMapping("/api/settings/preferred-suppliers") public ApiResponse<PreferenceView> preferences(@AuthenticationPrincipal UserPrincipal user){return ApiResponse.success(new PreferenceView(preferences.get(user.userId())));}
    @PutMapping("/api/settings/preferred-suppliers/{mall}") @OperationAudit(value="SUPPLIER_PREFERENCE_SAVE",targetType="SUPPLIER_PREFERENCE",targetIdPathVariable="mall")
    public ApiResponse<PreferenceView> saveMall(@AuthenticationPrincipal UserPrincipal user,@PathVariable cc.ataglace.molebutter.dto.product.ProductDtos.ProcurementMall mall,@RequestBody MallPreferenceInput input){return ApiResponse.success(new PreferenceView(preferences.saveMall(user.userId(),mall,input)));}
    @DeleteMapping("/api/settings/preferred-suppliers/{mall}") @OperationAudit(value="SUPPLIER_PREFERENCE_DELETE",targetType="SUPPLIER_PREFERENCE")
    public ApiResponse<PreferenceView> deleteMall(@AuthenticationPrincipal UserPrincipal user,@PathVariable cc.ataglace.molebutter.dto.product.ProductDtos.ProcurementMall mall,@RequestBody Revision input){return ApiResponse.success(new PreferenceView(preferences.deleteMall(user.userId(),mall,input.revision())));}
    @PostMapping("/api/settings/supplier-stores") @OperationAudit(value="SUPPLIER_STORE_CREATE",targetType="SUPPLIER_STORE")
    public ApiResponse<Store> createStore(@AuthenticationPrincipal UserPrincipal user,@RequestBody StoreInput input){return ApiResponse.success("BRAND_STORE".equals(input.kind())?officialStores.register(user.userId(),input):preferences.saveStore(user.userId(),null,input));}
    @PostMapping("/api/settings/supplier-stores/{id}") @OperationAudit(value="SUPPLIER_STORE_EDIT",targetType="SUPPLIER_STORE",targetIdPathVariable="id")
    public ApiResponse<Store> editStore(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@RequestBody StoreInput input){return ApiResponse.success(preferences.saveStore(user.userId(),id,input));}
    @GetMapping("/api/settings/supplier-store-identities") public ApiResponse<java.util.List<IdentityCandidate>> identities(@AuthenticationPrincipal UserPrincipal user){return ApiResponse.success(preferences.identityCandidates(user.userId()));}
    @PostMapping("/api/settings/supplier-stores/{id}/identity") @OperationAudit(value="SUPPLIER_STORE_IDENTITY",targetType="SUPPLIER_STORE",targetIdPathVariable="id")
    public ApiResponse<Store> bindIdentity(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@RequestBody IdentityInput input){return ApiResponse.success(preferences.bindIdentity(user.userId(),id,input));}
    @PostMapping("/api/settings/supplier-stores/{id}/delete") @OperationAudit(value="SUPPLIER_STORE_DELETE",targetType="SUPPLIER_STORE",targetIdPathVariable="id")
    public ApiResponse<Void> deleteStore(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@RequestBody Revision input){preferences.deleteStore(user.userId(),id,input.revision());return ApiResponse.success(null);}
    @PostMapping("/api/settings/preferred-suppliers") @OperationAudit(value="SUPPLIER_PREFERENCE_CREATE",targetType="SUPPLIER_PREFERENCE")
    public ApiResponse<Rule> createRule(@AuthenticationPrincipal UserPrincipal user,@RequestBody RuleInput input){return ApiResponse.success(preferences.saveRule(user.userId(),null,input));}
    @PostMapping("/api/settings/preferred-suppliers/{id}") @OperationAudit(value="SUPPLIER_PREFERENCE_EDIT",targetType="SUPPLIER_PREFERENCE",targetIdPathVariable="id")
    public ApiResponse<Rule> editRule(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@RequestBody RuleInput input){return ApiResponse.success(preferences.saveRule(user.userId(),id,input));}
    @PostMapping("/api/settings/preferred-suppliers/{id}/delete") @OperationAudit(value="SUPPLIER_PREFERENCE_DELETE",targetType="SUPPLIER_PREFERENCE",targetIdPathVariable="id")
    public ApiResponse<Void> deleteRule(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@RequestBody Revision input){preferences.deleteRule(user.userId(),id,input.revision());return ApiResponse.success(null);}
    @PostMapping("/api/products/{id}/suppliers/{supplier}/store") @OperationAudit(value="PRODUCT_SUPPLIER_STORE",targetType="PRODUCT",targetIdPathVariable="id")
    public ApiResponse<Comparison> assign(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@PathVariable long supplier,@RequestBody AssignmentInput input){return ApiResponse.success(suppliers.assign(user.userId(),id,supplier,input));}
    @PostMapping("/api/products/{id}/selection") @OperationAudit(value="PRODUCT_SUPPLIER_SELECT",targetType="PRODUCT",targetIdPathVariable="id")
    public ApiResponse<Comparison> select(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@RequestBody SelectionInput input){return ApiResponse.success(suppliers.select(user.userId(),id,input));}
    @PostMapping("/api/products/{id}/suppliers/{supplier}/stock-lookups")
    public ApiResponse<StockLookupJob> stockLookup(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@PathVariable long supplier,@RequestBody StockLookupInput input){return ApiResponse.success(stockLookups.enqueue(user.userId(),id,supplier,input));}
    @GetMapping("/api/products/{id}/suppliers/{supplier}/stock-lookups/{job}")
    public ApiResponse<StockLookupJob> stockLookupStatus(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@PathVariable long supplier,@PathVariable long job){return ApiResponse.success(stockLookups.get(user.userId(),id,supplier,job));}
    @DeleteMapping("/api/products/{id}/suppliers/{supplier}/stock-lookups/{job}")
    public ApiResponse<StockLookupJob> cancelStockLookup(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@PathVariable long supplier,@PathVariable long job,@RequestBody Revision input){return ApiResponse.success(stockLookups.cancel(user.userId(),id,supplier,job,input.revision()));}
    @PostMapping("/api/products/{id}/suppliers/{supplier}/stock-lookups/{job}/resume")
    public ApiResponse<StockLookupJob> resumeStockLookup(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@PathVariable long supplier,@PathVariable long job,@RequestBody Revision input){return ApiResponse.success(stockLookups.resume(user.userId(),id,supplier,job,input.revision()));}
}
