package cc.ataglace.molebutter.imaging.internal.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

import org.springframework.stereotype.Component;

import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideLayoutDto;
import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.internal.parse.SizeDimensionParser;
import cc.ataglace.molebutter.imaging.internal.policy.PhotoSizeGuidePolicy;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 상품 사진 위에 치수를 얹는 사이즈 이미지(1440×940) 렌더러.
 * 원본(at-a-glance) 산출물과 구분되도록 자체 스타일을 쓴다:
 * 흰 배경, 반투명 사진, 화살촉 없는 검은 점선, 숫자만 표기(한글 라벨 없음),
 * 세로선 없는 미니멀 표, Nanum Gothic + Avenir Next 폰트.
 * layout 이 null 이면 템플릿 기본 배치로 그리고 그 레이아웃을 함께 반환한다(프리뷰 핸들 seed 용).
 */
@Component
@RequiredArgsConstructor
public class PhotoSizeGuideRenderer {

    private static final Color BACKGROUND = Color.WHITE;
    private static final Color INK = new Color(26, 26, 26);
    // 작은 글자는 780px 출력에서 획이 1px 남짓이라 옅은 회색이면 끊겨 보인다 → 대비를 올린다.
    private static final Color MUTED = new Color(110, 110, 110);
    private static final Color RULE = new Color(203, 200, 194);
    /**
     * 사진 투명도. 곱셈 블렌딩과 함께 적용해 흰 배경 상품컷의 배경은 캔버스에 완전히 녹아들고
     * 제품만 은은하게 비친다(점선·숫자가 제품 위에서도 읽히도록).
     */
    private static final float PHOTO_ALPHA = 0.65f;
    private static final BasicStroke DASHED_STROKE = new BasicStroke(
            3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 12f, 9f }, 0f);

    private static final int TABLE_WIDTH = 1080;
    private static final int TABLE_X = (RenderSupport.SIZE_IMAGE_WIDTH - TABLE_WIDTH) / 2;
    private static final int TABLE_TOP = 656;
    private static final int HEADER_HEIGHT = 74;
    private static final int VALUE_HEIGHT = 94;
    private static final int MEASUREMENT_GAP = 48;
    private static final int MEASUREMENT_CANVAS_MARGIN = 52;
    private static final int MEASUREMENT_BOTTOM_LIMIT = TABLE_TOP - 72;
    private static final int MEASUREMENT_VALUE_GAP = 18;

    private final FontResolver fontResolver;

    public record PhotoRender(BufferedImage image, SizeGuideLayoutDto layout) {
    }

    /** 1440×940 작업 캔버스(테스트·레이아웃 기준). */
    public PhotoRender render(
            SizeGuideTemplate template,
            SizeDimensionsDto dimensions,
            String sizeLabel,
            BufferedImage baseImage,
            SizeGuideLayoutDto layoutOverride) {
        return render(template, dimensions, sizeLabel, baseImage, layoutOverride, 1.0);
    }

    /**
     * scale 배율로 직접 그린다(저장·미리보기는 OUTPUT_SCALE → 780×509). 레이아웃 비율과 1440 기준 좌표는 그대로이고,
     * 글자·점선은 출력 해상도에서 래스터화되며 사진도 최종 크기로 한 번만 리샘플링된다.
     */
    public PhotoRender render(
            SizeGuideTemplate template,
            SizeDimensionsDto dimensions,
            String sizeLabel,
            BufferedImage baseImage,
            SizeGuideLayoutDto layoutOverride,
            double scale) {
        RenderSupport.validateSizeGuideInput(template, dimensions, sizeLabel);
        if (baseImage == null) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "사이즈 이미지의 상품 사진이 필요합니다.");
        }
        RenderSupport.validateImageDimensions(baseImage.getWidth(), baseImage.getHeight());
        validateLayout(layoutOverride);
        int width = RenderSupport.SIZE_IMAGE_WIDTH;
        int height = RenderSupport.SIZE_IMAGE_HEIGHT;

        BufferedImage image = RenderSupport.newScaledCanvas(width, height, scale);
        Graphics2D graphics = RenderSupport.createScaledGraphics(image, scale);

        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, width, height);

        PhotoSizeGuidePolicy.PhotoLayout photoLayout = PhotoSizeGuidePolicy.resolve(template);
        boolean twoDimensionSizeGuide = SizeDimensionParser.isTwoDimensionSizeGuide(template, dimensions);
        SizeGuideLayoutDto effectiveLayout = layoutOverride != null
                ? layoutOverride
                : buildDefaultPhotoLayout(photoLayout, baseImage, twoDimensionSizeGuide);

        drawPhotoFromLayout(image, baseImage, photoLayout, effectiveLayout.photo(), scale);
        drawPhotoMeasurementsFromLayout(graphics, effectiveLayout, twoDimensionSizeGuide,
                dimensions.width(), dimensions.depth(), dimensions.height());
        drawPhotoSizeTable(graphics, template, twoDimensionSizeGuide, sizeLabel,
                dimensions.width(), dimensions.depth(), dimensions.height());

        graphics.dispose();
        return new PhotoRender(image, effectiveLayout);
    }

    private void validateLayout(SizeGuideLayoutDto layout) {
        if (layout == null) {
            return;
        }
        SizeGuideLayoutDto.PhotoPlacement photo = layout.photo();
        if (photo == null || !Double.isFinite(photo.scale()) || photo.scale() < 0.3 || photo.scale() > 2.5) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "상품 사진 배율은 0.3~2.5 범위여야 합니다.");
        }
        validateRatios(photo.centerXRatio(), photo.centerYRatio());
        for (SizeGuideLayoutDto.ArrowPlacement arrow : new SizeGuideLayoutDto.ArrowPlacement[] {
                layout.width(), layout.height(), layout.depth() }) {
            if (arrow != null) {
                validateRatios(arrow.startXRatio(), arrow.startYRatio(), arrow.endXRatio(), arrow.endYRatio(),
                        arrow.labelXRatio(), arrow.labelYRatio());
            }
        }
    }

    private void validateRatios(double... ratios) {
        for (double ratio : ratios) {
            if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) {
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "이미지 배치 좌표는 0~1 범위여야 합니다.");
            }
        }
    }

    /** override 가 없을 때 현재 템플릿 기본 배치를 캔버스 기준 비율로 환산한다(프리뷰 핸들 seed). */
    private SizeGuideLayoutDto buildDefaultPhotoLayout(
            PhotoSizeGuidePolicy.PhotoLayout photoLayout,
            BufferedImage baseImage,
            boolean twoDimensionSizeGuide) {
        int boxWidth = photoLayout.photoBoxWidth();
        int boxHeight = photoLayout.photoBoxHeight();
        int boxX = (RenderSupport.SIZE_IMAGE_WIDTH - boxWidth) / 2;
        int boxY = Math.max(RenderSupport.SIZE_PHOTO_CLIP_TOP, photoLayout.photoBoxY() - 36);
        DrawnPhoto photo = computeBoxFitPhotoRect(baseImage, boxX, boxY, boxWidth, boxHeight);
        DrawnPhoto subject = detectDrawnSubjectBounds(baseImage, photo);

        SizeGuideLayoutDto.PhotoPlacement photoPlacement = new SizeGuideLayoutDto.PhotoPlacement(
                1.0,
                (boxX + boxWidth / 2.0) / RenderSupport.SIZE_IMAGE_WIDTH,
                (boxY + boxHeight / 2.0) / RenderSupport.SIZE_IMAGE_HEIGHT);
        return new SizeGuideLayoutDto(
                photoPlacement,
                outsideWidthMeasurement(subject),
                outsideHeightMeasurement(subject),
                twoDimensionSizeGuide ? null : outsideDepthMeasurement(subject));
    }

    private DrawnPhoto computeBoxFitPhotoRect(BufferedImage source, int boxX, int boxY, int boxWidth, int boxHeight) {
        float scale = Math.min(boxWidth / (float) source.getWidth(), boxHeight / (float) source.getHeight());
        int imageWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int imageHeight = Math.max(1, Math.round(source.getHeight() * scale));
        int imageX = boxX + (boxWidth - imageWidth) / 2;
        int imageY = boxY + (boxHeight - imageHeight) / 2;
        return new DrawnPhoto(imageX, imageY, imageWidth, imageHeight);
    }

    /** 흰색·투명 배경을 제외한 실제 제품 영역을 찾아 그려진 사진 좌표로 환산한다. */
    private DrawnPhoto detectDrawnSubjectBounds(BufferedImage source, DrawnPhoto drawnPhoto) {
        SourceBounds sourceBounds = detectSourceSubjectBounds(source);
        double scaleX = drawnPhoto.width() / (double) source.getWidth();
        double scaleY = drawnPhoto.height() / (double) source.getHeight();
        int x = drawnPhoto.x() + (int) Math.round(sourceBounds.x() * scaleX);
        int y = drawnPhoto.y() + (int) Math.round(sourceBounds.y() * scaleY);
        int width = Math.max(1, (int) Math.round(sourceBounds.width() * scaleX));
        int height = Math.max(1, (int) Math.round(sourceBounds.height() * scaleY));
        return new DrawnPhoto(x, y, width, height);
    }

    private SourceBounds detectSourceSubjectBounds(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int step = Math.max(1, Math.max(width, height) / 1200);
        int sampledColumns = (width + step - 1) / step;
        int sampledRows = (height + step - 1) / step;
        int[] columnHits = new int[sampledColumns];
        int[] rowHits = new int[sampledRows];

        int cornerInsetX = Math.min(width - 1, Math.max(0, width / 50));
        int cornerInsetY = Math.min(height - 1, Math.max(0, height / 50));
        int[] corners = {
                source.getRGB(cornerInsetX, cornerInsetY),
                source.getRGB(width - 1 - cornerInsetX, cornerInsetY),
                source.getRGB(cornerInsetX, height - 1 - cornerInsetY),
                source.getRGB(width - 1 - cornerInsetX, height - 1 - cornerInsetY)
        };
        int backgroundAlpha = averageChannel(corners, 24);
        int backgroundRed = averageChannel(corners, 16);
        int backgroundGreen = averageChannel(corners, 8);
        int backgroundBlue = averageChannel(corners, 0);
        boolean transparentBackground = backgroundAlpha < 64;

        int sampledY = 0;
        for (int y = 0; y < height; y += step, sampledY++) {
            int sampledX = 0;
            for (int x = 0; x < width; x += step, sampledX++) {
                if (isSubjectPixel(source.getRGB(x, y), transparentBackground,
                        backgroundRed, backgroundGreen, backgroundBlue)) {
                    columnHits[sampledX]++;
                    rowHits[sampledY]++;
                }
            }
        }

        int minimumColumnHits = Math.max(2, sampledRows / 200);
        int minimumRowHits = Math.max(2, sampledColumns / 200);
        int firstColumn = firstSignificantIndex(columnHits, minimumColumnHits);
        int lastColumn = lastSignificantIndex(columnHits, minimumColumnHits);
        int firstRow = firstSignificantIndex(rowHits, minimumRowHits);
        int lastRow = lastSignificantIndex(rowHits, minimumRowHits);

        if (firstColumn < 0 || firstRow < 0) {
            return insetFallbackBounds(width, height);
        }
        int detectedX = firstColumn * step;
        int detectedY = firstRow * step;
        int detectedRight = Math.min(width, (lastColumn + 1) * step);
        int detectedBottom = Math.min(height, (lastRow + 1) * step);
        int detectedWidth = detectedRight - detectedX;
        int detectedHeight = detectedBottom - detectedY;
        if (detectedWidth < width / 20 || detectedHeight < height / 20) {
            return insetFallbackBounds(width, height);
        }
        return new SourceBounds(detectedX, detectedY, detectedWidth, detectedHeight);
    }

    private int averageChannel(int[] colors, int shift) {
        int total = 0;
        for (int color : colors) {
            total += (color >> shift) & 0xFF;
        }
        return total / colors.length;
    }

    private boolean isSubjectPixel(int rgb, boolean transparentBackground,
            int backgroundRed, int backgroundGreen, int backgroundBlue) {
        int alpha = (rgb >>> 24) & 0xFF;
        if (alpha <= 24) {
            return false;
        }
        if (transparentBackground) {
            return alpha >= 40;
        }
        int redDelta = Math.abs(((rgb >> 16) & 0xFF) - backgroundRed);
        int greenDelta = Math.abs(((rgb >> 8) & 0xFF) - backgroundGreen);
        int blueDelta = Math.abs((rgb & 0xFF) - backgroundBlue);
        return Math.max(redDelta, Math.max(greenDelta, blueDelta)) > 18
                || redDelta + greenDelta + blueDelta > 45;
    }

    private int firstSignificantIndex(int[] hits, int minimumHits) {
        for (int index = 0; index < hits.length; index++) {
            if (hits[index] >= minimumHits) {
                return index;
            }
        }
        return -1;
    }

    private int lastSignificantIndex(int[] hits, int minimumHits) {
        for (int index = hits.length - 1; index >= 0; index--) {
            if (hits[index] >= minimumHits) {
                return index;
            }
        }
        return -1;
    }

    private SourceBounds insetFallbackBounds(int width, int height) {
        int insetX = Math.max(1, width / 10);
        int insetY = Math.max(1, height / 10);
        return new SourceBounds(insetX, insetY,
                Math.max(1, width - insetX * 2), Math.max(1, height - insetY * 2));
    }

    private SizeGuideLayoutDto.ArrowPlacement outsideWidthMeasurement(DrawnPhoto subject) {
        int inset = Math.max(4, subject.width() / 50);
        int startX = subject.x() + inset;
        int endX = subject.x() + subject.width() - inset;
        int y = Math.min(MEASUREMENT_BOTTOM_LIMIT, subject.y() + subject.height() + MEASUREMENT_GAP);
        return arrowPlacement(startX, y, endX, y);
    }

    private SizeGuideLayoutDto.ArrowPlacement outsideHeightMeasurement(DrawnPhoto subject) {
        int inset = Math.max(4, subject.height() / 50);
        int x = Math.max(MEASUREMENT_CANVAS_MARGIN, subject.x() - MEASUREMENT_GAP);
        return arrowPlacement(x, subject.y() + inset, x, subject.y() + subject.height() - inset);
    }

    private SizeGuideLayoutDto.ArrowPlacement outsideDepthMeasurement(DrawnPhoto subject) {
        int run = Math.max(150, Math.min(200, subject.width() / 4));
        // 첫 로드/초기화는 왼쪽 아래 → 오른쪽 위 45도로 고정한다(실제 픽셀 이동량 기준).
        double rise = run;
        int startX = Math.min(RenderSupport.SIZE_IMAGE_WIDTH - MEASUREMENT_CANVAS_MARGIN - run,
                subject.x() + subject.width() + MEASUREMENT_GAP);
        // 작은 제품이 사진 위쪽에 있어도 선 끝이 캔버스 밖으로 나가지 않도록 통째로 내린다.
        double startY = Math.max(MEASUREMENT_CANVAS_MARGIN + rise,
                Math.min(MEASUREMENT_BOTTOM_LIMIT, subject.y() + subject.height() + MEASUREMENT_GAP));
        return arrowPlacement(startX, startY, startX + run, startY - rise);
    }

    private SizeGuideLayoutDto.ArrowPlacement arrowPlacement(double startX, double startY, double endX, double endY) {
        double middleX = (startX + endX) / 2.0;
        double middleY = (startY + endY) / 2.0;
        return new SizeGuideLayoutDto.ArrowPlacement(
                canvasXRatio(startX), canvasYRatio(startY),
                canvasXRatio(endX), canvasYRatio(endY),
                canvasXRatio(middleX), canvasYRatio(middleY));
    }

    private double canvasXRatio(double px) {
        return px / RenderSupport.SIZE_IMAGE_WIDTH;
    }

    private double canvasYRatio(double px) {
        return px / RenderSupport.SIZE_IMAGE_HEIGHT;
    }

    private void drawPhotoFromLayout(
            BufferedImage canvas,
            BufferedImage source,
            PhotoSizeGuidePolicy.PhotoLayout photoLayout,
            SizeGuideLayoutDto.PhotoPlacement placement,
            double canvasScale) {
        float autofit = Math.min(
                photoLayout.photoBoxWidth() / (float) source.getWidth(),
                photoLayout.photoBoxHeight() / (float) source.getHeight());
        // 픽셀 블렌딩은 변환이 걸리지 않는 캔버스 픽셀 좌표에서 하므로 1440 기준 값에 canvasScale 을 직접 곱한다.
        double scale = autofit * placement.scale() * canvasScale;
        long requestedWidth = Math.max(1, Math.round(source.getWidth() * scale));
        long requestedHeight = Math.max(1, Math.round(source.getHeight() * scale));
        RenderSupport.validateImageDimensions(requestedWidth, requestedHeight);
        int drawnWidth = (int) requestedWidth;
        int drawnHeight = (int) requestedHeight;
        int centerX = (int) Math.round(placement.centerXRatio() * RenderSupport.SIZE_IMAGE_WIDTH * canvasScale);
        int centerY = (int) Math.round(placement.centerYRatio() * RenderSupport.SIZE_IMAGE_HEIGHT * canvasScale);
        int imageX = centerX - drawnWidth / 2;
        int imageY = centerY - drawnHeight / 2;

        BufferedImage scaled = new BufferedImage(drawnWidth, drawnHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D scaledGraphics = scaled.createGraphics();
        RenderSupport.applyQualityHints(scaledGraphics);
        scaledGraphics.setColor(Color.WHITE);
        scaledGraphics.fillRect(0, 0, drawnWidth, drawnHeight);
        scaledGraphics.drawImage(source, 0, 0, drawnWidth, drawnHeight, null);
        scaledGraphics.dispose();

        // 클립 밴드(제목/표 보호)와 캔버스 경계로 잘라 곱셈+투명도 블렌딩.
        int top = Math.max(imageY, (int) Math.round(RenderSupport.SIZE_PHOTO_CLIP_TOP * canvasScale));
        int bottom = Math.min(imageY + drawnHeight, (int) Math.round(RenderSupport.SIZE_PHOTO_CLIP_BOTTOM * canvasScale));
        int left = Math.max(imageX, 0);
        int right = Math.min(imageX + drawnWidth, canvas.getWidth());
        if (top >= bottom || left >= right) {
            return;
        }
        int regionWidth = right - left;
        for (int y = top; y < bottom; y++) {
            int[] canvasRow = canvas.getRGB(left, y, regionWidth, 1, null, 0, regionWidth);
            int[] photoRow = scaled.getRGB(left - imageX, y - imageY, regionWidth, 1, null, 0, regionWidth);
            for (int index = 0; index < regionWidth; index++) {
                canvasRow[index] = multiplyMix(canvasRow[index], photoRow[index]);
            }
            canvas.setRGB(left, y, regionWidth, 1, canvasRow, 0, regionWidth);
        }
    }

    /** out = (1-α)·bg + α·(src×bg/255) — 흰 픽셀은 배경 그대로, 어두운 픽셀만 은은하게 얹힌다. */
    private int multiplyMix(int backgroundRgb, int sourceRgb) {
        int red = mixChannel((backgroundRgb >> 16) & 0xFF, (sourceRgb >> 16) & 0xFF);
        int green = mixChannel((backgroundRgb >> 8) & 0xFF, (sourceRgb >> 8) & 0xFF);
        int blue = mixChannel(backgroundRgb & 0xFF, sourceRgb & 0xFF);
        return (red << 16) | (green << 8) | blue;
    }

    private int mixChannel(int background, int source) {
        int multiplied = source * background / 255;
        return Math.round((1 - PHOTO_ALPHA) * background + PHOTO_ALPHA * multiplied);
    }

    private void drawPhotoMeasurementsFromLayout(
            Graphics2D graphics,
            SizeGuideLayoutDto layout,
            boolean twoDimensionSizeGuide,
            String widthValue,
            String depthValue,
            String heightValue) {
        int productCenterX = (int) Math.round(
                layout.photo().centerXRatio() * RenderSupport.SIZE_IMAGE_WIDTH);
        int productCenterY = (int) Math.round(
                layout.photo().centerYRatio() * RenderSupport.SIZE_IMAGE_HEIGHT);
        drawPhotoMeasurementAt(graphics, layout.width(), widthValue, productCenterX, productCenterY);
        drawPhotoMeasurementAt(graphics, layout.height(), heightValue, productCenterX, productCenterY);
        if (!twoDimensionSizeGuide) {
            drawPhotoMeasurementAt(graphics, layout.depth(), depthValue, productCenterX, productCenterY);
        }
    }

    /** 화살촉·한글 라벨 없이 — 검은 점선 + 숫자만. */
    private void drawPhotoMeasurementAt(
            Graphics2D graphics,
            SizeGuideLayoutDto.ArrowPlacement arrow,
            String value,
            int productCenterX,
            int productCenterY) {
        if (arrow == null) {
            return;
        }
        double startX = arrow.startXRatio() * RenderSupport.SIZE_IMAGE_WIDTH;
        double startY = arrow.startYRatio() * RenderSupport.SIZE_IMAGE_HEIGHT;
        double endX = arrow.endXRatio() * RenderSupport.SIZE_IMAGE_WIDTH;
        double endY = arrow.endYRatio() * RenderSupport.SIZE_IMAGE_HEIGHT;

        graphics.setColor(INK);
        graphics.setStroke(DASHED_STROKE);
        // 정수 drawLine으로 반올림하면 짧은 선의 각도가 바뀌므로 소수점 좌표로 렌더한다.
        graphics.draw(new Line2D.Double(startX, startY, endX, endY));
        drawMeasurementValue(graphics, value, startX, startY, endX, endY, productCenterX, productCenterY);
    }

    /** 점선은 끊지 않고, 숫자는 제품 중심의 반대편에 간격을 두어 배치한다. */
    private void drawMeasurementValue(Graphics2D graphics, String value,
            double startX, double startY, double endX, double endY, int productCenterX, int productCenterY) {
        Font font = fontResolver.numberFont(Font.BOLD, 42);
        FontMetrics metrics = graphics.getFontMetrics(font);
        MeasurementValuePosition position = calculateMeasurementValuePosition(
                startX, startY, endX, endY, productCenterX, productCenterY,
                metrics.stringWidth(value), metrics.getHeight(), MEASUREMENT_VALUE_GAP);
        int baselineY = position.centerY() + (metrics.getAscent() - metrics.getDescent()) / 2;
        RenderSupport.drawCenteredString(graphics, value, position.centerX(), baselineY, font, INK);
    }

    static MeasurementValuePosition calculateMeasurementValuePosition(
            double startX,
            double startY,
            double endX,
            double endY,
            int productCenterX,
            int productCenterY,
            int textWidth,
            int textHeight,
            int gap) {
        double middleX = (startX + endX) / 2.0;
        double middleY = (startY + endY) / 2.0;
        double lineX = endX - startX;
        double lineY = endY - startY;
        double lineLength = Math.hypot(lineX, lineY);
        if (lineLength == 0) {
            return new MeasurementValuePosition((int) Math.round(middleX), (int) Math.round(middleY));
        }

        // 선의 법선 두 방향 중 제품 중심과 반대쪽을 선택한다.
        double normalX = -lineY / lineLength;
        double normalY = lineX / lineLength;
        double towardProduct = normalX * (productCenterX - middleX)
                + normalY * (productCenterY - middleY);
        if (towardProduct > 0) {
            normalX = -normalX;
            normalY = -normalY;
        }

        double textProjection = Math.abs(normalX) * textWidth / 2.0
                + Math.abs(normalY) * textHeight / 2.0;
        double distance = textProjection + gap;
        return new MeasurementValuePosition(
                (int) Math.round(middleX + normalX * distance),
                (int) Math.round(middleY + normalY * distance));
    }

    /** 세로 구분선·배경칸 없는 미니멀 표 — 상단 굵은 라인 + 가는 가로 라인 두 줄. */
    private void drawPhotoSizeTable(Graphics2D graphics, SizeGuideTemplate template, boolean twoDimensionSizeGuide,
            String sizeLabel,
            String widthValue, String depthValue, String heightValue) {
        int valueCount = twoDimensionSizeGuide ? 2 : 3;
        int columnCount = valueCount + 1;
        int columnWidth = TABLE_WIDTH / columnCount;
        int midRuleY = TABLE_TOP + HEADER_HEIGHT;
        int bottomRuleY = midRuleY + VALUE_HEIGHT;

        graphics.setColor(INK);
        graphics.setStroke(new BasicStroke(3));
        graphics.drawLine(TABLE_X, TABLE_TOP, TABLE_X + TABLE_WIDTH, TABLE_TOP);
        graphics.setColor(RULE);
        graphics.setStroke(new BasicStroke(1));
        graphics.drawLine(TABLE_X, midRuleY, TABLE_X + TABLE_WIDTH, midRuleY);
        graphics.drawLine(TABLE_X, bottomRuleY, TABLE_X + TABLE_WIDTH, bottomRuleY);

        int[] centers = new int[columnCount];
        for (int index = 0; index < columnCount; index++) {
            centers[index] = TABLE_X + columnWidth * index + columnWidth / 2;
        }

        Font tableHeaderFont = fontResolver.photoTextFont(Font.PLAIN, 30);
        int headerBaselineY = TABLE_TOP + 48;
        RenderSupport.drawCenteredString(graphics, "사이즈", centers[0], headerBaselineY, tableHeaderFont, MUTED);
        RenderSupport.drawCenteredString(graphics, template.widthLabel(), centers[1], headerBaselineY, tableHeaderFont, MUTED);
        if (twoDimensionSizeGuide) {
            RenderSupport.drawCenteredString(graphics, template.heightLabel(), centers[2], headerBaselineY, tableHeaderFont, MUTED);
        } else {
            RenderSupport.drawCenteredString(graphics, template.depthLabel(), centers[2], headerBaselineY, tableHeaderFont, MUTED);
            RenderSupport.drawCenteredString(graphics, template.heightLabel(), centers[3], headerBaselineY, tableHeaderFont, MUTED);
        }

        Font valueFont = fontResolver.numberFont(Font.BOLD, 36);
        int valueBaselineY = midRuleY + 60;
        RenderSupport.drawCenteredStringWithin(graphics, RenderSupport.displaySizeLabel(sizeLabel), centers[0],
                valueBaselineY, valueFont, INK, columnWidth - 40, 22);
        RenderSupport.drawCenteredStringWithin(graphics, widthValue, centers[1], valueBaselineY, valueFont, INK,
                columnWidth - 40, 22);
        if (twoDimensionSizeGuide) {
            RenderSupport.drawCenteredStringWithin(graphics, heightValue, centers[2], valueBaselineY, valueFont, INK,
                    columnWidth - 40, 22);
        } else {
            RenderSupport.drawCenteredStringWithin(graphics, depthValue, centers[2], valueBaselineY, valueFont, INK,
                    columnWidth - 40, 22);
            RenderSupport.drawCenteredStringWithin(graphics, heightValue, centers[3], valueBaselineY, valueFont, INK,
                    columnWidth - 40, 22);
        }

        RenderSupport.drawCenteredString(graphics,
                "사이즈는 측정 기준에 따라 다소 차이가 날 수 있으며, 표기 단위는 cm입니다.",
                RenderSupport.SIZE_IMAGE_WIDTH / 2, bottomRuleY + 44,
                fontResolver.photoTextFont(Font.PLAIN, 28), MUTED);
    }

    private record DrawnPhoto(int x, int y, int width, int height) {
    }

    private record SourceBounds(int x, int y, int width, int height) {
    }

    record MeasurementValuePosition(int centerX, int centerY) {
    }
}
