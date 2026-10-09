package cc.ataglace.molebutter.imaging.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideLayoutDto;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;

class SizeGuideRendererTest {

    private final FontResolver fontResolver =
            new FontResolver("Apple SD Gothic Neo", "Nanum Gothic", "Avenir Next", "AppleGothic");
    private final TemplateSizeGuideRenderer templateRenderer = new TemplateSizeGuideRenderer(fontResolver);
    private final PhotoSizeGuideRenderer photoRenderer = new PhotoSizeGuideRenderer(fontResolver);

    @Test
    void rendersTemplateSizeGuideAtFixedCanvas() throws IOException {
        byte[] png = templateRenderer.renderPng(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "L(large)");

        // 저장본은 쿠팡 권장 폭 780px(비율 유지 → 509px), 작업 캔버스는 1440×940 그대로.
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(780);
        assertThat(image.getHeight()).isEqualTo(509);
        BufferedImage canvas = templateRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "L(large)");
        assertThat(canvas.getWidth()).isEqualTo(1440);
        assertThat(canvas.getHeight()).isEqualTo(940);
    }

    @Test
    void outputScaleRendersDirectlyAtFinalResolutionWithTheSameLayout() {
        assertThat(RenderSupport.outputHeight(1440, 2000)).isEqualTo(1083);
        BufferedImage base = sampleBaseImage();
        SizeDimensionsDto dimensions = new SizeDimensionsDto("30", "14", "22");
        PhotoSizeGuideRenderer.PhotoRender canvas = photoRenderer.render(SizeGuideTemplate.TOTE, dimensions, "FREE", base, null);
        PhotoSizeGuideRenderer.PhotoRender output = photoRenderer.render(
                SizeGuideTemplate.TOTE, dimensions, "FREE", base, null, RenderSupport.OUTPUT_SCALE);

        // 배율이 달라도 레이아웃(비율 좌표)은 완전히 같아야 편집기 핸들과 저장본이 어긋나지 않는다.
        assertThat(output.layout()).isEqualTo(canvas.layout());
        assertThat(output.image().getWidth()).isEqualTo(780);
        assertThat(output.image().getHeight()).isEqualTo(509);
        // 표 상단 굵은 선(1440 기준 y=656)이 출력 좌표(y≈355)에 그려진다 = 변환이 글자·선 전체에 적용됐다.
        int ruleY = (int) Math.round(656 * RenderSupport.OUTPUT_SCALE);
        assertThat(new Color(output.image().getRGB(390, ruleY)).getRed()).isLessThan(140);
        assertThat(output.image().getRGB(390, ruleY - 12)).isEqualTo(Color.WHITE.getRGB());
    }

    @ParameterizedTest
    @EnumSource(SizeGuideTemplate.class)
    void categorySizeImagesRenderWithoutAnyRetiredIllustrations(SizeGuideTemplate template) {
        BufferedImage image = templateRenderer.render(template, new SizeDimensionsDto("30", "14", "22"), "FREE");
        BufferedImage white = new BufferedImage(1440, 250, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = white.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1440, 250);
        graphics.dispose();
        assertThat(pixels(image.getSubimage(0, 0, 1440, 250))).isEqualTo(pixels(white));
        assertThat(getClass().getResource("/static/image/" + template.assetBaseName() + ".jpg")).isNull();
        assertThat(getClass().getResource("/static/image/" + template.assetBaseName() + ".png")).isNull();
    }

    @Test
    void beltTemplateDrawsSharedStrapSampleWithLengthAndStrapWidthOnly() {
        BufferedImage image = templateRenderer.render(
                SizeGuideTemplate.BELT, new SizeDimensionsDto("122", "-", "3"), "FREE");

        // 상단 250px 은 흰 여백, 그 아래 스트랩 밴드에는 무채색(R=G=B) 회색 픽셀이 있어야 한다.
        assertThat(pixels(image.getSubimage(0, 0, 1440, 250))).containsOnly(Color.WHITE.getRGB());
        Color strap = new Color(image.getRGB(700, 380));
        assertThat(strap.getRed()).isEqualTo(strap.getGreen()).isEqualTo(strap.getBlue());
        assertThat(strap.getRed()).isLessThan(200);
        assertThat(image.getRGB(700, 300)).isEqualTo(Color.WHITE.getRGB());
        // 표는 2열(총길이·너비)이라 3열 구분선 자리(가로 3분할)가 아니라 2분할 자리에 세로선이 있다.
        int tableY = 580;
        int tableX = 180;
        int firstColumnWidth = 270;
        int valueColumnWidth = (1440 - tableX * 2 - firstColumnWidth) / 2;
        assertThat(image.getRGB(tableX + firstColumnWidth + valueColumnWidth, tableY + 50))
                .isNotEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void beltPhotoRenderSeedsTwoLinesOnly() {
        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.BELT, new SizeDimensionsDto("122", "-", "3"), "FREE", sampleBaseImage(), null);
        assertThat(render.layout().width()).isNotNull();
        assertThat(render.layout().height()).isNotNull();
        assertThat(render.layout().depth()).isNull();
        assertThat(render.image().getWidth()).isEqualTo(1440);
    }

    @Test
    void photoRenderSeedsDefaultLayoutWithinCanvasRatios() {
        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "L(large)",
                sampleBaseImage(), null);

        SizeGuideLayoutDto layout = render.layout();
        assertThat(layout).isNotNull();
        assertThat(layout.photo().scale()).isEqualTo(1.0);
        for (SizeGuideLayoutDto.ArrowPlacement arrow : new SizeGuideLayoutDto.ArrowPlacement[] {
                layout.width(), layout.height(), layout.depth() }) {
            assertThat(arrow).isNotNull();
            assertThat(arrow.startXRatio()).isBetween(0.0, 1.0);
            assertThat(arrow.startYRatio()).isBetween(0.0, 1.0);
            assertThat(arrow.endXRatio()).isBetween(0.0, 1.0);
            assertThat(arrow.endYRatio()).isBetween(0.0, 1.0);
        }
        assertThat(render.image().getWidth()).isEqualTo(1440);
        assertThat(render.image().getHeight()).isEqualTo(940);
    }

    @Test
    void photoRenderOmitsDepthArrowForTwoDimensionTemplates() {
        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.CARD_WALLET, new SizeDimensionsDto("7.6", "-", "11.5"), "FREE",
                sampleBaseImage(), null);

        assertThat(render.layout().depth()).isNull();
    }

    @Test
    void photoRenderUsesKoreanTableHeadersForTwoAndThreeDimensions() {
        for (boolean twoDimensions : new boolean[] { false, true }) {
            PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                    twoDimensions ? SizeGuideTemplate.CARD_WALLET : SizeGuideTemplate.SHOULDER_CROSS,
                    new SizeDimensionsDto("34", twoDimensions ? "-" : "15", "24"), "FREE",
                    sampleBaseImage(), null);
            String[] headers = twoDimensions
                    ? new String[] { "사이즈", "가로", "높이" }
                    : new String[] { "사이즈", "가로", "폭", "높이" };
            BufferedImage expectedHeader = new BufferedImage(1080, 64, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = expectedHeader.createGraphics();
            RenderSupport.applyQualityHints(graphics);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1080, 64);
            int columnWidth = 1080 / headers.length;
            for (int index = 0; index < headers.length; index++) {
                RenderSupport.drawCenteredString(graphics, headers[index],
                        columnWidth * index + columnWidth / 2, 44,
                        fontResolver.photoTextFont(Font.PLAIN, 30), new Color(110, 110, 110));
            }
            graphics.dispose();

            assertThat(pixels(render.image().getSubimage(180, 660, 1080, 64)))
                    .as("Korean headers for %s columns", headers.length)
                    .isEqualTo(pixels(expectedHeader));
        }
    }

    @Test
    void photoRenderRoundTripsDefaultLayoutToIdenticalPixels() {
        BufferedImage base = sampleBaseImage();
        SizeDimensionsDto dimensions = new SizeDimensionsDto("34", "15", "24");

        PhotoSizeGuideRenderer.PhotoRender first = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, dimensions, "L(large)", base, null);
        PhotoSizeGuideRenderer.PhotoRender second = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, dimensions, "L(large)", base, first.layout());

        assertThat(pixels(second.image())).isEqualTo(pixels(first.image()));
    }

    @Test
    void photoRenderPlacesDefaultMeasurementsOutsideDetectedSubject() {
        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "L(large)",
                sampleBaseImage(), null);

        SizeGuideLayoutDto layout = render.layout();
        assertThat(layout.height().startXRatio()).isLessThan(layout.width().startXRatio());
        assertThat(layout.width().startYRatio()).isGreaterThan(layout.height().endYRatio());
        assertThat(layout.depth().startXRatio()).isGreaterThan(layout.width().endXRatio());
        assertThat(layout.depth().endYRatio()).isLessThan(layout.depth().startYRatio());
    }

    @ParameterizedTest
    @EnumSource(value = SizeGuideTemplate.class, names = { "CARD_WALLET", "BELT" }, mode = EnumSource.Mode.EXCLUDE)
    void defaultDepthLineUses45DegreesForEveryThreeDimensionTemplate(SizeGuideTemplate template) {
        for (int[] size : new int[][] { { 600, 750 }, { 1000, 300 }, { 300, 1000 } }) {
            BufferedImage base = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = base.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, size[0], size[1]);
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(size[0] / 10, size[1] / 10, size[0] * 4 / 5, size[1] * 4 / 5);
            graphics.dispose();

            SizeGuideLayoutDto layout = photoRenderer.render(
                    template, new SizeDimensionsDto("30", "14", "22"), "FREE", base, null).layout();

            assert45DegreeDepthWithinCanvas(layout.depth());
            assertThat(layout.width().startYRatio()).isEqualTo(layout.width().endYRatio());
            assertThat(layout.height().startXRatio()).isEqualTo(layout.height().endXRatio());
        }
    }

    @Test
    void default45DegreeDepthLineStaysInsideCanvasForSmallSubjectNearTop() {
        BufferedImage base = new BufferedImage(600, 750, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = base.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 750);
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(260, 25, 80, 80);
        graphics.dispose();

        SizeGuideLayoutDto layout = photoRenderer.render(
                SizeGuideTemplate.TOTE, new SizeDimensionsDto("30", "14", "22"), "FREE", base, null).layout();

        assert45DegreeDepthWithinCanvas(layout.depth());
    }

    @Test
    void manualDepthLineAngleIsPreservedInsteadOfResetToDefaultAngle() {
        BufferedImage base = sampleBaseImage();
        SizeDimensionsDto dimensions = new SizeDimensionsDto("30", "14", "22");
        SizeGuideLayoutDto initial = photoRenderer.render(SizeGuideTemplate.TOTE, dimensions, "FREE", base, null).layout();
        SizeGuideLayoutDto.ArrowPlacement manualDepth = new SizeGuideLayoutDto.ArrowPlacement(
                0.70, 0.60, 0.90, 0.54, 0.80, 0.57);
        SizeGuideLayoutDto manual = new SizeGuideLayoutDto(initial.photo(), initial.width(), initial.height(), manualDepth);

        SizeGuideLayoutDto restored = photoRenderer.render(SizeGuideTemplate.TOTE, dimensions, "FREE", base, manual).layout();

        assertThat(restored).isEqualTo(manual);
    }

    private void assert45DegreeDepthWithinCanvas(SizeGuideLayoutDto.ArrowPlacement depth) {
        // 1440×940 캔버스의 서로 다른 가로/세로 비율을 실제 픽셀로 되돌려 각도를 검증한다.
        double run = (depth.endXRatio() - depth.startXRatio()) * RenderSupport.SIZE_IMAGE_WIDTH;
        double rise = (depth.startYRatio() - depth.endYRatio()) * RenderSupport.SIZE_IMAGE_HEIGHT;
        assertThat(run).isPositive();
        assertThat(rise).isCloseTo(run, within(0.000001));
        assertThat(Math.toDegrees(Math.atan2(rise, run))).isCloseTo(45.0, within(0.000000001));
        assertThat(depth.startXRatio()).isBetween(0.0, 1.0);
        assertThat(depth.endXRatio()).isBetween(0.0, 1.0);
        assertThat(depth.startYRatio()).isBetween(0.0, 1.0);
        assertThat(depth.endYRatio()).isBetween(
                RenderSupport.SIZE_PHOTO_CLIP_TOP / (double) RenderSupport.SIZE_IMAGE_HEIGHT, 1.0);
    }

    @Test
    void photoRenderIgnoresLegacyManualValuePositions() {
        BufferedImage base = sampleBaseImage();
        SizeDimensionsDto dimensions = new SizeDimensionsDto("34", "15", "24");
        PhotoSizeGuideRenderer.PhotoRender first = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, dimensions, "L(large)", base, null);
        SizeGuideLayoutDto original = first.layout();
        SizeGuideLayoutDto.ArrowPlacement width = original.width();
        SizeGuideLayoutDto layoutWithStaleLabelPosition = new SizeGuideLayoutDto(
                original.photo(),
                new SizeGuideLayoutDto.ArrowPlacement(
                        width.startXRatio(), width.startYRatio(), width.endXRatio(), width.endYRatio(), 0.02, 0.02),
                original.height(), original.depth());

        PhotoSizeGuideRenderer.PhotoRender second = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, dimensions, "L(large)", base, layoutWithStaleLabelPosition);

        assertThat(pixels(second.image())).isEqualTo(pixels(first.image()));
    }

    @Test
    void measurementValuesSitAcrossTheLineFromTheProductWithAGap() {
        PhotoSizeGuideRenderer.MeasurementValuePosition below =
                PhotoSizeGuideRenderer.calculateMeasurementValuePosition(
                        100, 200, 300, 200, 200, 100, 60, 40, 20);
        PhotoSizeGuideRenderer.MeasurementValuePosition left =
                PhotoSizeGuideRenderer.calculateMeasurementValuePosition(
                        100, 100, 100, 300, 200, 200, 60, 40, 20);
        PhotoSizeGuideRenderer.MeasurementValuePosition diagonalOutside =
                PhotoSizeGuideRenderer.calculateMeasurementValuePosition(
                        100, 300, 300, 100, 100, 100, 60, 40, 20);

        assertThat(below.centerX()).isEqualTo(200);
        assertThat(below.centerY()).isEqualTo(240);
        assertThat(left.centerX()).isEqualTo(50);
        assertThat(left.centerY()).isEqualTo(200);
        assertThat(diagonalOutside.centerX()).isGreaterThan(200);
        assertThat(diagonalOutside.centerY()).isGreaterThan(200);
    }

    @Test
    void displaySizeLabelNormalizesPlaceholdersToFree() {
        assertThat(RenderSupport.displaySizeLabel("OS(one size)")).isEqualTo("FREE");
        assertThat(RenderSupport.displaySizeLabel("FREE(free)")).isEqualTo("FREE");
        assertThat(RenderSupport.displaySizeLabel(null)).isEqualTo("FREE");
        assertThat(RenderSupport.displaySizeLabel("  ")).isEqualTo("FREE");
        assertThat(RenderSupport.displaySizeLabel("L(large)")).isEqualTo("L(large)");
        assertThat(RenderSupport.displaySizeLabel("MINI(mini)")).isEqualTo("MINI(mini)");
    }

    @Test
    void photoRenderUsesWhiteBackground() {
        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "L(large)",
                sampleBaseImage(), null);

        int corner = render.image().getRGB(2, 2) & 0xFFFFFF;
        assertThat(corner).isEqualTo(0xFFFFFF);
    }

    @Test
    void photoRenderLeavesRemovedTitleAreaBlank() {
        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "L(large)",
                sampleBaseImage(), null);

        boolean blank = true;
        outer:
        for (int y = 0; y < RenderSupport.SIZE_PHOTO_CLIP_TOP; y++) {
            for (int x = 0; x < render.image().getWidth(); x++) {
                if ((render.image().getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    blank = false;
                    break outer;
                }
            }
        }
        assertThat(blank).isTrue();
    }

    @Test
    void photoRenderBlendsWhiteProductBackgroundIntoWhiteCanvas() {
        BufferedImage whiteProductPhoto = new BufferedImage(600, 750, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = whiteProductPhoto.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, whiteProductPhoto.getWidth(), whiteProductPhoto.getHeight());
        graphics.dispose();

        PhotoSizeGuideRenderer.PhotoRender render = photoRenderer.render(
                SizeGuideTemplate.SHOULDER_CROSS, new SizeDimensionsDto("34", "15", "24"), "FREE",
                whiteProductPhoto, null);

        assertThat(render.image().getRGB(RenderSupport.SIZE_IMAGE_WIDTH / 2, 300) & 0xFFFFFF)
                .isEqualTo(0xFFFFFF);
    }

    private BufferedImage sampleBaseImage() {
        BufferedImage image = new BufferedImage(600, 750, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.LIGHT_GRAY);
        graphics.fillRect(0, 0, 600, 750);
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(120, 150, 360, 450);
        graphics.dispose();
        return image;
    }

    private int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
