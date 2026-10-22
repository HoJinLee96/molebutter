package cc.ataglace.molebutter.imaging.api;

/** Fixed body dimensions, not display diagonals. Null width means illustrative proportions only. */
public record SizeGuideComparisonDto(String id, int version, String name, Double widthCm, double heightCm,
        double aspectRatio, String kind) { }
