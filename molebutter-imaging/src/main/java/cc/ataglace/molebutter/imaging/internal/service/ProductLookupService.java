package cc.ataglace.molebutter.imaging.internal.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import java.util.Locale;

import tools.jackson.databind.JsonNode;

import cc.ataglace.molebutter.imaging.internal.client.LfmallApiClient;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;
import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.internal.parse.LfmallResponseParser;
import cc.ataglace.molebutter.imaging.internal.parse.SizeDimensionParser;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** LF몰 API 5~7종을 묶어 상품 조회 DTO 를 만드는 오케스트레이션. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductLookupService {

    private static final Pattern PRODUCT_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,40}$");
    private static final Map<String, BrandProfile> BRAND_PROFILES = Map.of(
            "DAKS", new BrandProfile("DAKS", "닥스"),
            "HAZZYS", new BrandProfile("HAZZYS", "헤지스"),
            "JILLSTUART", new BrandProfile("JILLSTUART", "질스튜어트"),
            "LQT", new BrandProfile("LQT", "루이까또즈"),
            "SAMSONITE", new BrandProfile("SAMSONITE", "쌤소나이트"));

    private final LfmallApiClient lfmallApiClient;

    public ProductLookupDto lookup(String rawProductCode, String rawBrandCode) {
        String productCode = normalizeProductCode(rawProductCode);
        BrandProfile brand = normalizeBrand(rawBrandCode);
        log.info("상품 정보 조회 시작: productCode={}, brandCode={}", productCode, brand.code());

        JsonNode init = lfmallApiClient.init(productCode);
        JsonNode price = lfmallApiClient.price(productCode);
        JsonNode notifications = lfmallApiClient.notifications(productCode);
        JsonNode contents = lfmallApiClient.contents(productCode);
        JsonNode sizeChart = lfmallApiClient.sizeChart(productCode);

        Map<String, String> notificationFields = LfmallResponseParser.parseNotificationFields(notifications);
        List<String> categoryNames = fetchCategoryNames(productCode);
        Map<String, String> sizeMeasurements = LfmallResponseParser.extractSizeMeasurements(sizeChart);
        String originalProductName = LfmallResponseParser.productName(init);
        String gender = SizeGuidePolicy.inferGender(originalProductName, productCode, init.toString(), notificationFields);
        List<String> imageUrls = LfmallResponseParser.extractImageUrls(contents);
        Integer originalPrice = LfmallResponseParser.originalPrice(price);
        Integer salePrice = LfmallResponseParser.salePrice(price);
        String sizeLabel = resolveSizeLabel(originalProductName, productCode);
        SizeGuideTemplate sizeGuideTemplate =
                SizeGuidePolicy.resolveSizeGuideTemplate(originalProductName, categoryNames, notificationFields);
        String sizeDescription = SizeDimensionParser.firstSizeDimensionField(notificationFields).orElse("");
        SizeDimensionsDto sizeDimensions = sizeGuideTemplate == SizeGuideTemplate.BELT
                ? SizeDimensionParser.resolveBelt(sizeDescription, notificationFields, sizeMeasurements)
                : SizeDimensionParser.resolve(sizeDescription, notificationFields, sizeMeasurements);

        log.info("상품 정보 조회 완료: productCode={}, name={}, imageCount={}, sizeGuideType={}, dimensions={}",
                productCode, originalProductName, imageUrls.size(), sizeGuideTemplate.displayName(), sizeDimensions);

        return new ProductLookupDto(
                productCode,
                brand.code(),
                brand.label(),
                buildDisplayProductName(brand.label(), gender, originalProductName),
                originalPrice,
                salePrice,
                imageUrls,
                categoryNames,
                notificationFields,
                sizeLabel,
                sizeDescription,
                sizeMeasurements,
                sizeGuideTemplate.supported(),
                sizeGuideTemplate.displayName(),
                sizeGuideTemplate.supported() ? sizeGuideTemplate.key() : null,
                sizeDimensions);
    }

    private List<String> fetchCategoryNames(String productCode) {
        try {
            return LfmallResponseParser.extractCategoryNames(lfmallApiClient.categories(productCode));
        } catch (ImagingFailure e) {
            log.debug("상품 카테고리 조회 실패, 상품명 기반 분류로 대체: productCode={}, error={}", productCode, e.getMessage());
            return List.of();
        }
    }

    private String resolveSizeLabel(String productName, String productCode) {
        Optional<String> productNameSizeLabel = SizeGuidePolicy.extractProductNameSizeLabel(productName);
        if (productNameSizeLabel.isPresent()) {
            return productNameSizeLabel.get();
        }
        return extractOptionSizeLabel(productCode).orElse(SizeGuidePolicy.FREE_SIZE_LABEL);
    }

    private Optional<String> extractOptionSizeLabel(String productCode) {
        try {
            JsonNode options = lfmallApiClient.options(productCode);
            for (JsonNode size : LfmallResponseParser.optionSizeNodes(options)) {
                Optional<String> sizeValue =
                        SizeGuidePolicy.labelForSizeToken(LfmallResponseParser.text(size, "sizeValue"));
                if (sizeValue.isPresent() && !SizeGuidePolicy.isPlaceholderSizeLabel(sizeValue.get())) {
                    return sizeValue;
                }
                Optional<String> sizeCode =
                        SizeGuidePolicy.labelForSizeToken(LfmallResponseParser.text(size, "sizeCode"));
                if (sizeCode.isPresent() && !SizeGuidePolicy.isPlaceholderSizeLabel(sizeCode.get())) {
                    return sizeCode;
                }
            }
        } catch (ImagingFailure e) {
            log.debug("상품 옵션 사이즈 라벨 조회 실패, 기본 FREE로 대체: productCode={}, error={}", productCode, e.getMessage());
        }
        return Optional.empty();
    }

    private String normalizeProductCode(String rawProductCode) {
        String productCode = rawProductCode == null ? "" : rawProductCode.trim().toUpperCase(Locale.ROOT);
        if (!PRODUCT_CODE_PATTERN.matcher(productCode).matches()) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "올바른 상품코드를 입력해주세요.");
        }
        return productCode;
    }

    private BrandProfile normalizeBrand(String rawBrandCode) {
        String brandCode = rawBrandCode == null || rawBrandCode.isBlank()
                ? "DAKS"
                : rawBrandCode.trim().toUpperCase(Locale.ROOT);
        BrandProfile brand = BRAND_PROFILES.get(brandCode);
        if (brand == null) throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "지원하는 브랜드를 선택해주세요.");
        return brand;
    }

    private String buildDisplayProductName(String brandName, String gender, String productName) {
        String safeProductName = productName == null || productName.isBlank() ? "상품명 없음" : productName.trim();
        return "%s %s %s".formatted(brandName, gender, safeProductName).replaceAll("\\s+", " ").trim();
    }

    private record BrandProfile(String code, String label) {
    }
}
