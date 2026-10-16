package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.media.api.R2Images;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupang/r2-images")
public class CoupangR2ImageController {
    private final R2Images images;
    public CoupangR2ImageController(R2Images images){this.images=images;}
    @GetMapping
    public ApiResponse<R2Images.Page> list(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String productCode,@RequestParam(required=false) String cursor,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return ApiResponse.success(images.list(actor.userId(),productCode,cursor));}
    @GetMapping("/resolve")
    public ApiResponse<R2Images.Image> resolve(@AuthenticationPrincipal UserPrincipal actor,@RequestParam String url,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return ApiResponse.success(images.resolve(actor.userId(),url));}
}
