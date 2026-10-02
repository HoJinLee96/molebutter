package cc.ataglace.molebutter.infra.product;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

class ProductSourceMetadataTest {
    @Test void resolvesSupportedProductLinksWithoutTrustingDisplayNames() {
        Map<Mall,String> urls=Map.of(
            Mall.LFMALL,"https://www.lfmall.co.kr/app/product/P123",
            Mall.HAZZYS,"https://www.hazzys.com/product.do?PROD_CD=P123",
            Mall.NAVER_SMART_STORE,"https://smartstore.naver.com/shop/products/P123",
            Mall.LOTTE_ON,"https://www.lotteon.com/p/product?pdNo=WRONG&sitmNo=P123",
            Mall.LOTTE_IMALL,"https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no=P123",
            Mall.HI_THEHYUNDAI,"https://hi.thehyundai.com/product/P123",
            Mall.HMALL,"https://www.hmall.com/p/pda/itemPtc.do?slitmCd=P123");
        urls.forEach((mall,url)->{
            assertThat(ProductSourceMetadata.mall(url)).isEqualTo(mall);
            assertThat(ProductSourceMetadata.mall(url.replace("https:","http:"))).isEqualTo(mall);
            assertThat(ProductSourceMetadata.productId(mall,url,"")).isEqualTo("P123");
        });
        assertThat(ProductSourceMetadata.mall("https://hazzys.com.evil.example/products/P123")).isNull();
        assertThat(ProductSourceMetadata.mall("https://hazzys.com@evil.example/P123")).isNull();
        assertThat(ProductSourceMetadata.mall("https://search.shopping.naver.com:444/P123")).isNull();
        assertThat(ProductSourceMetadata.productId(Mall.LOTTE_ON,"https://www.lotteon.com/p?pdNo=UNKNOWN","")).isEmpty();
        assertThat(ProductSourceMetadata.productId(Mall.LOTTE_ON,"https://www.lotteon.com/p/product/LE1204272674?sitmNo=LE1204272674_1222907552","LE1204272674")).isEqualTo("LE1204272674_1222907552");
        assertThat(ProductSourceMetadata.mall("https://cr.shopping.naver.com/adcr?nvMid=123")).isNull();
        assertThat(ProductSourceMetadata.mall("https://search.shopping.naver.com/gate.nhn?id=123")).isNull();
        assertThat(ProductSourceMetadata.mall("https://shopping.naver.com/redirect?id=123")).isNull();
        assertThat(ProductSourceMetadata.mall("https://shopping.naver.com/window-products/department/123")).isEqualTo(Mall.NAVER_SMART_STORE);
    }
    @Test void prefersSupportedDirectLinkOverTrackingUrl() {
        var result=new NaverSearchPayload(new ObjectMapper()).parse("""
            {"products":[{"nvMid":"N1","mallName":"LF몰","productName":"가방",
              "purchaseUrl":"https://ad.example/redirect","mallProductUrl":"http://www.lfmall.co.kr/app/product/P123"}]}
            """);
        assertThat(result.offers().getFirst().mall()).isEqualTo(Mall.LFMALL);
        assertThat(result.offers().getFirst().url()).isEqualTo("http://www.lfmall.co.kr/app/product/P123");
        assertThat(result.offers().getFirst().mallProductId()).isEqualTo("P123");
    }
    @Test void preservesCandidateImagesAndResolvedMallWithLongStringIds() {
        var parser=new NaverSearchPayload(new ObjectMapper());
        var result=parser.parse("""
            {"products":[{"nvMid":"9007199254740993","mallName":"임의의 판매점 이름",
             "productName":"가방","purchaseUrl":"https://www.hazzys.com/product.do?PROD_CD=P123",
             "imageUrl":"https://shopping-phinf.pstatic.net/photo.jpg","price":69000,"deliveryFee":0}]}
            """);
        var offer=result.offers().getFirst();
        assertThat(offer.naverProductId()).isEqualTo("9007199254740993");
        assertThat(offer.mall()).isEqualTo(Mall.HAZZYS);assertThat(offer.mallProductId()).isEqualTo("P123");
        assertThat(offer.imageUrl()).isEqualTo("https://shopping-phinf.pstatic.net/photo.jpg");
        assertThat(ProductSourceMetadata.imageUrl("javascript:alert(1)")).isNull();
        assertThat(ProductSourceMetadata.imageUrl("data:image/svg+xml,<svg/>")).isNull();
        assertThat(ProductSourceMetadata.imageUrl("https://example.com/"+"사진".repeat(200))).isNull();
    }
    @Test void oldPersistedSearchAndLinkSnapshotsRemainReadable() {
        var json=new ObjectMapper();
        var link=json.readTree("{\"id\":\"1\",\"productId\":\"2\",\"mall\":\"HAZZYS\",\"url\":\"https://www.hazzys.com\"}");
        assertThat(link.path("imageUrl").isMissingNode()).isTrue();
        var offer=json.readValue("{\"naverProductId\":\"1\",\"price\":10000}",Offer.class);
        assertThat(offer.mall()).isNull();assertThat(offer.imageUrl()).isNull();
    }
}
