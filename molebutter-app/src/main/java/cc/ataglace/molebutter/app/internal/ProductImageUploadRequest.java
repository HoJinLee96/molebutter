package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.imaging.api.DownloadImageItemDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ProductImageUploadRequest(String requestId, String productCode, String brandCode,
        String uploadProductCode, List<DownloadImageItemDto> images) {
    public ProductImageUploadRequest {
        images = images == null ? null : Collections.unmodifiableList(new ArrayList<>(images));
    }
}
