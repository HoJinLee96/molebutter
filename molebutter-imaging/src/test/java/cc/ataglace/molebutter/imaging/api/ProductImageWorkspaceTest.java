package cc.ataglace.molebutter.imaging.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.internal.service.*;
import cc.ataglace.molebutter.imaging.internal.job.JobRegistry;
import cc.ataglace.molebutter.imaging.internal.client.ImageDownloadClient;

class ProductImageWorkspaceTest {
    private ProductLookupDto product(String url){return new ProductLookupDto("BAG1","DAKS","닥스","상품",null,null,List.of(url),List.of(),Map.of(),"FREE",null,Map.of(),false,null,null,null);}
    @Test void queuedDownloadPreservesSelectedPngsAndOrderAfterEvictionAndAnotherLookup() throws Exception {
        var cache = mock(LookupCacheService.class);
        var images = mock(ImageDownloadClient.class);
        var sizes = mock(SizeGuideService.class);
        var generated = new GeneratedImageStore();
        var downloads = new DownloadService(images, null, sizes, generated);
        var queue = new ArrayList<Runnable>();
        var jobs = new JobRegistry(queue::add, Duration.ofHours(1));
        var workspace = new ProductImageWorkspace(cache, sizes, downloads, jobs, new ObservedProductStore(), null);
        var old = product("https://nimg.lfmall.co.kr/old.jpg");
        var newer = product("https://nimg.lfmall.co.kr/new.jpg");
        when(cache.refreshProduct("BAG1", "DAKS")).thenReturn(old, newer);
        workspace.lookup(1L, "BAG1", "DAKS");
        byte[] sizePng = png(Color.BLUE);
        byte[] noticePng = png(Color.GREEN);
        String sizeId = generated.put(1L, old, sizePng, GeneratedImageStore.ImageKind.SIZE);
        String noticeId = generated.put(1L, old, noticePng, GeneratedImageStore.ImageKind.NOTICE);
        for (int i = 0; i < 14; i++) generated.put(1L, old, sizePng);
        var request = new DownloadRequestDto("BAG1", "DAKS", null, false, false, null, List.of(
                new DownloadImageItemDto(0, null, old.imageUrls().getFirst()),
                new DownloadImageItemDto(null, noticeId), new DownloadImageItemDto(null, sizeId)));

        var accepted = workspace.startDownload(1L, request);
        assertThat(queue).hasSize(1);
        verifyNoInteractions(images, sizes);
        generated.put(1L, old, sizePng);
        generated.put(1L, old, noticePng);
        assertThatThrownBy(() -> generated.get(1L, sizeId, old)).isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(() -> generated.get(1L, noticeId, old)).isInstanceOf(ImagingFailure.class);
        workspace.lookup(1L, "BAG1", "DAKS");
        when(images.downloadProductImage(old.imageUrls().getFirst(), old.productCode())).thenReturn(
                new ImageDownloadClient.DownloadedImage(png(Color.RED), "image/png", ".png"));
        queue.getFirst().run();

        var finished = workspace.job(1L, accepted.id());
        assertThat(finished.status()).isEqualTo(JobDto.STATUS_SUCCEEDED);
        assertThat(finished.result().savedFiles()).containsExactly("01.png", "상품정보.png", "사이즈.png");
        var artifact = workspace.download(1L, accepted.id());
        assertThat(artifact.downloadName()).isEqualTo("BAG1.zip");
        Map<String, byte[]> zipFiles = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(artifact.bytes()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                zipFiles.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertThat(zipFiles.keySet()).containsExactly("01.png", "상품정보.png", "사이즈.png");
        assertThat(zipFiles.get("상품정보.png")).isEqualTo(noticePng);
        assertThat(zipFiles.get("사이즈.png")).isEqualTo(sizePng);
        verify(images).downloadProductImage(old.imageUrls().getFirst(), old.productCode());
        verifyNoMoreInteractions(images);
    }
    @Test void invalidSelectedImagesFailBeforeQueueAcceptanceOrSupplierDownload() {
        var cache = mock(LookupCacheService.class);
        var images = mock(ImageDownloadClient.class);
        var generated = new GeneratedImageStore();
        var downloads = new DownloadService(images, null, null, generated);
        var queue = new ArrayList<Runnable>();
        var workspace = new ProductImageWorkspace(cache, null, downloads, new JobRegistry(queue::add, Duration.ofHours(1)),
                new ObservedProductStore(), null);
        var observed = product("https://nimg.lfmall.co.kr/current.jpg");
        when(cache.refreshProduct("BAG1", "DAKS")).thenReturn(observed);
        workspace.lookup(1L, "BAG1", "DAKS");
        String otherActorImage = generated.put(2L, observed, new byte[]{1});
        for (var item : List.of(new DownloadImageItemDto(null, UUID.randomUUID().toString()),
                new DownloadImageItemDto(null, otherActorImage),
                new DownloadImageItemDto(0, null, "https://nimg.lfmall.co.kr/stale.jpg"))) {
            var request = new DownloadRequestDto("BAG1", "DAKS", null, false, false, null, List.of(item));
            assertThatThrownBy(() -> workspace.startDownload(1L, request)).isInstanceOf(ImagingFailure.class);
            assertThat(queue).isEmpty();
        }
        verifyNoInteractions(images);
    }
    private static byte[] png(Color color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) image.setRGB(x, y, color.getRGB());
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
    @Test void queuedDownloadCapturesObservedActorSnapshotBeforeAnotherLookup(){
        var cache=mock(LookupCacheService.class);var sizes=mock(SizeGuideService.class);var downloads=mock(DownloadService.class);var queue=new ArrayList<Runnable>();var jobs=new JobRegistry(queue::add,Duration.ofHours(1));
        var workspace=new ProductImageWorkspace(cache,sizes,downloads,jobs,new ObservedProductStore(),mock(NoticeImageService.class));var old=product("https://nimg.lfmall.co.kr/old.jpg");var newer=product("https://nimg.lfmall.co.kr/new.jpg");
        when(cache.refreshProduct("BAG1","DAKS")).thenReturn(old,newer);workspace.lookup(1L,"BAG1","DAKS");
        var request=new DownloadRequestDto("BAG1","DAKS",null,false,false,null,List.of(new DownloadImageItemDto(0,null,old.imageUrls().getFirst())));
        var snapshot=new DownloadService.ExportSnapshot(request,old,request.images(),Map.of());
        when(downloads.capture(eq(1L),eq(request),same(old))).thenReturn(snapshot);
        when(downloads.downloadImages(same(snapshot))).thenReturn(new DownloadService.PreparedDownload(new DownloadResultDto("BAG1.zip",List.of("01.jpg")),new byte[]{1}));
        var job=workspace.startDownload(1L,request);workspace.lookup(1L,"BAG1","DAKS");queue.getFirst().run();
        assertThat(workspace.job(1L,job.id()).status()).isEqualTo(JobDto.STATUS_SUCCEEDED);
        verify(downloads).capture(eq(1L),eq(request),same(old));verify(downloads).downloadImages(same(snapshot));
    }
    @Test void expiredOrMissingObservationFailsBeforeStartingJobOrRendering(){
        var cache=mock(LookupCacheService.class);var sizes=mock(SizeGuideService.class);var downloads=mock(DownloadService.class);var jobs=mock(JobRegistry.class);
        var workspace=new ProductImageWorkspace(cache,sizes,downloads,jobs,new ObservedProductStore(),mock(NoticeImageService.class));var request=new DownloadRequestDto("BAG1","DAKS",null,false,false,null,List.of(new DownloadImageItemDto(0,null)));
        assertThatThrownBy(()->workspace.startDownload(1L,request)).hasMessageContaining("다시 조회");
        assertThatThrownBy(()->workspace.preview(1L,"BAG1",new SizeGuidePreviewRequestDto("DAKS","TEMPLATE","TOTE",null,null,null))).hasMessageContaining("다시 조회");verifyNoInteractions(cache,sizes,downloads,jobs);
    }
    @Test void noticeEndpointsRequireObservedActorAndReuseThatSnapshot(){
        var cache=mock(LookupCacheService.class);var sizes=mock(SizeGuideService.class);var downloads=mock(DownloadService.class);var jobs=mock(JobRegistry.class);var notices=mock(NoticeImageService.class);
        var workspace=new ProductImageWorkspace(cache,sizes,downloads,jobs,new ObservedProductStore(),notices);var observed=product("https://nimg.lfmall.co.kr/a.jpg");when(cache.refreshProduct("BAG1","DAKS")).thenReturn(observed);
        var request=new NoticeImageRequestDto("DAKS",Map.of());
        assertThatThrownBy(()->workspace.noticeLayout(1L,"BAG1",request)).hasMessageContaining("다시 조회");assertThatThrownBy(()->workspace.generateNotice(2L,"BAG1",request)).hasMessageContaining("다시 조회");verifyNoInteractions(notices);
        workspace.lookup(1L,"BAG1","DAKS");var layout=new NoticeImageLayoutDto(List.of(new NoticeImageCardDto("정보","값",true)));var generated=new GeneratedNoticeImageDto("id","상품정보고시","data:image/png;base64,AQ==");
        when(notices.layout(same(observed),eq(request))).thenReturn(layout);when(notices.generate(eq(1L),same(observed),eq(request))).thenReturn(generated);
        assertThat(workspace.noticeLayout(1L,"BAG1",request)).isSameAs(layout);assertThat(workspace.generateNotice(1L,"BAG1",request)).isSameAs(generated);
        assertThatThrownBy(()->workspace.noticeLayout(2L,"BAG1",request)).hasMessageContaining("다시 조회");
    }
    @Test void exportPlanCapturesTheCurrentObservationBeforeAnotherLookupWithoutRefreshingDuringPreparation() {
        var cache=mock(LookupCacheService.class);var downloads=mock(DownloadService.class);
        var workspace=new ProductImageWorkspace(cache,mock(SizeGuideService.class),downloads,mock(JobRegistry.class),new ObservedProductStore(),mock(NoticeImageService.class));
        var first=product("https://nimg.lfmall.co.kr/first.jpg");var second=product("https://nimg.lfmall.co.kr/second.jpg");
        when(cache.refreshProduct("BAG1","DAKS")).thenReturn(first,second);workspace.lookup(1L,"BAG1","DAKS");
        var request=new DownloadRequestDto("BAG1","DAKS",null,false,false,null,List.of(new DownloadImageItemDto(0,null,first.imageUrls().getFirst())));
        var snapshot=new DownloadService.ExportSnapshot(request,first,request.images(),Map.of());
        when(downloads.capture(eq(1L),eq(request),same(first))).thenReturn(snapshot);
        var images=List.of(new ExportImageDto("01.png","image/png",".png",new byte[]{1}));when(downloads.exportImages(same(snapshot))).thenReturn(images);
        var plan=workspace.prepareExport(1L,request);workspace.lookup(1L,"BAG1","DAKS");assertThat(plan.prepare()).isSameAs(images);
        verify(downloads).capture(eq(1L),eq(request),same(first));verify(downloads).exportImages(same(snapshot));
        verify(cache,times(2)).refreshProduct("BAG1","DAKS");
        assertThatThrownBy(()->workspace.prepareExport(2L,request)).hasMessageContaining("다시 조회");
    }
    @Test void noticePreviewAndGenerationShareTheBoundedRenderSlots() throws Exception {
        var cache=mock(LookupCacheService.class);var notices=mock(NoticeImageService.class);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var layout=new NoticeImageLayoutDto(List.of());
        when(cache.refreshProduct("BAG1","DAKS")).thenReturn(product("https://nimg.lfmall.co.kr/a.jpg"));
        when(notices.layout(any(),any())).thenAnswer(call->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException();return layout;});
        var workspace=new ProductImageWorkspace(cache,mock(SizeGuideService.class),mock(DownloadService.class),mock(JobRegistry.class),new ObservedProductStore(),notices);workspace.lookup(1L,"BAG1","DAKS");var request=new NoticeImageRequestDto("DAKS",Map.of());var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->workspace.noticeLayout(1L,"BAG1",request));var second=workers.submit(()->workspace.noticeLayout(1L,"BAG1",request));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->workspace.noticeLayout(1L,"BAG1",request)).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.BUSY);
            assertThatThrownBy(()->workspace.generateNotice(1L,"BAG1",request)).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.BUSY);
            release.countDown();assertThat(first.get(1,TimeUnit.SECONDS)).isSameAs(layout);assertThat(second.get(1,TimeUnit.SECONDS)).isSameAs(layout);
        } finally {release.countDown();workers.shutdownNow();}
    }
}
