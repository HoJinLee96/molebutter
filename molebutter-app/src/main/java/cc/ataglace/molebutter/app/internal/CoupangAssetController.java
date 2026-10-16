package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupang")
public class CoupangAssetController {
    private final ImageAssets assets;
    public CoupangAssetController(ImageAssets assets) {
        this.assets = assets;
    }
    @GetMapping("/assets/{id}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal UserPrincipal actor, @PathVariable String id) {
        var asset = assets.read(actor.userId(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.metadata().mimeType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox").cacheControl(CacheControl.noStore())
                .body(asset.bytes());
    }
}
