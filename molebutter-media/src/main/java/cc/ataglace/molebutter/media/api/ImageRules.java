package cc.ataglace.molebutter.media.api;

import java.util.*;

/** File rules shared by validation and Coupang payload construction. */
public final class ImageRules {
    private ImageRules() {
    }
    public static List<String> errors(String role, String mime, int width, int height, long bytes) {
        var errors = new ArrayList<String>();
        if (width < 1 || height < 1 || bytes < 1)
            errors.add("이미지 파일을 읽지 못했습니다.");
        if (bytes > 10 * 1024 * 1024 || (long) width * height > 25_000_000L)
            errors.add("앱 처리 제한: 10MiB·2,500만 픽셀 이하 이미지를 선택해 주세요.");
        if (!Set.of("image/jpeg", "image/png").contains(mime))
            errors.add("앱 처리 형식: JPG·PNG 이미지를 선택해 주세요.");
        if (!"DESCRIPTION".equals(role) && (bytes > 3 * 1024 * 1024 || width != height || width < 500 || width > 5000))
            errors.add("쿠팡 상품 이미지는 3MiB 이하, 500~5000px 정사각형이어야 합니다.");
        return List.copyOf(errors);
    }
}
