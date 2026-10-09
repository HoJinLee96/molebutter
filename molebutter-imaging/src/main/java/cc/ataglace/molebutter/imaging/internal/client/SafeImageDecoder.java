package cc.ataglace.molebutter.imaging.internal.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

/** Inspects dimensions before any decompressed pixel allocation. */
public final class SafeImageDecoder {
    public static final int MAX_SIDE = 8192;
    public static final long MAX_PIXELS = 20_000_000L;
    private SafeImageDecoder() { }
    public static void checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE
                || (long) width * height > MAX_PIXELS) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "이미지 해상도가 너무 큽니다.");
        }
    }
    public static BufferedImage read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > 10_000_000) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "이미지는 10MB 이하 파일만 사용할 수 있습니다.");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (image != null) checkDimensions(image.getWidth(), image.getHeight());
                return image;
            } finally { reader.dispose(); }
        }
    }
}
