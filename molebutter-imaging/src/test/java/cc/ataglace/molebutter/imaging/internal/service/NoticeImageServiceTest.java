package cc.ataglace.molebutter.imaging.internal.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.client.ImageDownloadClient;
import cc.ataglace.molebutter.imaging.internal.render.*;

class NoticeImageServiceTest {
    private final NoticeImageRenderer renderer = new NoticeImageRenderer(new FontResolver("Dialog","Dialog","Dialog","Dialog"));
    private final GeneratedImageStore store = new GeneratedImageStore();
    private final NoticeImageService service = new NoticeImageService(renderer,store);
    private ProductLookupDto product() {
        Map<String,String> fields = new LinkedHashMap<>(); fields.put("상품코드","BAG1"); fields.put("종류","가방"); fields.put("크기","30 x 12 x 22"); fields.put("제조국","대한민국"); fields.put("A/S 책임자","1234-5678"); fields.put("선택"," ");
        return new ProductLookupDto("BAG1","DAKS","닥스","상품",null,null,List.of("https://nimg.lfmall.co.kr/a.png"),List.of("토트백"),fields,"FREE",null,Map.of(),true,"토트백","TOTE",new SizeDimensionsDto("30","12","22"));
    }
    @Test void editsOnlyKnownValuesPreservingSupplierKeysOrderAndSource() {
        var product=product();var overrides=new LinkedHashMap<String,String>(); overrides.put("제조국","이탈리아"); overrides.put("종류","핸드백");
        var request=new NoticeImageRequestDto("DAKS",overrides);overrides.put("종류","changed-after-request");
        Map<String,String> merged=service.editedFields(product,request);
        assertThat(merged.keySet()).containsExactlyElementsOf(product.notificationFields().keySet());
        assertThat(merged).containsEntry("종류","핸드백").containsEntry("제조국","이탈리아").containsEntry("크기","30 x 12 x 22");
        assertThat(product.notificationFields()).containsEntry("종류","가방").containsEntry("제조국","대한민국");
        assertThat(product.productCode()).isEqualTo("BAG1");assertThat(product.brandCode()).isEqualTo("DAKS");
        assertThatThrownBy(()->service.layout(product,new NoticeImageRequestDto("DAKS",Map.of("새 항목","값")))).hasMessageContaining("항목만 편집");
    }
    @Test void layoutMatchesActualRendererMeasurementAndFiltersExactlyTheSameFields() {
        var product=product();var request=new NoticeImageRequestDto("DAKS",Map.of("크기","긴 내용 ".repeat(50),"제조국",""));
        assertThat(service.layout(product,request)).isEqualTo(renderer.layout(service.editedFields(product,request)));
        assertThat(service.layout(product,request).cards()).extracting(NoticeImageCardDto::label).containsExactly("종류","크기");
        assertThat(service.layout(product,request).cards()).allSatisfy(card->assertThat(card.fullWidth()).isTrue());
        assertThat(service.layout(product,new NoticeImageRequestDto("DAKS",null))).isEqualTo(service.layout(product,new NoticeImageRequestDto("DAKS",Map.of())));
    }
    @Test void generatedPngIsAnOwnedFrozenSnapshotAndLaterEditsLeaveEarlierBytesUntouched() throws Exception {
        var product=product();var first=service.generate(1L,product,new NoticeImageRequestDto("DAKS",Map.of("종류","첫 편집")));
        byte[] firstPng=png(first);assertThat(ImageIO.read(new ByteArrayInputStream(firstPng)).getWidth()).isEqualTo(780);
        assertThat(first.displayName()).isEqualTo("상품정보고시");assertThat(store.get(1L,first.id(),product)).isEqualTo(firstPng);
        var later=service.generate(1L,product,new NoticeImageRequestDto("DAKS",Map.of("종류","나중 편집")));
        assertThat(later.id()).isNotEqualTo(first.id());assertThat(png(later)).isNotEqualTo(firstPng);assertThat(store.get(1L,first.id(),product)).isEqualTo(firstPng);
        assertThatThrownBy(()->store.get(2L,first.id(),product)).extracting(e->((ImagingFailure)e).kind()).isEqualTo(ImagingFailure.Kind.NOT_FOUND);
        assertThat(product.notificationFields()).containsEntry("종류","가방");
    }
    @Test void noticeSnapshotsExpireWithTheSameGeneratedImageTtl() {
        var clock=new GeneratedImageStoreTest.MutableClock();var timedStore=new GeneratedImageStore(clock);var timedService=new NoticeImageService(renderer,timedStore);var product=product();
        var generated=timedService.generate(1L,product,new NoticeImageRequestDto("DAKS",Map.of()));clock.now=clock.now.plus(GeneratedImageStore.TTL);
        assertThatThrownBy(()->timedStore.get(1L,generated.id(),product)).hasMessageContaining("다시 추가");
    }
    @Test void mixedOrderKeepsNoticeSizeAndOriginalPixelsWithNoImplicitNotice() throws Exception {
        var product=product();var notice=service.generate(1L,product,new NoticeImageRequestDto("DAKS",Map.of("종류","편집한 값")));
        byte[] size=solidPng(Color.BLUE,780,509);String sizeId=store.put(1L,product,size);var images=mock(ImageDownloadClient.class);var sizes=mock(SizeGuideService.class);
        when(images.downloadProductImage(product.imageUrls().getFirst(),product.productCode())).thenReturn(new ImageDownloadClient.DownloadedImage(solidPng(Color.RED,20,30),"image/png",".png"));
        var downloads=new DownloadService(images,renderer,sizes,store);var request=new DownloadRequestDto("BAG1","DAKS",null,false,false,null,List.of(new DownloadImageItemDto(null,notice.id()),new DownloadImageItemDto(0,null,product.imageUrls().getFirst()),new DownloadImageItemDto(null,sizeId)));
        var result=downloads.downloadImages(1L,request,product);assertThat(result.metadata().savedFiles()).containsExactly("BAG1/processed/상품정보.png","BAG1/official/02.png","BAG1/processed/사이즈.png");var archive=unzip(result.archive());
        assertThat(archive.get("BAG1/processed/상품정보.png")).isEqualTo(png(notice));assertThat(archive.get("BAG1/processed/사이즈.png")).isEqualTo(size);
        assertThat(ImageIO.read(new ByteArrayInputStream(archive.get("BAG1/official/02.png"))).getHeight()).isEqualTo(40);verifyNoInteractions(sizes);
    }
    @Test void legacyIncludeNoticeStillRendersUneditedSupplierNotice() throws Exception {
        var product=product();var downloads=new DownloadService(mock(ImageDownloadClient.class),renderer,mock(SizeGuideService.class),store);
        var result=downloads.downloadImages(1L,new DownloadRequestDto("BAG1","DAKS",List.of(),true,false,null),product);
        assertThat(result.metadata().savedFiles()).containsExactly("BAG1/processed/상품정보.png");assertThat(unzip(result.archive()).get("BAG1/processed/상품정보.png")).isEqualTo(renderer.renderPng(product.notificationFields()));
    }
    @Test void rejectsOversizedEditsAndUnknownEmptyKeysBeforeGenerating() {
        var product=product();assertThatThrownBy(()->service.generate(1L,product,new NoticeImageRequestDto("DAKS",Map.of("종류","x".repeat(8193))))).hasMessageContaining("너무 깁니다");
        assertThatThrownBy(()->service.layout(product,new NoticeImageRequestDto("DAKS",Map.of("","")))).hasMessageContaining("항목만 편집");
        var fields=new LinkedHashMap<String,String>();for(int i=0;i<101;i++)fields.put("key"+i,"v");assertThatThrownBy(()->service.layout(product,new NoticeImageRequestDto("DAKS",fields))).hasMessageContaining("100개");
        assertThat(service.layout(product,new NoticeImageRequestDto("DAKS",Map.of("종류","","크기","","제조국",""))).cards()).containsExactly(new NoticeImageCardDto("정보","표시할 정보가 없습니다.",true));
    }
    private static byte[] png(GeneratedNoticeImageDto dto){return Base64.getDecoder().decode(dto.imageDataUrl().substring("data:image/png;base64,".length()));}
    private static byte[] solidPng(Color color,int width,int height)throws IOException{var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(color);g.fillRect(0,0,width,height);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    private static Map<String,byte[]> unzip(byte[] bytes)throws IOException{var result=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),zip.readAllBytes());}return result;}
}
