package cc.ataglace.molebutter.catalog.api;

import java.time.LocalDateTime;
import java.util.List;

/** Mutations join a writable transaction whose caller already holds the exclusive guard. */
public interface CatalogCommands {
    record BrandSelection(String id, String name) {}
    record Brand(Long id, String name, String key) {}
    record ProductInput(BrandSelection brand, String productCode, List<String> registrationNames) {}
    record VersionedProduct(String id, Long revision) {}
    record BrandInference(int assigned, int preserved, int unresolved) {}

    Brand resolveBrand(BrandSelection selection);
    List<Long> matchingIds(String productCode);
    List<String> registrationNames(long id);
    boolean validRegistrationRow(String code, String title);
    void appendRegistrationNames(long id, List<String> names);
    void mergeRegistrationNames(long target, List<Long> ids);
    List<Long> checkVersions(List<VersionedProduct> products);
    void validateMerge(long target, List<Long> ids, String productCode);
    BrandInference inferBrands(List<Long> ids, LocalDateTime at);
    void checkRevision(long id, Long expectedRevision);
    void create(long id, ProductInput input, LocalDateTime at);
    void edit(long id, long expectedRevision, ProductInput input, LocalDateTime at);
    /** Changes the brand while preserving the existing product code verbatim. */
    void editBrand(long id, long expectedRevision, BrandSelection brand, LocalDateTime at);
    void bump(long id, LocalDateTime at);
    void bump(long id);
    void delete(long id, Long actor, LocalDateTime at);
    void mergeReference(long target, long source);
    void mergeHistory(long id, long target, Long actor, LocalDateTime at, String before, String after);
    void registrationNames(long id, List<String> names);
}
