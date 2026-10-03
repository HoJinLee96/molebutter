package cc.ataglace.molebutter.controller;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cc.ataglace.molebutter.dto.ApiResponse;
import cc.ataglace.molebutter.infra.product.ProductSourceMetadata;
import cc.ataglace.molebutter.infra.web.OperationAudit;
import cc.ataglace.molebutter.security.UserPrincipal;
import cc.ataglace.molebutter.service.product.ProductStore;
import cc.ataglace.molebutter.service.product.SharedSettingsService;
import cc.ataglace.molebutter.service.product.SharedSettingsService.*;
import cc.ataglace.molebutter.service.inventory.InventoryPaymentMethodService;
import cc.ataglace.molebutter.service.inventory.InventoryPaymentMethodService.Method;
import lombok.RequiredArgsConstructor;

@RestController @RequiredArgsConstructor @RequestMapping("/api/settings")
public class SettingsController {
    private final SharedSettingsService settings;
    private final InventoryPaymentMethodService payments;
    private final ProductStore db;
    public record Supplier(String code,String name,boolean stockSupported) {}
    @GetMapping("/brands") public ApiResponse<List<Brand>> brands(@AuthenticationPrincipal UserPrincipal u) {return ApiResponse.success(settings.brands(u.userId()));}
    @PostMapping("/brands") @OperationAudit(value="BRAND_CREATE",targetType="BRAND")
    public ApiResponse<Brand> createBrand(@AuthenticationPrincipal UserPrincipal u,@RequestBody NameInput input) {return ApiResponse.success(settings.saveBrand(u.userId(),null,input));}
    @PostMapping("/brands/{id}") @OperationAudit(value="BRAND_EDIT",targetType="BRAND",targetIdPathVariable="id")
    public ApiResponse<Brand> editBrand(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody NameInput input) {return ApiResponse.success(settings.saveBrand(u.userId(),id,input));}
    @PostMapping("/brands/{id}/delete") @OperationAudit(value="BRAND_DELETE",targetType="BRAND",targetIdPathVariable="id")
    public ApiResponse<Void> deleteBrand(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody RevisionInput input) {settings.deleteBrand(u.userId(),id,input.revision());return ApiResponse.success(null);}
    @GetMapping("/payment-methods") public ApiResponse<List<Method>> paymentMethods(@AuthenticationPrincipal UserPrincipal u) {return ApiResponse.success(payments.list(u.userId()));}
    @PostMapping("/payment-methods") @OperationAudit(value="PAYMENT_METHOD_CREATE",targetType="PAYMENT_METHOD")
    public ApiResponse<Method> createPaymentMethod(@AuthenticationPrincipal UserPrincipal u,@RequestBody NameInput input) {return ApiResponse.success(payments.save(u.userId(),null,input));}
    @PostMapping("/payment-methods/{id}") @OperationAudit(value="PAYMENT_METHOD_EDIT",targetType="PAYMENT_METHOD",targetIdPathVariable="id")
    public ApiResponse<Method> editPaymentMethod(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody NameInput input) {return ApiResponse.success(payments.save(u.userId(),id,input));}
    @PostMapping("/payment-methods/{id}/delete") @OperationAudit(value="PAYMENT_METHOD_DELETE",targetType="PAYMENT_METHOD",targetIdPathVariable="id")
    public ApiResponse<Void> deletePaymentMethod(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody RevisionInput input) {payments.delete(u.userId(),id,input.revision());return ApiResponse.success(null);}
    @GetMapping("/suppliers") public ApiResponse<List<Supplier>> suppliers(@AuthenticationPrincipal UserPrincipal u) {
        db.authorize(u.userId(),false);
        return ApiResponse.success(ProductSourceMetadata.supportedMalls().stream().map(m->new Supplier(m.name(),m.getDisplayName(),true)).toList());
    }
}
