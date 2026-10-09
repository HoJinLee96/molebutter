package cc.ataglace.molebutter.imaging.internal.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy;

/** Java2D 공통 드로잉 유틸. 캔버스 규격 상수도 여기서 관리한다. */
public final class RenderSupport {

    public static final int NOTICE_IMAGE_WIDTH = 1440;
    public static final int SIZE_IMAGE_WIDTH = 1440;
    public static final int SIZE_IMAGE_HEIGHT = 940;
    public static final int MAX_IMAGE_SIDE = 8192;
    public static final long MAX_IMAGE_PIXELS = 20_000_000;
    private static final int MAX_RENDER_TEXT_LENGTH = 8192;
    /**
     * 최종 저장 폭(쿠팡 상세페이지 권장 780px). 좌표계는 1440 기준을 유지하고(레이아웃 비율·편집기 좌표 그대로),
     * 저장용 렌더는 OUTPUT_SCALE 변환을 건 캔버스에 직접 그린다 → 사이즈 이미지는 780×509.
     */
    public static final int OUTPUT_IMAGE_WIDTH = 780;
    /** 1440 기준 좌표계를 최종 저장 폭으로 옮기는 배율(≈0.5417). */
    public static final double OUTPUT_SCALE = OUTPUT_IMAGE_WIDTH / (double) SIZE_IMAGE_WIDTH;
    // 사용자가 사진을 키우거나 옮겨도 캔버스 끝/표 영역을 덮지 않도록 사진 렌더를 이 세로 밴드로 clip 한다.
    public static final int SIZE_PHOTO_CLIP_TOP = 24;
    public static final int SIZE_PHOTO_CLIP_BOTTOM = 600;

    private RenderSupport() {
    }

    public static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static List<String> wrapText(Graphics2D graphics, String value, Font font, int maxWidth) {
        validateRenderText(value);
        if (maxWidth <= 0) {
            throw invalidInput("텍스트 영역 폭은 양수여야 합니다.");
        }
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        List<String> lines = new ArrayList<>();
        FontMetrics metrics = graphics.getFontMetrics(font);
        StringBuilder line = new StringBuilder();
        for (String token : normalized.split(" ")) {
            String candidate = line.isEmpty() ? token : line + " " + token;
            if (!line.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            while (metrics.stringWidth(token) > maxWidth) {
                int cut = largestFittingCharacterCount(metrics, token, maxWidth);
                lines.add(token.substring(0, cut));
                token = token.substring(cut);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(token);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static int largestFittingCharacterCount(FontMetrics metrics, String text, int maxWidth) {
        int cut = 1;
        for (int index = 1; index <= text.length(); index++) {
            if (metrics.stringWidth(text.substring(0, index)) > maxWidth) {
                break;
            }
            cut = index;
        }
        return cut;
    }

    public static void drawTextBlock(Graphics2D graphics, List<String> lines, int x, int y, int blockHeight, Font font,
            int lineHeight, Color color) {
        graphics.setFont(font);
        graphics.setColor(color);
        FontMetrics metrics = graphics.getFontMetrics(font);
        int textHeight = lines.size() * lineHeight - (lineHeight - metrics.getHeight());
        int lineY = y + Math.max(0, (blockHeight - textHeight) / 2) + metrics.getAscent();
        for (String line : lines) {
            graphics.drawString(line, x, lineY);
            lineY += lineHeight;
        }
    }

    public static void drawCenteredString(Graphics2D graphics, String text, int centerX, int baselineY, Font font,
            Color color) {
        validateRenderText(text);
        graphics.setFont(font);
        graphics.setColor(color);
        FontMetrics metrics = graphics.getFontMetrics(font);
        graphics.drawString(text, centerX - metrics.stringWidth(text) / 2, baselineY);
    }

    public static void drawCenteredStringWithin(Graphics2D graphics, String text, int centerX, int baselineY, Font font,
            Color color, int maxWidth, int minSize) {
        validateRenderText(text);
        Font resolvedFont = font;
        FontMetrics metrics = graphics.getFontMetrics(resolvedFont);
        while (metrics.stringWidth(text) > maxWidth && resolvedFont.getSize() > minSize) {
            resolvedFont = resolvedFont.deriveFont((float) resolvedFont.getSize() - 1);
            metrics = graphics.getFontMetrics(resolvedFont);
        }
        drawCenteredString(graphics, text, centerX, baselineY, resolvedFont, color);
    }

    /** 표에 표기할 사이즈 라벨 — 값이 없거나 원사이즈 플레이스홀더면 "FREE" 로 표기한다. */
    public static String displaySizeLabel(String rawSizeLabel) {
        return SizeGuidePolicy.normalizeSizeLabel(rawSizeLabel);
    }

    /** 작업 캔버스 높이가 최종 저장 폭 기준으로 몇 px 이 되는지(가로세로 비율 유지). */
    public static int outputHeight(int canvasWidth, int canvasHeight) {
        validateImageDimensions(canvasWidth, canvasHeight);
        return Math.max(1, (int) Math.round(canvasHeight * (OUTPUT_IMAGE_WIDTH / (double) canvasWidth)));
    }

    /**
     * 최종 저장 배율의 캔버스를 만든다. 좌표·폰트 크기는 1440 기준(user space) 그대로 쓰고 Graphics2D 변환으로만 축소하므로
     * 글자와 선이 출력 해상도에서 직접 래스터화된다 — 큰 그림을 비트맵으로 줄일 때 생기는 글자 뭉개짐이 없다.
     */
    public static BufferedImage newScaledCanvas(int canvasWidth, int canvasHeight, double scale) {
        validateImageDimensions(canvasWidth, canvasHeight);
        validateScale(scale);
        long width = Math.max(1, Math.round(canvasWidth * scale));
        long height = Math.max(1, Math.round(canvasHeight * scale));
        validateImageDimensions(width, height);
        return new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_RGB);
    }

    public static void validateImageDimensions(long width, long height) {
        if (width <= 0 || height <= 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                || width * height > MAX_IMAGE_PIXELS) {
            throw invalidInput("이미지 크기는 가로·세로 8192px, 총 2000만 픽셀 이하여야 합니다.");
        }
    }

    public static void validateScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0) {
            throw invalidInput("이미지 배율은 유한한 양수여야 합니다.");
        }
    }

    public static void validateRenderText(String text) {
        if (text != null && text.length() > MAX_RENDER_TEXT_LENGTH) {
            throw invalidInput("이미지에 표시할 텍스트가 너무 깁니다.");
        }
    }

    public static void validateSizeGuideInput(SizeGuidePolicy.SizeGuideTemplate template,
            SizeDimensionsDto dimensions, String sizeLabel) {
        if (template == null || dimensions == null || dimensions.width() == null
                || dimensions.depth() == null || dimensions.height() == null) {
            throw invalidInput("사이즈 이미지의 템플릿과 치수가 필요합니다.");
        }
        validateRenderText(dimensions.width());
        validateRenderText(dimensions.depth());
        validateRenderText(dimensions.height());
        validateRenderText(sizeLabel);
    }

    private static ImagingFailure invalidInput(String message) {
        return new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, message);
    }

    /** 배율 캔버스용 Graphics2D: 품질 힌트 + 축소 변환. 축소 렌더에서는 글자 간격이 픽셀 반올림으로 어긋나지 않게 분수 메트릭을 쓴다. */
    public static Graphics2D createScaledGraphics(BufferedImage canvas, double scale) {
        validateScale(scale);
        Graphics2D graphics = canvas.createGraphics();
        // 변환 전에 실제 픽셀 전체를 흰색으로 채운다 — 배율 반올림으로 생기는 마지막 행/열의 부분 픽셀이 검게 남지 않게.
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        applyQualityHints(graphics);
        if (scale != 1.0) {
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.scale(scale, scale);
        }
        return graphics;
    }

    public static byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", outputStream)) {
                throw new IOException("PNG 이미지 인코더를 찾지 못했습니다.");
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ImagingFailure(ImagingFailure.Kind.INTERNAL, "이미지를 인코딩하지 못했습니다.", e);
        }
    }
}
