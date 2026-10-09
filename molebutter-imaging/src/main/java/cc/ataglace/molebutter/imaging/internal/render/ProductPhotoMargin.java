package cc.ataglace.molebutter.imaging.internal.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.w3c.dom.Node;

import cc.ataglace.molebutter.imaging.internal.client.ImageDownloadClient;
import cc.ataglace.molebutter.imaging.internal.client.SafeImageDecoder;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import lombok.extern.slf4j.Slf4j;

/**
 * 다운로드한 상품컷 하단에 흰 여백 띠를 붙인다(쿠팡 상세페이지에서 사진 사이 간격용).
 * 생성 이미지(상품사이즈·상품정보고시 PNG)에는 적용하지 않는다 — 호출자가 상품컷에만 태운다.
 *
 * 확장자 정책: .jpg → JPEG(품질 0.92, RGB) 유지 / .png → PNG(알파 유지) / 그 외(webp·gif 등 디코딩 가능) → PNG 로 바꾸고 .png.
 * 디코딩할 수 없으면(AVIF, CMYK JPEG, 손상 파일, 10MB 초과) 경고만 남기고 원본 바이트·확장자를 그대로 돌려준다.
 *
 * 저장 파일에는 메타데이터 세그먼트를 남기지 않는다: JPEG 는 JFIF(APP0)·EXIF·ICC·XMP·주석 없이 구조 테이블만,
 * PNG 는 IHDR/IDAT/IEND 만. 원본을 그대로 통과시키는 JPEG 도 APP/COM 세그먼트는 무손실로 잘라낸다.
 */
@Slf4j
public final class ProductPhotoMargin {

    public static final int BOTTOM_MARGIN_PX = 10;
    private static final float JPEG_QUALITY = 0.92f;
    private static final int MAX_DECODE_BYTES = 10_000_000;
    private static final String JPEG_METADATA_FORMAT = "javax_imageio_jpeg_image_1.0";

    private ProductPhotoMargin() {
    }

    public static ImageDownloadClient.DownloadedImage apply(ImageDownloadClient.DownloadedImage source) {
        if (source.bytes().length > MAX_DECODE_BYTES) {
            log.warn("상품컷이 너무 커서 하단 여백을 건너뜀: bytes={}, extension={}", source.bytes().length, source.fileExtension());
            return passthrough(source);
        }
        BufferedImage original;
        try {
            original = SafeImageDecoder.read(source.bytes());
        } catch (ImagingFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException e) {
            // 일부 ImageIO 플러그인은 손상 파일에 IllegalArgumentException 등을 던진다.
            log.warn("상품컷 디코딩 실패로 하단 여백을 건너뜀: extension={}, error={}", source.fileExtension(), e.getMessage());
            return passthrough(source);
        }
        if (original == null) {
            log.warn("지원하지 않는 상품컷 형식이라 하단 여백을 건너뜀: contentType={}, extension={}",
                    source.contentType(), source.fileExtension());
            return passthrough(source);
        }

        boolean jpeg = ".jpg".equals(source.fileExtension());
        boolean keepAlpha = !jpeg && original.getColorModel().hasAlpha();
        SafeImageDecoder.checkDimensions(original.getWidth(), original.getHeight() + BOTTOM_MARGIN_PX);
        BufferedImage padded = new BufferedImage(original.getWidth(), original.getHeight() + BOTTOM_MARGIN_PX,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = padded.createGraphics();
        try {
            if (!keepAlpha) {
                // RGB 캔버스는 검정으로 시작한다 — 투명 원본이 JPEG 로 갈 때도 흰 배경이 되도록 전체를 먼저 칠한다.
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, padded.getWidth(), padded.getHeight());
            }
            graphics.drawImage(original, 0, 0, null);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, original.getHeight(), padded.getWidth(), BOTTOM_MARGIN_PX);
        } finally {
            graphics.dispose();
        }

        if (jpeg) {
            byte[] encoded = encodeJpeg(padded);
            return encoded == null ? passthrough(source) : new ImageDownloadClient.DownloadedImage(encoded, "image/jpeg", ".jpg");
        }
        return new ImageDownloadClient.DownloadedImage(RenderSupport.encodePng(padded), "image/png", ".png");
    }

    /** 원본을 그대로 쓰는 경우에도 JPEG 라면 메타데이터 세그먼트만 잘라낸다(재인코딩 없음). */
    private static ImageDownloadClient.DownloadedImage passthrough(ImageDownloadClient.DownloadedImage source) {
        byte[] stripped = stripJpegMetadata(source.bytes());
        if (stripped == source.bytes()) {
            return source;
        }
        return new ImageDownloadClient.DownloadedImage(stripped, source.contentType(), source.fileExtension());
    }

    /**
     * JPEG 바이트에서 APP1~APP13·APP15·COM 세그먼트를 제거한다(EXIF/ICC/XMP/주석).
     * APP0(JFIF)과 APP14(Adobe)는 색 변환 판별에 쓰이므로 남긴다. SOS 이후 엔트로피 데이터는 그대로 복사한다.
     * JPEG 가 아니거나 구조가 손상돼 있으면 입력 배열을 그대로 돌려준다(동일 참조).
     */
    static byte[] stripJpegMetadata(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            return bytes;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 2);
        boolean changed = false;
        int index = 2;
        while (index + 1 < bytes.length) {
            if ((bytes[index] & 0xFF) != 0xFF) {
                return bytes; // 마커 자리에 다른 바이트 → 손상 파일, 원본 유지
            }
            int marker = bytes[index + 1] & 0xFF;
            if (marker == 0xFF) {
                index++; // fill byte
                continue;
            }
            if (marker == 0xDA) {
                out.write(bytes, index, bytes.length - index); // SOS 부터 끝까지 그대로
                return changed ? out.toByteArray() : bytes;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                out.write(bytes, index, 2); // 길이 없는 단독 마커
                index += 2;
                continue;
            }
            if (index + 3 >= bytes.length) {
                return bytes;
            }
            int length = ((bytes[index + 2] & 0xFF) << 8) | (bytes[index + 3] & 0xFF);
            if (length < 2 || index + 2 + length > bytes.length) {
                return bytes;
            }
            boolean metadataSegment = (marker >= 0xE1 && marker <= 0xED) || marker == 0xEF || marker == 0xFE;
            if (metadataSegment) {
                changed = true;
            } else {
                out.write(bytes, index, 2 + length);
            }
            index += 2 + length;
        }
        return bytes; // SOS 없이 끝남 → 손상 파일
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            IIOMetadata metadata = withoutJfif(writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param));
            try (ImageOutputStream imageOutput = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(imageOutput);
                writer.write(null, new IIOImage(image, null, metadata), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("상품컷 JPEG 재인코딩 실패로 원본 유지: {}", e.getMessage());
            return null;
        } finally {
            writer.dispose();
        }
    }

    /** 기본 메타데이터 트리에서 app0JFIF 노드를 비워 JFIF 세그먼트 없이 쓰게 한다(LF몰 원본과 같은 마커 구성). */
    private static IIOMetadata withoutJfif(IIOMetadata metadata) throws IOException {
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(JPEG_METADATA_FORMAT);
        Node variety = root.getElementsByTagName("JPEGvariety").item(0);
        if (variety != null) {
            while (variety.getFirstChild() != null) {
                variety.removeChild(variety.getFirstChild());
            }
        }
        try {
            metadata.setFromTree(JPEG_METADATA_FORMAT, root);
        } catch (javax.imageio.metadata.IIOInvalidTreeException e) {
            throw new IOException("JPEG 메타데이터 트리 설정 실패", e);
        }
        return metadata;
    }
}
