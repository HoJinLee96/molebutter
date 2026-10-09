package cc.ataglace.molebutter.imaging.api;

/** Exactly one source index or generated ID; source URL protects an observed index from stale tabs. */
public record DownloadImageItemDto(Integer imageIndex, String generatedImageId, String sourceImageUrl) {
    public DownloadImageItemDto(Integer imageIndex, String generatedImageId) { this(imageIndex, generatedImageId, null); }
}
