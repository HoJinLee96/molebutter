package cc.ataglace.molebutter.imaging.internal.client;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

/** LF몰 비공식 JSON API 클라이언트. 브라우저 위장 헤더 + 상품 페이지 Referer 를 붙여 호출한다. */
@Slf4j
@Component
public class LfmallApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final String affiliateCode;

    public LfmallApiClient(
            @Qualifier("imagingRestClient") RestClient imagingRestClient, ObjectMapper mapper,
            @Value("${imaging.lfmall.api.base-url:https://nxapi.lfmall.co.kr}") String baseUrl,
            @Value("${imaging.lfmall.api.affiliate-code:2000}") String affiliateCode) {
        this.restClient = imagingRestClient;
        this.mapper = mapper;
        this.baseUrl = LfmallUrls.validate(baseUrl).toString();
        this.affiliateCode = affiliateCode;
    }

    public JsonNode init(String productCode) {
        return getJson(productUrl("/product/v1/basic/init/{code}")
                .queryParam("affiliateCode", affiliateCode)
                .queryParam("usePopup", "Y")
                .build(productCode), productCode);
    }

    public JsonNode price(String productCode) {
        return getJson(productUrl("/product/v1/price/{code}")
                .queryParam("affiliateCode", affiliateCode)
                .build(productCode), productCode);
    }

    public JsonNode notifications(String productCode) {
        return getJson(productUrl("/product/v1/notifications/{code}").build(productCode), productCode);
    }

    public JsonNode contents(String productCode) {
        return getJson(productUrl("/product/v1/contents/{code}").build(productCode), productCode);
    }

    public JsonNode sizeChart(String productCode) {
        return getJson(productUrl("/product/v1/size-chart/{code}").build(productCode), productCode);
    }

    public JsonNode categories(String productCode) {
        return getJson(productUrl("/product/v1/basic/category/{code}")
                .queryParam("affiliateCode", affiliateCode)
                .queryParam("stockCheckYn", "N")
                .queryParam("usePopup", "Y")
                .build(productCode), productCode);
    }

    public JsonNode options(String productCode) {
        return getJson(productUrl("/product/detail/v1/options/{code}")
                .queryParam("stockCheckYn", "N")
                .build(productCode), productCode);
    }

    private UriComponentsBuilder productUrl(String path) {
        return UriComponentsBuilder.fromUriString(baseUrl).path(path);
    }

    private JsonNode getJson(URI uri, String productCode) {
        byte[] bytes = BoundedHttp.get(restClient, uri, productCode, 2_000_000, "application/json").bytes();
        try {
            JsonNode body = mapper.readTree(bytes);
            if (body == null || body.isNull()) {
                throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 상품 정보를 가져오지 못했습니다.");
            }
            return body;
        } catch (ImagingFailure e) { throw e; }
        catch (Exception e) {
            throw new ImagingFailure(ImagingFailure.Kind.UPSTREAM, "LFmall 상품 정보를 해석하지 못했습니다.", e);
        }
    }
}
