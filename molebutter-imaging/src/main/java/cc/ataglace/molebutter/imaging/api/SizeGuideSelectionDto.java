package cc.ataglace.molebutter.imaging.api;

/**
 * 사이즈 이미지 생성 방식 선택. 프리뷰 요청과 다운로드 요청이 공유한다.
 * mode: TEMPLATE(실루엣) | PHOTO(사진 기반). templateKey 가 없으면 자동 선택 템플릿 사용.
 * dimensions 는 수동 입력 치수 — 있으면 파싱 결과 대신 사용한다.
 */
public record SizeGuideSelectionDto(
        String mode,
        String templateKey,
        String baseImageUrl,
        SizeGuideLayoutDto layout,
        SizeDimensionsDto dimensions,
        String sizeLabel) {

    public SizeGuideSelectionDto(String mode, String templateKey, String baseImageUrl,
            SizeGuideLayoutDto layout, SizeDimensionsDto dimensions) {
        this(mode, templateKey, baseImageUrl, layout, dimensions, null);
    }

    public static final String MODE_TEMPLATE = "TEMPLATE";
    public static final String MODE_PHOTO = "PHOTO";

    public boolean photoMode() {
        return MODE_PHOTO.equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}
