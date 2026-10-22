package cc.ataglace.molebutter.media.internal;
import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ServerImageInspectionTest {
    @Test
    void missingObjectIsUnverifiedRatherThanAValidFormat() {
        var r2 = mock(R2Images.class);
        when(r2.resolve(1L, "url")).thenThrow(new InputValidationFailure("이미지가 삭제되었습니다."));
        var checks = new ServerImageInspection(r2, mock(ImageAssets.class), mock(BusinessAccess.class)).inspect(1L,
                List.of(new ImageInspection.Input("DETAIL", null, "one", "url", null, false)));
        assertThat(checks.getFirst().status()).isEqualTo("UNVERIFIED");
    }
    @Test
    void untrustedLocalFilesAreNeverPublished() {
        var assets = mock(ImageAssets.class);
        when(assets.editedMetadata(1L, "legacy")).thenThrow(new InputValidationFailure("기존 로컬 이미지"));
        var checks = new ServerImageInspection(mock(R2Images.class), assets, mock(BusinessAccess.class)).inspect(1L,
                List.of(new ImageInspection.Input("REPRESENTATION", null, "one", "/api/coupang/assets/legacy", "legacy",
                        false)));
        assertThat(checks.getFirst().status()).isEqualTo("UNVERIFIED");
        verify(assets, never()).submissionUrl(any(), any());
    }
    @Test
    void squareAndBoundaryRulesAreSharedAndDescriptionsRemainIndependent() {
        assertThat(ImageInspection.descriptionInputs("<p style=\"background:#fff\">본문</p>", "")).isEmpty();
        assertThatThrownBy(() -> ImageInspection
                .descriptionInputs("<p style=\"background-image:url(https://assets.test/a)\">본문</p>", ""))
                .isInstanceOf(InputValidationFailure.class);
        assertThat(ImageRules.errors("REPRESENTATION", "image/png", 750, 750, 88040)).isEmpty();
        assertThat(ImageRules.errors("DETAIL", "image/jpeg", 500, 5000, 3*1024*1024)).isEmpty();
        assertThat(ImageRules.warnings("DETAIL",500,5000)).hasSize(1);
        assertThat(ImageRules.warnings("DETAIL",500,500)).isEmpty();
        assertThat(ImageRules.warnings("DESCRIPTION",500,5000)).isEmpty();
        for(int[] size:List.of(new int[]{499,500},new int[]{500,499},new int[]{5001,500},new int[]{500,5001}))assertThat(ImageRules.errors("DETAIL","image/png",size[0],size[1],100)).isNotEmpty();
        assertThat(ImageRules.errors("DETAIL","image/png",500,700,3*1024*1024+1)).isNotEmpty();
        assertThat(ImageRules.errors("DETAIL","image/webp",500,700,100)).isNotEmpty();
        assertThat(ImageRules.errors("DETAIL", "image/png", 499, 499, 100)).isNotEmpty();
        assertThat(ImageRules.errors("DESCRIPTION", "image/png", 750, 962, 88040)).isEmpty();
        assertThat(ImageRules.errors("DETAIL", "image/png", 5000, 5000, 3 * 1024 * 1024)).isEmpty();
        assertThat(ImageRules.errors("DETAIL", "image/png", 5001, 5001, 100)).isNotEmpty();
    }

    @Test
    void cachedExistingCdnBytesNeverAuthorizeANewImageReference() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(750, 750, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        var r2 = mock(R2Images.class);
        String url = "https://img1a.coupangcdn.com/image/existing.png";
        when(r2.resolve(1L, url)).thenThrow(new InputValidationFailure("R2 원본이 아닙니다."));
        var http = mock(java.net.http.HttpClient.class);
        var response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new java.io.ByteArrayInputStream(out.toByteArray()));
        doReturn(response).when(http).send(any(), any());
        var address = java.net.InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        try (var dns = mockStatic(java.net.InetAddress.class)) {
            dns.when(() -> java.net.InetAddress.getAllByName("img1a.coupangcdn.com"))
                    .thenReturn(new java.net.InetAddress[]{address});
            var service = new ServerImageInspection(r2, mock(ImageAssets.class), mock(BusinessAccess.class), http);
            var checks = service.inspect(1L,
                    List.of(new ImageInspection.Input("REPRESENTATION", null, "existing", url, null, true),
                            new ImageInspection.Input("DETAIL", null, "new", url, null, false)));
            assertThat(checks).extracting(ImageInspection.Check::status).containsExactly("PASS", "UNVERIFIED");
            verify(http, times(1)).send(any(), any());
        }
    }
}
