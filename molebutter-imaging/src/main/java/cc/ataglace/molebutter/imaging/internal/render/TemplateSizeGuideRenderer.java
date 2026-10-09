package cc.ataglace.molebutter.imaging.internal.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

import org.springframework.stereotype.Component;

import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.internal.parse.SizeDimensionParser;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 카테고리/치수표 사이즈 이미지(1440×940). 기존 실루엣은 제거한 상태다.
 * 벨트만은 상품 사진 없이도 쓸 수 있는 공용 표본(스트랩·버클 실루엣 + 총길이·너비 점선)을 그린다.
 */
@Component
@RequiredArgsConstructor
public class TemplateSizeGuideRenderer {

    private static final Color INK = new Color(26, 26, 26);
    private static final BasicStroke DASHED_STROKE = new BasicStroke(
            3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 12f, 9f }, 0f);

    // 공용 벨트 표본 배치(상단 250px 은 항상 흰 여백으로 둔다).
    private static final int BELT_STRAP_LEFT = 300;
    private static final int BELT_STRAP_RIGHT = 1180;
    private static final int BELT_STRAP_TOP = 350;
    private static final int BELT_STRAP_HEIGHT = 60;
    private static final int BELT_BUCKLE_LEFT = 218;
    private static final int BELT_BUCKLE_WIDTH = 92;
    private static final int BELT_TABLE_Y = 580;
    private static final int DEFAULT_TABLE_Y = 380;

    private final FontResolver fontResolver;

    public byte[] renderPng(SizeGuideTemplate template, SizeDimensionsDto dimensions, String sizeLabel) {
        return RenderSupport.encodePng(render(template, dimensions, sizeLabel, RenderSupport.OUTPUT_SCALE));
    }

    /** 1440×940 작업 캔버스(테스트·레이아웃 기준). */
    public BufferedImage render(SizeGuideTemplate template, SizeDimensionsDto dimensions, String sizeLabel) {
        return render(template, dimensions, sizeLabel, 1.0);
    }

    /** scale 배율로 직접 그린다(저장용은 OUTPUT_SCALE → 780×509). */
    public BufferedImage render(SizeGuideTemplate template, SizeDimensionsDto dimensions, String sizeLabel,
            double scale) {
        RenderSupport.validateSizeGuideInput(template, dimensions, sizeLabel);
        int width = RenderSupport.SIZE_IMAGE_WIDTH;
        int height = RenderSupport.SIZE_IMAGE_HEIGHT;

        BufferedImage image = RenderSupport.newScaledCanvas(width, height, scale);
        Graphics2D graphics = RenderSupport.createScaledGraphics(image, scale);

        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);

        boolean twoDimensionSizeGuide = SizeDimensionParser.isTwoDimensionSizeGuide(template, dimensions);
        int tableY = DEFAULT_TABLE_Y;
        if (template == SizeGuideTemplate.BELT) {
            drawBeltSample(graphics, dimensions.width(), dimensions.height());
            tableY = BELT_TABLE_Y;
        } else {
            // 기존 카테고리 그림은 제거했다. 새 그림을 등록하기 전에는 카테고리와 치수표만 표시한다.
            RenderSupport.drawCenteredString(graphics, template.displayName(),
                    width / 2, 300, fontResolver.photoTextFont(Font.PLAIN, 40), INK);
        }
        drawSizeTable(graphics, template, twoDimensionSizeGuide, tableY, sizeLabel,
                dimensions.width(), dimensions.depth(), dimensions.height());

        graphics.dispose();
        return image;
    }

    /**
     * 공용 벨트 표본: 왼쪽 버클, 오른쪽으로 갈수록 뾰족해지는 스트랩, 벨트 구멍.
     * 총길이 점선은 버클 바깥에서 스트랩 끝까지, 너비 점선은 스트랩 세로 폭을 잰다(사진 모드와 같은 검은 점선 + 숫자).
     */
    private void drawBeltSample(Graphics2D graphics, String lengthValue, String strapWidthValue) {
        int strapBottom = BELT_STRAP_TOP + BELT_STRAP_HEIGHT;
        int strapCenterY = BELT_STRAP_TOP + BELT_STRAP_HEIGHT / 2;
        // 무채색: 어떤 색상의 벨트에도 어울리도록 회색 톤만 쓴다.
        Color leather = new Color(120, 120, 120);
        Color leatherEdge = new Color(70, 70, 70);
        Color stitch = new Color(215, 215, 215);
        Color metal = new Color(190, 190, 190);
        Color metalEdge = new Color(95, 95, 95);

        // 스트랩: 왼쪽은 버클 안으로, 오른쪽 끝은 둥근 화살촉 모양
        Path2D.Double strap = new Path2D.Double();
        int tipStart = BELT_STRAP_RIGHT - 70;
        strap.moveTo(BELT_STRAP_LEFT - 30, BELT_STRAP_TOP);
        strap.lineTo(tipStart, BELT_STRAP_TOP);
        strap.quadTo(BELT_STRAP_RIGHT - 10, BELT_STRAP_TOP + 6, BELT_STRAP_RIGHT, strapCenterY);
        strap.quadTo(BELT_STRAP_RIGHT - 10, strapBottom - 6, tipStart, strapBottom);
        strap.lineTo(BELT_STRAP_LEFT - 30, strapBottom);
        strap.closePath();
        graphics.setColor(leather);
        graphics.fill(strap);
        graphics.setColor(leatherEdge);
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(strap);

        // 스티치 라인
        graphics.setColor(stitch);
        graphics.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[] { 7f, 5f }, 0f));
        graphics.draw(new Line2D.Double(BELT_STRAP_LEFT + 4, BELT_STRAP_TOP + 8, tipStart - 6, BELT_STRAP_TOP + 8));
        graphics.draw(new Line2D.Double(BELT_STRAP_LEFT + 4, strapBottom - 8, tipStart - 6, strapBottom - 8));

        // 벨트 구멍 5개
        graphics.setColor(leatherEdge);
        for (int index = 0; index < 5; index++) {
            int holeX = tipStart - 180 + index * 38;
            graphics.fill(new Ellipse2D.Double(holeX - 6, strapCenterY - 6, 12, 12));
        }

        // 버클: 둥근 사각 프레임 + 가운데 핀
        int buckleTop = BELT_STRAP_TOP - 22;
        int buckleHeight = BELT_STRAP_HEIGHT + 44;
        graphics.setColor(metal);
        graphics.fill(new RoundRectangle2D.Double(BELT_BUCKLE_LEFT, buckleTop, BELT_BUCKLE_WIDTH, buckleHeight, 26, 26));
        graphics.setColor(Color.WHITE);
        graphics.fill(new RoundRectangle2D.Double(BELT_BUCKLE_LEFT + 16, buckleTop + 16,
                BELT_BUCKLE_WIDTH - 32, buckleHeight - 32, 14, 14));
        graphics.setColor(metalEdge);
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(new RoundRectangle2D.Double(BELT_BUCKLE_LEFT, buckleTop, BELT_BUCKLE_WIDTH, buckleHeight, 26, 26));
        graphics.draw(new RoundRectangle2D.Double(BELT_BUCKLE_LEFT + 16, buckleTop + 16,
                BELT_BUCKLE_WIDTH - 32, buckleHeight - 32, 14, 14));
        graphics.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(new Line2D.Double(BELT_BUCKLE_LEFT + BELT_BUCKLE_WIDTH / 2.0, buckleTop + 4,
                BELT_BUCKLE_LEFT + BELT_BUCKLE_WIDTH / 2.0, buckleTop + buckleHeight - 4));
        graphics.draw(new Line2D.Double(BELT_BUCKLE_LEFT + BELT_BUCKLE_WIDTH / 2.0, strapCenterY,
                BELT_BUCKLE_LEFT + BELT_BUCKLE_WIDTH + 28, strapCenterY));

        // 총길이 점선(아래) + 너비 점선(오른쪽)
        Font valueFont = fontResolver.numberFont(Font.BOLD, 42);
        FontMetrics metrics = graphics.getFontMetrics(valueFont);
        graphics.setColor(INK);
        graphics.setStroke(DASHED_STROKE);
        int lengthLineY = strapBottom + 62;
        graphics.draw(new Line2D.Double(BELT_BUCKLE_LEFT, lengthLineY, BELT_STRAP_RIGHT, lengthLineY));
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(new Line2D.Double(BELT_BUCKLE_LEFT, lengthLineY - 14, BELT_BUCKLE_LEFT, lengthLineY + 14));
        graphics.draw(new Line2D.Double(BELT_STRAP_RIGHT, lengthLineY - 14, BELT_STRAP_RIGHT, lengthLineY + 14));
        RenderSupport.drawCenteredString(graphics, lengthValue, (BELT_BUCKLE_LEFT + BELT_STRAP_RIGHT) / 2,
                lengthLineY + 18 + metrics.getAscent(), valueFont, INK);

        int widthLineX = BELT_STRAP_RIGHT + 46;
        graphics.setStroke(DASHED_STROKE);
        graphics.draw(new Line2D.Double(widthLineX, BELT_STRAP_TOP, widthLineX, strapBottom));
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(new Line2D.Double(widthLineX - 14, BELT_STRAP_TOP, widthLineX + 14, BELT_STRAP_TOP));
        graphics.draw(new Line2D.Double(widthLineX - 14, strapBottom, widthLineX + 14, strapBottom));
        int valueBaseline = strapCenterY + (metrics.getAscent() - metrics.getDescent()) / 2;
        graphics.setFont(valueFont);
        graphics.drawString(strapWidthValue, widthLineX + 24, valueBaseline);
    }

    private void drawSizeTable(Graphics2D graphics, SizeGuideTemplate template, boolean twoDimensionSizeGuide,
            int tableY, String sizeLabel, String widthValue, String depthValue, String heightValue) {
        int tableX = 180;
        int tableWidth = RenderSupport.SIZE_IMAGE_WIDTH - tableX * 2;
        int firstColumnWidth = 270;
        int valueCount = twoDimensionSizeGuide ? 2 : 3;
        int valueColumnWidth = (tableWidth - firstColumnWidth) / valueCount;
        int headerHeight = 96;
        int rowHeight = 96;
        int tableHeight = headerHeight + rowHeight;

        graphics.setColor(new Color(250, 250, 250));
        graphics.fillRect(tableX, tableY, tableWidth, headerHeight);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(tableX, tableY + headerHeight, tableWidth, rowHeight);

        graphics.setColor(new Color(210, 210, 210));
        graphics.setStroke(new BasicStroke(2));
        graphics.drawLine(tableX, tableY, tableX + tableWidth, tableY);
        graphics.drawLine(tableX, tableY + tableHeight, tableX + tableWidth, tableY + tableHeight);
        for (int index = 1; index <= valueCount; index++) {
            int lineX = tableX + firstColumnWidth + valueColumnWidth * (index - 1);
            graphics.drawLine(lineX, tableY, lineX, tableY + tableHeight);
        }

        Font headerFont = fontResolver.font(Font.PLAIN, 38);
        Font valueFont = fontResolver.font(Font.PLAIN, 40);
        Font noteFont = fontResolver.font(Font.PLAIN, 28);
        int[] centers = new int[valueCount + 1];
        centers[0] = tableX + firstColumnWidth / 2;
        for (int index = 0; index < valueCount; index++) {
            centers[index + 1] = tableX + firstColumnWidth + valueColumnWidth * index + valueColumnWidth / 2;
        }

        int headerBaselineY = tableY + 62;
        RenderSupport.drawCenteredString(graphics, "단위 : cm", centers[0], headerBaselineY, headerFont,
                new Color(145, 145, 145));
        RenderSupport.drawCenteredString(graphics, template.widthLabel(), centers[1], headerBaselineY, headerFont, Color.BLACK);
        if (twoDimensionSizeGuide) {
            RenderSupport.drawCenteredString(graphics, template.heightLabel(), centers[2], headerBaselineY, headerFont, Color.BLACK);
        } else {
            RenderSupport.drawCenteredString(graphics, template.depthLabel(), centers[2], headerBaselineY, headerFont, Color.BLACK);
            RenderSupport.drawCenteredString(graphics, template.heightLabel(), centers[3], headerBaselineY, headerFont, Color.BLACK);
        }

        int valueBaselineY = tableY + headerHeight + 64;
        RenderSupport.drawCenteredStringWithin(graphics, RenderSupport.displaySizeLabel(sizeLabel), centers[0],
                valueBaselineY, valueFont, Color.BLACK, firstColumnWidth - 36, 28);
        RenderSupport.drawCenteredStringWithin(graphics, widthValue, centers[1], valueBaselineY, valueFont,
                Color.BLACK, valueColumnWidth - 24, 28);
        if (twoDimensionSizeGuide) {
            RenderSupport.drawCenteredStringWithin(graphics, heightValue, centers[2], valueBaselineY, valueFont,
                    Color.BLACK, valueColumnWidth - 24, 28);
        } else {
            RenderSupport.drawCenteredStringWithin(graphics, depthValue, centers[2], valueBaselineY, valueFont,
                    Color.BLACK, valueColumnWidth - 24, 28);
            RenderSupport.drawCenteredStringWithin(graphics, heightValue, centers[3], valueBaselineY, valueFont,
                    Color.BLACK, valueColumnWidth - 24, 28);
        }

        RenderSupport.drawCenteredStringWithin(graphics, "측정 기준에 따라 표기된 치수와 차이가 있을 수 있습니다. (단위: cm)",
                RenderSupport.SIZE_IMAGE_WIDTH / 2, tableY + tableHeight + 48,
                noteFont, new Color(115, 115, 115), tableWidth, 22);
    }
}
