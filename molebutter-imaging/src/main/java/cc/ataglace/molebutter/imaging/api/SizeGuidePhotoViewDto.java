package cc.ataglace.molebutter.imaging.api;

/** Output-space zoom limit and visible subject's center relative to the source photo center. */
public record SizeGuidePhotoViewDto(double maximumScale, double subjectOffsetXRatio, double subjectOffsetYRatio) { }
