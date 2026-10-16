package cc.ataglace.molebutter.media.internal;
import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.awt.image.BufferedImage;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ServerImageEditsTest {
 final PlatformTransactionManager manager=new AbstractPlatformTransactionManager(){protected Object doGetTransaction(){return new Object();}protected void doBegin(Object o,TransactionDefinition d){}protected void doCommit(DefaultTransactionStatus s){}protected void doRollback(DefaultTransactionStatus s){}};
 @Test void serverCropsExactPixelsAndRecordsProvenanceWithoutR2Writes()throws Exception{
  var r2=mock(R2Images.class);var assets=mock(ImageAssets.class);var db=mock(JdbcTemplate.class);var source=new BufferedImage(750,962,BufferedImage.TYPE_INT_RGB);source.setRGB(12,25,0xffcc0000);var bytes=new ByteArrayOutputStream();ImageIO.write(source,"png",bytes);String url="https://assets.example.test/products/CODE/official/01.png";
  when(r2.read(1L,url)).thenReturn(new R2Images.Content(new R2Images.Image("key",url,"image/png",750,962,bytes.size()),bytes.toByteArray()));
  when(assets.upload(eq(1L),any())).thenAnswer(i->new ImageAssets.Asset(UUID.randomUUID().toString(),"private","image/png",750,750,((byte[])i.getArgument(1)).length));
  var service=new ServerImageEdits(r2,assets,mock(BusinessAccess.class),db,manager);var opened=service.open(1L,url,null);assertThat(opened.width()).isEqualTo(750);assertThat(opened.height()).isEqualTo(962);
  assertThatThrownBy(()->service.preview(2L,opened.token())).isInstanceOf(InputValidationFailure.class);
  assertThatThrownBy(()->service.crop(1L,new ImageEdits.Request(opened.token(),new ImageEdits.Rect(749,0,2,2)))).isInstanceOf(InputValidationFailure.class);
  service.crop(1L,new ImageEdits.Request(opened.token(),new ImageEdits.Rect(0,12,750,750)));
  var captured=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(assets,times(2)).upload(eq(1L),captured.capture());var crop=ImageIO.read(new ByteArrayInputStream(captured.getAllValues().get(1)));assertThat(crop.getWidth()).isEqualTo(750);assertThat(crop.getHeight()).isEqualTo(750);assertThat(crop.getRGB(12,13)&0xffffff).isEqualTo(0xcc0000);verify(r2,times(1)).read(1L,url);
 }
}
