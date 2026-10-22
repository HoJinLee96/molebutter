package cc.ataglace.molebutter.media.api;

import java.util.*;

/** File rules shared by validation and Coupang payload construction. */
public final class ImageRules {
    private ImageRules() {
    }
    public static List<String> warnings(String role, int width, int height) {
        return !"DESCRIPTION".equals(role) && width > 0 && height > 0 && width != height
            ? List.of("직사각형 원본을 사용합니다. 쿠팡 API 문서는 정사각형을 안내하므로 실제 등록 시 비율을 확인해 주세요. Wing의 자동 리사이징 안내와는 별개입니다.") : List.of();
    }
    public static List<String> errors(String role, String mime, int width, int height, long bytes) {
        var errors = new ArrayList<String>();
        if (width < 1 || height < 1 || bytes < 1)
            errors.add("이미지 파일을 읽지 못했습니다.");
        if (bytes > 10 * 1024 * 1024 || (long) width * height > 25_000_000L)
            errors.add("앱 처리 제한: 10MiB·2,500만 픽셀 이하 이미지를 선택해 주세요.");
        if (!Set.of("image/jpeg", "image/png").contains(mime))
            errors.add("앱 처리 형식: JPG·PNG 이미지를 선택해 주세요.");
        if (!"DESCRIPTION".equals(role) && (bytes > 3 * 1024 * 1024 || width < 500 || width > 5000 || height < 500 || height > 5000))
            errors.add("쿠팡 상품 이미지는 3MiB 이하, 가로·세로 각각 500~5000px이어야 합니다.");
        return List.copyOf(errors);
    }
}
