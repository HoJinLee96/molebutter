package cc.ataglace.molebutter.app.internal;

import java.util.List;

/** Existing product HTTP fields remain stable; these are application requests and results. */
public final class ProductHttpDtos {
    private ProductHttpDtos() {}

    public record VersionedId(String id, Long revision) {
    }

    public record ProductEditRequest(Long revision, String brand, String productCode, String searchQuery, String brandId,
            String searchMode, String codeType) {
        public ProductEditRequest(Long revision, String brand, String code, String query) {
            this(revision, brand, code, query, null, null, null);
        }
    }

    public record BulkEdit(List<VersionedId> products, String brand, Boolean managed, String brandId) {
    }

    public record DeleteProducts(List<VersionedId> products) {
    }

    public record MergeInput(List<VersionedId> products, String brand, String productCode, String searchQuery,
            Boolean managed, String brandId, String searchMode, String codeType, String selectedSupplierId) {
        public MergeInput(List<VersionedId> products, String brand, String code, String query, Boolean managed,
                String brandId, String mode, String type) {
            this(products, brand, code, query, managed, brandId, mode, type, null);
        }
    }

    public record BrandInferenceInput(List<VersionedId> products) {
    }

    public record BrandInferenceResult(int assigned, int preserved, int unresolved) {
    }

    public record ImportResult(int totalRows, int created, int existing, int duplicates, int excluded,
            int brandsAssigned, List<String> warnings) {
    }
}
