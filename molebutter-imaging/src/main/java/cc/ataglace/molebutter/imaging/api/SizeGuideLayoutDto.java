package cc.ataglace.molebutter.imaging.api;

/**
 * 사진 기반 사이즈 이미지의 수동 조정 레이아웃.
 * 모든 좌표는 1440x940 캔버스 기준 0~1 비율이며, 프리뷰/최종 렌더가 동일하게 사용한다.
 */
public record SizeGuideLayoutDto(
        PhotoPlacement photo,
        ArrowPlacement width,
        ArrowPlacement height,
        ArrowPlacement depth) {

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
