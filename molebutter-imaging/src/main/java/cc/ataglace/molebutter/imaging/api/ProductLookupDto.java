package cc.ataglace.molebutter.imaging.api;

import java.util.List;
import java.util.Map;

public record ProductLookupDto(
        String productCode,
        String brandCode,
        String brandName,
        String productName,
        Integer originalPrice,
        Integer salePrice,
        List<String> imageUrls,
        List<String> categoryNames,
        Map<String, String> notificationFields,
        String sizeLabel,
        String sizeDescription,
        Map<String, String> sizeMeasurements,
        boolean sizeGuideSupported,
        String sizeGuideTypeName,
        String sizeGuideTemplateKey,
        SizeDimensionsDto sizeDimensions) {
    public ProductLookupDto {
        imageUrls = List.copyOf(imageUrls);
        categoryNames = List.copyOf(categoryNames);
        notificationFields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(notificationFields));
        sizeMeasurements = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sizeMeasurements));
    }
}
