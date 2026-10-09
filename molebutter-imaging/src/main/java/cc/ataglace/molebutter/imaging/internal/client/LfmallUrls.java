package cc.ataglace.molebutter.imaging.internal.client;
import java.net.URI;
import java.util.Locale;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

public final class LfmallUrls {
    private LfmallUrls() { }
    public static URI validate(String value) {
        try {
            if (value == null || value.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(lower.equals("lfmall.co.kr") || lower.endsWith(".lfmall.co.kr"))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "LFmall HTTPS 주소만 사용할 수 있습니다.");
        }
    }
}
