package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.coupang.api.CoupangRegistrationDefaults;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/settings/coupang-registration")
public final class CoupangRegistrationDefaultsController {
    private final CoupangRegistrationDefaults defaults;
    public CoupangRegistrationDefaultsController(CoupangRegistrationDefaults defaults){this.defaults=defaults;}
    @GetMapping public ApiResponse<CoupangRegistrationDefaults.Settings> get(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.success(defaults.get(actor.userId()));}
    @PutMapping @OperationAudit("COUPANG_REGISTRATION_DEFAULTS_SAVE") public ApiResponse<CoupangRegistrationDefaults.Settings> save(@AuthenticationPrincipal UserPrincipal actor,@RequestBody CoupangRegistrationDefaults.Save input){return ApiResponse.success(defaults.save(actor.userId(),input));}
}
