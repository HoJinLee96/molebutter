package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.catalog.api.SharedSettingsService.*;


import cc.ataglace.molebutter.common.api.NamedSettingInput;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cc.ataglace.molebutter.app.internal.ApiResponse;
import cc.ataglace.molebutter.procurement.api.ProcurementSources;
import cc.ataglace.molebutter.app.internal.OperationAudit;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.catalog.api.SharedSettingsService;

import cc.ataglace.molebutter.inventory.api.InventoryPaymentMethodService;
import cc.ataglace.molebutter.inventory.api.InventoryPaymentMethodService.Method;
import lombok.RequiredArgsConstructor;

@RestController @RequiredArgsConstructor @RequestMapping("/api/settings")
public class SettingsController {
    private final SharedSettingsService settings;
    private final InventoryPaymentMethodService payments;
    private final BusinessAccess db;
    public record Supplier(String code,String name,boolean stockSupported) {}
    @GetMapping("/brands") public ApiResponse<List<Brand>> brands(@AuthenticationPrincipal UserPrincipal u) {return ApiResponse.success(settings.brands(u.userId()));}
    @PostMapping("/brands") @OperationAudit(value="BRAND_CREATE",targetType="BRAND")
    public ApiResponse<Brand> createBrand(@AuthenticationPrincipal UserPrincipal u,@RequestBody NamedSettingInput input) {return ApiResponse.success(settings.saveBrand(u.userId(),null,input));}
    @PostMapping("/brands/{id}") @OperationAudit(value="BRAND_EDIT",targetType="BRAND",targetIdPathVariable="id")
    public ApiResponse<Brand> editBrand(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody NamedSettingInput input) {return ApiResponse.success(settings.saveBrand(u.userId(),id,input));}
    @PostMapping("/brands/{id}/delete") @OperationAudit(value="BRAND_DELETE",targetType="BRAND",targetIdPathVariable="id")
    public ApiResponse<Void> deleteBrand(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody RevisionInput input) {settings.deleteBrand(u.userId(),id,input.revision());return ApiResponse.success(null);}
    @GetMapping("/payment-methods") public ApiResponse<List<Method>> paymentMethods(@AuthenticationPrincipal UserPrincipal u) {return ApiResponse.success(payments.list(u.userId()));}
    @PostMapping("/payment-methods") @OperationAudit(value="PAYMENT_METHOD_CREATE",targetType="PAYMENT_METHOD")
    public ApiResponse<Method> createPaymentMethod(@AuthenticationPrincipal UserPrincipal u,@RequestBody NamedSettingInput input) {return ApiResponse.success(payments.save(u.userId(),null,input));}
    @PostMapping("/payment-methods/{id}") @OperationAudit(value="PAYMENT_METHOD_EDIT",targetType="PAYMENT_METHOD",targetIdPathVariable="id")
    public ApiResponse<Method> editPaymentMethod(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody NamedSettingInput input) {return ApiResponse.success(payments.save(u.userId(),id,input));}
    @PostMapping("/payment-methods/{id}/delete") @OperationAudit(value="PAYMENT_METHOD_DELETE",targetType="PAYMENT_METHOD",targetIdPathVariable="id")
    public ApiResponse<Void> deletePaymentMethod(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody RevisionInput input) {payments.delete(u.userId(),id,input.revision());return ApiResponse.success(null);}
    @GetMapping("/suppliers") public ApiResponse<List<Supplier>> suppliers(@AuthenticationPrincipal UserPrincipal u) {
        db.productActor(u.userId(),false);
        return ApiResponse.success(ProcurementSources.supportedMalls().stream().map(m->new Supplier(m.name(),m.getDisplayName(),true)).toList());
    }
}
