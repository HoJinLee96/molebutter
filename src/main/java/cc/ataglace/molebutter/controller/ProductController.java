package cc.ataglace.molebutter.controller;
import cc.ataglace.molebutter.exception.InputValidationFailure;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import cc.ataglace.molebutter.dto.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.infra.web.OperationAudit;
import cc.ataglace.molebutter.security.UserPrincipal;
import cc.ataglace.molebutter.service.product.*;
import lombok.RequiredArgsConstructor;

@RestController @RequiredArgsConstructor
public class ProductController {
    private final ProductService products;
    private final SupplierRefreshService refresh;
    private final ProductChangeService changes;
    @GetMapping("/api/products/{id}/changes") public ApiResponse<PageResponse<cc.ataglace.molebutter.dto.product.ChangeDtos.HistoryItem>> changes(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestParam(defaultValue="")String supplier,@RequestParam(defaultValue="ALL")String kind,@RequestParam(defaultValue="0")int page){return ApiResponse.success(changes.history(u.userId(),id,supplier,kind,page));}
    @PostMapping("/api/products/{id}/change-reviews") @OperationAudit(value="PRODUCT_CHANGE_REVIEW",targetType="PRODUCT",targetIdPathVariable="id")
    public ApiResponse<cc.ataglace.molebutter.dto.product.ChangeDtos.Summary> reviewChanges(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody cc.ataglace.molebutter.dto.product.ChangeDtos.ReviewInput input){return ApiResponse.success(changes.review(u.userId(),id,input));}
    @GetMapping("/api/products/change-counts") public ApiResponse<cc.ataglace.molebutter.dto.product.ChangeDtos.Counts> changeCounts(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="ALL")String mode,@RequestParam(defaultValue="")String status){return ApiResponse.success(products.changeCounts(u.userId(),q,mode,status));}
    public record Revision(Long revision) {}
    @GetMapping("/api/products") public ApiResponse<PageResponse<CatalogProduct>> list(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="ALL")String mode,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="ALL")String change){return ApiResponse.success(products.list(u.userId(),q,mode,status,page,size,change));}
    @GetMapping("/api/products/{id}") public ApiResponse<Map<String,Object>> detail(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){return ApiResponse.success(products.detail(u.userId(),id));}
    @GetMapping("/api/products/{id}/refresh-status") public ApiResponse<RefreshStatus> refreshStatus(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){return ApiResponse.success(refresh.status(u.userId(),id));}
    @PostMapping("/api/products/{id}") @OperationAudit(value="PRODUCT_EDIT",targetType="PRODUCT",targetIdPathVariable="id")
    public ApiResponse<CatalogProduct> edit(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody CatalogEdit input){return ApiResponse.success(products.edit(u.userId(),id,input));}
    @GetMapping("/api/products/{id}/history") public ApiResponse<PageResponse<Map<String,Object>>> history(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestParam(defaultValue="0")int page){return ApiResponse.success(products.history(u.userId(),id,page));}
    @PostMapping(value="/api/products/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @OperationAudit(value="PRODUCT_IMPORT",targetType="PRODUCT")
    public ApiResponse<ImportResult> upload(@AuthenticationPrincipal UserPrincipal u,@RequestParam("file")MultipartFile file)throws java.io.IOException{return ApiResponse.success(products.upload(u.userId(),file.getOriginalFilename(),file.getBytes()));}
    @GetMapping("/api/products/brands") public ApiResponse<List<String>> brands(@AuthenticationPrincipal UserPrincipal u){return ApiResponse.success(products.brands(u.userId()));}
    @PostMapping("/api/products/code-preview") public ApiResponse<Map<String,String>> codePreview(@AuthenticationPrincipal UserPrincipal u,@RequestBody CatalogEdit input){return ApiResponse.success(products.codePreview(u.userId(),input));}
    @PostMapping("/api/products") @OperationAudit(value="PRODUCT_CREATE",targetType="PRODUCT")
    public ApiResponse<CatalogProduct> create(@AuthenticationPrincipal UserPrincipal u,@RequestBody CatalogEdit input){return ApiResponse.success(products.create(u.userId(),input));}
    @PostMapping("/api/products/bulk") @OperationAudit(value="PRODUCT_BULK_EDIT",targetType="PRODUCT")
    public ApiResponse<Void> bulk(@AuthenticationPrincipal UserPrincipal u,@RequestBody BulkEdit input){products.bulk(u.userId(),input);return ApiResponse.success(null);}
    @PostMapping("/api/products/infer-brands") @OperationAudit(value="PRODUCT_BRAND_INFER",targetType="PRODUCT")
    public ApiResponse<BrandInferenceResult> inferBrands(@AuthenticationPrincipal UserPrincipal u,@RequestBody BrandInferenceInput input){return ApiResponse.success(products.inferBrands(u.userId(),input));}
    @PostMapping("/api/products/delete") @OperationAudit(value="PRODUCT_DELETE",targetType="PRODUCT")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal u,@RequestBody DeleteProducts input){products.delete(u.userId(),input);return ApiResponse.success(null);}
    @GetMapping("/api/products/{id}/duplicates") public ApiResponse<List<CatalogProduct>> duplicates(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){return ApiResponse.success(products.duplicates(u.userId(),id));}
    @PostMapping("/api/products/{id}/merge") @OperationAudit(value="PRODUCT_MERGE",targetType="PRODUCT",targetIdPathVariable="id")
    public ApiResponse<CatalogProduct> merge(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody MergeInput input){return ApiResponse.success(products.merge(u.userId(),id,input));}
    @GetMapping("/api/product-refresh/settings")public ApiResponse<ScheduleSettings> schedule(@AuthenticationPrincipal UserPrincipal u){return ApiResponse.success(products.schedule(u.userId()));}
    @PostMapping("/api/product-refresh/settings") @OperationAudit(value="PRODUCT_SCHEDULE",targetType="PRODUCT")
    public ApiResponse<ScheduleSettings> schedule(@AuthenticationPrincipal UserPrincipal u,@RequestBody ScheduleSettings s){return ApiResponse.success(products.updateSchedule(u.userId(),s));}
    @PostMapping("/api/product-refresh") @OperationAudit(value="PRODUCT_REFRESH_START",targetType="PRODUCT_REFRESH")
    public ApiResponse<Map<String,String>> start(@AuthenticationPrincipal UserPrincipal u,@RequestBody RefreshInput input){return ApiResponse.success(Map.of("id",refresh.start(u.userId(),input)));}
    @GetMapping("/api/product-refresh")public ApiResponse<RefreshRunPage> runs(@AuthenticationPrincipal UserPrincipal u,@RequestParam(defaultValue="")String from,@RequestParam(defaultValue="")String to,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="false")boolean failed,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)Long run){return ApiResponse.success(refresh.runs(u.userId(),new RefreshRunQuery(from,to,status,failed,page,size,run)));}
    @GetMapping("/api/product-refresh/{id}")public ApiResponse<PageResponse<Map<String,Object>>> items(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestParam(defaultValue="0")int page){return ApiResponse.success(refresh.items(u.userId(),id,page));}
    @GetMapping("/api/product-refresh/{id}/search-attempts") public ApiResponse<PageResponse<Map<String,Object>>> searchAttempts(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="")String code,@RequestParam(required=false)Long product){return ApiResponse.success(refresh.searchAttempts(u.userId(),id,page,code,product));}
    public record SearchGateResume(Long version){}
    @PostMapping("/api/product-refresh/search-gate/resume") @OperationAudit(value="PRODUCT_SEARCH_RESUME",targetType="PRODUCT_REFRESH")
    public ApiResponse<Object> resumeSearch(@AuthenticationPrincipal UserPrincipal u,@RequestBody SearchGateResume input){if(input.version()==null)throw new InputValidationFailure("버전이 필요합니다.");refresh.resumeSearchGate(u.userId(),input.version());return ApiResponse.success(null);}
    @PostMapping("/api/product-refresh/{id}/{action}") @OperationAudit(value="PRODUCT_REFRESH_CONTROL",targetType="PRODUCT_REFRESH",targetIdPathVariable="id")
    public ApiResponse<Object> control(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@PathVariable String action){if(action.equals("retry"))return ApiResponse.success(Map.of("id",refresh.retry(u.userId(),id)));refresh.control(u.userId(),id,action);return ApiResponse.success(null);}
}
