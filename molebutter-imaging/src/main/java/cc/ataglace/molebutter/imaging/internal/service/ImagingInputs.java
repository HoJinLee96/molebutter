package cc.ataglace.molebutter.imaging.internal.service;
import java.util.Locale;
import java.util.Set;
import cc.ataglace.molebutter.imaging.api.*;

public final class ImagingInputs {
    public static final int MAX_DOWNLOAD_ITEMS = 64;
    private static final Set<String> BRANDS = Set.of("DAKS","HAZZYS","JILLSTUART","LQT","SAMSONITE");
    private ImagingInputs() { }
    public static void actor(Long actor) {
        if (actor == null || actor <= 0) fail("로그인이 필요합니다.");
    }
    public static void product(String code, String brand) {
        if (code == null || !code.trim().matches("[A-Za-z0-9_-]{4,40}")) fail("올바른 상품코드를 입력해주세요.");
        if (brand != null && !brand.isBlank() && !BRANDS.contains(brand.trim().toUpperCase(Locale.ROOT))) fail("지원하는 브랜드를 선택해주세요.");
    }
    public static void preview(String code, SizeGuidePreviewRequestDto request) {
        if (request == null) fail("사이즈 이미지 요청이 필요합니다.");
        product(code, request.brandCode()); selection(request.toSelection());
    }
    public static void notice(String code, NoticeImageRequestDto request) {
        if (request == null) fail("상품정보고시 요청이 필요합니다.");
        product(code, request.brandCode());
        if (request.fields() == null) return;
        if (request.fields().size() > 100) fail("상품정보고시 항목은 100개 이하여야 합니다.");
        long totalLength = 0;
        for (var field : request.fields().entrySet()) {
            if (field.getKey() == null || field.getKey().length() > 512) fail("상품정보고시 항목이 올바르지 않습니다.");
            int length = field.getValue() == null ? 0 : field.getValue().length();
            totalLength += (long) field.getKey().length() + length;
            if (length > 8192 || totalLength > 65536) fail("상품정보고시 텍스트가 너무 깁니다.");
        }
    }
    public static void selection(SizeGuideSelectionDto selection) {
        if (selection == null) return;
        if (selection.sizeLabel() != null && (selection.sizeLabel().length() > 100
                || selection.sizeLabel().chars().anyMatch(Character::isISOControl))) fail("표기 사이즈는 100자 이하로 입력해주세요.");
        if (selection.mode() != null && !selection.mode().isBlank()
                && !Set.of("PHOTO","TEMPLATE").contains(selection.mode().trim().toUpperCase(Locale.ROOT))) fail("이미지 모드를 확인해주세요.");
        if (selection.templateKey() != null && selection.templateKey().length() > 64) fail("템플릿을 확인해주세요.");
        if (selection.baseImageUrl() != null && selection.baseImageUrl().length() > 2048) fail("이미지 주소가 너무 깁니다.");
        if (selection.dimensions() != null) {
            dimension(selection.dimensions().width()); dimension(selection.dimensions().depth()); dimension(selection.dimensions().height());
        }
        SizeGuideLayoutDto layout = selection.layout();
        if (layout == null) return;
        if (layout.photo() != null) {
            double scale = layout.photo().scale();
            if (!Double.isFinite(scale) || scale < 0.3 || scale > 2.5) fail("사진 배율을 확인해주세요.");
            ratio(layout.photo().centerXRatio()); ratio(layout.photo().centerYRatio());
        }
        arrow(layout.width()); arrow(layout.depth()); arrow(layout.height());
    }
    private static void dimension(String value) {
        if (value == null || value.isBlank() || "-".equals(value.trim())) return;
        if (value.chars().anyMatch(Character::isISOControl)) fail("치수를 확인해주세요.");
        String numeric = value.trim().replaceFirst("(?i)\\s*cm$", "").trim();
        if (value.length() > 40 || !numeric.matches("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) fail("치수를 확인해주세요.");
        try {
            double number = Double.parseDouble(numeric);
            if (!Double.isFinite(number) || number <= 0 || number > 10000) fail("치수를 확인해주세요.");
        } catch (NumberFormatException e) { fail("치수를 확인해주세요."); }
    }
    private static void arrow(SizeGuideLayoutDto.ArrowPlacement arrow) {
        if (arrow == null) return;
        ratio(arrow.startXRatio()); ratio(arrow.startYRatio()); ratio(arrow.endXRatio()); ratio(arrow.endYRatio());
        ratio(arrow.labelXRatio()); ratio(arrow.labelYRatio());
    }
    private static void ratio(double value) { if (!Double.isFinite(value) || value < 0 || value > 1) fail("편집 좌표를 확인해주세요."); }
    public static void download(DownloadRequestDto request) {
        if (request == null) fail("다운로드 요청이 필요합니다.");
        product(request.productCode(), request.brandCode()); selection(request.sizeGuide());
        if (request.images() != null) {
            if (request.images().size() > MAX_DOWNLOAD_ITEMS) fail("한 번에 64개까지 다운로드할 수 있습니다.");
            for (var item : request.images()) {
                if (item == null || (item.imageIndex() == null) == (item.generatedImageId() == null)) fail("다운로드 이미지 항목이 올바르지 않습니다.");
                if (item.sourceImageUrl() != null && (item.imageIndex() == null || item.sourceImageUrl().isBlank() || item.sourceImageUrl().length() > 2048)) fail("원본 이미지 주소를 확인해주세요.");
                if (item.imageIndex() != null && item.imageIndex() < 0) fail("원본 이미지 번호를 확인해주세요.");
                if (item.generatedImageId() != null && !item.generatedImageId().matches("[a-fA-F0-9-]{36}")) fail("생성 이미지 ID를 확인해주세요.");
            }
        }
        if (request.imageIndexes() != null) {
            if (request.imageIndexes().size() > MAX_DOWNLOAD_ITEMS) fail("한 번에 64개까지 다운로드할 수 있습니다.");
            for (Integer index : request.imageIndexes()) if (index == null || index < 0) fail("원본 이미지 번호를 확인해주세요.");
        }
    }
    private static void fail(String message) { throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, message); }
}
