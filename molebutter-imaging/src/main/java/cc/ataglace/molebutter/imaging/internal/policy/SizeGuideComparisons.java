package cc.ataglace.molebutter.imaging.internal.policy;

import cc.ataglace.molebutter.imaging.api.ImagingFailure;
import cc.ataglace.molebutter.imaging.api.SizeGuideComparisonDto;
import cc.ataglace.molebutter.imaging.api.SizeGuideLayoutDto.ComparisonPlacement;
import java.util.List;

/** Immutable versioned presets. Old snapshot versions remain readable. */
public final class SizeGuideComparisons {
    private SizeGuideComparisons() { }
    private static final List<SizeGuideComparisonDto> PRESETS = List.of(
            new SizeGuideComparisonDto("samdasoo-500", 2, "삼다수 500ml", null, 21, SizeGuideComparisonAssets.bottleAspectRatio(), "BOTTLE"),
            device("iphone-16", "iPhone 16", 7.16, 14.76, "PHONE"),
            device("galaxy-s26", "Galaxy S26", 7.17, 14.96, "PHONE"),
            device("ipad-air-11-m4", "iPad Air 11 (M4)", 17.85, 24.76, "TABLET"),
            device("ipad-air-13-m4", "iPad Air 13 (M4)", 21.49, 28.06, "TABLET"),
            device("macbook-air-13-m5", "MacBook Air 13 (M5)", 30.41, 21.50, "LAPTOP"),
            device("macbook-air-15-m5", "MacBook Air 15 (M5)", 34.04, 23.76, "LAPTOP"),
            device("macbook-pro-16-m5", "MacBook Pro 16 (M5)", 35.57, 24.81, "LAPTOP"));
    private static SizeGuideComparisonDto device(String id, String name, double width, double height, String kind) {
        return new SizeGuideComparisonDto(id, 2, name, width, height, width / height, kind);
    }
    public static List<SizeGuideComparisonDto> list() { return PRESETS; }
    public static void validate(List<ComparisonPlacement> placements) {
        if (placements.size() > PRESETS.size()) throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "비교 대상은 최대 8개까지 선택해주세요.");
        var seen = new java.util.HashSet<String>();
        for (var placement : placements) {
            if (placement == null || !seen.add(placement.presetId()))
                throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "비교 대상은 중복 없이 선택해주세요.");
            resolve(placement);
        }
    }
    public static SizeGuideComparisonDto resolve(ComparisonPlacement placement) {
        if ((placement.centerXRatio() == null) != (placement.baseYRatio() == null)
                || (placement.centerXRatio() != null && (!Double.isFinite(placement.centerXRatio())
                || !Double.isFinite(placement.baseYRatio()) || Math.abs(placement.centerXRatio()) > 10000
                || Math.abs(placement.baseYRatio()) > 10000))) {
            throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "비교 대상 배치 좌표를 확인해주세요.");
        }
        if ("samdasoo-500".equals(placement.presetId()) && placement.version() == 1)
            return new SizeGuideComparisonDto("samdasoo-500", 1, "삼다수 500ml", null, 21, .30, "BOTTLE");
        var preset = PRESETS.stream().filter(value -> value.id().equals(placement.presetId()) && value.version() == placement.version())
                .findFirst().orElse(null);
        if (preset != null) return preset;
        if (placement.version() == 1) {
            var legacy = PRESETS.stream().filter(value -> value.id().equals(placement.presetId()) && !value.kind().equals("BOTTLE")).findFirst().orElse(null);
            if (legacy != null) return new SizeGuideComparisonDto(legacy.id(), 1, legacy.name(), legacy.widthCm(), legacy.heightCm(), legacy.aspectRatio(), legacy.kind());
        }
        throw new ImagingFailure(ImagingFailure.Kind.INVALID_INPUT, "비교 대상 모델을 다시 선택해주세요.");
    }
}
