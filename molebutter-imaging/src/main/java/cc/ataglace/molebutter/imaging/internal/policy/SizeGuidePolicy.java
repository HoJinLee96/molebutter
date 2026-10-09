package cc.ataglace.molebutter.imaging.internal.policy;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상품명/카테고리/고시 텍스트로 사이즈 이미지 템플릿을 고르고, 사이즈 라벨·성별을 추론하는 정책.
 * at-a-glance ProductLookupSizeGuidePolicy 이식본.
 */
public final class SizeGuidePolicy {

    public static final String FREE_SIZE_LABEL = "FREE";

    private static final Pattern BRACKET_TEXT_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern ALPHA_SIZE_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:^|[\\s_\\-/\\(\\[])(3XL|XXXL|2XL|XXL|XL|XS|S|M|L)(?:$|[\\s_\\-/\\)\\].,])");
    private static final List<SizeLabelRule> TEXT_SIZE_LABEL_RULES = List.of(
            new SizeLabelRule(Pattern.compile(
                    "(?i)(?<![a-z0-9])(?:one\\s*-?\\s*size|os|free)(?![a-z0-9])"), FREE_SIZE_LABEL),
            new SizeLabelRule(Pattern.compile("(?i)엑스\\s*라지|엑스트라\\s*라지|특대형|\\bx\\s*-?\\s*large\\b"),
                    "XL(x-large)"),
            new SizeLabelRule(Pattern.compile("(?i)미니|초소형|(?<![a-z0-9])mini(?:$|[^a-z0-9]|(?=bag|crossbody|crossbag|wallet|purse))"), "MINI(mini)"),
            new SizeLabelRule(Pattern.compile("(?i)스몰|소형|\\bsmall\\b"), "S(small)"),
            new SizeLabelRule(Pattern.compile("(?i)미듐|미디움|중형|\\bmedium\\b"), "M(medium)"),
            new SizeLabelRule(Pattern.compile("(?i)라지|대형|\\blarge\\b"), "L(large)"));
    private static final Map<String, String> SIZE_LABELS = Map.ofEntries(
            Map.entry("XS", "XS(x-small)"),
            Map.entry("S", "S(small)"),
            Map.entry("M", "M(medium)"),
            Map.entry("L", "L(large)"),
            Map.entry("XL", "XL(x-large)"),
            Map.entry("XXL", "XXL(2x-large)"),
            Map.entry("2XL", "XXL(2x-large)"),
            Map.entry("XXXL", "XXXL(3x-large)"),
            Map.entry("3XL", "XXXL(3x-large)"),
            Map.entry("FREE", FREE_SIZE_LABEL));
    private static final Set<String> FREE_SIZE_ALIASES = Set.of(
            "FREE", "FREE(FREE)", "OS", "ONE SIZE", "ONE-SIZE", "ONE_SIZE", "ONESIZE", "OS(ONE SIZE)");

    private SizeGuidePolicy() {
    }

    public static SizeGuideTemplate resolveSizeGuideTemplate(
            String productName,
            List<String> categoryNames,
            Map<String, String> notificationFields) {
        String categoryText = String.join(" ", categoryNames == null ? List.of() : categoryNames);
        String notificationText = notificationFields == null
                ? ""
                : notificationFields.values().stream().reduce("", (left, right) -> left + " " + right);
        String combinedText = productName + " " + categoryText + " " + notificationText;

        SizeGuideTemplate walletTemplate = classifyWalletTemplate(productName);
        if (!walletTemplate.supported()) {
            walletTemplate = classifyWalletTemplate(categoryText);
        }
        if (!walletTemplate.supported()) {
            walletTemplate = classifyWalletTemplate(notificationText);
        }
        if (walletTemplate.supported()) {
            return walletTemplate;
        }

        // 벨트는 공용 표본(가로로 펼친 스트랩 + 버클)으로 총길이·너비만 표기한다. 벨트백은 가방 분류로 넘긴다.
        if (isBelt(productName) || isBelt(categoryText) || isBelt(notificationText)) {
            return SizeGuideTemplate.BELT;
        }

        if (hasUnsupportedAccessoryCategory(categoryNames)) {
            return SizeGuideTemplate.UNSUPPORTED;
        }

        SizeGuideTemplate primaryProductNameTemplate = classifyPrimaryBagTemplate(productName);
        if (primaryProductNameTemplate.supported()) {
            return primaryProductNameTemplate;
        }

        if (isMaleCrossMessengerBag(combinedText)) {
            return SizeGuideTemplate.MALE_CROSS_MESSENGER;
        }

        SizeGuideTemplate productNameTemplate = classifySupportedBagTemplate(productName);
        if (productNameTemplate.supported()) {
            return productNameTemplate;
        }

        SizeGuideTemplate categoryTemplate = classifySupportedBagTemplate(categoryText);
        if (categoryTemplate.supported()) {
            return categoryTemplate;
        }

        return classifySupportedBagTemplate(notificationText);
    }

    private static boolean isBelt(String text) {
        String productTypeText = normalizeProductTypeText(text);
        if (productTypeText.isBlank() || containsAny(productTypeText, "벨트백", "beltbag", "벨트파우치", "beltpouch")) {
            return false;
        }
        return containsAny(productTypeText, "벨트", "belt");
    }

    private static boolean hasUnsupportedAccessoryCategory(List<String> categoryNames) {
        if (categoryNames == null || categoryNames.isEmpty()) {
            return false;
        }
        // 벨트백은 가방이므로 "벨트" 액세서리 판정에서 제외한다.
        String categoryText = normalizeProductTypeText(String.join(" ", categoryNames))
                .replace("벨트백", "").replace("beltbag", "");
        if (containsAny(categoryText, "동전지갑", "클러치", "clutch", "coinwallet", "coinpurse")) {
            return false;
        }
        return containsAny(categoryText, "지갑", "벨트", "키홀더", "키링", "참장식", "카드홀더", "파우치");
    }

    private static SizeGuideTemplate classifySupportedBagTemplate(String text) {
        String productTypeText = normalizeProductTypeText(text);
        if (productTypeText.isBlank()) {
            return SizeGuideTemplate.UNSUPPORTED;
        }
        SizeGuideTemplate primaryTemplate = classifyPrimaryBagTemplate(productTypeText);
        if (primaryTemplate.supported()) {
            return primaryTemplate;
        }
        if (isMaleCrossMessengerBag(productTypeText)) {
            return SizeGuideTemplate.MALE_CROSS_MESSENGER;
        }
        SizeGuideTemplate walletTemplate = classifyWalletTemplate(productTypeText);
        if (walletTemplate.supported()) {
            return walletTemplate;
        }
        if (containsAny(productTypeText, "크로스백", "크로스", "메신저백", "crossbag", "crossbody", "messenger")) {
            return SizeGuideTemplate.SHOULDER_CROSS;
        }
        if (containsAny(productTypeText, "서류가방", "브리프케이스", "briefcase", "숄더백", "숄더", "shoulder")) {
            return SizeGuideTemplate.SHOULDER_CROSS;
        }
        if (containsAny(productTypeText, "핸드백", "가방", "bag", "bags", "handbag")) {
            return SizeGuideTemplate.SHOULDER_CROSS;
        }
        return SizeGuideTemplate.UNSUPPORTED;
    }

    private static SizeGuideTemplate classifyPrimaryBagTemplate(String text) {
        String productTypeText = normalizeProductTypeText(text);
        if (containsAny(productTypeText, "백팩", "배낭", "backpack", "rucksack")) {
            return SizeGuideTemplate.BACKPACK;
        }
        if (containsAny(productTypeText, "슬링백", "slingbag", "slingback", "sling")) {
            return SizeGuideTemplate.SLINGBAG;
        }
        if (containsAny(productTypeText, "클러치백", "클러치", "동전지갑", "clutch", "coinwallet", "coinpurse")) {
            return SizeGuideTemplate.CLUTCH;
        }
        if (containsAny(productTypeText, "토트백", "사첼백", "보스턴백", "쇼퍼백",
                "tote", "satchel", "bostonbag", "shopperbag", "shopper")) {
            return SizeGuideTemplate.TOTE;
        }
        if (containsAny(productTypeText, "호보백", "버킷백", "나노백", "마이크로백", "미니백", "핸드백", "탑핸들백",
                "hobobag", "bucketbag", "nanobag", "microbag", "minibag", "handbag", "tophandlebag")) {
            return SizeGuideTemplate.SHOULDER_CROSS;
        }
        return SizeGuideTemplate.UNSUPPORTED;
    }

    private static SizeGuideTemplate classifyWalletTemplate(String text) {
        String productTypeText = normalizeProductTypeText(text);
        if (productTypeText.isBlank()) {
            return SizeGuideTemplate.UNSUPPORTED;
        }
        if (containsAny(productTypeText,
                "카드지갑", "카드홀더", "명함지갑", "카드명함지갑", "카드수납", "카드수납공간",
                "투명창수납", "멀티케이스", "cardwallet", "cardholder", "businesscardwallet", "multicase")) {
            return SizeGuideTemplate.CARD_WALLET;
        }
        if (containsAny(productTypeText, "반지갑", "미니지갑", "halfwallet", "miniwallet", "bifoldwallet", "bifold")) {
            return SizeGuideTemplate.WALLET_OPEN_HALF;
        }
        if (containsAny(productTypeText, "중지갑", "장지갑", "멀티지갑", "mediumwallet", "longwallet", "longpurse",
                "multiwallet")) {
            if (hasVerticalWalletOpeningHint(productTypeText)) {
                return SizeGuideTemplate.WALLET_OPEN_MEDIUM_LONG_VERTICAL;
            }
            return SizeGuideTemplate.WALLET_OPEN_MEDIUM_LONG;
        }
        return SizeGuideTemplate.UNSUPPORTED;
    }

    private static boolean hasVerticalWalletOpeningHint(String productTypeText) {
        return containsAny(productTypeText,
                "세로여닫", "세로개폐", "세로오픈", "세로형오픈",
                "verticalopening", "verticalopen", "verticallyopen", "portraitwallet");
    }

    private static boolean isMaleCrossMessengerBag(String text) {
        String productTypeText = normalizeProductTypeText(text);
        if (!containsAny(productTypeText, "남성가방", "남성", "남자", "남성용")) {
            return false;
        }
        return containsAny(productTypeText, "크로스백", "메신저백", "crossbag", "crossbody", "messenger");
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeProductTypeText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .toLowerCase()
                .replaceAll("[\\s_\\-/()\\[\\].,]", "");
    }

    public static Optional<String> extractProductNameSizeLabel(String productName) {
        if (productName == null || productName.isBlank()) {
            return Optional.empty();
        }

        String normalizedProductName = Normalizer.normalize(productName, Normalizer.Form.NFC);
        Optional<String> textSizeLabel = findTextSizeLabel(normalizedProductName);
        if (textSizeLabel.isPresent()) {
            return textSizeLabel;
        }

        String searchable = BRACKET_TEXT_PATTERN.matcher(normalizedProductName).replaceAll(" ");
        textSizeLabel = findTextSizeLabel(searchable);
        if (textSizeLabel.isPresent()) {
            return textSizeLabel;
        }

        Matcher matcher = ALPHA_SIZE_TOKEN_PATTERN.matcher(searchable);
        String lastToken = "";
        while (matcher.find()) {
            lastToken = matcher.group(1).toUpperCase();
        }
        return labelForSizeToken(lastToken);
    }

    private static Optional<String> findTextSizeLabel(String searchable) {
        for (SizeLabelRule rule : TEXT_SIZE_LABEL_RULES) {
            if (rule.pattern().matcher(searchable).find()) {
                return Optional.of(rule.label());
            }
        }
        return Optional.empty();
    }

    public static Optional<String> labelForSizeToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String token = Normalizer.normalize(rawToken, Normalizer.Form.NFC)
                .trim()
                .toUpperCase()
                .replaceAll("\\s+", " ");
        if ("XXX".equals(token)) {
            return Optional.empty();
        }
        if (FREE_SIZE_ALIASES.contains(token)) {
            return Optional.of(FREE_SIZE_LABEL);
        }
        return Optional.ofNullable(SIZE_LABELS.get(token));
    }

    public static boolean isPlaceholderSizeLabel(String sizeLabel) {
        return sizeLabel != null && FREE_SIZE_LABEL.equals(normalizeSizeLabel(sizeLabel));
    }

    /** 과거 표기와 외부 옵션값을 포함한 모든 원사이즈 표기를 화면용 FREE 로 통일한다. */
    public static String normalizeSizeLabel(String rawSizeLabel) {
        if (rawSizeLabel == null || rawSizeLabel.isBlank()) {
            return FREE_SIZE_LABEL;
        }
        String normalized = Normalizer.normalize(rawSizeLabel, Normalizer.Form.NFC)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return FREE_SIZE_ALIASES.contains(normalized) ? FREE_SIZE_LABEL : rawSizeLabel.trim();
    }

    public static String inferGender(
            String productName,
            String productCode,
            String basicProductText,
            Map<String, String> notificationFields) {
        Optional<String> notificationGender = inferGenderFromNotificationFields(notificationFields);
        if (notificationGender.isPresent()) {
            return notificationGender.get();
        }

        Optional<String> textGender = inferGenderFromText(productName + " " + basicProductText);
        if (textGender.isPresent()) {
            return textGender.get();
        }

        return inferGenderFromProductCode(productCode).orElse("");
    }

    private static Optional<String> inferGenderFromNotificationFields(Map<String, String> notificationFields) {
        if (notificationFields == null || notificationFields.isEmpty()) {
            return Optional.empty();
        }

        List<String> preferredValues = notificationFields.entrySet().stream()
                .filter(entry -> {
                    String label = normalizeGenderText(entry.getKey());
                    return label.contains("종류") || label.contains("성별") || label.contains("대상");
                })
                .map(Map.Entry::getValue)
                .toList();
        Optional<String> preferredGender = inferGenderFromText(String.join(" ", preferredValues));
        if (preferredGender.isPresent()) {
            return preferredGender;
        }

        return inferGenderFromText(notificationFields.values().stream().reduce("", (left, right) -> left + " " + right));
    }

    private static Optional<String> inferGenderFromText(String text) {
        String searchable = normalizeGenderText(text);
        if (searchable.isBlank() || searchable.contains("남녀공용") || searchable.contains("공용")
                || searchable.contains("unisex")) {
            return Optional.empty();
        }
        if (searchable.contains("여성") || searchable.contains("여자") || searchable.contains("우먼")
                || searchable.contains("woman") || searchable.contains("women") || searchable.contains("female")
                || searchable.contains("lady")) {
            return Optional.of("여성");
        }
        if (searchable.contains("남성") || searchable.contains("남자") || searchable.contains("맨")
                || searchable.contains(" man ") || searchable.contains(" men ") || searchable.contains("male")) {
            return Optional.of("남성");
        }
        return Optional.empty();
    }

    private static Optional<String> inferGenderFromProductCode(String productCode) {
        if (productCode != null && !productCode.isBlank()) {
            char first = Character.toUpperCase(productCode.charAt(0));
            if (first == 'M') {
                return Optional.of("남성");
            }
            if (first == 'W' || first == 'F') {
                return Optional.of("여성");
            }
        }
        return Optional.empty();
    }

    private static String normalizeGenderText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record SizeLabelRule(Pattern pattern, String label) {
    }

    public record MeasurementLayout(
            float heightXRatio,
            float heightYRatio,
            float depthXRatio,
            float depthYRatio,
            float widthXRatio,
            float widthYRatio) {
    }

    public enum SizeGuideTemplate {
        TOTE("bag-tote", "토트백", 800, 60,
                new MeasurementLayout(0.171f, 0.533f, 0.296f, 0.954f, 0.589f, 0.944f)),
        SHOULDER_CROSS("bag-shoulder", "숄더/크로스백", 700, 60,
                new MeasurementLayout(0.169f, 0.595f, 0.318f, 0.955f, 0.596f, 0.944f)),
        MALE_CROSS_MESSENGER("bag_cross", "남성 크로스/메신저백", 600, 20,
                new MeasurementLayout(0.245f, 0.729f, 0.67f, 0.932f, 0.456f, 0.986f)),
        BACKPACK("backpack", "백팩", 700, 50,
                new MeasurementLayout(0.844f, 0.44f, 0.375f, 0.973f, 0.666f, 0.905f)),
        SLINGBAG("slingbag", "슬링백", 700, 30,
                new MeasurementLayout(0.187f, 0.581f, 0.717f, 0.907f, 0.408f, 0.962f)),
        CLUTCH("clutchbag", "클러치백/동전지갑", 760, 130,
                new MeasurementLayout(0.178f, 0.488f, 0.784f, 0.838f, 0.475f, 0.917f)),
        CARD_WALLET("cardwallet", "카드지갑", 600, -35,
                new MeasurementLayout(0.928f, 0.491f, 0.74f, 0.92f, 0.437f, 0.769f)),
        WALLET_OPEN_HALF("wallet-open-half", "가로 여닫는 2단 반지갑", 420, 135,
                new MeasurementLayout(1.057f, 0.435f, 0.915f, 0.933f, 0.306f, 0.997f)),
        WALLET_OPEN_MEDIUM_LONG("wallet-open-medium-long", "가로 여닫는 2단 중/장지갑", 250, 90,
                new MeasurementLayout(1.092f, 0.478f, 0.971f, 0.95f, 0.271f, 0.981f)),
        WALLET_OPEN_MEDIUM_LONG_VERTICAL("wallet-open-medium-long-vertical", "세로 여닫는 2단 중/장지갑", 550, 180,
                new MeasurementLayout(1.064f, 0.413f, 0.971f, 0.916f, 0.401f, 0.99f)),
        // 공용 벨트 표본: 사진 없이도 스트랩·버클 실루엣을 그려 쓰는 2차원(총길이×너비) 템플릿
        BELT("belt", "벨트", 1000, 60,
                new MeasurementLayout(0.08f, 0.5f, 0.5f, 0.5f, 0.5f, 0.72f)),
        UNSUPPORTED("", "미지원 카테고리", 700, 56,
                new MeasurementLayout(0.14f, 0.64f, 0.31f, 0.95f, 0.58f, 0.82f));

        private final String assetBaseName;
        private final String displayName;
        private final int templateWidth;
        private final int templateY;
        private final MeasurementLayout measurementLayout;

        SizeGuideTemplate(String assetBaseName, String displayName, int templateWidth, int templateY,
                MeasurementLayout measurementLayout) {
            this.assetBaseName = assetBaseName;
            this.displayName = displayName;
            this.templateWidth = templateWidth;
            this.templateY = templateY;
            this.measurementLayout = measurementLayout;
        }

        public boolean supported() {
            return this != UNSUPPORTED;
        }

        public String assetBaseName() {
            return assetBaseName;
        }

        public String displayName() {
            return displayName;
        }

        public int templateWidth() {
            return templateWidth;
        }

        public int templateY() {
            return templateY;
        }

        public MeasurementLayout measurementLayout() {
            return measurementLayout;
        }

        /** 표·입력란에 쓰는 첫 번째 치수 이름. 벨트는 가로 대신 총길이. */
        public String widthLabel() {
            return this == BELT ? "총길이" : "가로";
        }

        public String depthLabel() {
            return "폭";
        }

        /** 세 번째 치수 이름. 벨트는 높이 대신 너비(스트랩 폭). */
        public String heightLabel() {
            return this == BELT ? "너비" : "높이";
        }

        /** 폭 치수를 아예 쓰지 않는 템플릿(항상 2차원). */
        public boolean alwaysTwoDimensional() {
            return this == BELT || this == CARD_WALLET;
        }

        public String key() {
            return name();
        }

        public static Optional<SizeGuideTemplate> fromKey(String key) {
            String normalized = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return Optional.empty();
            }
            for (SizeGuideTemplate template : values()) {
                if (template.name().equals(normalized)) {
                    return Optional.of(template);
                }
            }
            return Optional.empty();
        }

        public static List<SizeGuideTemplate> supportedTemplates() {
            return Arrays.stream(values())
                    .filter(SizeGuideTemplate::supported)
                    .toList();
        }
    }
}
