package cc.ataglace.molebutter.imaging.api;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.internal.job.JobRegistry;
import cc.ataglace.molebutter.imaging.internal.service.*;

/** Product imaging facade. The app supplies the authenticated user ID for every user operation. */
@Service
public class ProductImageWorkspace {
    private final LookupCacheService cache;
    private final SizeGuideService sizes;
    private final NoticeImageService notices;
    private final DownloadService downloads;
    private final JobRegistry jobs;
    private final ObservedProductStore observations;
    private final Semaphore renderSlots = new Semaphore(2);
    @Autowired
    ProductImageWorkspace(LookupCacheService cache, SizeGuideService sizes, DownloadService downloads, JobRegistry jobs, ObservedProductStore observations, NoticeImageService notices) {
        this.cache = cache; this.sizes = sizes; this.downloads = downloads; this.jobs = jobs; this.observations = observations; this.notices = notices;
    }
    public ProductLookupDto lookup(Long actor, String code, String brand) {
        ImagingInputs.actor(actor); ImagingInputs.product(code, brand);
        ProductLookupDto product = cache.refreshProduct(code, brand);
        observations.put(actor, product); return product;
    }
    public List<SizeGuideTemplateDto> listTemplates() { return sizes.listTemplates(); }
    public SizeGuidePreviewResponseDto preview(Long actor, String code, SizeGuidePreviewRequestDto request) {
        ImagingInputs.actor(actor); ImagingInputs.preview(code, request);
        ProductLookupDto product = observations.get(actor, code, request.brandCode());
        return rendered(() -> sizes.preview(product, request));
    }
    public GeneratedSizeImageDto generate(Long actor, String code, SizeGuidePreviewRequestDto request) {
        ImagingInputs.actor(actor); ImagingInputs.preview(code, request);
        ProductLookupDto product = observations.get(actor, code, request.brandCode());
        return rendered(() -> sizes.generate(actor, product, request));
    }
    public NoticeImageLayoutDto noticeLayout(Long actor, String code, NoticeImageRequestDto request) {
        ImagingInputs.actor(actor); ImagingInputs.notice(code, request);
        ProductLookupDto product = observations.get(actor, code, request.brandCode());
        return rendered(() -> notices.layout(product, request));
    }
    public GeneratedNoticeImageDto generateNotice(Long actor, String code, NoticeImageRequestDto request) {
        ImagingInputs.actor(actor); ImagingInputs.notice(code, request);
        ProductLookupDto product = observations.get(actor, code, request.brandCode());
        return rendered(() -> notices.generate(actor, product, request));
    }
    public JobDto startDownload(Long actor, DownloadRequestDto request) {
        ImagingInputs.actor(actor); ImagingInputs.download(request);
        ProductLookupDto product = observations.get(actor, request.productCode(), request.brandCode());
        return jobs.start(actor, () -> rendered(() -> downloads.downloadImages(actor, request, product)));
    }
    public ImageExportPlan prepareExport(Long actor, DownloadRequestDto request) {
        ImagingInputs.actor(actor); ImagingInputs.download(request);
        ProductLookupDto product = observations.get(actor, request.productCode(), request.brandCode());
        var snapshot = downloads.capture(actor, request, product);
        return () -> rendered(() -> downloads.exportImages(snapshot));
    }
    public JobDto job(Long actor, String id) { return jobs.get(actor, id); }
    public DownloadArtifact download(Long actor, String id) { return jobs.artifact(actor, id); }
    private <T> T rendered(Supplier<T> render) {
        if (!renderSlots.tryAcquire()) throw new ImagingFailure(ImagingFailure.Kind.BUSY, "이미지를 처리 중입니다. 잠시 후 다시 시도해주세요.");
        try { return render.get(); } finally { renderSlots.release(); }
    }
}
