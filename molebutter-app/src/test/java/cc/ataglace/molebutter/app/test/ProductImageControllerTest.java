package cc.ataglace.molebutter.app.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cc.ataglace.molebutter.app.internal.ProductImageController;
import cc.ataglace.molebutter.app.internal.ProductImageUploadService;
import cc.ataglace.molebutter.app.internal.ProductImageUploadJob;
import cc.ataglace.molebutter.app.internal.ProductImageUploadRequest;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.imaging.api.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductImageControllerTest {
    private final ProductImageWorkspace workspace = mock(ProductImageWorkspace.class);
    private final BusinessAccess access = mock(BusinessAccess.class);
    private final ProductImageUploadService uploads = mock(ProductImageUploadService.class);
    private MockMvc http;

    @BeforeEach void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(42L, "fixture@example.com", UserRole.PRODUCT), null, List.of()));
        http = MockMvcBuilders.standaloneSetup(new ProductImageController(workspace, access, uploads))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void uploadUsesTrustedActorAndSeparatesSourceCodeFromDestinationCode() throws Exception {
        String id = "801fed42-3ad7-4db4-bd9c-362e6312c009";
        var job = new ProductImageUploadJob(id, "RUNNING", "준비 중", Instant.now(), null,
                new ProductImageUploadJob.Result("HIHO861W2", List.of(), 2), null);
        when(uploads.start(eq(42L), any())).thenReturn(job);
        when(uploads.get(42L, id)).thenReturn(job);
        http.perform(post("/api/product-images/products/upload").contentType("application/json")
                .content("""
                    {"requestId":"801fed42-3ad7-4db4-bd9c-362e6312c009","productCode":"HIHO6F861W2","brandCode":"HAZZYS",
                     "uploadProductCode":"HIHO861W2","actorId":999,
                     "images":[{"generatedImageId":"11111111-1111-1111-1111-111111111111"},
                               {"imageIndex":2,"sourceImageUrl":"https://nimg.lfmall.co.kr/a.jpg"}]}
                    """))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.result.uploadProductCode").value("HIHO861W2"));
        var request = ArgumentCaptor.forClass(ProductImageUploadRequest.class);
        verify(uploads).start(eq(42L), request.capture());
        assertThat(request.getValue().productCode()).isEqualTo("HIHO6F861W2");
        assertThat(request.getValue().uploadProductCode()).isEqualTo("HIHO861W2");
        assertThat(request.getValue().images()).extracting(DownloadImageItemDto::imageIndex).containsExactly(null, 2);
        http.perform(get("/api/product-images/products/upload/jobs/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(uploads).get(42L, id);
        verify(access, times(2)).productActor(42L, false);
        verifyNoInteractions(workspace);
    }

    @Test void downloadUsesTrustedActorAndPreservesMixedImageOrder() throws Exception {
        when(workspace.startDownload(eq(42L), any())).thenReturn(new JobDto("job-1", "RUNNING", "준비 중",
                Instant.now(), null, null, null));
        http.perform(post("/api/product-images/products/download").contentType("application/json")
                .content("""
                    {"productCode":"DCBA870W3","brandCode":"DAKS","actorId":999,"downloadProductCode":" wbba162w3 ",
                     "images":[{"imageIndex":2},{"generatedImageId":"generated-1"},{"imageIndex":0}],
                     "includeNoticeImage":true,"includeSizeImage":false}
                    """))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value("job-1"));
        var input = ArgumentCaptor.forClass(DownloadRequestDto.class);
        verify(workspace).startDownload(eq(42L), input.capture());
        assertThat(input.getValue().images()).extracting(DownloadImageItemDto::imageIndex).containsExactly(2, null, 0);
        assertThat(input.getValue().images().get(1).generatedImageId()).isEqualTo("generated-1");
        assertThat(input.getValue().productCode()).isEqualTo("DCBA870W3");
        assertThat(input.getValue().downloadProductCode()).isEqualTo(" wbba162w3 ");
        verify(access).productActor(42L, false);
    }

    @Test void legacyDownloadCanOmitTheStorageCodeAndNullableFlags() throws Exception {
        when(workspace.startDownload(eq(42L), any())).thenReturn(new JobDto("legacy", "RUNNING", "준비 중",
                Instant.now(), null, null, null));
        http.perform(post("/api/product-images/products/download").contentType("application/json")
                .content("""
                    {"productCode":"BAG1","brandCode":"DAKS","imageIndexes":[0],"includeNoticeImage":null}
                    """))
                .andExpect(status().isAccepted());
        var input=ArgumentCaptor.forClass(DownloadRequestDto.class);
        verify(workspace).startDownload(eq(42L),input.capture());
        assertThat(input.getValue().downloadProductCode()).isNull();
        assertThat(input.getValue().includeNoticeImage()).isFalse();
        assertThat(input.getValue().includeSizeImage()).isFalse();
    }

    @Test void archiveIsPrivateBrowserAttachmentAndFailuresUseApiContract() throws Exception {
        var bytes = new byte[]{80, 75, 3, 4};
        when(workspace.download(42L, "owned-job")).thenReturn(new DownloadArtifact("DCBA870W3.zip", bytes));
        var result = http.perform(get("/api/product-images/products/download/jobs/owned-job/archive"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("attachment", "DCBA870W3.zip");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        when(workspace.job(42L, "foreign-job")).thenThrow(new ImagingFailure(ImagingFailure.Kind.NOT_FOUND, "다운로드 작업을 찾을 수 없습니다."));
        http.perform(get("/api/product-images/products/download/jobs/foreign-job"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGING_NOT_FOUND"));
    }

    @Test void noticePreparationKeepsEditedTextAndUsesAuthenticatedActorWithoutDownloading() throws Exception {
        when(workspace.noticeLayout(eq(42L), eq("DCBA870W3"), any())).thenReturn(
                new NoticeImageLayoutDto(List.of(new NoticeImageCardDto("치수", "첫 줄\n둘째 줄", true))));
        when(workspace.generateNotice(eq(42L), eq("DCBA870W3"), any())).thenReturn(
                new GeneratedNoticeImageDto("notice-1", "상품정보", "data:image/png;base64,cG5n"));
        String input = """
            {"brandCode":"DAKS","actorId":999,"fields":{"치수":"첫 줄\\n둘째 줄","상품코드":"0"}}
            """;
        http.perform(post("/api/product-images/products/DCBA870W3/notice-image/preview")
                .contentType("application/json").content(input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cards[0].fullWidth").value(true));
        http.perform(post("/api/product-images/products/DCBA870W3/notice-image/images")
                .contentType("application/json").content(input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("notice-1"));
        var request = ArgumentCaptor.forClass(NoticeImageRequestDto.class);
        verify(workspace).generateNotice(eq(42L), eq("DCBA870W3"), request.capture());
        assertThat(request.getValue().fields()).containsExactlyInAnyOrderEntriesOf(
                Map.of("치수", "첫 줄\n둘째 줄", "상품코드", "0"));
        verify(access, times(2)).productActor(42L, false);
        verify(workspace, never()).startDownload(any(), any());
        verify(workspace, never()).download(any(), any());
    }
}
