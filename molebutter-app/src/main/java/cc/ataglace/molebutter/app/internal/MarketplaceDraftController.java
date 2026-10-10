package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/marketplaces")
public class MarketplaceDraftController {
    private final MarketplaceDrafts drafts;
    private final ImageAssets assets;
    public MarketplaceDraftController(MarketplaceDrafts drafts,ImageAssets assets){this.drafts=drafts;this.assets=assets;}
    @GetMapping("/drafts")
    public ApiResponse<PageResponse<MarketplaceDrafts.Summary>> list(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.success(drafts.list(actor.userId(),query,page,size));
    }
    @PostMapping("/drafts")
    public ApiResponse<MarketplaceDrafts.Document> create(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceDrafts.Document input){
        return ApiResponse.success(drafts.create(actor.userId(),input));
    }
    @GetMapping("/drafts/{id}")
    public ApiResponse<MarketplaceDrafts.Document> get(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){
        return ApiResponse.success(drafts.get(actor.userId(),id));
    }
    @PutMapping("/drafts/{id}")
    public ApiResponse<MarketplaceDrafts.Document> save(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id,@RequestBody MarketplaceDrafts.Document input){
        return ApiResponse.success(drafts.save(actor.userId(),id,input.revision(),input));
    }
    @PostMapping("/drafts/validate")
    public ApiResponse<MarketplaceDrafts.Validation> validate(@AuthenticationPrincipal UserPrincipal actor,@RequestBody MarketplaceDrafts.Document input,
            @RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){
        return ApiResponse.success(drafts.validate(actor.userId(),input,requestId));
    }
    @PostMapping("/drafts/import/coupang/{sellerProductId}")
    public ApiResponse<MarketplaceDrafts.Document> importCoupang(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String sellerProductId,
            @RequestHeader(value="X-Coupang-Request-Id",required=false) String requestId){
        return ApiResponse.success(drafts.importCoupang(actor.userId(),sellerProductId,requestId));
    }
    @PostMapping(value="/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageAssets.Asset> upload(@AuthenticationPrincipal UserPrincipal actor,@RequestParam("file") MultipartFile file)throws IOException{
        return ApiResponse.success(assets.upload(actor.userId(),file.getBytes()));
    }
    @ExceptionHandler(MarketplaceDraftFailure.class)
    public ResponseEntity<ApiResponse<Void>> draftFailure(MarketplaceDraftFailure failure){
        var status=failure.kind()==MarketplaceDraftFailure.Kind.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ApiResponse.error(status,"MARKETPLACE_"+failure.kind(),failure.getMessage()));
    }
    @ExceptionHandler(MarketplaceFailure.class)
    public ResponseEntity<ApiResponse<Void>> externalReadFailure(MarketplaceFailure failure){
        var status=switch(failure.kind()){
            case CONFIGURATION->HttpStatus.SERVICE_UNAVAILABLE;
            case BUSY,CANCELLED->HttpStatus.CONFLICT;
            case RATE_LIMIT->HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT->HttpStatus.GATEWAY_TIMEOUT;
            default->HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(status,"COUPANG_"+failure.kind(),failure.getMessage()));
    }
    @GetMapping("/assets/{id}")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal UserPrincipal actor,@PathVariable String id){
        var asset=assets.read(actor.userId(),id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.metadata().mimeType()))
            .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox")
            .cacheControl(CacheControl.noStore()).body(asset.bytes());
    }
}
