package cc.ataglace.molebutter.imaging.internal.render;

import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuideComparisons;
import cc.ataglace.molebutter.imaging.internal.policy.SizeGuideComparisonAssets;
import java.awt.*;
import java.awt.geom.*;

/** Bundled cutout and vector bodies. Body bounds, not decorations or transparent padding, determine physical scale. */
final class SizeGuideComparisonRenderer {
    record Geometry(SizeGuideComparisonDto preset, Rectangle2D.Double bounds) { }
    static final double SCENE_WIDTH = 1376, SCENE_HEIGHT = 554;
    record Caption(String text, java.util.List<String> lines, Font font, Rectangle2D.Double bounds) { }

    static Geometry geometry(SizeGuideLayoutDto layout, SizeDimensionsDto dimensions) {
        return geometry(layout, dimensions, layout.comparison());
    }
    static Geometry geometry(SizeGuideLayoutDto layout, SizeDimensionsDto dimensions, SizeGuideLayoutDto.ComparisonPlacement placement) {
        var preset = SizeGuideComparisons.resolve(placement);
        var arrow = layout.height();
        double cm;
        try { cm = Double.parseDouble(dimensions.height().trim().replaceFirst("(?i)\\s*cm$", "").trim()); }
        catch (RuntimeException invalid) { throw invalidHeight(); }
        double length = arrow == null ? 0 : Math.abs(arrow.endYRatio() - arrow.startYRatio()) * RenderSupport.SIZE_IMAGE_HEIGHT;
        if (!Double.isFinite(cm) || cm <= 0 || cm > 10000 || !Double.isFinite(length) || length <= 0) throw invalidHeight();
        double height = length / cm * preset.heightCm();
        double width = height * preset.aspectRatio();
        if (!Double.isFinite(height) || !Double.isFinite(width)) throw invalidHeight();
        return new Geometry(preset, new Rectangle2D.Double(
                placement.centerXRatio() * RenderSupport.SIZE_IMAGE_WIDTH - width / 2,
                placement.baseYRatio() * RenderSupport.SIZE_IMAGE_HEIGHT - height, width, height));
    }

    private static ImagingFailure invalidHeight() {
        return new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "비교 대상의 크기를 맞추려면 상품 높이와 높이 점선의 길이를 확인해주세요.");
    }

    static SizeGuideSceneTransformDto fit(Rectangle2D bounds) {
        double scale = Math.min(1, Math.min((SCENE_WIDTH + .01) / bounds.getWidth(), (SCENE_HEIGHT + .01) / bounds.getHeight()));
        double x = Math.max(32 - bounds.getMinX() * scale, Math.min(0, 1408 - bounds.getMaxX() * scale));
        double y = Math.max(32 - bounds.getMinY() * scale, Math.min(0, 586 - bounds.getMaxY() * scale));
        if (Math.abs(x) < .02) x = 0;
        if (Math.abs(y) < .02) y = 0;
        return new SizeGuideSceneTransformDto(scale, x / 1440, y / 940);
    }

    static void draw(Graphics2D graphics, Geometry geometry, SizeGuideSceneTransformDto transform, FontResolver fonts, java.util.List<Rectangle2D> occupied) {
        var raw = geometry.bounds();
        double x = raw.x * transform.scale() + transform.offsetXRatio() * 1440;
        double y = raw.y * transform.scale() + transform.offsetYRatio() * 940;
        double width = raw.width * transform.scale(), height = raw.height * transform.scale();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setStroke(new BasicStroke(2));
        g.setColor(new Color(220, 228, 236));
        // Device v1 snapshots have the same calibrated body dimensions, so their presentation can use the photo too.
        if (geometry.preset().version() >= 2 || !geometry.preset().kind().equals("BOTTLE")) {
            var asset = SizeGuideComparisonAssets.image(geometry.preset().id());
            double deviceScale = Math.hypot(g.getTransform().getScaleX(), g.getTransform().getShearY());
            asset = RenderSupport.resampleImage(asset, Math.max(1, (int)Math.ceil(width * deviceScale)), Math.max(1, (int)Math.ceil(height * deviceScale)));
            var placement = new AffineTransform();
            placement.translate(x, y); placement.scale(width / asset.getWidth(), height / asset.getHeight());
            g.drawImage(asset, placement, null);
        } else {
            drawBottle(g, x, y, width, height);
        }
        var caption = caption(g, geometry, transform, fonts, occupied);
        var plate = caption.bounds();
        double radius = Math.min(8, Math.min(plate.width, plate.height) * .2);
        g.setColor(new Color(255, 255, 255, 225));
        g.fill(new RoundRectangle2D.Double(plate.x, plate.y, plate.width, plate.height, radius, radius));
        g.clip(plate);
        g.setFont(caption.font()); g.setColor(new Color(30, 41, 59));
        FontMetrics metrics = g.getFontMetrics();
        float baseline = (float) (plate.getCenterY() - caption.lines().size() * metrics.getHeight() / 2.0 + metrics.getAscent());
        for (String line : caption.lines()) {
            g.drawString(line, (float) (plate.getCenterX() - metrics.stringWidth(line) / 2.0), baseline);
            baseline += metrics.getHeight();
        }
        g.dispose();
    }

    static Caption caption(Graphics2D g, Geometry geometry, SizeGuideSceneTransformDto transform, FontResolver fonts, java.util.List<Rectangle2D> occupied) {
        var raw = geometry.bounds();
        double x = raw.x * transform.scale() + transform.offsetXRatio() * 1440;
        double y = raw.y * transform.scale() + transform.offsetYRatio() * 940;
        double width = raw.width * transform.scale(), height = raw.height * transform.scale();
        String text = geometry.preset().name().replaceFirst("\\s*\\([^)]*\\)\\s*$", "");
        double availableWidth = width * .84, availableHeight = height * .20;
        float size = (float) Math.min(26, Math.max(.1, availableHeight * .7));
        java.util.List<String> lines = java.util.List.of(text);
        Font font = fonts.photoTextFont(Font.BOLD, 26);
        double textWidth = 0, textHeight = 0;
        for (int pass = 0; pass < 80; pass++) {
            font = font.deriveFont(size);
            FontMetrics metrics = g.getFontMetrics(font);
            var wrapped = new java.util.ArrayList<String>();
            String current = "";
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (!current.isEmpty() && metrics.stringWidth(candidate) > availableWidth * .9) {
                    wrapped.add(current); current = word;
                } else current = candidate;
            }
            if (!current.isEmpty()) wrapped.add(current);
            lines = java.util.List.copyOf(wrapped);
            textWidth = lines.stream().mapToInt(metrics::stringWidth).max().orElse(0);
            textHeight = lines.size() * metrics.getHeight();
            if (textWidth <= availableWidth * .9 && textHeight <= availableHeight * .9) break;
            size *= .88f;
        }
        double labelWidth = Math.min(availableWidth, Math.max(textWidth + size * .6, width * .4));
        double labelHeight = Math.min(availableHeight, textHeight + size * .3);
        return new Caption(text, lines, font, new Rectangle2D.Double(x + (width - labelWidth) / 2,
                y + height * .82 - labelHeight / 2, labelWidth, labelHeight));
    }

    private static void drawBottle(Graphics2D graphics, double x, double y, double width, double height) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y); g.scale(width, height);
        Path2D body = new Path2D.Double();
        body.moveTo(.32, .07); body.lineTo(.68, .07); body.lineTo(.68, .17);
        body.curveTo(.68, .22, .98, .25, .98, .34); body.lineTo(.98, .94);
        body.quadTo(.98, 1, .87, 1); body.lineTo(.13, 1); body.quadTo(.02, 1, .02, .94);
        body.lineTo(.02, .34); body.curveTo(.02, .25, .32, .22, .32, .17); body.closePath();
        g.setColor(new Color(224, 241, 250)); g.fill(body);
        g.setColor(new Color(74, 130, 166)); g.setStroke(new BasicStroke(.015f)); g.draw(body);
        g.setColor(new Color(71, 147, 193)); g.fill(new RoundRectangle2D.Double(.27, 0, .46, .09, .035, .018));
        g.setColor(new Color(186, 218, 237)); g.fill(new Rectangle2D.Double(.035, .43, .93, .25));
        g.setColor(new Color(119, 176, 207));
        for (double rib : new double[]{.36, .40, .75, .82, .89}) g.draw(new Line2D.Double(.1, rib, .9, rib));
        g.dispose();
    }
}
