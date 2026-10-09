package cc.ataglace.molebutter.imaging.internal.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;


import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import org.springframework.beans.factory.annotation.Qualifier;


/**
 * LF몰 이미지 바이트 다운로드.
 * - lfmall.co.kr 호스트 화이트리스트 (HTTPS 필수)
 * - 상품 페이지 Referer 부착(핫링크 차단 회피)
 * - 스트리밍 바이트 상한과 실제 이미지 시그니처 검증
 */
@Component
public class ImageDownloadClient {

    private static final int MAX_IMAGE_BYTES = 10_000_000;

    private final RestClient imagingRestClient;
    public ImageDownloadClient(@Qualifier("imagingRestClient") RestClient imagingRestClient) {
        this.imagingRestClient = imagingRestClient;
    }

    /** 상품컷 원본 바이트 다운로드. 이미지가 아니면 502. */
    public DownloadedImage downloadProductImage(String imageUrl, String productCode) {
        URI uri = validateImageUrl(imageUrl);
        byte[] bytes = fetchBytes(uri, productCode);
        String contentType = resolveImageContentType(bytes);
        return new DownloadedImage(bytes, contentType, fileExtension(uri, contentType));
    }

    /** 사진 사이즈 모드의 베이스 이미지 — 10MB 제한 + 디코딩까지 수행. */
    public BufferedImage downloadBaseImage(String imageUrl, String productCode) {
        URI uri = validateImageUrl(imageUrl);
        byte[] body = fetchBytes(uri, productCode);
        if (body.length > MAX_IMAGE_BYTES) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "베이스 이미지는 10MB 이하 파일만 사용할 수 있습니다.");
        }
        resolveImageContentType(body);
        try {
            BufferedImage image = SafeImageDecoder.read(body);
            if (image == null) {
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                        "베이스 이미지 형식을 디코딩할 수 없습니다. 다른 이미지를 선택해주세요.");
            }
            return image;
        } catch (IOException e) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "베이스 이미지를 읽을 수 없습니다.", e);
        }
    }

    public static URI validateImageUrl(String imageUrl) { return LfmallUrls.validate(imageUrl); }

    private byte[] fetchBytes(URI uri, String productCode) {
        return BoundedHttp.get(imagingRestClient, uri, productCode, MAX_IMAGE_BYTES,
                "image/jpeg,image/png,image/gif,image/webp,image/avif").bytes();
    }

    private String resolveImageContentType(byte[] bytes) {
        String signature = contentTypeFromBytes(bytes);
        if (!signature.isBlank()) return signature;
        throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "다운로드한 파일이 이미지가 아닙니다.");
    }

    /** 저장 파일 확장자(".jpg" 형태). URL 확장자 우선, 없으면 시그니처 기반. */
    private String fileExtension(URI imageUri, String contentType) {
        String extension = extensionFromPath(imageUri.getPath());
        if (!extension.isBlank()) {
            return "." + ("jpeg".equals(extension) ? "jpg" : extension);
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/avif" -> ".avif";
            default -> ".jpg";
        };
    }

    private String extensionFromPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String cleanPath = path.toLowerCase(Locale.ROOT);
        int dotIndex = cleanPath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == cleanPath.length() - 1) {
            return "";
        }
        String extension = cleanPath.substring(dotIndex + 1);
        return switch (extension) {
            case "jpg", "jpeg", "png", "webp", "gif", "avif" -> extension;
            default -> "";
        };
    }

    private String contentTypeFromBytes(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 6
                && bytes[0] == 0x47
                && bytes[1] == 0x49
                && bytes[2] == 0x46) {
            return "image/gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50) {
            return "image/webp";
        }
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            String brands = new String(bytes, 8, Math.min(bytes.length - 8, 56), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (brands.contains("avif") || brands.contains("avis")) return "image/avif";
        }
        return "";
    }

    public record DownloadedImage(byte[] bytes, String contentType, String fileExtension) {
    }

}
