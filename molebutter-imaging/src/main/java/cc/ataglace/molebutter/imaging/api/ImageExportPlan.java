package cc.ataglace.molebutter.imaging.api;

import java.util.List;

/** An owned, immutable selection captured from the observed product before asynchronous work. */
@FunctionalInterface
public interface ImageExportPlan {
    /** Prepares all files in the selected order before an external storage write begins. */
    List<ExportImageDto> prepare();
}
