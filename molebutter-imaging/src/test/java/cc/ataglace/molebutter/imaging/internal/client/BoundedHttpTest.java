package cc.ataglace.molebutter.imaging.internal.client;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.mock.http.client.MockClientHttpResponse;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

class BoundedHttpTest {
    @Test void allowsOnlyExactLfmallBoundaryAndHttps(){
        assertThat(LfmallUrls.validate("https://nimg.lfmall.co.kr/a.jpg").getHost()).isEqualTo("nimg.lfmall.co.kr");assertThat(LfmallUrls.validate("https://lfmall.co.kr/a.jpg").getHost()).isEqualTo("lfmall.co.kr");
        for(String url:new String[]{"https://evillfmall.co.kr/a.jpg","https://lfmall.co.kr.evil.test/a.jpg","http://lfmall.co.kr/a.jpg","https://user:pass@lfmall.co.kr/a.jpg","https://lfmall.co.kr:8443/a.jpg","https://127.0.0.1/a.jpg"})assertThatThrownBy(()->LfmallUrls.validate(url)).isInstanceOf(ImagingFailure.class);
    }
    @Test void validatesRedirectDestinationBeforeSecondRequest(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/a.jpg";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION,"https://evillfmall.co.kr/private"));
        assertThatThrownBy(()->BoundedHttp.get(builder.build(),URI.create(url),"BAG1",64,"image/png")).isInstanceOf(ImagingFailure.class);server.verify();
    }
    @Test void preservesRefererAndAllowsValidatedRelativeRedirect(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/a.jpg";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION,"/b.png"));
        server.expect(requestTo("https://nimg.lfmall.co.kr/b.png")).andExpect(header(HttpHeaders.REFERER,"https://www.lfmall.co.kr/app/product/BAG1")).andRespond(withSuccess(new byte[]{1,2},MediaType.IMAGE_PNG));
        assertThat(BoundedHttp.get(builder.build(),URI.create(url),"BAG1",64,"image/png").bytes()).containsExactly((byte)1,(byte)2);server.verify();
    }
    @Test void limitsUnknownLengthBodyWhileStreaming(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/a.jpg";
        server.expect(requestTo(url)).andRespond(request->new MockClientHttpResponse(new byte[101],HttpStatus.OK));
        assertThatThrownBy(()->BoundedHttp.get(builder.build(),URI.create(url),"BAG1",100,"image/png")).hasMessageContaining("허용 크기");server.verify();
    }
    @Test void limitsDeclaredLengthBeforeReading(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();String url="https://nimg.lfmall.co.kr/a.jpg";
        server.expect(requestTo(url)).andRespond(withSuccess(new byte[]{1},MediaType.IMAGE_PNG).header(HttpHeaders.CONTENT_LENGTH,"101"));
        assertThatThrownBy(()->BoundedHttp.get(builder.build(),URI.create(url),"BAG1",100,"image/png")).hasMessageContaining("허용 크기");server.verify();
    }
}
