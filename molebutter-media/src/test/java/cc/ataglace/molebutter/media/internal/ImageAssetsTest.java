package cc.ataglace.molebutter.media.internal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
class ImageAssetsTest {
    @Test void recognizesActualImageInsteadOfExtensionAndPreservesNonSquareOriginals()throws Exception {
        var image=new BufferedImage(600,1200,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);
        var meta=LocalImageAssets.inspect(bytes.toByteArray());assertThat(meta.mimeType()).isEqualTo("image/png");assertThat(meta.width()).isEqualTo(600);assertThat(meta.height()).isEqualTo(1200);
        bytes.reset();ImageIO.write(image,"JPEG",bytes);assertThat(LocalImageAssets.inspect(bytes.toByteArray()).mimeType()).isEqualTo("image/jpeg");
    }
    @Test void rejectsInvalidAndOversizedUploads() {
        for(byte[] bytes:new byte[][]{new byte[0],"<svg onload='alert(1)'/>".getBytes(),new byte[LocalImageAssets.MAX_BYTES+1]})
            assertThatThrownBy(()->LocalImageAssets.inspect(bytes)).isInstanceOf(InputValidationFailure.class);
    }
    @Test void rejectsUnsupportedImages()throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"GIF",bytes);
        assertThatThrownBy(()->LocalImageAssets.inspect(bytes.toByteArray())).isInstanceOf(InputValidationFailure.class);
    }
    @Test void publicationRequiresAnExplicitPublicHttpsOrigin() {
        assertThat(LocalImageAssets.publicBase("https://images.example.com/")).isEqualTo("https://images.example.com");
        for(String url: new String[]{"", "http://images.example.com", "https://localhost", "https://127.0.0.1", "https://192.168.0.1", "https://[::1]", "https://files.local", "https://images.example.com/private", "https://user:password@images.example.com", "https://images.example.com?token=x"})
            assertThatThrownBy(()->LocalImageAssets.publicBase(url)).isInstanceOf(InputValidationFailure.class);
    }
}
