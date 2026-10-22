package cc.ataglace.molebutter.imaging.api;

public record SizeGuidePreviewResponseDto(
        String templateKey,
        String displayName,
        String imageDataUrl,
        int canvasWidth,
        int canvasHeight,
        SizeGuideLayoutDto layout,
        SizeGuideSceneTransformDto sceneTransform,
        SizeGuidePhotoViewDto photoView) {
    public SizeGuidePreviewResponseDto(String templateKey, String displayName, String imageDataUrl,
            int canvasWidth, int canvasHeight, SizeGuideLayoutDto layout, SizeGuideSceneTransformDto sceneTransform) {
        this(templateKey, displayName, imageDataUrl, canvasWidth, canvasHeight, layout, sceneTransform, null);
    }
    public SizeGuidePreviewResponseDto(String templateKey, String displayName, String imageDataUrl,
            int canvasWidth, int canvasHeight, SizeGuideLayoutDto layout) {
        this(templateKey, displayName, imageDataUrl, canvasWidth, canvasHeight, layout, SizeGuideSceneTransformDto.IDENTITY);
    }
}
