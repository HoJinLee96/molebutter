package cc.ataglace.molebutter.imaging.internal.render;

import java.io.IOException;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.internal.client.SafeImageDecoder;

/** Upload-only rendition; the owned editing/download snapshot remains unchanged. */
public final class SizeGuideUploadImage {
    public static final int WIDTH = 750;
    private SizeGuideUploadImage() { }

    public static byte[] prepare(byte[] png) {
        try {
            var source = SafeImageDecoder.read(png);
            if (source == null) throw unreadable(null);
            if (source.getWidth() == WIDTH) return png.clone();
            int height = Math.max(1, (int) Math.round(source.getHeight() * (WIDTH / (double) source.getWidth())));
            return RenderSupport.encodePng(RenderSupport.resampleImage(source, WIDTH, height));
        } catch (IOException failure) {
            throw unreadable(failure);
        }
    }

    private static ImagingFailure unreadable(Exception cause) {
        return new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                "사이즈 이미지를 읽을 수 없습니다. 이미지를 다시 추가한 뒤 업로드해주세요.", cause);
    }
}
