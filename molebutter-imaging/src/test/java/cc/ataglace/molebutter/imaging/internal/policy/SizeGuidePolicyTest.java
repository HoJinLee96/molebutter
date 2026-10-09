package cc.ataglace.molebutter.imaging.internal.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;

class SizeGuidePolicyTest {

    private String assetBaseName(String productName, List<String> categories, Map<String, String> notice) {
        SizeGuideTemplate template = SizeGuidePolicy.resolveSizeGuideTemplate(productName, categories, notice);
        return template.supported() ? template.assetBaseName() : null;
    }

    @Test
    void extractsBagSizeLabelFromProductName() {
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel(
                "[스테디셀러] 브라운 로고프린트 캔버스 토트백 겸 크로스백 S")).contains("S(small)");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel(
                "[D-PLEATS] 민트 솔리드 플리츠 토트백 M")).contains("M(medium)");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel(
                "[스테디셀러] 블랙 DD로고 소가죽 미니크로스백")).contains("MINI(mini)");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel(
                "[BIRKBECK CHARLOTTE] 버크백샬롯 브라운 가죽 토트백 L")).contains("L(large)");
    }

    @Test
    void extractsMiniTextBeforeOptionCodeFallback() {
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel("블랙 미니 크로스백 M")).contains("MINI(mini)");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel("BLACK MINI CROSSBODY M")).contains("MINI(mini)");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel("BLACK MINIBAG M")).contains("MINI(mini)");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel("[미니] 블랙 크로스백 M")).contains("MINI(mini)");
    }

    @Test
    void normalizesEveryOneSizeAliasToFree() {
        assertThat(SizeGuidePolicy.labelForSizeToken("OS")).contains("FREE");
        assertThat(SizeGuidePolicy.labelForSizeToken("one size")).contains("FREE");
        assertThat(SizeGuidePolicy.labelForSizeToken("FREE")).contains("FREE");
        assertThat(SizeGuidePolicy.normalizeSizeLabel("OS(one size)")).isEqualTo("FREE");
        assertThat(SizeGuidePolicy.normalizeSizeLabel("FREE(free)")).isEqualTo("FREE");
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel("블랙 토트백 ONE SIZE")).contains("FREE");
    }

    @Test
    void ignoresBracketedCollectionNameWhenExtractingSizeLabel() {
        assertThat(SizeGuidePolicy.extractProductNameSizeLabel("[M] 블랙 DD로고 소가죽 크로스백")).isEmpty();
    }

    @Test
    void resolvesSizeGuideTemplateFromProductNameBeforeCategoryFallback() {
        assertThat(assetBaseName(
                "[셀럽픽] 리아 블랙 소가죽 플라워 참장식 토트백 M",
                List.of("숄더백", "여성가방", "가방"),
                Map.of("종류", "여성 가방")))
                .isEqualTo("bag-tote");
    }

    @Test
    void resolvesSizeGuideTemplateFromApiCategoryWhenProductNameIsGeneric() {
        assertThat(assetBaseName("블랙 로고 장식 소가죽", List.of("백팩", "남성가방", "가방"), Map.of("종류", "남성 가방")))
                .isEqualTo("backpack");
        assertThat(assetBaseName("블랙 로고 장식 소가죽", List.of("크로스백", "여성가방", "가방"), Map.of("종류", "여성 가방")))
                .isEqualTo("bag-shoulder");
        assertThat(assetBaseName("블랙 로고 장식 소가죽", List.of("서류가방", "남성가방", "가방"), Map.of("종류", "남성 가방")))
                .isEqualTo("bag-shoulder");
        assertThat(assetBaseName("블랙 로고 장식 메신저백", List.of("크로스백", "남성가방", "가방"), Map.of("종류", "남성 가방")))
                .isEqualTo("bag_cross");
        assertThat(assetBaseName("블랙 로고 장식 나일론", List.of("슬링백", "남성가방", "가방"), Map.of("종류", "남성 가방")))
                .isEqualTo("slingbag");
    }

    @Test
    void separatesMaleBriefcasesFromMaleCrossBags() {
        Map<String, String> maleBagNotice = Map.of("종류", "남성 가방");
        assertThat(assetBaseName("블랙 DD로고 가죽 서류가방", List.of("서류가방", "남성가방", "가방"), maleBagNotice))
                .isEqualTo("bag-shoulder");
        assertThat(assetBaseName("[스테디셀러] 블랙 체크 가죽 크로스백", List.of("크로스백", "남성가방", "가방"), maleBagNotice))
                .isEqualTo("bag_cross");
        assertThat(assetBaseName("블랙 로고 장식 슬링백", List.of("크로스백", "남성가방", "가방"), maleBagNotice))
                .isEqualTo("slingbag");
    }

    @Test
    void resolvesSizeGuideTemplateForWallets() {
        assertThat(assetBaseName("블랙 소가죽 반지갑", List.of("지갑", "남성잡화"), Map.of("종류", "지갑")))
                .isEqualTo("wallet-open-half");
        assertThat(SizeGuidePolicy.resolveSizeGuideTemplate(
                "블랙 소가죽 반지갑", List.of("지갑", "남성잡화"), Map.of("종류", "지갑")).displayName())
                .isEqualTo("가로 여닫는 2단 반지갑");
        assertThat(assetBaseName("블랙 로고 장식 소가죽", List.of("여성중지갑", "지갑", "여성잡화"), Map.of("종류", "지갑")))
                .isEqualTo("wallet-open-medium-long");
        assertThat(assetBaseName("세로 여닫는 블랙 소가죽 중지갑", List.of("지갑", "여성잡화"), Map.of("종류", "중지갑")))
                .isEqualTo("wallet-open-medium-long-vertical");
        assertThat(assetBaseName("블랙 소가죽 카드지갑", List.of("지갑", "여성잡화"), Map.of("종류", "카드지갑")))
                .isEqualTo("cardwallet");
        assertThat(assetBaseName("블랙 소가죽", List.of("지갑", "여성잡화"),
                Map.of("제품 주요 사양", "카드수납공간-6칸 투명창수납공간-1칸")))
                .isEqualTo("cardwallet");
    }

    @Test
    void resolvesBagAndWalletAliases() {
        assertThat(assetBaseName("미니몬트 사첼백", List.of("Bags"), Map.of("크기", "21.5cm x 17cm x 8.5cm")))
                .isEqualTo("bag-tote");
        assertThat(assetBaseName("미니캐비어 나노백 아이보리", List.of("Bags"), Map.of("크기", "11.7cm x 8.5cm x 3.5cm")))
                .isEqualTo("bag-shoulder");
        assertThat(assetBaseName("에센셜 명함지갑", List.of("Wallets"), Map.of("종류", "명함지갑")))
                .isEqualTo("cardwallet");
        assertThat(assetBaseName("씨엘 미니지갑 슈가핑크", List.of("Wallets"), Map.of("종류", "미니지갑")))
                .isEqualTo("wallet-open-half");
        assertThat(assetBaseName("헤리티지 멀티지갑", List.of("Wallets"), Map.of("종류", "멀티지갑")))
                .isEqualTo("wallet-open-medium-long");
    }

    @Test
    void doesNotResolveSizeGuideTemplateForUnsupportedWalletAccessories() {
        assertThat(assetBaseName("블랙 소가죽 지갑", List.of("지갑", "남성잡화"), Map.of("종류", "지갑"))).isNull();
        assertThat(assetBaseName("블랙 소가죽 파우치", List.of("여행용파우치", "가방/잡화"), Map.of("종류", "여성 가방"))).isNull();
    }

    @Test
    void resolvesBeltsToTheSharedBeltSampleButKeepsBeltBagsAsBags() {
        assertThat(assetBaseName("블랙 소가죽 벨트", List.of("벨트", "남성잡화"), Map.of("종류", "벨트"))).isEqualTo("belt");
        assertThat(assetBaseName("[길이조절가능] 브라운 골드버클 소가죽 여성 벨트 24mm",
                List.of("여성벨트", "패션소품", "벨트", "가방/잡화"), Map.of("종류", "여자 벨트"))).isEqualTo("belt");
        assertThat(assetBaseName("블랙 로고 벨트백", List.of("벨트백", "여성가방", "가방"), Map.of("종류", "여성 가방")))
                .isEqualTo("bag-shoulder");
        assertThat(SizeGuideTemplate.BELT.widthLabel()).isEqualTo("총길이");
        assertThat(SizeGuideTemplate.BELT.heightLabel()).isEqualTo("너비");
        assertThat(SizeGuideTemplate.BELT.alwaysTwoDimensional()).isTrue();
        assertThat(SizeGuideTemplate.TOTE.widthLabel()).isEqualTo("가로");
    }

    @Test
    void resolvesSizeGuideTemplateForClutchesAndCoinWallets() {
        assertThat(assetBaseName("블랙 소가죽 클러치백", List.of("클러치백", "여성가방", "가방"), Map.of("종류", "여성 가방")))
                .isEqualTo("clutchbag");
        assertThat(assetBaseName("블랙 소가죽 동전지갑", List.of("동전지갑", "여성잡화"), Map.of("종류", "동전지갑")))
                .isEqualTo("clutchbag");
    }

    @Test
    void listsSupportedTemplates() {
        assertThat(SizeGuideTemplate.supportedTemplates())
                .extracting(SizeGuideTemplate::key)
                .contains("CARD_WALLET", "WALLET_OPEN_MEDIUM_LONG_VERTICAL", "BELT")
                .doesNotContain("UNSUPPORTED");
    }

    @Test
    void infersGenderFromNotificationProductTypeBeforeFallbacks() {
        assertThat(SizeGuidePolicy.inferGender(
                "[서류가방][백몰전용] 블랙 로고 장식 소가죽", "DBBA6F335BK", "", Map.of("종류", "남성 가방")))
                .isEqualTo("남성");
        assertThat(SizeGuidePolicy.inferGender(
                "[셀럽픽] 리아 블랙 소가죽 플라워 참장식 토트백 M", "DCBA6F553BK", "", Map.of("종류", "여성 가방")))
                .isEqualTo("여성");
    }

    @Test
    void doesNotDefaultUnknownGenderToWomen() {
        assertThat(SizeGuidePolicy.inferGender("블랙 로고 장식 소가죽", "DBBA6F335BK", "", Map.of())).isEmpty();
    }
}
