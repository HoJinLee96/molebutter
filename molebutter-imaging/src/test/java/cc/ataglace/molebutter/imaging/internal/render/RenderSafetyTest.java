package cc.ataglace.molebutter.imaging.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.SizeDimensionsDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideLayoutDto;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuidePolicy.SizeGuideTemplate;

class RenderSafetyTest {
    private final PhotoSizeGuideRenderer renderer = new PhotoSizeGuideRenderer(
            new FontResolver("SansSerif", "SansSerif", "Dialog", "SansSerif"));
    private final SizeDimensionsDto dimensions = new SizeDimensionsDto("30", "14", "22");
    private final BufferedImage source = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);

    @Test
    void rejectsInvalidScaleAndOversizedCanvasesBeforeAllocation() {
        for (double scale : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 10 }) {
            assertInvalid(() -> RenderSupport.newScaledCanvas(1440, 940, scale));
        }
        assertInvalid(() -> RenderSupport.newScaledCanvas(8193, 1, 1));
        assertInvalid(() -> RenderSupport.newScaledCanvas(5000, 5000, 1));
        assertInvalid(() -> RenderSupport.newScaledCanvas(0, 100, 1));
        assertThat(RenderSupport.newScaledCanvas(8192, 1, 1).getWidth()).isEqualTo(8192);
    }

    @Test
    void rejectsUnboundedOrNonFinitePhotoPlacement() {
        for (double scale : new double[] { 0, 0.29, 2.51, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertInvalidLayout(new SizeGuideLayoutDto(
                    new SizeGuideLayoutDto.PhotoPlacement(scale, 0.5, 0.3), null, null, null));
        }
        for (double coordinate : new double[] { -0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertInvalidLayout(new SizeGuideLayoutDto(
                    new SizeGuideLayoutDto.PhotoPlacement(1, coordinate, 0.3), null, null, null));
            assertInvalidLayout(new SizeGuideLayoutDto(
                    new SizeGuideLayoutDto.PhotoPlacement(1, 0.5, 0.3),
                    new SizeGuideLayoutDto.ArrowPlacement(0.2, 0.3, coordinate, 0.3, 0.5, 0.3), null, null));
        }
        assertInvalidLayout(new SizeGuideLayoutDto(null, null, null, null));
    }

    @Test
    void permitsBothEditorScaleBoundariesAndAbsentMeasurementLines() {
        for (double scale : new double[] { 0.3, 2.5 }) {
            SizeGuideLayoutDto layout = new SizeGuideLayoutDto(
                    new SizeGuideLayoutDto.PhotoPlacement(scale, 0.5, 0.3), null, null, null);
            assertThat(renderer.render(SizeGuideTemplate.TOTE, dimensions, "FREE", source, layout,
                    RenderSupport.OUTPUT_SCALE).layout()).isEqualTo(layout);
        }
    }

    @Test
    void rejectsOversizedSourceAndDimensionText() {
        assertInvalid(() -> renderer.render(SizeGuideTemplate.TOTE, dimensions, "FREE",
                new BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB), null));
        assertInvalid(() -> renderer.render(SizeGuideTemplate.TOTE,
                new SizeDimensionsDto("1".repeat(8193), "14", "22"), "FREE", source, null));
    }

    private void assertInvalidLayout(SizeGuideLayoutDto layout) {
        assertInvalid(() -> renderer.render(SizeGuideTemplate.TOTE, dimensions, "FREE", source, layout));
    }

    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ImagingFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(ImagingFailure.Kind.INVALID_INPUT));
    }
}
