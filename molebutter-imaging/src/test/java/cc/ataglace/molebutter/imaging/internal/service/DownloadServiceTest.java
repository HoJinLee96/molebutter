package cc.ataglace.molebutter.imaging.internal.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.client.ImageDownloadClient;
import cc.ataglace.molebutter.imaging.internal.render.*;

class DownloadServiceTest {
    private final LookupCacheService cache=mock(LookupCacheService.class);
    private final ImageDownloadClient images=mock(ImageDownloadClient.class);
    private final SizeGuideService sizes=mock(SizeGuideService.class);
    private final GeneratedImageStore generated=new GeneratedImageStore();
    private final DownloadService service=new DownloadService(images,new NoticeImageRenderer(new FontResolver("Dialog","Dialog","Dialog","Dialog")),sizes,generated);
    private ProductLookupDto product() {
        return new ProductLookupDto("BAG1","DAKS","닥스","가방",null,null,List.of("https://nimg.lfmall.co.kr/a.jpg","https://nimg.lfmall.co.kr/b.jpg"),List.of("토트백"),Map.of("종류","여성 가방"),"FREE",null,Map.of(),true,"토트백","TOTE",new SizeDimensionsDto("30","12","22"));
    }
    private DownloadRequestDto request(List<DownloadImageItemDto> items, boolean notice) {return new DownloadRequestDto("BAG1","DAKS",List.of(1),notice,true,null,items);}
    @Test void freezesGeneratedImagesAndKeepsMixedOrderDuplicatesAndMargins() throws Exception {
        var p=product();when(cache.product("BAG1","DAKS")).thenReturn(p);
        byte[] frozen=solidPng(Color.BLUE,12,16);String id=generated.put(1L,p,frozen);
        when(images.downloadProductImage(p.imageUrls().getFirst(),"BAG1")).thenReturn(new ImageDownloadClient.DownloadedImage(solidPng(Color.RED,20,30),"image/png",".png"));
        var result=service.downloadImages(1L,request(List.of(new DownloadImageItemDto(null,id),new DownloadImageItemDto(0,null),new DownloadImageItemDto(0,null)),true),product());
        assertThat(result.metadata().downloadName()).isEqualTo("BAG1.zip");
        assertThat(result.metadata().savedFiles()).containsExactly("사이즈.png","02.png","03.png","상품정보.png");
        var archive=unzip(result.archive());assertThat(archive.keySet()).containsExactlyElementsOf(result.metadata().savedFiles());
        assertThat(archive.get("사이즈.png")).isEqualTo(frozen);
        assertThat(archive.get("02.png")).isEqualTo(archive.get("03.png"));
        BufferedImage padded=ImageIO.read(new ByteArrayInputStream(archive.get("02.png")));
        assertThat(padded.getHeight()).isEqualTo(40);assertThat(padded.getRGB(10,37)).isEqualTo(Color.WHITE.getRGB());
        verify(images,never()).downloadProductImage(eq(p.imageUrls().get(1)),any());verify(sizes,never()).render(any(),any());
    }
    @Test void emptyMixedListOverridesAllLegacyOptionsExceptNotice() throws Exception {
        when(cache.product("BAG1","DAKS")).thenReturn(product());
        var result=service.downloadImages(1L,request(List.of(),true),product());
        assertThat(unzip(result.archive()).keySet()).containsExactly("상품정보.png");verifyNoInteractions(images,sizes);
    }
    @Test void validatesAllReferencesAndActorBeforeFirstDownload() {
        var p=product();when(cache.product("BAG1","DAKS")).thenReturn(p);String id=generated.put(2L,p,new byte[]{1});
        assertThatThrownBy(() -> service.downloadImages(1L,request(List.of(new DownloadImageItemDto(0,null),new DownloadImageItemDto(null,id)),false),product())).isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(() -> service.downloadImages(1L,request(List.of(new DownloadImageItemDto(0,null),new DownloadImageItemDto(20,null)),false),product())).isInstanceOf(ImagingFailure.class);
        verifyNoInteractions(images,sizes);
    }
    @Test void staleSourceUrlFailsBeforeAnyFileOrNetworkWork() {
        var selected = new DownloadImageItemDto(0, null, "https://nimg.lfmall.co.kr/previous.jpg");
        assertThatThrownBy(() -> service.downloadImages(1L,request(List.of(selected),false),product())).hasMessageContaining("다시 조회");
        verifyNoInteractions(images,sizes);
    }
    @Test void boundsItemCountBeforeSupplierRequests() {
        List<DownloadImageItemDto> tooMany=java.util.Collections.nCopies(65,new DownloadImageItemDto(0,null));
        assertThatThrownBy(() -> service.downloadImages(1L,request(tooMany,false),product())).hasMessageContaining("64개");verifyNoInteractions(cache,images,sizes);
    }
    @Test void rejectsUncompressedAggregateEvenForHighlyCompressibleImages() {
        var p=product();when(cache.product("BAG1","DAKS")).thenReturn(p);
        byte[] image=new byte[1_100_000];String id=generated.put(1L,p,image);
        assertThatThrownBy(() -> service.downloadImages(1L,request(java.util.Collections.nCopies(64,new DownloadImageItemDto(null,id)),false),product())).hasMessageContaining("64MB");
    }
    @Test void boundsZipBytesForIncompressibleImages() {
        var p=product();when(cache.product("BAG1","DAKS")).thenReturn(p);
        byte[] image=new byte[6_000_000];new java.util.Random(71).nextBytes(image);String id=generated.put(1L,p,image);
        assertThatThrownBy(() -> service.downloadImages(1L,request(java.util.Collections.nCopies(6,new DownloadImageItemDto(null,id)),false),product())).hasMessageContaining("32MB");
    }
    @Test void namesGeneratedSnapshotsByKindAndKeepsStableDuplicateNamesAndOriginalMixedOrder() throws Exception {
        var p=product();byte[] first=solidPng(Color.BLUE,780,509);byte[] second=solidPng(Color.GREEN,780,509);
        String firstId=generated.put(1L,p,first,GeneratedImageStore.ImageKind.SIZE);String secondId=generated.put(1L,p,second,GeneratedImageStore.ImageKind.SIZE);
        var noticeRenderer=new NoticeImageRenderer(new FontResolver("Dialog","Dialog","Dialog","Dialog"));
        var notice=new NoticeImageService(noticeRenderer,generated).generate(1L,p,new NoticeImageRequestDto("DAKS",Map.of()));
        byte[] noticeBytes=java.util.Base64.getDecoder().decode(notice.imageDataUrl().substring("data:image/png;base64,".length()));
        when(images.downloadProductImage(p.imageUrls().get(0),p.productCode())).thenReturn(new ImageDownloadClient.DownloadedImage(solidPng(Color.RED,20,30),"image/png",".png"));
        when(images.downloadProductImage(p.imageUrls().get(1),p.productCode())).thenReturn(new ImageDownloadClient.DownloadedImage(solidPng(Color.YELLOW,20,30),"image/png",".png"));
        var selected=List.of(new DownloadImageItemDto(null,firstId),new DownloadImageItemDto(1,null,p.imageUrls().get(1)),new DownloadImageItemDto(null,notice.id()),
                new DownloadImageItemDto(null,secondId),new DownloadImageItemDto(null,firstId),new DownloadImageItemDto(0,null,p.imageUrls().get(0)),new DownloadImageItemDto(null,notice.id()));
        var result=service.downloadImages(1L,request(selected,true),p);var archive=unzip(result.archive());
        assertThat(result.metadata().savedFiles()).containsExactly("사이즈.png","02.png","상품정보.png","사이즈_2.png","사이즈_3.png","06.png","상품정보_2.png","상품정보_3.png");
        assertThat(archive.keySet()).containsExactlyElementsOf(result.metadata().savedFiles());
        assertThat(archive.get("사이즈.png")).isEqualTo(first);assertThat(archive.get("사이즈_2.png")).isEqualTo(second);assertThat(archive.get("사이즈_3.png")).isEqualTo(first);
        assertThat(archive.get("상품정보.png")).isEqualTo(noticeBytes);assertThat(archive.get("상품정보_2.png")).isEqualTo(noticeBytes);
        assertThat(archive.get("상품정보_3.png")).isEqualTo(noticeRenderer.renderPng(p.notificationFields()));
        var exported=service.exportImages(service.capture(1L,request(selected,true),p));
        assertThat(exported).extracting(ExportImageDto::fileName).containsExactlyElementsOf(result.metadata().savedFiles());
        for (ExportImageDto file:exported) assertThat(file.bytes()).isEqualTo(archive.get(file.fileName()));
        assertThat(ImageIO.read(new ByteArrayInputStream(archive.get("02.png"))).getHeight()).isEqualTo(40);assertThat(ImageIO.read(new ByteArrayInputStream(archive.get("06.png"))).getHeight()).isEqualTo(40);
        verifyNoInteractions(sizes);
    }
    @Test void legacyIncludedSizeUsesTheSameFilenameWithoutPadding() throws Exception {
        var p=product();byte[] size=solidPng(Color.BLUE,780,509);
        when(images.downloadProductImage(p.imageUrls().get(1),p.productCode())).thenReturn(new ImageDownloadClient.DownloadedImage(solidPng(Color.RED,20,30),"image/png",".png"));
        when(sizes.render(p,null)).thenReturn(new SizeGuideService.RenderedSizeGuide(cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate.TOTE,size,null));
        var result=service.downloadImages(1L,new DownloadRequestDto("BAG1","DAKS",List.of(1),false,true,null),p);var archive=unzip(result.archive());
        assertThat(result.metadata().savedFiles()).containsExactly("01.png","사이즈.png");assertThat(archive.keySet()).containsExactly("01.png","사이즈.png");
        assertThat(archive.get("사이즈.png")).isEqualTo(size);assertThat(ImageIO.read(new ByteArrayInputStream(archive.get("사이즈.png"))).getHeight()).isEqualTo(509);
    }
    @Test void exportCaptureFreezesOwnedGeneratedBytesAndDoesNoNetworkWorkUntilPreparation() throws Exception {
        var p=product();byte[] size=solidPng(Color.BLUE,12,16);String id=generated.put(1L,p,size);
        var selected=List.of(new DownloadImageItemDto(1,null,p.imageUrls().get(1)),new DownloadImageItemDto(null,id));
        var snapshot=service.capture(1L,new DownloadRequestDto("BAG1","DAKS",null,false,false,null,selected),p);
        verifyNoInteractions(images,sizes);
        // Later generated entries evict the original cache item; the accepted export owns its copy.
        for(int i=0;i<16;i++)generated.put(1L,p,solidPng(Color.GREEN,12,16));
        assertThatThrownBy(()->generated.get(1L,id,p)).isInstanceOf(ImagingFailure.class);
        when(images.downloadProductImage(p.imageUrls().get(1),p.productCode())).thenReturn(
                new ImageDownloadClient.DownloadedImage(solidPng(Color.RED,20,30),"image/png",".png"));
        var result=service.exportImages(snapshot);
        assertThat(result).extracting(ExportImageDto::fileName).containsExactly("01.png","사이즈.png");
        assertThat(result.get(1).bytes()).isEqualTo(size);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.getFirst().bytes())).getHeight()).isEqualTo(40);
        byte[] exposed=result.get(1).bytes();exposed[0]=0;
        assertThat(result.get(1).bytes()).isEqualTo(size);
        verify(images).downloadProductImage(p.imageUrls().get(1),p.productCode());
    }
    @Test void exportCaptureRejectsOtherActorsAndStaleOriginalReferencesBeforePreparingAnyFiles() {
        var p=product();String id=generated.put(2L,p,new byte[]{1});
        assertThatThrownBy(()->service.capture(1L,request(List.of(new DownloadImageItemDto(0,null,p.imageUrls().getFirst()),new DownloadImageItemDto(null,id)),false),p))
                .isInstanceOf(ImagingFailure.class);
        assertThatThrownBy(()->service.capture(1L,request(List.of(new DownloadImageItemDto(0,null,"https://nimg.lfmall.co.kr/stale.jpg")),false),p))
                .hasMessageContaining("다시 조회");
        verifyNoInteractions(images,sizes);
    }
    private static byte[] solidPng(Color color,int width,int height)throws Exception{var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(color);g.fillRect(0,0,width,height);g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
    private static Map<String,byte[]> unzip(byte[] bytes)throws IOException{var result=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),zip.readAllBytes());}return result;}
}
