package cc.ataglace.molebutter.catalog.api;


import java.time.LocalDateTime;

/** Changes participate in the caller's transaction; caller must acquire the catalog guard first. */
public interface CatalogCommands {
    void checkRevision(long id, Long expectedRevision);
    void create(long id,String brand,Long brandId,String productCode,String registrationNames,LocalDateTime at);
    void edit(long id,long expectedRevision,String brand,Long brandId,String productCode,LocalDateTime at);
    void bump(long id,LocalDateTime at);
    void bump(long id);
    void delete(long id,Long actor,LocalDateTime at);
    void mergeReference(long target,long source);
    void mergeHistory(long id,long target,Long actor,LocalDateTime at,String before,String after);
    void registrationNames(long id,String json);
}
