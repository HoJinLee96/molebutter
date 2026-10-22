package cc.ataglace.molebutter.imaging.api;

import java.util.List;

/** An owned, immutable selection captured from the observed product before asynchronous work. */
@FunctionalInterface
public interface ImageExportPlan {
    /** Prepares upload files in order, including 750px size guides, before any storage write. */
    List<ExportImageDto> prepare();
}
