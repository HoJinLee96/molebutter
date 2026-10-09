package cc.ataglace.molebutter.imaging.api;

import java.util.List;

public record DownloadRequestDto(
        String productCode,
        String brandCode,
        List<Integer> imageIndexes,
        Boolean includeNoticeImage,
        Boolean includeSizeImage,
        SizeGuideSelectionDto sizeGuide,
        List<DownloadImageItemDto> images) {

    // 선택 옵션은 생략/null 모두 false로 정규화한다. Jackson 3의 primitive null 거부와 분리한다.
    public DownloadRequestDto {
        imageIndexes = imageIndexes == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(imageIndexes));
        images = images == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(images));
        includeNoticeImage = Boolean.TRUE.equals(includeNoticeImage);
        includeSizeImage = Boolean.TRUE.equals(includeSizeImage);
    }

    // 이전 호출자는 유지하고 새 화면은 images의 혼합 순서를 사용한다.
    public DownloadRequestDto(String productCode, String brandCode, List<Integer> imageIndexes,
            boolean includeNoticeImage, boolean includeSizeImage, SizeGuideSelectionDto sizeGuide) {
        this(productCode, brandCode, imageIndexes, includeNoticeImage, includeSizeImage, sizeGuide, null);
    }
}
