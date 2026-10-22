package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.coupang.api.CoupangBrandLogos;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/coupang/working-copies/brand-logo")
public final class CoupangBrandLogoController {
    private final CoupangBrandLogos logos;
    public CoupangBrandLogoController(CoupangBrandLogos logos){this.logos=logos;}
    @PostMapping public ApiResponse<CoupangBrandLogos.Result> select(@AuthenticationPrincipal UserPrincipal actor,@RequestBody CoupangBrandLogos.Input input){return ApiResponse.success(logos.select(actor.userId(),input));}
}
