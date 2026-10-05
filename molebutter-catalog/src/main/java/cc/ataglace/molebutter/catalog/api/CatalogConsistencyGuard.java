package cc.ataglace.molebutter.catalog.api;
public interface CatalogConsistencyGuard {
    void exclusive();
    void shared();
    void requireExclusive();
    void requireShared();
}
