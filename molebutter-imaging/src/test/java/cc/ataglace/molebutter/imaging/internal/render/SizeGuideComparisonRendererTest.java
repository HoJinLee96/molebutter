package cc.ataglace.molebutter.imaging.internal.render;

import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuideComparisons;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuideComparisonAssets;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SizeGuideComparisonRendererTest {
    private final FontResolver fonts = new FontResolver("Apple SD Gothic Neo", "Nanum Gothic", "Avenir Next", "AppleGothic");
    private final PhotoSizeGuideRenderer renderer = new PhotoSizeGuideRenderer(fonts);
    private static final SizeDimensionsDto DIMENSIONS = new SizeDimensionsDto("30", "14", "10");
    static List<SizeGuideComparisonDto> presets() { return SizeGuideComparisons.list(); }
    private static SizeGuideLayoutDto.ArrowPlacement arrow(double x1, double y1, double x2, double y2) {
        return new SizeGuideLayoutDto.ArrowPlacement(x1, y1, x2, y2, (x1+x2)/2, (y1+y2)/2);
    }
    private static SizeGuideLayoutDto layout(String id, double length, Double x, Double base) {
        return new SizeGuideLayoutDto(new SizeGuideLayoutDto.PhotoPlacement(1, .35, .32),
                arrow(.1, .6, .6, .6), arrow(.62, 200.0/940, .62, (200+length)/940), null,
                id == null ? null : new SizeGuideLayoutDto.ComparisonPlacement(id, SizeGuideComparisons.list().stream()
                        .filter(preset -> preset.id().equals(id)).findFirst().orElseThrow().version(), x, base));
    }
    private static BufferedImage photo() {
        BufferedImage image = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 640, 400);
        g.setColor(new Color(100, 80, 63)); g.fillRoundRect(140, 100, 350, 250, 30, 30);
        g.setStroke(new BasicStroke(15)); g.drawArc(240, 20, 150, 180, 0, 180); g.dispose(); return image;
    }
    @Test void userProvidedBottleHeightCalibratesOneHundredAndTwoHundredPixelsWithoutInventingMeasuredWidth() {
        var one = SizeGuideComparisonRenderer.geometry(layout("samdasoo-500", 100, .8, .5), DIMENSIONS);
        var two = SizeGuideComparisonRenderer.geometry(layout("samdasoo-500", 200, .8, .5), DIMENSIONS);
        assertThat(one.bounds().height).isCloseTo(210, within(1e-9));
        assertThat(two.bounds().height).isCloseTo(420, within(1e-9));
        assertThat(one.preset().widthCm()).isNull();
        assertThat(one.bounds().width / one.bounds().height).isEqualTo(SizeGuideComparisonAssets.bottleAspectRatio());
        assertThat(one.preset().version()).isEqualTo(2);
        assertThat(SizeGuideComparisons.resolve(new SizeGuideLayoutDto.ComparisonPlacement("samdasoo-500", 1, .8, .5)).aspectRatio()).isEqualTo(.30);
    }
    @Test void bottlePhotoHasTransparentBackgroundAndItsBodyRatherThanCanvasDefinesAspect() {
        var bottle = SizeGuideComparisonAssets.bottle();
        assertThat(bottle.getColorModel().hasAlpha()).isTrue();
        assertThat(bottle.getWidth()).isLessThan(bottle.getHeight());
        assertThat(bottle.getWidth() / (double) bottle.getHeight()).isBetween(.25, .35);
        assertThat(bottle.getRGB(0, 0) >>> 24).isLessThan(32);
        assertThat(bottle.getRGB(bottle.getWidth() / 2, bottle.getHeight() / 2) >>> 24).isGreaterThan(200);
    }
    @ParameterizedTest @MethodSource("presets")
    void latestPhotoCutoutsKeepCalibratedBodyDimensionsAndReadableLegacyPresets(SizeGuideComparisonDto preset) {
        assertThat(preset.version()).isEqualTo(2);
        var image = SizeGuideComparisonAssets.image(preset.id());
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(image.getWidth()).isGreaterThan(100);
        assertThat(image.getHeight()).isGreaterThan(100);
        assertThat(image.getRGB(image.getWidth() / 2, image.getHeight() / 2) >>> 24).isGreaterThan(200);
        assertThat(image.getRGB(0, 0) >>> 24).isLessThan(32);
        // Cutout padding does not change the official calibrated device body dimensions.
        assertThat(image.getWidth() / (double) image.getHeight() / preset.aspectRatio()).isCloseTo(1, within(.035));
        var legacy = SizeGuideComparisons.resolve(new SizeGuideLayoutDto.ComparisonPlacement(preset.id(), 1, .8, .5));
        assertThat(legacy.heightCm()).isEqualTo(preset.heightCm());
        assertThat(legacy.widthCm()).isEqualTo(preset.widthCm());
        if (!preset.kind().equals("BOTTLE")) {
            assertThat(legacy.aspectRatio()).isEqualTo(preset.aspectRatio());
            var latestLayout = layout(preset.id(), 100, .8, .5);
            var legacyLayout = new SizeGuideLayoutDto(latestLayout.photo(), latestLayout.width(), latestLayout.height(), latestLayout.depth(),
                    new SizeGuideLayoutDto.ComparisonPlacement(preset.id(), 1, .8, .5));
            assertThat(RenderSupport.encodePng(renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), legacyLayout).image()))
                    .isEqualTo(RenderSupport.encodePng(renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), latestLayout).image()));
        }
    }
    @ParameterizedTest @MethodSource("presets")
    void coreNameStaysInsideBodyWithoutTakingAdditionalSceneSpace(SizeGuideComparisonDto preset) {
        var g = new BufferedImage(1440, 940, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            for (double height : new double[]{.5, 100, 420}) {
                var body = new java.awt.geom.Rectangle2D.Double(300, 50, height * preset.aspectRatio(), height);
                var caption = SizeGuideComparisonRenderer.caption(g, new SizeGuideComparisonRenderer.Geometry(preset, body),
                        SizeGuideSceneTransformDto.IDENTITY, fonts, List.of());
                assertThat(caption.text()).isEqualTo(preset.name().replaceFirst("\\s*\\([^)]*\\)\\s*$", ""));
                assertThat(caption.text()).doesNotContain("높이", "cm", "무라벨", "(M");
                assertThat(body.contains(caption.bounds())).isTrue();
                assertThat(String.join(" ", caption.lines())).isEqualTo(caption.text());
                SizeGuideComparisonRenderer.draw(g, new SizeGuideComparisonRenderer.Geometry(preset, body),
                        SizeGuideSceneTransformDto.IDENTITY, fonts, List.of());
            }
        } finally { g.dispose(); }
    }
    private static SizeGuideLayoutDto zoom(SizeGuideLayoutDto layout, double scale) {
        double factor = scale / layout.photo().scale();
        double cx = layout.photo().centerXRatio(), cy = layout.photo().centerYRatio();
        var placement = layout.comparison();
        return new SizeGuideLayoutDto(new SizeGuideLayoutDto.PhotoPlacement(scale, cx, cy),
                zoom(layout.width(), factor, cx, cy), zoom(layout.height(), factor, cx, cy), zoom(layout.depth(), factor, cx, cy),
                placement == null ? null : new SizeGuideLayoutDto.ComparisonPlacement(placement.presetId(), placement.version(),
                        cx + (placement.centerXRatio() - cx) * factor, cy + (placement.baseYRatio() - cy) * factor), true);
    }
    private static SizeGuideLayoutDto.ArrowPlacement zoom(SizeGuideLayoutDto.ArrowPlacement a, double f, double x, double y) {
        return a == null ? null : new SizeGuideLayoutDto.ArrowPlacement(x+(a.startXRatio()-x)*f, y+(a.startYRatio()-y)*f,
                x+(a.endXRatio()-x)*f, y+(a.endYRatio()-y)*f, x+(a.labelXRatio()-x)*f, y+(a.labelYRatio()-y)*f);
    }
    private static int brownPhotoPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < 600; y++) for (int x = 0; x < image.getWidth(); x++) {
            var color = new Color(image.getRGB(x, y));
            if (color.getRed() > 90 && color.getRed() < 210 && color.getGreen() < color.getRed() - 5
                    && color.getBlue() < color.getGreen() - 5) count++;
        }
        return count;
    }
    @ParameterizedTest @MethodSource("presets")
    void zoomChangesActualProductPixelsInsteadOfBeingCancelledByAutoFit(SizeGuideComparisonDto preset) {
        var seed = new SizeGuideLayoutDto(new SizeGuideLayoutDto.PhotoPlacement(1, .5, .4),
                arrow(.35, .55, .65, .55), arrow(.3, .3, .3, .4), null,
                new SizeGuideLayoutDto.ComparisonPlacement(preset.id(), preset.version(), null, null));
        var base = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), seed).layout();
        double maximum = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), base).photoView().maximumScale();
        var small = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), zoom(base, maximum * .4));
        var large = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), zoom(base, maximum * .8));
        assertThat(small.sceneTransform()).isEqualTo(SizeGuideSceneTransformDto.IDENTITY);
        assertThat(large.sceneTransform()).isEqualTo(SizeGuideSceneTransformDto.IDENTITY);
        assertThat(small.layout().photo().scale()).isCloseTo(maximum * .4, within(1e-8));
        assertThat(large.layout().photo().scale()).isCloseTo(maximum * .8, within(1e-8));
        assertThat(brownPhotoPixels(large.image())).isGreaterThan((int) (brownPhotoPixels(small.image()) * 3.5));
        double smallLine = small.layout().height().endYRatio() - small.layout().height().startYRatio();
        double largeLine = large.layout().height().endYRatio() - large.layout().height().startYRatio();
        assertThat(largeLine).isCloseTo(smallLine * 2, within(1e-9));
        assertThat(SizeGuideComparisonRenderer.geometry(large.layout(), DIMENSIONS).bounds().height)
                .isCloseTo(SizeGuideComparisonRenderer.geometry(small.layout(), DIMENSIONS).bounds().height * 2, within(1e-6));
    }
    @ParameterizedTest @MethodSource("presets")
    void everyFixedBodyKeepsAspectAndCommonFitAtBothCanvasAndExportResolutions(SizeGuideComparisonDto preset) {
        var input = layout(preset.id(), 400, null, null);
        var canvas = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), input);
        var export = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), input, RenderSupport.PHOTO_OUTPUT_SCALE);
        assertThat(canvas.layout()).isEqualTo(export.layout());
        assertThat(canvas.sceneTransform()).isEqualTo(export.sceneTransform());
        assertThat(export.image().getWidth()).isEqualTo(2880);
        assertThat(export.image().getHeight()).isEqualTo(1880);
        var geometry = SizeGuideComparisonRenderer.geometry(canvas.layout(), DIMENSIONS);
        var t = canvas.sceneTransform();
        assertThat(t.scale()).isPositive().isLessThanOrEqualTo(1);
        assertThat(geometry.bounds().width / geometry.bounds().height).isCloseTo(preset.aspectRatio(), within(1e-12));
        assertThat(geometry.bounds().getMinX() * t.scale() + t.offsetXRatio() * 1440).isGreaterThanOrEqualTo(31.98);
        assertThat(geometry.bounds().getMaxX() * t.scale() + t.offsetXRatio() * 1440).isLessThanOrEqualTo(1408.02);
        assertThat(geometry.bounds().getMinY() * t.scale() + t.offsetYRatio() * 940).isGreaterThanOrEqualTo(31.98);
        assertThat(geometry.bounds().getMaxY() * t.scale() + t.offsetYRatio() * 940).isLessThanOrEqualTo(586.02);
        assertThat(canvas.layout().comparison().baseYRatio()).isEqualTo(canvas.layout().height().endYRatio());
        // The size table stays outside the scene camera.
        assertThat(new Color(canvas.image().getRGB(720, 656)).getRed()).isLessThan(220);
    }
    @Test void comparisonNormalizesLegacyTiltedHeightWithoutChangingItsVerticalSpan() {
        var legacy = layout(null, 100, null, null);
        legacy = new SizeGuideLayoutDto(legacy.photo(), legacy.width(), arrow(.4, .2, .6, .5), legacy.depth(),
                new SizeGuideLayoutDto.ComparisonPlacement("iphone-16", 1, .85, .5));
        var rendered = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), legacy);
        assertThat(rendered.layout().height().startXRatio()).isEqualTo(rendered.layout().height().endXRatio());
        assertThat(rendered.layout().height().endYRatio() - rendered.layout().height().startYRatio())
                .isCloseTo(.3 * rendered.layout().photo().scale(), within(1e-9));
    }
    @Test void noComparisonUsesIdentityCameraAndRetainsLegacyLayout() {
        var layout = layout(null, 100, null, null);
        var rendered = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), layout);
        assertThat(rendered.layout()).isEqualTo(layout);
        assertThat(rendered.sceneTransform()).isEqualTo(SizeGuideSceneTransformDto.IDENTITY);
    }
    @Test void switchingComparisonKeepsActualProductScaleWhenVisibleBodiesFitDespiteLargeWhitePhotoPadding() {
        var source = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 2000, 2000);
        g.setColor(new Color(100, 80, 63)); g.fillRect(100, 200, 300, 240); g.dispose();
        var dimensions = new SizeDimensionsDto("11", "1.5", "9");
        var original = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, dimensions, "FREE", source, null);
        var larger = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, dimensions, "FREE", source, zoom(original.layout(), 2));
        var l = larger.layout();
        var compared = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, dimensions, "FREE", source,
                new SizeGuideLayoutDto(l.photo(), l.width(), l.height(), l.depth(),
                        new SizeGuideLayoutDto.ComparisonPlacement("samdasoo-500", 2, null, null), true));
        assertThat(compared.layout().photo().scale()).isCloseTo(2, within(1e-9));
        assertThat(brownPhotoPixels(compared.image())).isCloseTo(brownPhotoPixels(larger.image()), within(30));
        assertThat(compared.sceneTransform()).isEqualTo(SizeGuideSceneTransformDto.IDENTITY);
        assertThat(Math.abs(compared.photoView().subjectOffsetXRatio())).isGreaterThan(.05);
        assertThat(compared.layout().photo().centerXRatio() + compared.photoView().subjectOffsetXRatio()).isBetween(0.0, 1.0);
        var c = compared.layout();
        var removed = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, dimensions, "FREE", source,
                new SizeGuideLayoutDto(c.photo(), c.width(), c.height(), c.depth(), null, true));
        assertThat(removed.layout().photo().scale()).isEqualTo(c.photo().scale());
        assertThat(brownPhotoPixels(removed.image())).isCloseTo(brownPhotoPixels(compared.image()), within(30));
    }
    @Test void requiredFitIsBakedIntoPhotoScaleAndStableOnReentryWithNoUnnecessaryHeadroom() {
        var first = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), layout("samdasoo-500", 400, null, null));
        assertThat(first.layout().photo().scale()).isLessThan(1);
        assertThat(first.sceneTransform()).isEqualTo(SizeGuideSceneTransformDto.IDENTITY);
        assertThat(first.photoView().maximumScale()).isCloseTo(first.layout().photo().scale(), within(.005));
        var restored = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), first.layout());
        assertThat(restored.layout()).isEqualTo(first.layout());
        assertThat(RenderSupport.encodePng(restored.image())).isEqualTo(RenderSupport.encodePng(first.image()));
    }
    @Test void missingHeightZeroSpanBadPresetVersionAndCoordinatesAreRejected() {
        for (String height : new String[]{"-", "0", "NaN", "Infinity", "-10"}) {
            assertThatThrownBy(() -> renderer.render(SizeGuideTemplate.TOTE, new SizeDimensionsDto("30", "14", height),
                    "FREE", photo(), layout("samdasoo-500", 100, .8, .5))).isInstanceOf(ImagingFailure.class);
        }
        assertThatThrownBy(() -> renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(),
                layout("samdasoo-500", 0, .8, .5))).hasMessageContaining("높이");
        var normal = layout(null, 100, null, null);
        for (var placement : List.of(new SizeGuideLayoutDto.ComparisonPlacement("unknown", 1, .8, .5),
                new SizeGuideLayoutDto.ComparisonPlacement("iphone-16", 99, .8, .5),
                new SizeGuideLayoutDto.ComparisonPlacement("iphone-16", 1, Double.NaN, .5),
                new SizeGuideLayoutDto.ComparisonPlacement("iphone-16", 1, .8, null))) {
            assertThatThrownBy(() -> renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(),
                    new SizeGuideLayoutDto(normal.photo(), normal.width(), normal.height(), null, placement)))
                    .isInstanceOf(ImagingFailure.class);
        }
    }
    private SizeGuideLayoutDto multipleLayout() {
        var base = layout(null, 100, null, null);
        return new SizeGuideLayoutDto(base.photo(), base.width(), base.height(), base.depth(), null, false,
                presets().stream().map(p -> new SizeGuideLayoutDto.ComparisonPlacement(p.id(), p.version(), null, null)).toList());
    }
    @Test void eightObjectsKeepPhysicalRatiosSeparatePlacementAndStableHighResolutionRestoration() {
        var result = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), multipleLayout(), RenderSupport.PHOTO_OUTPUT_SCALE);
        assertThat(result.layout().comparisons()).hasSize(8);
        assertThat(result.layout().comparison()).isEqualTo(result.layout().comparisons().getFirst());
        double pixelsPerCm = (result.layout().height().endYRatio() - result.layout().height().startYRatio()) * 940 / 10;
        java.awt.geom.Rectangle2D previous = null;
        for (var placement : result.layout().comparisons()) {
            var body = SizeGuideComparisonRenderer.geometry(result.layout(), DIMENSIONS, placement);
            assertThat(body.bounds().height).isCloseTo(body.preset().heightCm() * pixelsPerCm, within(1e-8));
            assertThat(body.bounds().getMinX()).isGreaterThanOrEqualTo(31.98);
            assertThat(body.bounds().getMaxX()).isLessThanOrEqualTo(1408.02);
            assertThat(body.bounds().getMinY()).isGreaterThanOrEqualTo(31.98);
            assertThat(body.bounds().getMaxY()).isLessThanOrEqualTo(586.02);
            if (previous != null) assertThat(body.bounds().getMinX()).isGreaterThan(previous.getMaxX());
            previous = body.bounds();
        }
        var restored = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), result.layout(), RenderSupport.PHOTO_OUTPUT_SCALE);
        assertThat(restored.layout()).isEqualTo(result.layout());
        assertThat(RenderSupport.encodePng(restored.image())).isEqualTo(RenderSupport.encodePng(result.image()));
        assertThat(result.image().getWidth()).isEqualTo(2880);
        assertThat(result.image().getHeight()).isEqualTo(1880);
    }
    @Test void multipleSelectionRejectsDuplicateNullUnknownAndOverflowRatherThanSilentlyLosingObjects() {
        var first = multipleLayout().comparisons().getFirst();
        for (var placements : List.of(List.of(first, first), Arrays.asList(first, null),
                List.of(first, new SizeGuideLayoutDto.ComparisonPlacement("unknown", 2, null, null)),
                Collections.nCopies(9, first))) {
            var base = multipleLayout();
            var input = new SizeGuideLayoutDto(base.photo(), base.width(), base.height(), null, null, false, placements);
            assertThatThrownBy(() -> renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), input))
                    .isInstanceOf(ImagingFailure.class);
        }
    }
    @Test void snapshotsReadSingleLegacySelectionsAndExplicitArrayWinsIncludingEmpty() throws Exception {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var legacy = "{\"outputSpace\":false,\"comparison\":{\"presetId\":\"iphone-16\",\"version\":1,\"centerXRatio\":0.8,\"baseYRatio\":0.5}}";
        var old = mapper.readValue(legacy, SizeGuideLayoutDto.class);
        assertThat(old.comparisons()).containsExactly(old.comparison());
        var empty = mapper.readValue(legacy.substring(0, legacy.length()-1) + ",\"comparisons\":[]}", SizeGuideLayoutDto.class);
        assertThat(empty.comparisons()).isEmpty(); assertThat(empty.comparison()).isNull();
        var multiple = multipleLayout();
        assertThat(mapper.readValue(mapper.writeValueAsString(multiple), SizeGuideLayoutDto.class)).isEqualTo(multiple);
    }
    @Test void progressiveDownsamplingAveragesFineDetailAndKeepsTransparentEdgesClean() {
        var original = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<1024; y++) for(int x=0; x<1024; x++)
            original.setRGB(x,y, ((x+y)%2==0 ? Color.BLACK : Color.WHITE).getRGB());
        var resized = RenderSupport.resampleImage(original, 30, 30);
        for (int y=2; y<28; y++) for (int x=2; x<28; x++)
            assertThat(new Color(resized.getRGB(x,y)).getRed()).isBetween(120, 135);
        assertThat(original.getRGB(0,0)).isEqualTo(Color.BLACK.getRGB());
        var cutout = new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);
        var g=cutout.createGraphics(); g.setColor(Color.RED); g.fillOval(32,32,192,192); g.dispose();
        var small=RenderSupport.resampleImage(cutout,31,31);
        boolean edge=false;
        for(int y=0;y<31;y++) for(int x=0;x<31;x++) {
            var color=new Color(small.getRGB(x,y),true);
            if(color.getAlpha()>10 && color.getAlpha()<240) {
                edge=true; assertThat(color.getRed()).isGreaterThan(245); assertThat(color.getBlue()).isZero();
            }
        }
        assertThat(edge).isTrue();
        assertThat(small.getRGB(0,0)>>>24).isZero();
    }
    @Test void buildsActualRendererFixturesForOfflineBrowserAndVisualChecks() throws Exception {
        var samples = new LinkedHashMap<String, SizeGuidePreviewResponseDto>();
        var entries = new ArrayList<>(presets());
        entries.add(new SizeGuideComparisonDto("none", 1, "없음", null, 1, 1, "NONE"));
        var defaultLayout = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), null).layout();
        for (var preset : entries) {
            var input = new SizeGuideLayoutDto(defaultLayout.photo(), defaultLayout.width(), defaultLayout.height(), defaultLayout.depth(),
                    preset.id().equals("none") ? null : new SizeGuideLayoutDto.ComparisonPlacement(preset.id(), preset.version(), null, null));
            var rendered = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(),
                    input, RenderSupport.PHOTO_OUTPUT_SCALE);
            samples.put(preset.id(), new SizeGuidePreviewResponseDto("bag-tote", "토트백",
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(RenderSupport.encodePng(rendered.image())),
                    2880, 1880, rendered.layout(), rendered.sceneTransform(), rendered.photoView()));
        }
        Path directory = Path.of("../molebutter-app/target/size-comparison-check/comparison-fixtures");
        Files.createDirectories(directory);
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var zooms = new LinkedHashMap<String, SizeGuidePreviewResponseDto>();
        for (double scale : new double[]{.2, .4}) {
            var rendered = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), zoom(samples.get("samdasoo-500").layout(), scale), RenderSupport.PHOTO_OUTPUT_SCALE);
            zooms.put(String.valueOf(scale), new SizeGuidePreviewResponseDto("bag-tote", "토트백",
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(RenderSupport.encodePng(rendered.image())),
                    2880, 1880, rendered.layout(), rendered.sceneTransform(), rendered.photoView()));
            Files.write(directory.resolve("zoom-" + scale + ".png"), RenderSupport.encodePng(rendered.image()));
        }
        var transitions = new LinkedHashMap<String, SizeGuidePreviewResponseDto>();
        var wallet = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB);
        var wg = wallet.createGraphics(); wg.setColor(Color.WHITE); wg.fillRect(0, 0, 2000, 2000);
        wg.setColor(new Color(100, 80, 63)); wg.fillRect(100, 200, 300, 240); wg.dispose();
        var walletDimensions = new SizeDimensionsDto("11", "1.5", "9");
        var seed = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, walletDimensions, "FREE", wallet, null);
        var without = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, walletDimensions, "FREE", wallet, zoom(seed.layout(), 2), RenderSupport.PHOTO_OUTPUT_SCALE);
        var l = without.layout();
        var with = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, walletDimensions, "FREE", wallet,
                new SizeGuideLayoutDto(l.photo(), l.width(), l.height(), l.depth(), new SizeGuideLayoutDto.ComparisonPlacement("samdasoo-500", 2, null, null), true), RenderSupport.PHOTO_OUTPUT_SCALE);
        var c = with.layout();
        var removed = renderer.render(SizeGuideTemplate.WALLET_OPEN_HALF, walletDimensions, "FREE", wallet,
                new SizeGuideLayoutDto(c.photo(), c.width(), c.height(), c.depth(), null, true), RenderSupport.PHOTO_OUTPUT_SCALE);
        var views = List.of(without, with, removed);
        var names = List.of("without", "with", "removed");
        for (int i = 0; i < views.size(); i++) {
            var rendered = views.get(i);
            transitions.put(names.get(i), new SizeGuidePreviewResponseDto("wallet-open-half", "반지갑",
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(RenderSupport.encodePng(rendered.image())),
                    2880, 1880, rendered.layout(), rendered.sceneTransform(), rendered.photoView()));
            Files.write(directory.resolve("wallet-" + names.get(i) + ".png"), RenderSupport.encodePng(rendered.image()));
        }
        var m = multipleLayout();
        m = new SizeGuideLayoutDto(defaultLayout.photo(), defaultLayout.width(), defaultLayout.height(), defaultLayout.depth(), null, false, m.comparisons().subList(0,3));
        var multi = renderer.render(SizeGuideTemplate.TOTE, DIMENSIONS, "FREE", photo(), m, RenderSupport.PHOTO_OUTPUT_SCALE);
        var multiView = new SizeGuidePreviewResponseDto("bag-tote", "토트백",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(RenderSupport.encodePng(multi.image())),
                2880, 1880, multi.layout(), multi.sceneTransform(), multi.photoView());
        Files.write(directory.resolve("multi.png"), RenderSupport.encodePng(multi.image()));
        Files.writeString(directory.resolve("previews.json"), mapper.writeValueAsString(Map.of("presets", presets(), "samples", samples, "zooms", zooms, "transitions", transitions, "multi", multiView)));
        for (var entry : samples.entrySet()) Files.write(directory.resolve(entry.getKey() + ".png"),
                Base64.getDecoder().decode(entry.getValue().imageDataUrl().substring("data:image/png;base64,".length())));
    }
}
