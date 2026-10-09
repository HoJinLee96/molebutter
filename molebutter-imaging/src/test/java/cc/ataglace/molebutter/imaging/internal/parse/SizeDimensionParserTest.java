package cc.ataglace.molebutter.imaging.internal.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;

class SizeDimensionParserTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "가로=250mm 폭=80mm 높이=200mm",
            "250mm x 80mm x 200mm",
            "250mmx80mmx200mm",
            "250 MM × 80 MM × 200 MM",
            "250mm x 200mm x 80mm(가로x세로x폭)",
            "250mm(가로) x 200mm(높이) x 80mm(폭)",
            "250 x 80 x 200mm",
            "250X80X200MM",
            "250 x 200 x 80mm(가로x세로x폭)",
            "25cm x 80mm x 20cm"
    })
    void resolvesMillimetersAsCentimetersAcrossSizeFormats(String text) {
        assertThat(SizeDimensionParser.resolve(text, Map.of(), Map.of()))
                .isEqualTo(new SizeDimensionsDto("25", "8", "20"));
    }

    @Test
    void resolvesMillimeterChartValuesBeforeApplyingChartPrecedence() {
        assertThat(SizeDimensionParser.resolve("30 x 15 x 20cm", Map.of(),
                Map.of("가로", "250mm", "폭", "80mm", "높이", "200mm")))
                .isEqualTo(new SizeDimensionsDto("25", "8", "20"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"250mm x 200mm(가로x세로)", "250mm(가로) x 200mm(세로)", "250 x 200mm(가로x세로)"})
    void resolvesMillimeterWalletPairs(String text) {
        assertThat(SizeDimensionParser.resolve("", Map.of("크기", text), Map.of()))
                .isEqualTo(new SizeDimensionsDto("25", "-", "20"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20mm x 1080mm(폭x총길이)", "1080mm(총길이) x 20mm(너비)",
            "20 x 1080mm", "2cm x 1080mm"})
    void resolvesBeltMillimetersBeforeOrderingLengthAndStrapWidth(String text) {
        assertThat(SizeDimensionParser.resolveBelt(text, Map.of(), Map.of()))
                .isEqualTo(new SizeDimensionsDto("108", "-", "2"));
    }

    @Test
    void preservesExactDecimalConversionAndCentimeterValues() {
        assertThat(SizeDimensionParser.resolve("", Map.of(),
                Map.of("가로", "250.5mm", "폭", "0.1mm", "높이", "20.75cm")))
                .isEqualTo(new SizeDimensionsDto("25.05", "0.01", "20.75"));
    }

    @Test
    void resolvesOrderedSizeTripleFromNotificationFields() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "", Map.of("제품 주요 사양", "12 X 11 X 2(가로X세로X폭)"), Map.of());

        assertThat(dimensions.width()).isEqualTo("12");
        assertThat(dimensions.height()).isEqualTo("11");
        assertThat(dimensions.depth()).isEqualTo("2");
    }

    @Test
    void resolvesPerValueLabeledSizeTriple() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "", Map.of("크기", "43(가로) x 31(세로) x 4(폭) (재는 위치에 따라 약간의 오차가 있을 수 있습니다.)"),
                Map.of());

        assertThat(dimensions.width()).isEqualTo("43");
        assertThat(dimensions.height()).isEqualTo("31");
        assertThat(dimensions.depth()).isEqualTo("4");
    }

    @Test
    void resolvesOrderedSizePairForCardWallet() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "", Map.of("제품 주요 사양",
                        "7.6X11.5(가로X세로) (재는 위치에 따라 약간의 오차가 있을 수 있습니다.) 카드수납공간-6칸 투명창수납공간-1칸"),
                Map.of());

        assertThat(dimensions.width()).isEqualTo("7.6");
        assertThat(dimensions.height()).isEqualTo("11.5");
        assertThat(dimensions.depth()).isEqualTo("-");
        assertThat(SizeDimensionParser.hasRequiredDimensions(SizeGuideTemplate.CARD_WALLET, dimensions)).isTrue();
        assertThat(SizeDimensionParser.isTwoDimensionSizeGuide(SizeGuideTemplate.CARD_WALLET, dimensions)).isTrue();
    }

    @Test
    void resolvesBeltLengthAndStrapWidthFromEveryLfmallNoticeShape() {
        // 폭X총길이 순서 라벨
        SizeDimensionsDto hook = SizeDimensionParser.resolveBelt(
                "2 X 108(폭X총길이) 버클기장 4 cm (재는 위치에 따라 약간의 오차가 있을 수 있습니다.)", Map.of(), Map.of());
        assertThat(hook.width()).isEqualTo("108");
        assertThat(hook.height()).isEqualTo("2");
        assertThat(hook.depth()).isEqualTo("-");
        // 가로X세로 라벨(가로=너비, 세로=총길이) — 버클 치수는 무시
        SizeDimensionsDto gold = SizeDimensionParser.resolveBelt(
                "2.4 X 109(가로X세로) 버클사이즈 3.5 X 3.6 (재는 위치에 따라 약간의 오차가 있을 수 있습니다.)", Map.of(), Map.of());
        assertThat(gold.width()).isEqualTo("109");
        assertThat(gold.height()).isEqualTo("2.4");
        // 라벨 없음: 큰 값이 총길이
        SizeDimensionsDto bare = SizeDimensionParser.resolveBelt("", Map.of("크기", "3 X 122"), Map.of());
        assertThat(bare.width()).isEqualTo("122");
        assertThat(bare.height()).isEqualTo("3");
        // 값별 라벨
        SizeDimensionsDto labeled = SizeDimensionParser.resolveBelt("", Map.of("크기", "115(총길이) x 3.5(너비)"), Map.of());
        assertThat(labeled.width()).isEqualTo("115");
        assertThat(labeled.height()).isEqualTo("3.5");
        assertThat(SizeDimensionParser.hasRequiredDimensions(SizeGuideTemplate.BELT, labeled)).isTrue();
        assertThat(SizeDimensionParser.isTwoDimensionSizeGuide(SizeGuideTemplate.BELT, labeled)).isTrue();
        assertThat(SizeDimensionParser.resolveBelt("", Map.of(), Map.of()).width()).isEqualTo("-");
    }

    @Test
    void missingDepthFailsRequirementForNonWalletTemplates() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "", Map.of("제품 주요 사양", "7.6X11.5(가로X세로)"), Map.of());

        assertThat(dimensions.depth()).isEqualTo("-");
        assertThat(SizeDimensionParser.hasRequiredDimensions(SizeGuideTemplate.SHOULDER_CROSS, dimensions)).isFalse();
        assertThat(SizeDimensionParser.hasRequiredDimensions(SizeGuideTemplate.WALLET_OPEN_HALF, dimensions)).isTrue();
        assertThat(SizeDimensionParser.isTwoDimensionSizeGuide(SizeGuideTemplate.WALLET_OPEN_HALF, dimensions)).isTrue();
    }

    @Test
    void resolvesBareSizeTripleOnlyFromTrustedSizeNoticeFields() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "", Map.of("제품 주요 사양", "20 x 15 x 8 cm"), Map.of());

        assertThat(dimensions.width()).isEqualTo("20");
        assertThat(dimensions.depth()).isEqualTo("15");
        assertThat(dimensions.height()).isEqualTo("8");
    }

    @Test
    void ignoresNumberedNoticeTextWhenResolvingSizeDimensions() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "", Map.of("제품 주요 사양", "1. 겉감 천연가죽 2. 안감 폴리에스터 3. 장식 금속"), Map.of());

        assertThat(dimensions.width()).isEqualTo("-");
        assertThat(dimensions.depth()).isEqualTo("-");
        assertThat(dimensions.height()).isEqualTo("-");
    }

    @Test
    void prefersDirectSizeChartValuesOverNoticeTriple() {
        SizeDimensionsDto dimensions = SizeDimensionParser.resolve(
                "30 X 25 X 12(가로X세로X폭)",
                Map.of("크기", "30 X 25 X 12(가로X세로X폭)"),
                Map.of("가로", "34", "폭", "15", "높이", "24"));

        assertThat(dimensions.width()).isEqualTo("34");
        assertThat(dimensions.depth()).isEqualTo("15");
        assertThat(dimensions.height()).isEqualTo("24");
    }

    @Test
    void mergePrefersManualValuesAndKeepsParsedForBlanks() {
        SizeDimensionsDto parsed = new SizeDimensionsDto("34", "15", "24");
        SizeDimensionsDto manual = new SizeDimensionsDto("31.5", null, "22cm");

        SizeDimensionsDto merged = SizeDimensionParser.merge(parsed, manual);

        assertThat(merged.width()).isEqualTo("31.5");
        assertThat(merged.depth()).isEqualTo("15");
        assertThat(merged.height()).isEqualTo("22");
    }

    @Test
    void leadingDotManualDecimalsKeepTheirValue() {
        SizeDimensionsDto merged = SizeDimensionParser.merge(new SizeDimensionsDto("30", "12", "22"),
                new SizeDimensionsDto(".5cm", ".75", "1"));
        assertThat(merged.width()).isEqualTo("0.5");
        assertThat(merged.depth()).isEqualTo("0.75");
    }

    @Test
    void mergeWithoutManualReturnsParsed() {
        SizeDimensionsDto parsed = new SizeDimensionsDto("34", "15", "24");

        assertThat(SizeDimensionParser.merge(parsed, null)).isEqualTo(parsed);
        assertThat(SizeDimensionParser.merge(null, null)).isEqualTo(SizeDimensionsDto.empty());
    }
}
