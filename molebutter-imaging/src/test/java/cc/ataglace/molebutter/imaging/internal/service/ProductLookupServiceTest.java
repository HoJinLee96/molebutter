package cc.ataglace.molebutter.imaging.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;

import cc.ataglace.molebutter.imaging.internal.client.LfmallApiClient;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;

class ProductLookupServiceTest {

    private static final String BASE = "https://nxapi.lfmall.co.kr";

    private MockRestServiceServer server;
    private ProductLookupService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        service = new ProductLookupService(new LfmallApiClient(builder.build(), new tools.jackson.databind.json.JsonMapper(), BASE, "2000"));
    }

    @Test
    void assemblesLookupDtoFromLfmallApis() {
        server.expect(requestTo(startsWith(BASE + "/product/v1/basic/init/DCBA6E370BK")))
                .andExpect(header("Referer", "https://www.lfmall.co.kr/app/product/DCBA6E370BK"))
                .andRespond(withSuccess("""
                        {"body":{"productBasicDTO":{"productName":"[테스트] 블랙 소가죽 토트백 L"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/product/v1/price/DCBA6E370BK")))
                .andRespond(withSuccess("""
                        {"body":{"productPriceDTO":{"originalPrice":100000,"salePrice":79000}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/product/v1/notifications/DCBA6E370BK")))
                .andRespond(withSuccess("""
                        {"body":{"productNotificationDTOList":[{"value":
                        "<table><tr><th>종류</th><td>여성 가방</td></tr><tr><th>크기</th><td>30 X 25 X 12(가로X세로X폭)</td></tr></table>"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/product/v1/contents/DCBA6E370BK")))
                .andRespond(withSuccess("""
                        {"body":{"productContentsDTO":{"productImageDTOList":[
                        {"imageUrl":"https://nimg.lfmall.co.kr/b.jpg","displaySequence":2},
                        {"imageUrl":"https://nimg.lfmall.co.kr/a.jpg","displaySequence":1},
                        {"imageUrl":"https://nimg.lfmall.co.kr/a.jpg","displaySequence":3}]}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/product/v1/size-chart/DCBA6E370BK")))
                .andRespond(withSuccess("""
                        {"body":{"sizeChart":{"productSizeChartMDPResultVOList":[{
                        "sectionList":[{"sectionCd":"W","sectionName":"가로"},{"sectionCd":"D","sectionName":"폭"},{"sectionCd":"H","sectionName":"높이"}],
                        "productSizeMdpInfoResultList":[{"productSizeMDPInfoDtlResultVOList":[
                        {"sectionCd":"W","sectionValue":"34"},{"sectionCd":"D","sectionValue":"15"},{"sectionCd":"H","sectionValue":"24"}]}]}]}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/product/v1/basic/category/DCBA6E370BK")))
                .andRespond(withSuccess("""
                        {"body":{"productStandardCategoriesDTOList":[{"standardCategoryName":"토트백"}],
                        "productDetailCategoriesDTOList":[{"productDetailCategoryName":"여성가방"}]}}
                        """, MediaType.APPLICATION_JSON));

        ProductLookupDto product = service.lookup("dcba6e370bk", "DAKS");

        assertThat(product.productCode()).isEqualTo("DCBA6E370BK");
        assertThat(product.productName()).isEqualTo("닥스 여성 [테스트] 블랙 소가죽 토트백 L");
        assertThat(product.originalPrice()).isEqualTo(100000);
        assertThat(product.salePrice()).isEqualTo(79000);
        assertThat(product.imageUrls()).containsExactly(
                "https://nimg.lfmall.co.kr/a.jpg", "https://nimg.lfmall.co.kr/b.jpg");
        assertThat(product.categoryNames()).containsExactly("토트백", "여성가방");
        assertThat(product.notificationFields())
                .containsEntry("종류", "여성 가방")
                .containsEntry("크기", "30 X 25 X 12(가로X세로X폭)");
        assertThat(product.sizeLabel()).isEqualTo("L(large)");
        assertThat(product.sizeDescription()).isEqualTo("30 X 25 X 12(가로X세로X폭)");
        assertThat(product.sizeMeasurements())
                .containsEntry("가로", "34")
                .containsEntry("높이", "24");
        assertThat(product.sizeGuideSupported()).isTrue();
        assertThat(product.sizeGuideTemplateKey()).isEqualTo("TOTE");
        assertThat(product.sizeGuideTypeName()).isEqualTo("토트백");
        assertThat(product.sizeDimensions().width()).isEqualTo("34");
        assertThat(product.sizeDimensions().depth()).isEqualTo("15");
        assertThat(product.sizeDimensions().height()).isEqualTo("24");
        server.verify();
    }

    @Test
    void rejectsInvalidProductCode() {
        assertThatThrownBy(() -> service.lookup("x!", "DAKS"))
                .isInstanceOf(ImagingFailure.class)
                .extracting(e -> ((ImagingFailure) e).kind())
                .isEqualTo(ImagingFailure.Kind.INVALID_INPUT);
    }

    @Test
    void wrapsApiFailureAsBadGateway() {
        server.expect(requestTo(startsWith(BASE + "/product/v1/basic/init/DCBA6E370BK")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.lookup("DCBA6E370BK", "DAKS"))
                .isInstanceOf(ImagingFailure.class)
                .hasMessageContaining("LFmall 응답을 가져오지 못했습니다");
    }
}
