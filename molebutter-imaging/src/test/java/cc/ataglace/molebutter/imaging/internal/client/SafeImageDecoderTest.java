package cc.ataglace.molebutter.imaging.internal.client;
import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

class SafeImageDecoderTest {
    @Test void checksPngHeaderBeforeDecompressedAllocation() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",out);byte[] png=out.toByteArray();
        ByteBuffer.wrap(png,16,8).putInt(8193).putInt(8193);var crc=new CRC32();crc.update(png,12,17);ByteBuffer.wrap(png,29,4).putInt((int)crc.getValue());
        assertThatThrownBy(()->SafeImageDecoder.read(png)).isInstanceOf(ImagingFailure.class).hasMessageContaining("해상도");
    }
    @Test void rejectsPixelProductEvenWhenBothSidesAreAllowed(){assertThatThrownBy(()->SafeImageDecoder.checkDimensions(6000,6000)).isInstanceOf(ImagingFailure.class);}
    @Test void returnsNullForUndecodableBytesAndReadsValidPng() throws Exception {
        assertThat(SafeImageDecoder.read(new byte[]{1,2,3})).isNull();var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,12,BufferedImage.TYPE_INT_RGB),"png",out);
        var image=SafeImageDecoder.read(out.toByteArray());assertThat(image.getWidth()).isEqualTo(8);assertThat(image.getHeight()).isEqualTo(12);
    }
}
