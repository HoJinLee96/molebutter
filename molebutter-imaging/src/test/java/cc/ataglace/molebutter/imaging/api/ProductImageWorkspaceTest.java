package cc.ataglace.molebutter.imaging.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.internal.service.*;
import cc.ataglace.molebutter.imaging.internal.job.JobRegistry;

class ProductImageWorkspaceTest {
    private ProductLookupDto product(String url){return new ProductLookupDto("BAG1","DAKS","닥스","상품",null,null,List.of(url),List.of(),Map.of(),"FREE",null,Map.of(),false,null,null,null);}
    @Test void queuedDownloadCapturesObservedActorSnapshotBeforeAnotherLookup(){
        var cache=mock(LookupCacheService.class);var sizes=mock(SizeGuideService.class);var downloads=mock(DownloadService.class);var queue=new ArrayList<Runnable>();var jobs=new JobRegistry(queue::add,Duration.ofHours(1));
        var workspace=new ProductImageWorkspace(cache,sizes,downloads,jobs,new ObservedProductStore(),mock(NoticeImageService.class));var old=product("https://nimg.lfmall.co.kr/old.jpg");var newer=product("https://nimg.lfmall.co.kr/new.jpg");
        when(cache.refreshProduct("BAG1","DAKS")).thenReturn(old,newer);workspace.lookup(1L,"BAG1","DAKS");
        var request=new DownloadRequestDto("BAG1","DAKS",null,false,false,null,List.of(new DownloadImageItemDto(0,null,old.imageUrls().getFirst())));
        when(downloads.downloadImages(eq(1L),eq(request),same(old))).thenReturn(new DownloadService.PreparedDownload(new DownloadResultDto("BAG1.zip",List.of("01.jpg")),new byte[]{1}));
        var job=workspace.startDownload(1L,request);workspace.lookup(1L,"BAG1","DAKS");queue.getFirst().run();
        assertThat(workspace.job(1L,job.id()).status()).isEqualTo(JobDto.STATUS_SUCCEEDED);verify(downloads).downloadImages(eq(1L),eq(request),same(old));
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
