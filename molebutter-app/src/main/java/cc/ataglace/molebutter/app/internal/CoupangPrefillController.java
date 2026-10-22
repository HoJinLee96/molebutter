package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/coupang/working-copies/prefill")
public final class CoupangPrefillController {
    private final CoupangPrefillService service;
    public CoupangPrefillController(CoupangPrefillService service){this.service=service;}
    @PostMapping public ApiResponse<CoupangPrefillService.Result> prepare(@AuthenticationPrincipal UserPrincipal actor,@RequestBody CoupangPrefillService.Request input){return ApiResponse.success(service.prepare(actor.userId(),input));}
    @GetMapping("/{token}") public ApiResponse<CoupangPrefillService.Result> get(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String token){return ApiResponse.success(service.get(actor.userId(),token));}
}
