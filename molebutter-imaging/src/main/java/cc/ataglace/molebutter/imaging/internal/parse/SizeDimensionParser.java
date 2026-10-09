package cc.ataglace.molebutter.imaging.internal.parse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.ataglace.molebutter.imaging.api.ProductLookupDto;
import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;

/**
 * 고시/사이즈차트 텍스트에서 가로·폭·높이 치수를 뽑는 정규식 계층.
 * 직접 라벨 매칭 → 라벨드 패턴 → 순서 괄호/값별 괄호 삼중·이중 → 순수 AxBxC 폴백 순.
 * (at-a-glance 의 LQT 전용 분기는 제외 — 수동 입력으로 대체)
 */
public final class SizeDimensionParser {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern SIZE_TRIPLE_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|CM|mm|MM)?\\s*[xX*×]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|CM|mm|MM)?\\s*[xX*×]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern ORDERED_SIZE_TRIPLE_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|mm)?\\s*[x*×]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|mm)?\\s*[x*×]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|mm)?(?:\\(([^)]*)\\))?");
    private static final Pattern LABELED_SIZE_TRIPLE_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)(?:cm|mm)?\\(([^)]*)\\)\\s*[x*×]\\s*([0-9]+(?:\\.[0-9]+)?)(?:cm|mm)?\\(([^)]*)\\)\\s*[x*×]\\s*([0-9]+(?:\\.[0-9]+)?)(?:cm|mm)?\\(([^)]*)\\)");
    private static final Pattern ORDERED_SIZE_PAIR_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|mm)?\\s*[x*×]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|mm)?(?:\\(([^)]*)\\))?");
    private static final Pattern LABELED_SIZE_PAIR_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)(?:cm|mm)?\\(([^)]*)\\)\\s*[x*×]\\s*([0-9]+(?:\\.[0-9]+)?)(?:cm|mm)?\\(([^)]*)\\)");
    private static final List<String> SIZE_DIMENSION_NOTICE_LABELS = List.of(
            "크기",
            "치수",
            "사이즈",
            "제품 주요 사양",
            "제품 주요 사항",
            "제품 크기",
            "제품 사이즈",
            "주요 사양",
            "주요 사항",
            "실측");

    private SizeDimensionParser() {
    }

    public static SizeDimensionsDto resolve(ProductLookupDto product) {
        return resolve(product.sizeDescription(), product.notificationFields(), product.sizeMeasurements());
    }

    public static SizeDimensionsDto resolve(
            String sizeDescription,
            Map<String, String> notificationFields,
            Map<String, String> sizeMeasurements) {
        Map<String, String> fields = buildSizeFields(sizeDescription, notificationFields, sizeMeasurements);
        Optional<String> width = findDirectDimension(fields, "가로", "너비", "밑면가로", "width");
        Optional<String> depth = findDirectDimension(fields, "폭", "깊이", "밑면폭", "depth");
        Optional<String> height = findDirectDimension(fields, "높이", "세로", "height");

        List<String> sizeTexts = new ArrayList<>();
        if (sizeDescription != null && !sizeDescription.isBlank()) {
            sizeTexts.add(sizeDescription);
        }
        fields.forEach((label, value) -> sizeTexts.add(label + " " + value));

        if (width.isEmpty()) {
            width = findLabeledDimension(sizeTexts, "가로", "너비", "width");
        }
        if (depth.isEmpty()) {
            depth = findLabeledDimension(sizeTexts, "폭", "깊이", "depth");
        }
        if (height.isEmpty()) {
            height = findLabeledDimension(sizeTexts, "높이", "세로", "height");
        }

        Optional<SizeDimensionsDto> triple = parseSizeTriple(sizeTexts);
        if (triple.isPresent()) {
            SizeDimensionsDto fallback = triple.get();
            width = width.or(() -> Optional.of(fallback.width()));
            depth = depth.or(() -> Optional.of(fallback.depth()));
            height = height.or(() -> Optional.of(fallback.height()));
        }

        return new SizeDimensionsDto(
                width.orElse(SizeDimensionsDto.MISSING),
                depth.orElse(SizeDimensionsDto.MISSING),
                height.orElse(SizeDimensionsDto.MISSING));
    }

    /**
     * 벨트 전용 해석: width=총길이, height=너비(스트랩 폭), depth 없음.
     * LF몰 벨트 고시 예: "2 X 108(폭X총길이) 버클기장 4 cm", "2.4 X 109(가로X세로) 버클사이즈 3.5 X 3.6".
     * 라벨(총길이/길이/세로 ↔ 폭/너비/가로)로 짝을 정하고, 라벨이 없으면 큰 값을 총길이로 본다.
     */
    public static SizeDimensionsDto resolveBelt(
            String sizeDescription,
            Map<String, String> notificationFields,
            Map<String, String> sizeMeasurements) {
        Map<String, String> fields = buildSizeFields(sizeDescription, notificationFields, sizeMeasurements);
        List<String> sizeTexts = new ArrayList<>();
        if (sizeDescription != null && !sizeDescription.isBlank()) {
            sizeTexts.add(sizeDescription);
        }
        fields.forEach((label, value) -> sizeTexts.add(label + " " + value));

        Optional<String> length = findDirectDimension(fields, "총길이", "길이", "length");
        Optional<String> strapWidth = findDirectDimension(fields, "너비", "폭", "width");
        for (String text : sizeTexts) {
            if (length.isPresent() && strapWidth.isPresent()) {
                break;
            }
            String normalizedText = normalizeSizeText(text);
            Optional<BeltPair> pair = parseBeltPair(normalizedText);
            if (pair.isPresent()) {
                length = length.or(() -> Optional.of(pair.get().length()));
                strapWidth = strapWidth.or(() -> Optional.of(pair.get().strapWidth()));
            }
        }
        return new SizeDimensionsDto(
                length.orElse(SizeDimensionsDto.MISSING),
                SizeDimensionsDto.MISSING,
                strapWidth.orElse(SizeDimensionsDto.MISSING));
    }

    private static Optional<BeltPair> parseBeltPair(String normalizedText) {
        Matcher labeled = LABELED_SIZE_PAIR_PATTERN.matcher(normalizedText);
        if (labeled.find()) {
            return beltPairFromLabels(labeled.group(1), labeled.group(2), labeled.group(3), labeled.group(4));
        }
        Matcher ordered = ORDERED_SIZE_PAIR_PATTERN.matcher(normalizedText);
        if (!ordered.find()) {
            return Optional.empty();
        }
        String first = formatDimensionNumber(ordered.group(1));
        String second = formatDimensionNumber(ordered.group(2));
        String orderText = ordered.group(3);
        if (orderText != null && !orderText.isBlank()) {
            String[] labels = normalizeSizeText(orderText).split("[x*×]");
            if (labels.length == 2) {
                Optional<BeltPair> fromLabels = beltPairFromLabels(first, labels[0], second, labels[1]);
                if (fromLabels.isPresent()) {
                    return fromLabels;
                }
            }
        }
        // 라벨이 없거나 해석 불가: 벨트는 총길이가 항상 너비보다 크다.
        return Optional.of(Double.parseDouble(first) >= Double.parseDouble(second)
                ? new BeltPair(first, second)
                : new BeltPair(second, first));
    }

    private static Optional<BeltPair> beltPairFromLabels(String firstValue, String firstLabel,
            String secondValue, String secondLabel) {
        boolean firstIsLength = isBeltLengthLabel(firstLabel);
        boolean secondIsLength = isBeltLengthLabel(secondLabel);
        if (firstIsLength == secondIsLength) {
            return Optional.empty();
        }
        return Optional.of(firstIsLength
                ? new BeltPair(formatDimensionNumber(firstValue), formatDimensionNumber(secondValue))
                : new BeltPair(formatDimensionNumber(secondValue), formatDimensionNumber(firstValue)));
    }

    private static boolean isBeltLengthLabel(String label) {
        String normalized = normalizeSizeText(label);
        return normalized.contains("총길이") || normalized.contains("길이") || normalized.contains("세로")
                || normalized.contains("length") || normalized.contains("height");
    }

    private record BeltPair(String length, String strapWidth) {
    }

    /** 수동 입력 치수(manual)가 있으면 그 값을, 없으면 파싱값(parsed)을 쓴다. */
    public static SizeDimensionsDto merge(SizeDimensionsDto parsed, SizeDimensionsDto manual) {
        SizeDimensionsDto base = parsed == null ? SizeDimensionsDto.empty() : parsed;
        if (manual == null) {
            return base;
        }
        return new SizeDimensionsDto(
                mergeValue(base.width(), manual.width()),
                mergeValue(base.depth(), manual.depth()),
                mergeValue(base.height(), manual.height()));
    }

    private static String mergeValue(String parsed, String manual) {
        if (!SizeDimensionsDto.hasValue(manual)) {
            return parsed;
        }
        String normalized = manual.trim();
        if (normalized.startsWith(".")) normalized = "0" + normalized;
        return normalizeDimensionValue(normalized).orElse(parsed);
    }

    /** 고시 크기 계열 + 사이즈차트 실측값을 치수 원천 필드 Map 으로 합친다. */
    public static Map<String, String> buildSizeFields(
            String sizeDescription,
            Map<String, String> notificationFields,
            Map<String, String> sizeMeasurements) {
        Map<String, String> sizeFields = new LinkedHashMap<>();
        if (sizeDescription != null && !sizeDescription.isBlank()) {
            sizeFields.put("고시 크기", sizeDescription);
        }
        if (notificationFields != null) {
            notificationFields.forEach((label, value) -> {
                if (label != null
                        && !label.isBlank()
                        && isSizeDimensionSourceLabel(label)
                        && value != null
                        && !value.isBlank()) {
                    sizeFields.putIfAbsent("고시 " + label, value);
                }
            });
        }
        if (sizeMeasurements != null) {
            sizeFields.putAll(sizeMeasurements);
        }
        return sizeFields;
    }

    public static Optional<String> firstSizeDimensionField(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return fields.entrySet().stream()
                .filter(entry -> isSizeDimensionSourceLabel(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.trim().isBlank())
                .findFirst();
    }

    public static boolean isSizeDimensionSourceLabel(String label) {
        String normalized = normalizeSizeText(label).replaceAll("[^0-9a-z가-힣]", "");
        if (SIZE_DIMENSION_NOTICE_LABELS.stream()
                .map(SizeDimensionParser::normalizeSizeText)
                .map(value -> value.replaceAll("[^0-9a-z가-힣]", ""))
                .anyMatch(normalized::contains)) {
            return true;
        }
        return normalized.contains("가로")
                || normalized.contains("너비")
                || normalized.contains("폭")
                || normalized.contains("깊이")
                || normalized.contains("높이")
                || normalized.contains("세로")
                || normalized.contains("width")
                || normalized.contains("depth")
                || normalized.contains("height");
    }

    public static boolean hasRequiredDimensions(SizeGuideTemplate template, SizeDimensionsDto dimensions) {
        if (dimensions == null) {
            return false;
        }
        if (template.alwaysTwoDimensional()
                || (isDepthOptionalWalletTemplate(template) && !dimensions.hasDepth())) {
            return dimensions.hasWidth() && dimensions.hasHeight();
        }
        return dimensions.hasWidth() && dimensions.hasDepth() && dimensions.hasHeight();
    }

    public static boolean isTwoDimensionSizeGuide(SizeGuideTemplate template, SizeDimensionsDto dimensions) {
        if (template.alwaysTwoDimensional()) {
            return true;
        }
        return isDepthOptionalWalletTemplate(template) && (dimensions == null || !dimensions.hasDepth());
    }

    public static boolean isDepthOptionalWalletTemplate(SizeGuideTemplate template) {
        return template == SizeGuideTemplate.WALLET_OPEN_HALF
                || template == SizeGuideTemplate.WALLET_OPEN_MEDIUM_LONG
                || template == SizeGuideTemplate.WALLET_OPEN_MEDIUM_LONG_VERTICAL;
    }

    public static String heightLabel(boolean twoDimensionSizeGuide) {
        return twoDimensionSizeGuide ? "세로" : "높이";
    }

    private static Optional<String> findDirectDimension(Map<String, String> fields, String... aliases) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String label = normalizeSizeText(entry.getKey());
            String value = entry.getValue();
            if (value == null || value.isBlank() || countNumbers(value) != 1) {
                continue;
            }
            for (String alias : aliases) {
                if (label.contains(normalizeSizeText(alias))) {
                    return normalizeDimensionValue(value);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findLabeledDimension(List<String> texts, String... aliases) {
        for (String text : texts) {
            String normalizedText = normalizeSizeText(text);
            if (normalizedText.isBlank()) {
                continue;
            }
            for (String alias : aliases) {
                Pattern pattern = Pattern.compile(Pattern.quote(normalizeSizeText(alias))
                        + "\\s*[:：=\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:cm|mm)?");
                Matcher matcher = pattern.matcher(normalizedText);
                if (matcher.find()) {
                    return Optional.of(formatDimensionNumber(matcher.group(1)));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<SizeDimensionsDto> parseSizeTriple(List<String> texts) {
        for (String text : texts) {
            Optional<SizeDimensionsDto> orderedTriple = parseOrderedSizeTriple(text);
            if (orderedTriple.isPresent()) {
                return orderedTriple;
            }
        }

        for (String text : texts) {
            String normalizedText = normalizeSizeText(text);
            Matcher matcher = SIZE_TRIPLE_PATTERN.matcher(normalizedText);
            if (matcher.find()) {
                return Optional.of(new SizeDimensionsDto(
                        formatDimensionNumber(matcher.group(1)),
                        formatDimensionNumber(matcher.group(2)),
                        formatDimensionNumber(matcher.group(3))));
            }
        }

        for (String text : texts) {
            Optional<SizeDimensionsDto> orderedPair = parseOrderedSizePair(text);
            if (orderedPair.isPresent()) {
                return orderedPair;
            }
        }

        return Optional.empty();
    }

    private static Optional<SizeDimensionsDto> parseOrderedSizeTriple(String text) {
        String normalizedText = normalizeSizeText(text);
        Optional<SizeDimensionsDto> inlineLabeledTriple = parseInlineLabeledSizeTriple(normalizedText);
        if (inlineLabeledTriple.isPresent()) {
            return inlineLabeledTriple;
        }

        Matcher matcher = ORDERED_SIZE_TRIPLE_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String orderText = matcher.group(4);
            if (orderText == null || orderText.isBlank()) {
                continue;
            }

            List<DimensionType> order = parseDimensionOrder(orderText, 3);
            if (order.size() != 3) {
                continue;
            }

            Map<DimensionType, String> values = new LinkedHashMap<>();
            values.put(order.get(0), formatDimensionNumber(matcher.group(1)));
            values.put(order.get(1), formatDimensionNumber(matcher.group(2)));
            values.put(order.get(2), formatDimensionNumber(matcher.group(3)));

            Optional<SizeDimensionsDto> dimensions = dimensionsFromValues(values, true);
            if (dimensions.isPresent()) {
                return dimensions;
            }
        }
        return Optional.empty();
    }

    private static Optional<SizeDimensionsDto> parseInlineLabeledSizeTriple(String normalizedText) {
        Matcher matcher = LABELED_SIZE_TRIPLE_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            Map<DimensionType, String> values = new LinkedHashMap<>();
            if (!putLabeledDimension(values, matcher.group(2), matcher.group(1))
                    || !putLabeledDimension(values, matcher.group(4), matcher.group(3))
                    || !putLabeledDimension(values, matcher.group(6), matcher.group(5))) {
                continue;
            }
            Optional<SizeDimensionsDto> dimensions = dimensionsFromValues(values, true);
            if (dimensions.isPresent()) {
                return dimensions;
            }
        }
        return Optional.empty();
    }

    private static Optional<SizeDimensionsDto> parseOrderedSizePair(String text) {
        String normalizedText = normalizeSizeText(text);
        Optional<SizeDimensionsDto> inlineLabeledPair = parseInlineLabeledSizePair(normalizedText);
        if (inlineLabeledPair.isPresent()) {
            return inlineLabeledPair;
        }

        Matcher matcher = ORDERED_SIZE_PAIR_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String orderText = matcher.group(3);
            if (orderText == null || orderText.isBlank()) {
                continue;
            }

            List<DimensionType> order = parseDimensionOrder(orderText, 2);
            if (order.size() != 2) {
                continue;
            }

            Map<DimensionType, String> values = new LinkedHashMap<>();
            values.put(order.get(0), formatDimensionNumber(matcher.group(1)));
            values.put(order.get(1), formatDimensionNumber(matcher.group(2)));
            Optional<SizeDimensionsDto> dimensions = dimensionsFromValues(values, false);
            if (dimensions.isPresent()) {
                return dimensions;
            }
        }
        return Optional.empty();
    }

    private static Optional<SizeDimensionsDto> parseInlineLabeledSizePair(String normalizedText) {
        Matcher matcher = LABELED_SIZE_PAIR_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            Map<DimensionType, String> values = new LinkedHashMap<>();
            if (!putLabeledDimension(values, matcher.group(2), matcher.group(1))
                    || !putLabeledDimension(values, matcher.group(4), matcher.group(3))) {
                continue;
            }
            Optional<SizeDimensionsDto> dimensions = dimensionsFromValues(values, false);
            if (dimensions.isPresent()) {
                return dimensions;
            }
        }
        return Optional.empty();
    }

    private static boolean putLabeledDimension(Map<DimensionType, String> values, String label, String value) {
        Optional<DimensionType> dimensionType = dimensionTypeFromLabel(label);
        if (dimensionType.isEmpty() || values.containsKey(dimensionType.get())) {
            return false;
        }
        values.put(dimensionType.get(), formatDimensionNumber(value));
        return true;
    }

    private static Optional<SizeDimensionsDto> dimensionsFromValues(
            Map<DimensionType, String> values, boolean requireDepth) {
        if (!values.containsKey(DimensionType.WIDTH) || !values.containsKey(DimensionType.HEIGHT)) {
            return Optional.empty();
        }
        if (requireDepth && !values.containsKey(DimensionType.DEPTH)) {
            return Optional.empty();
        }
        return Optional.of(new SizeDimensionsDto(
                values.get(DimensionType.WIDTH),
                values.getOrDefault(DimensionType.DEPTH, SizeDimensionsDto.MISSING),
                values.get(DimensionType.HEIGHT)));
    }

    private static List<DimensionType> parseDimensionOrder(String orderText, int expectedSize) {
        String normalizedOrderText = normalizeSizeText(orderText);
        String[] labels = normalizedOrderText.split("[x*×]");
        if (labels.length != expectedSize) {
            return List.of();
        }

        List<DimensionType> order = new ArrayList<>();
        for (String label : labels) {
            Optional<DimensionType> dimensionType = dimensionTypeFromLabel(label);
            if (dimensionType.isEmpty() || order.contains(dimensionType.get())) {
                return List.of();
            }
            order.add(dimensionType.get());
        }
        return order;
    }

    private static Optional<DimensionType> dimensionTypeFromLabel(String label) {
        String normalizedLabel = normalizeSizeText(label);
        if (normalizedLabel.contains("가로") || normalizedLabel.contains("너비") || normalizedLabel.contains("width")) {
            return Optional.of(DimensionType.WIDTH);
        }
        if (normalizedLabel.contains("폭") || normalizedLabel.contains("깊이") || normalizedLabel.contains("depth")) {
            return Optional.of(DimensionType.DEPTH);
        }
        if (normalizedLabel.contains("세로") || normalizedLabel.contains("높이") || normalizedLabel.contains("height")) {
            return Optional.of(DimensionType.HEIGHT);
        }
        return Optional.empty();
    }

    private static Optional<String> normalizeDimensionValue(String value) {
        Matcher matcher = NUMBER_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(formatDimensionNumber(matcher.group(1)));
    }

    private static int countNumbers(String value) {
        int count = 0;
        Matcher matcher = NUMBER_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String normalizeSizeText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .toLowerCase()
                .replaceAll("\\s+", "")
                .replace('×', 'x');
    }

    private static String formatDimensionNumber(String value) {
        if (value == null || value.isBlank()) {
            return SizeDimensionsDto.MISSING;
        }
        String formatted = value.trim();
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }

    private enum DimensionType {
        WIDTH, DEPTH, HEIGHT
    }
}
