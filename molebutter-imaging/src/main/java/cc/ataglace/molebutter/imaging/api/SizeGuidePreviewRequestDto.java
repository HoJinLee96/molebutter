package cc.ataglace.molebutter.imaging.api;

public record SizeGuidePreviewRequestDto(
        String brandCode,
        String mode,
        String templateKey,
        String baseImageUrl,
        SizeGuideLayoutDto layout,
        SizeDimensionsDto dimensions,
        String sizeLabel) {

    public SizeGuidePreviewRequestDto(String brandCode, String mode, String templateKey, String baseImageUrl,
            SizeGuideLayoutDto layout, SizeDimensionsDto dimensions) {
        this(brandCode, mode, templateKey, baseImageUrl, layout, dimensions, null);
    }

    public SizeGuideSelectionDto toSelection() {
        return new SizeGuideSelectionDto(mode, templateKey, baseImageUrl, layout, dimensions, sizeLabel);
    }
}
