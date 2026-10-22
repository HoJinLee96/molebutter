package cc.ataglace.molebutter.imaging.api;

/** Maps normalized model coordinates to normalized displayed coordinates. */
public record SizeGuideSceneTransformDto(double scale, double offsetXRatio, double offsetYRatio) {
    public static final SizeGuideSceneTransformDto IDENTITY = new SizeGuideSceneTransformDto(1, 0, 0);
}
