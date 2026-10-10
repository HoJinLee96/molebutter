package cc.ataglace.molebutter.imaging.internal.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.NoticeImageCardDto;
import cc.ataglace.molebutter.imaging.api.NoticeImageLayoutDto;
import lombok.RequiredArgsConstructor;

/**
 * 상품 정보 고시 이미지(폭 1440) 렌더러.
 * 항목명이 위, 내용이 아래에 놓이는 카드형 고시. 짧은 항목은 두 카드씩,
 * 긴 항목은 전체 너비로 배치한다. 종류는 제조사 오른쪽에 배치하고 나머지 항목 순서는 유지한다.
 * 상품코드와 "A/S 책임자" 항목은 이미지에서 제외한다.
 */
@Component
@RequiredArgsConstructor
public class NoticeImageRenderer {

    private static final String AS_RESPONSIBILITY_NOTICE_KEY = "as책임자";
    private static final String PRODUCT_CODE_NOTICE_KEY = "상품코드";
    private static final Color INK = new Color(34, 34, 34);
    private static final Color LABEL_INK = new Color(90, 90, 90);
    private static final Color CARD_BACKGROUND = new Color(246, 246, 246);
    private static final int CONTENT_X = 96;
    private static final int RIGHT_MARGIN = 96;
    private static final int CONTENT_WIDTH = RenderSupport.NOTICE_IMAGE_WIDTH - CONTENT_X - RIGHT_MARGIN;
    private static final int CONTENT_TOP = 72;
    private static final int CARD_GAP = 18;
    private static final int CARD_PADDING = 24;
    private static final int LABEL_VALUE_GAP = 12;
    private static final int MIN_CARD_HEIGHT = 144;
    private static final int BOTTOM_MARGIN = 64;
    private static final int LINE_GAP = 6;
    // 작업 캔버스가 아닌 최종 780px PNG에서 라벨·내용을 각각 2px 줄인다.
    private static final float FONT_REDUCTION = (float) (2.0 / RenderSupport.OUTPUT_SCALE);
    private static final int LABEL_FONT_SIZE = 28;
    private static final int VALUE_FONT_SIZE = 32;
    private static final int MAX_FIELDS = 100;
    private static final int MAX_LABEL_LENGTH = 512;
    private static final int MAX_VALUE_LENGTH = 8192;
    private static final int MAX_TOTAL_TEXT_LENGTH = 65_536;

    private final FontResolver fontResolver;

    public byte[] renderPng(Map<String, String> fields) {
        return RenderSupport.encodePng(render(fields, RenderSupport.OUTPUT_SCALE));
    }

    /** 렌더와 같은 실측 배치로 편집기 카드를 반환한다. 원문 라벨·값의 공백과 줄바꿈은 유지한다. */
    public NoticeImageLayoutDto layout(Map<String, String> fields) {
        return new NoticeImageLayoutDto(layoutCards(fields).stream()
                .map(card -> new NoticeImageCardDto(card.label(), card.value(), card.width() == CONTENT_WIDTH))
                .toList());
    }

    /** 1440 폭 작업 캔버스(테스트·레이아웃 기준). */
    public BufferedImage render(Map<String, String> fields) {
        return render(fields, 1.0);
    }

    /** scale 배율로 직접 그린다. 좌표·폰트는 1440 기준 그대로이고 출력 해상도에서 글자를 래스터화한다. */
    public BufferedImage render(Map<String, String> fields, double scale) {
        RenderSupport.validateScale(scale);
        int width = RenderSupport.NOTICE_IMAGE_WIDTH;
        Font labelFont = noticeFont(Font.PLAIN, LABEL_FONT_SIZE);
        Font valueFont = noticeFont(Font.PLAIN, VALUE_FONT_SIZE);
        List<NoticeCard> cards = layoutCards(fields);
        int contentBottom = cards.stream().mapToInt(card -> card.y() + card.height()).max().orElse(CONTENT_TOP);
        int height = Math.max(420, contentBottom + BOTTOM_MARGIN);

        BufferedImage image = RenderSupport.newScaledCanvas(width, height, scale);
        Graphics2D graphics = RenderSupport.createScaledGraphics(image, scale);

        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        FontMetrics labelMetrics = graphics.getFontMetrics(labelFont);
        FontMetrics valueMetrics = graphics.getFontMetrics(valueFont);
        int labelLineHeight = labelMetrics.getHeight() + LINE_GAP;
        int valueLineHeight = valueMetrics.getHeight() + LINE_GAP;
        for (NoticeCard card : cards) {
            graphics.setColor(CARD_BACKGROUND);
            graphics.fillRect(card.x(), card.y(), card.width(), card.height());
            int textX = card.x() + CARD_PADDING;
            int labelTop = card.y() + CARD_PADDING;
            int valueTop = labelTop + textHeight(card.labelLines(), labelMetrics) + LABEL_VALUE_GAP;
            drawLines(graphics, card.labelLines(), textX, labelTop + labelMetrics.getAscent(),
                    labelFont, labelLineHeight, LABEL_INK);
            drawLines(graphics, card.valueLines(), textX, valueTop + valueMetrics.getAscent(),
                    valueFont, valueLineHeight, INK);
        }

        graphics.dispose();
        return image;
    }

    /** 왼쪽→오른쪽, 위→아래 순서. 긴 항목 앞의 홀수 카드도 넓혀 빈 반쪽을 남기지 않는다. */
    List<NoticeCard> layoutCards(Map<String, String> fields) {
        validateFields(fields);
        List<NoticeField> entries = new ArrayList<>(noticeImageFields(fields).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> new NoticeField(entry.getKey(), entry.getValue())).toList());
        pairManufacturerAndKind(entries);
        if (entries.isEmpty()) {
            entries = List.of(new NoticeField("정보", "표시할 정보가 없습니다."));
        }
        int fullWidth = CONTENT_WIDTH;
        int halfWidth = (fullWidth - CARD_GAP) / 2;
        Font labelFont = noticeFont(Font.PLAIN, LABEL_FONT_SIZE);
        Font valueFont = noticeFont(Font.PLAIN, VALUE_FONT_SIZE);
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        RenderSupport.applyQualityHints(graphics);
        try {
            List<NoticeCard> cards = new ArrayList<>();
            int y = CONTENT_TOP;
            for (int index = 0; index < entries.size();) {
                MeasuredCard left = measureCard(entries.get(index), halfWidth, graphics, labelFont, valueFont);
                MeasuredCard right = index + 1 < entries.size()
                        ? measureCard(entries.get(index + 1), halfWidth, graphics, labelFont, valueFont) : null;
                boolean manufacturerPair = right != null
                        && isManufacturerAndKind(entries.get(index), entries.get(index + 1));
                // 앞 항목이 짧아도 제조사·종류의 두 칸을 갈라 놓지 않는다.
                boolean nextManufacturerPair = index + 2 < entries.size()
                        && isManufacturerAndKind(entries.get(index + 1), entries.get(index + 2));
                if (!manufacturerPair && (left.needsFullWidth() || right == null
                        || right.needsFullWidth() || nextManufacturerPair)) {
                    MeasuredCard full = measureCard(entries.get(index), fullWidth, graphics, labelFont, valueFont);
                    cards.add(full.place(CONTENT_X, y, fullWidth, full.height()));
                    y += full.height() + CARD_GAP;
                    index++;
                } else {
                    int rowHeight = Math.max(left.height(), right.height());
                    cards.add(left.place(CONTENT_X, y, halfWidth, rowHeight));
                    cards.add(right.place(CONTENT_X + halfWidth + CARD_GAP, y, halfWidth, rowHeight));
                    y += rowHeight + CARD_GAP;
                    index += 2;
                }
                if (y - CARD_GAP + BOTTOM_MARGIN > RenderSupport.MAX_IMAGE_SIDE) {
                    throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT,
                            "상품정보고시 이미지 높이는 8192px 이하여야 합니다.");
                }
            }
            return List.copyOf(cards);
        } finally {
            graphics.dispose();
        }
    }

    private void pairManufacturerAndKind(List<NoticeField> entries) {
        NoticeField manufacturer = entries.stream()
                .filter(field -> isManufacturer(field.label())).findFirst().orElse(null);
        NoticeField kind = entries.stream()
                .filter(field -> normalizeLabel(field.label()).equals("종류")).findFirst().orElse(null);
        if (manufacturer != null && kind != null) {
            entries.remove(kind);
            entries.add(entries.indexOf(manufacturer) + 1, kind);
        }
    }

    private boolean isManufacturerAndKind(NoticeField left, NoticeField right) {
        return isManufacturer(left.label()) && normalizeLabel(right.label()).equals("종류");
    }

    private boolean isManufacturer(String label) {
        return normalizeLabel(label).startsWith("제조사");
    }

    private void validateFields(Map<String, String> fields) {
        if (fields == null) {
            return;
        }
        if (fields.size() > MAX_FIELDS) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "상품정보고시 항목은 100개 이하여야 합니다.");
        }
        long totalLength = 0;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            int labelLength = field.getKey() == null ? 0 : field.getKey().length();
            int valueLength = field.getValue() == null ? 0 : field.getValue().length();
            totalLength += (long) labelLength + valueLength;
            if (labelLength > MAX_LABEL_LENGTH || valueLength > MAX_VALUE_LENGTH
                    || totalLength > MAX_TOTAL_TEXT_LENGTH) {
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "상품정보고시 텍스트가 너무 깁니다.");
            }
        }
    }

    private MeasuredCard measureCard(NoticeField field, int width, Graphics2D graphics, Font labelFont, Font valueFont) {
        int textWidth = width - CARD_PADDING * 2;
        List<String> labelLines = RenderSupport.wrapText(graphics, field.label(), labelFont, textWidth);
        List<String> valueLines = RenderSupport.wrapText(graphics, field.value(), valueFont, textWidth);
        int height = Math.max(MIN_CARD_HEIGHT, CARD_PADDING * 2 + LABEL_VALUE_GAP
                + textHeight(labelLines, graphics.getFontMetrics(labelFont))
                + textHeight(valueLines, graphics.getFontMetrics(valueFont)));
        return new MeasuredCard(field, List.copyOf(labelLines), List.copyOf(valueLines), height);
    }

    private int textHeight(List<String> lines, FontMetrics metrics) {
        return metrics.getHeight() + (lines.size() - 1) * (metrics.getHeight() + LINE_GAP);
    }

    private void drawLines(Graphics2D graphics, List<String> lines, int x, int baseline,
            Font font, int lineHeight, Color color) {
        graphics.setFont(font);
        graphics.setColor(color);
        for (String line : lines) {
            graphics.drawString(line, x, baseline);
            baseline += lineHeight;
        }
    }

    private Font noticeFont(int style, int originalSize) {
        return fontResolver.noticeFont(style, originalSize).deriveFont(originalSize - FONT_REDUCTION);
    }

    public static Map<String, String> noticeImageFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        Map<String, String> imageFields = new LinkedHashMap<>();
        fields.forEach((label, value) -> {
            if (!isExcludedNotice(label)) {
                imageFields.put(label, value);
            }
        });
        return imageFields;
    }

    private static boolean isExcludedNotice(String label) {
        String normalized = normalizeLabel(label);
        return normalized.contains(AS_RESPONSIBILITY_NOTICE_KEY) || normalized.equals(PRODUCT_CODE_NOTICE_KEY);
    }

    private static String normalizeLabel(String label) {
        return Normalizer.normalize(label == null ? "" : label, Normalizer.Form.NFC)
                .replaceAll("[^0-9A-Za-z가-힣]", "")
                .toLowerCase(Locale.ROOT);
    }

    private record NoticeField(String label, String value) {
    }

    private record MeasuredCard(NoticeField field, List<String> labelLines, List<String> valueLines, int height) {
        boolean needsFullWidth() {
            return labelLines.size() > 2 || valueLines.size() > 3;
        }

        NoticeCard place(int x, int y, int width, int rowHeight) {
            return new NoticeCard(x, y, width, rowHeight, labelLines, valueLines, field.label(), field.value());
        }
    }

    record NoticeCard(int x, int y, int width, int height, List<String> labelLines, List<String> valueLines,
            String label, String value) {
    }
}
