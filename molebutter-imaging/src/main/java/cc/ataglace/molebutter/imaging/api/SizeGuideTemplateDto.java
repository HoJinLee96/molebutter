package cc.ataglace.molebutter.imaging.api;

/** 화면의 카테고리 선택 목록 항목. 치수 라벨은 입력란·편집기 핸들 이름에 쓴다. */
public record SizeGuideTemplateDto(
        String templateKey,
        String displayName,
        String widthLabel,
        String depthLabel,
        String heightLabel,
        boolean twoDimensional) {
}
