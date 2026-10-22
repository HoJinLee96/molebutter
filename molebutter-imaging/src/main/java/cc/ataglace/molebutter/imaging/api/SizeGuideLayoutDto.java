package cc.ataglace.molebutter.imaging.api;

/**
 * 사진 기반 사이즈 이미지의 수동 조정 레이아웃.
 * 좌표는 1440x940 기준이다. outputSpace=true는 맞춤을 반영한 최종 출력 좌표이며
 * photo.scale도 최종 배율이다. 사진 배경 여백·이동 때문에 가상 좌표는 0~1 밖일 수 있다.
 * 기존 입력(outputSpace=false)의 좌표·배율 범위는 유지한다.
 */
public record SizeGuideLayoutDto(
        PhotoPlacement photo,
        ArrowPlacement width,
        ArrowPlacement height,
        ArrowPlacement depth,
        ComparisonPlacement comparison,
        boolean outputSpace,
        java.util.List<ComparisonPlacement> comparisons) {

    public SizeGuideLayoutDto {
        // Explicit arrays take precedence (including an empty array); old single selections remain readable.
        comparisons = comparisons == null ? (comparison == null ? java.util.List.of() : java.util.List.of(comparison))
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(comparisons));
        comparison = comparisons.isEmpty() ? null : comparisons.getFirst();
    }

    public SizeGuideLayoutDto(PhotoPlacement photo, ArrowPlacement width, ArrowPlacement height, ArrowPlacement depth,
            ComparisonPlacement comparison, boolean outputSpace) {
        this(photo, width, height, depth, comparison, outputSpace, null);
    }

    public SizeGuideLayoutDto(PhotoPlacement photo, ArrowPlacement width, ArrowPlacement height, ArrowPlacement depth, ComparisonPlacement comparison) {
        this(photo, width, height, depth, comparison, false);
    }

    public SizeGuideLayoutDto(PhotoPlacement photo, ArrowPlacement width, ArrowPlacement height, ArrowPlacement depth) {
        this(photo, width, height, depth, null, false);
    }

    /** Bottom-center placement in model coordinates; actual dimensions come only from the fixed catalog. */
    public record ComparisonPlacement(String presetId, int version, Double centerXRatio, Double baseYRatio) { }

    /** 상품 사진 배치. scale 은 auto-fit 대비 배율, center 는 캔버스 기준 중심 비율. */
    public record PhotoPlacement(double scale, double centerXRatio, double centerYRatio) {
    }

    /** 점선 양끝(start/end)과 호환용 숫자 위치(label). 숫자는 렌더링 시 제품 반대편에 자동 배치된다. */
    public record ArrowPlacement(
            double startXRatio,
            double startYRatio,
            double endXRatio,
            double endYRatio,
            double labelXRatio,
            double labelYRatio) {
    }
}
