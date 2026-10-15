package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/marketplaces/image-edits")
public final class MarketplaceImageEditController {
    private final ImageEdits edits;
    public MarketplaceImageEditController(ImageEdits edits){this.edits=edits;}
    @GetMapping("/source")
    public ApiResponse<ImageEdits.Source> open(@AuthenticationPrincipal UserPrincipal actor,@RequestParam(required=false) String url,@RequestParam(required=false) String assetId){return ApiResponse.success(edits.open(actor.userId(),url,assetId));}
    @GetMapping("/source/{token}")
    public ResponseEntity<byte[]> preview(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String token){var image=edits.preview(actor.userId(),token);return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(image.bytes());}
    @PostMapping
    public ApiResponse<ImageAssets.Asset> crop(@AuthenticationPrincipal UserPrincipal actor,@RequestBody ImageEdits.Request request){return ApiResponse.success(edits.crop(actor.userId(),request));}
}
