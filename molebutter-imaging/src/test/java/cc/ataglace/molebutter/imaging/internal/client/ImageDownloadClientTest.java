package cc.ataglace.molebutter.imaging.internal.client;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

class ImageDownloadClientTest {
    @Test void validatesRealImageBytesAndKeepsReferer() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,6,BufferedImage.TYPE_INT_RGB),"png",out);
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/photo";
        server.expect(requestTo(url)).andExpect(header(HttpHeaders.REFERER,"https://www.lfmall.co.kr/app/product/BAG1")).andRespond(withSuccess(out.toByteArray(),MediaType.APPLICATION_OCTET_STREAM));
        var image=new ImageDownloadClient(builder.build()).downloadProductImage(url,"BAG1");assertThat(image.contentType()).isEqualTo("image/png");assertThat(image.fileExtension()).isEqualTo(".png");assertThat(image.bytes()).isEqualTo(out.toByteArray());server.verify();
    }
    @Test void rejectsHtmlDespiteImageHeaderAndJpgFilename(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/photo.jpg";
        server.expect(requestTo(url)).andRespond(withSuccess("<html>not an image</html>",MediaType.IMAGE_JPEG));
        assertThatThrownBy(()->new ImageDownloadClient(builder.build()).downloadProductImage(url,"BAG1")).isInstanceOf(ImagingFailure.class).hasMessageContaining("이미지가 아닙니다");server.verify();
    }
    @Test void rejectsEncodedInvalidBaseImageInsteadOfLeakingDecoderDetails(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/photo.jpg";
        server.expect(requestTo(url)).andRespond(withSuccess(new byte[]{(byte)255,(byte)216,(byte)255},MediaType.IMAGE_JPEG));
        assertThatThrownBy(()->new ImageDownloadClient(builder.build()).downloadBaseImage(url,"BAG1")).isInstanceOf(ImagingFailure.class);server.verify();
    }
}
