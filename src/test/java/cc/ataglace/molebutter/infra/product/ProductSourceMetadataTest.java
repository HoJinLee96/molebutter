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

    @Test void nestedNaverBranchContradictionsPreventStoreAssignment() {
        var parser=new MallOptionParser(new ObjectMapper());
        String outer=naverChannel("현대백화점","목동점","H","M");
        for(String inner:java.util.List.of(naverChannel("현대백화점","천호점","H","C"),
                naverChannel("롯데백화점","목동점","L","M"),naverChannel("현대백화점","목동점","H","X"),naverChannel("현대백화점","목동점","h","M"))) {
            var detail=parser.details(Mall.NAVER_SMART_STORE,naverChannels(outer,inner),"42");
            var offer=new Offer("nv","","","42","https://shopping.naver.com/window-products/department/42",1L,0L,Mall.NAVER_SMART_STORE,null);
            var branch=cc.ataglace.molebutter.service.product.SupplierBranch.resolve(offer,detail);
            assertThat(branch.state()).isEqualTo("CONFLICT");
            assertThat(cc.ataglace.molebutter.service.product.SupplierStorePolicy.identity(offer,branch)).isNull();
        }
    }
    @Test void matchingAndComplementaryNaverChannelsKeepTheirStoreEvidence() {
        var parser=new MallOptionParser(new ObjectMapper());
        String full=naverChannel("현대백화점","목동점","H","M");
        for(String outer:java.util.List.of(full,naverChannel("현대백화점"," 목동점 ","H",""),"{\"id\":\"1\",\"channelUid\":\"U\",\"verticalType\":\"DEPARTMENT\"}","null")) {
            var detail=parser.details(Mall.NAVER_SMART_STORE,naverChannels(outer,full),"42");
            assertThat(detail.storeEvidence().kind()).isEqualTo("BRANCH");
            assertThat(detail.storeEvidence().externalId()).isEqualTo("H/M");
            assertThat(detail.storeEvidence().references()).containsEntry("channelUid","U");
        }
        assertThat(parser.details(Mall.NAVER_SMART_STORE,naverChannels(full,"null"),"42").storeEvidence().externalId()).isEqualTo("H/M");
    }
    private String naverChannel(String retailer,String branch,String retailerId,String branchId) {
        return new ObjectMapper().writeValueAsString(Map.of("id","1","channelUid","U","verticalType","DEPARTMENT",
                "storeCategory",Map.of("wholeNames",java.util.List.of(retailer,branch),"wholeIds",java.util.List.of(retailerId,branchId))));
    }
    private String naverChannels(String outer,String inner) {
        return "{\"id\":\"42\",\"channel\":"+outer+",\"contents\":{\"id\":\"42\",\"channel\":"+inner+"}}";
    }

    @Test void conflictingProductNumbersCannotBecomeSearchCandidates() {
        Map<Mall,String> urls=Map.of(
                Mall.HAZZYS,"https://www.hazzys.com/product.do?PROD_CD=P1",
                Mall.LFMALL,"https://www.lfmall.co.kr/app/product/P1",
                Mall.HI_THEHYUNDAI,"https://hi.thehyundai.com/product/P1",
                Mall.HMALL,"https://www.hmall.com/p/pda/itemPtc.do?slitmCd=P1",
                Mall.LOTTE_IMALL,"https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no=P1",
                Mall.NAVER_SMART_STORE,"https://shopping.naver.com/window-products/department/P1");
        var json=new ObjectMapper();var search=new NaverSearchPayload(json);
        urls.forEach((mall,url)->{
            assertThat(ProductSourceMetadata.productId(mall,url,"P2")).isEmpty();
            assertThat(ProductSourceMetadata.productId(mall,url,"p1")).isEmpty();
            for(String supplied:java.util.List.of("P1","","invalid id"))
                assertThat(ProductSourceMetadata.productId(mall,url,supplied)).isEqualTo("P1");
            var rows=java.util.List.of(
                    Map.of("nvMid","bad","mallName",mall.getDisplayName(),"purchaseUrl",url,"mallProductId","P2","price",1000),
                    Map.of("nvMid","good","mallName",mall.getDisplayName(),"purchaseUrl",url,"mallProductId","P1","price",2000));
            var result=search.parse(json.writeValueAsString(Map.of("products",rows)));
            assertThat(result.offers()).singleElement().satisfies(o->{
                assertThat(o.naverProductId()).isEqualTo("good");assertThat(o.mallProductId()).isEqualTo("P1");
                assertThat(o.price()).isEqualTo(2000L);
            });
        });
        assertThat(ProductSourceMetadata.productId(Mall.HAZZYS,"https://www.hazzys.com/product.do","P1")).isEqualTo("P1");
        // 롯데 pdNo와 sitmNo는 다른 종류의 번호이므로 URL의 판매 SKU를 사용한다.
        assertThat(ProductSourceMetadata.productId(Mall.LOTTE_ON,
                "https://www.lotteon.com/p/product/PD1?sitmNo=PD1_2","PD1")).isEqualTo("PD1_2");
    }

    @Test void hyundaiOptionsAndTitleStopAtTheMainProductBoundary() {
        var parser=new MallOptionParser(new ObjectMapper());
        String recommendation="""
                {"slitmCd":"B","productName":"추천 B","sellUitmList":[
                  {"uitmCd":"B1","uitmTotNm":"M","sellPossQty":7}]}
                """;
        for(String payload:java.util.List.of(
                "{\"slitmCd\":\"A\",\"recommendations\":["+recommendation+"]}",
                "{\"data\":{\"slitmCd\":\"A\",\"recommendations\":["+recommendation+"]}}",
                "{\"slitmCd\":\"B\",\"recommendations\":["+recommendation+"]}")) {
            var detail=parser.details(Mall.HI_THEHYUNDAI,payload,"B");
            assertThat(detail.options()).isEmpty();assertThat(detail.optionsComplete()).isFalse();
            assertThat(detail.title()).isEmpty();
        }
        var matched=parser.details(Mall.HI_THEHYUNDAI,"{\"data\":"+recommendation+"}","B");
        assertThat(matched.title()).isEqualTo("추천 B");assertThat(matched.optionsComplete()).isTrue();
        assertThat(matched.options()).singleElement().satisfies(o->assertThat(o.stock()).isEqualTo(7L));
    }

    @Test void lotteRetailerNamesMustAgreeBeforeAssigningAStore() {
        var json=new ObjectMapper();var parser=new MallOptionParser(json);
        var offer=new Offer("nv","상품","롯데ON","L1","https://www.lotteon.com/p?sitmNo=L1",1L,0L,Mall.LOTTE_ON,null);
        for(String basicRetailer:java.util.List.of("현대백화점","롯데백화점")) {
            String other=basicRetailer.equals("현대백화점")?"롯데백화점":"현대백화점";
            for(String retailerField:java.util.List.of("lrtrNm","trNm")) {
                var basic=new java.util.LinkedHashMap<String,Object>(Map.of("sitmNo","L1","trNo","10","trNm","판매자",
                        "lrtrNm",basicRetailer+" 목동점","trGrpNm",basicRetailer));
                var seller=new java.util.LinkedHashMap<String,Object>(Map.of("trNo","10","trNm","판매자","lrtrNm",other+" 목동점"));
                if(retailerField.equals("trNm")) {
                    basic.put("trNm",other+" 목동점");seller.put("trNm",other+" 목동점");
                    seller.put("lrtrNm","목동점");
                }
                String payload=json.writeValueAsString(Map.of("returnCode","200","data",
                        Map.of("basicInfo",basic,"slrInfo",Map.of("trBase",seller))));
                var detail=parser.details(Mall.LOTTE_ON,payload,"L1");
                var branch=cc.ataglace.molebutter.service.product.SupplierBranch.resolve(offer,detail);
                assertThat(detail.storeEvidence().kind()).isEqualTo("CONFLICT");
                assertThat(branch.state()).isEqualTo("CONFLICT");
                assertThat(cc.ataglace.molebutter.service.product.SupplierStorePolicy.identity(offer,branch)).isNull();
            }
        }
        String matching=json.writeValueAsString(Map.of("returnCode","200","data",Map.of(
                "basicInfo",Map.of("sitmNo","L1","trNo","10","trNm","판매자","lrtrNm","현대백화점 목동점"),
                "slrInfo",Map.of("trBase",Map.of("trNo","10","trNm","판매자","lrtrNm","현대백화점 목동점")))));
        var agreed=parser.details(Mall.LOTTE_ON,matching,"L1");
        assertThat(agreed.storeEvidence().retailer()).isEqualTo("현대백화점");
        assertThat(cc.ataglace.molebutter.service.product.SupplierBranch.resolve(offer,agreed).state()).isEqualTo("CONFIRMED");
    }
}
