package cc.ataglace.molebutter.imaging.api;

public record SizeGuidePreviewResponseDto(
        String templateKey,
        String displayName,
        String imageDataUrl,
        int canvasWidth,
        int canvasHeight,
        SizeGuideLayoutDto layout) {
}
