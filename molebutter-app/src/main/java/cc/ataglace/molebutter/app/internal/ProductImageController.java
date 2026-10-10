package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.imaging.api.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Authenticated adapter for the independent LFmall image workspace. */
@RestController
@RequestMapping("/api/product-images")
public class ProductImageController {
    private final ProductImageWorkspace workspace;
    private final BusinessAccess access;
    private final ProductImageUploadService uploads;

    public ProductImageController(ProductImageWorkspace workspace, BusinessAccess access, ProductImageUploadService uploads) {
        this.workspace = workspace;
        this.access = access;
        this.uploads = uploads;
    }

    private Long actor(UserPrincipal principal) {
        access.productActor(principal.userId(), false);
        return principal.userId();
    }

    @GetMapping("/products/{code}")
    public ApiResponse<ProductLookupDto> lookup(@AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String code, @RequestParam(required = false) String brand) {
        return ApiResponse.success(workspace.lookup(actor(principal), code, brand));
    }

    @GetMapping("/size-guide/templates")
    public ApiResponse<List<SizeGuideTemplateDto>> templates(@AuthenticationPrincipal UserPrincipal principal) {
        actor(principal);
        return ApiResponse.success(workspace.listTemplates());
    }

    @PostMapping("/products/{code}/size-guide/preview")
    public ApiResponse<SizeGuidePreviewResponseDto> preview(@AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String code, @RequestBody SizeGuidePreviewRequestDto request) {
        return ApiResponse.success(workspace.preview(actor(principal), code, request));
    }

    @PostMapping("/products/{code}/size-guide/images")
    public ApiResponse<GeneratedSizeImageDto> generate(@AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String code, @RequestBody SizeGuidePreviewRequestDto request) {
        return ApiResponse.success(workspace.generate(actor(principal), code, request));
    }

    @PostMapping("/products/{code}/notice-image/preview")
    public ApiResponse<NoticeImageLayoutDto> noticeLayout(@AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String code, @RequestBody NoticeImageRequestDto request) {
        return ApiResponse.success(workspace.noticeLayout(actor(principal), code, request));
    }

    @PostMapping("/products/{code}/notice-image/images")
    public ApiResponse<GeneratedNoticeImageDto> generateNotice(@AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String code, @RequestBody NoticeImageRequestDto request) {
        return ApiResponse.success(workspace.generateNotice(actor(principal), code, request));
    }

    @PostMapping("/products/download")
    public ResponseEntity<ApiResponse<JobDto>> startDownload(@AuthenticationPrincipal UserPrincipal principal,
            @RequestBody DownloadRequestDto request) {
        return ResponseEntity.accepted().body(ApiResponse.success(workspace.startDownload(actor(principal), request)));
    }

    @PostMapping("/products/upload")
    public ResponseEntity<ApiResponse<ProductImageUploadJob>> startUpload(@AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ProductImageUploadRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(uploads.start(actor(principal), request)));
    }

    @GetMapping("/products/upload/jobs/{id}")
    public ApiResponse<ProductImageUploadJob> uploadJob(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String id) {
        return ApiResponse.success(uploads.get(actor(principal), id));
    }

    @ExceptionHandler(ProductImageUploadFailure.class)
    public ResponseEntity<ApiResponse<Void>> uploadFailure(ProductImageUploadFailure failure) {
        var status = switch (failure.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUSY, CONFLICT, DUPLICATE -> HttpStatus.CONFLICT;
            case NOT_CONFIGURED, CHECK_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = failure.kind() == ProductImageUploadFailure.Kind.NOT_FOUND ? "IMAGING_UPLOAD_JOB_NOT_FOUND"
                : "IMAGING_UPLOAD_" + failure.kind();
        return ResponseEntity.status(status).body(ApiResponse.error(status, code, failure.getMessage()));
    }

    @GetMapping("/products/download/jobs/{id}")
    public ApiResponse<JobDto> job(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String id) {
        return ApiResponse.success(workspace.job(actor(principal), id));
    }

    @GetMapping("/products/download/jobs/{id}/archive")
    public ResponseEntity<byte[]> archive(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String id) {
        var file = workspace.download(actor(principal), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.downloadName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore())
                .body(file.bytes());
    }

    @ExceptionHandler(ImagingFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(ImagingFailure failure) {
        var status = switch (failure.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUSY -> HttpStatus.CONFLICT;
            case UPSTREAM -> HttpStatus.BAD_GATEWAY;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(status, "IMAGING_" + failure.kind(), failure.getMessage()));
    }
}
