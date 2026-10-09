package cc.ataglace.molebutter.app.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.imaging.api.ProductImageWorkspace;
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

    private static String input() {
        return """
            {"requestId":"801fed42-3ad7-4db4-bd9c-362e6312c009","productCode":"HIHO6F861W2","brandCode":"HAZZYS",
             "uploadProductCode":"HIHO861W2","images":[{"generatedImageId":"11111111-1111-1111-1111-111111111111"}]}
            """;
    }
}
