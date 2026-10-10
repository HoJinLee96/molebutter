package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.media.api.ImageAssets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
public class MarketplacePublicImageController {
    private final ImageAssets assets;
    public MarketplacePublicImageController(ImageAssets assets){this.assets=assets;}
    @GetMapping("/marketplace-images/{token}")
    public ResponseEntity<byte[]> image(@PathVariable String token){
        try{
            var image=assets.published(token);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.metadata().mimeType()))
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox")
                .header("Referrer-Policy","no-referrer").cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(image.bytes());
        }catch(InputValidationFailure|OperationFailure failure){return ResponseEntity.notFound().build();}
    }
}
