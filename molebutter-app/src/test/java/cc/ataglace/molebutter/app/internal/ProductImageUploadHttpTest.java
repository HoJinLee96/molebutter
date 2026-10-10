package cc.ataglace.molebutter.app.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.imaging.api.ProductImageWorkspace;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductImageUploadHttpTest {
    private final ProductImageWorkspace workspace = mock(ProductImageWorkspace.class);
    private final ProductImageUploadService uploads = mock(ProductImageUploadService.class);
    private MockMvc http;

    @BeforeEach void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(42L, "fixture@example.com", UserRole.PRODUCT), null, List.of()));
        http = MockMvcBuilders.standaloneSetup(new ProductImageController(workspace, mock(BusinessAccess.class), uploads))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void duplicateDestinationIsAnExplicitRejectionForTheUploadDialog() throws Exception {
        when(uploads.start(eq(42L), any())).thenThrow(new ProductImageUploadFailure(
                ProductImageUploadFailure.Kind.DUPLICATE, "이미 업로드한 상품입니다."));
        http.perform(post("/api/product-images/products/upload").contentType("application/json").content(input()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMAGING_UPLOAD_DUPLICATE"))
                .andExpect(jsonPath("$.message").value("이미 업로드한 상품입니다."));
        verifyNoInteractions(workspace);
    }

    @Test void preflightOutageIsDistinguishedFromAnAcceptedWriteWithLostResponse() throws Exception {
        when(uploads.start(eq(42L), any())).thenThrow(new ProductImageUploadFailure(
                ProductImageUploadFailure.Kind.CHECK_FAILED, "업로드 경로를 확인하지 못했습니다."));
        http.perform(post("/api/product-images/products/upload").contentType("application/json").content(input()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IMAGING_UPLOAD_CHECK_FAILED"))
                .andExpect(jsonPath("$.message").value("업로드 경로를 확인하지 못했습니다."));
        verifyNoInteractions(workspace);
    }

    @Test void acceptedMixedUploadKeepsBasenamesAndReturnsClassifiedPublicKeys() throws Exception {
        var files = List.of(
                new ProductImageUploadJob.File("01.jpg", "products/HIHO861W2/official/01.jpg",
                        "https://assets.example.test/products/HIHO861W2/official/01.jpg"),
                new ProductImageUploadJob.File("상품정보.png", "products/HIHO861W2/processed/상품정보.png",
                        "https://assets.example.test/products/HIHO861W2/processed/%EC%83%81%ED%92%88%EC%A0%95%EB%B3%B4.png"));
        when(uploads.start(eq(42L), any())).thenReturn(new ProductImageUploadJob(
                "801fed42-3ad7-4db4-bd9c-362e6312c009", "SUCCEEDED", "업로드 완료",
                Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-09T00:00:01Z"),
                new ProductImageUploadJob.Result("HIHO861W2", files, 2), null));
        String mixedInput = """
            {"requestId":"801fed42-3ad7-4db4-bd9c-362e6312c009","productCode":"HIHO6F861W2","brandCode":"HAZZYS",
             "uploadProductCode":"HIHO861W2","images":[
               {"imageIndex":0,"sourceImageUrl":"https://nimg.lfmall.co.kr/0.jpg"},
               {"generatedImageId":"11111111-1111-1111-1111-111111111111"}]}
            """;
        http.perform(post("/api/product-images/products/upload").contentType("application/json").content(mixedInput))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.result.uploadProductCode").value("HIHO861W2"))
                .andExpect(jsonPath("$.data.result.files[0].fileName").value("01.jpg"))
                .andExpect(jsonPath("$.data.result.files[0].key").value("products/HIHO861W2/official/01.jpg"))
                .andExpect(jsonPath("$.data.result.files[1].fileName").value("상품정보.png"))
                .andExpect(jsonPath("$.data.result.files[1].key").value("products/HIHO861W2/processed/상품정보.png"))
                .andExpect(jsonPath("$.data.result.files[1].url").value(files.get(1).url()));
        verifyNoInteractions(workspace);
    }

    private static String input() {
        return """
            {"requestId":"801fed42-3ad7-4db4-bd9c-362e6312c009","productCode":"HIHO6F861W2","brandCode":"HAZZYS",
             "uploadProductCode":"HIHO861W2","images":[{"generatedImageId":"11111111-1111-1111-1111-111111111111"}]}
            """;
    }
}
