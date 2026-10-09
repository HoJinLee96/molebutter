package cc.ataglace.molebutter.imaging.internal.client;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

/** Redirects are followed here, after validating each destination, never by the HTTP transport. */
public final class BoundedHttp {
    private BoundedHttp() { }
    public static Body get(RestClient client, URI start, String productCode, int limit, String accept) {
        URI uri = LfmallUrls.validate(start.toString());
        for (int hop = 0; hop <= 3; hop++) {
            URI current = uri;
            final Reply reply;
            try {
                reply = client.get().uri(current).header(HttpHeaders.ACCEPT, accept)
                        .header(HttpHeaders.REFERER, "https://www.lfmall.co.kr/app/product/" + productCode)
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            if (status >= 300 && status < 400) {
                                return new Reply(response.getHeaders().getLocation(), null);
                            }
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 응답을 가져오지 못했습니다.");
                            }
                            if (response.getHeaders().getContentLength() > limit) {
                                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "다운로드 파일이 허용 크기를 초과했습니다.");
                            }
                            var out = new ByteArrayOutputStream(Math.min(limit, 8192));
                            byte[] buffer = new byte[8192];
                            int count;
                            var input = response.getBody();
                            while ((count = input.read(buffer)) != -1) {
                                if ((long) out.size() + count > limit) {
                                    throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "다운로드 파일이 허용 크기를 초과했습니다.");
                                }
                                out.write(buffer, 0, count);
                            }
                            if (out.size() == 0) throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 응답이 비어 있습니다.");
                            return new Reply(null, new Body(out.toByteArray(), response.getHeaders().getContentType()));
                        });
            } catch (RestClientException e) {
                throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 응답을 가져오지 못했습니다.", e);
            }
            if (reply.body() != null) return reply.body();
            if (reply.redirect() == null || hop == 3) {
                throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 리디렉션을 처리하지 못했습니다.");
            }
            uri = LfmallUrls.validate(current.resolve(reply.redirect()).toString());
        }
        throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 응답을 가져오지 못했습니다.");
    }
    public record Body(byte[] bytes, MediaType contentType) { }
    private record Reply(URI redirect, Body body) { }
}
