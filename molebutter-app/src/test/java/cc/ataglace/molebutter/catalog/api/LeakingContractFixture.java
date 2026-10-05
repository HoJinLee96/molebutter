package cc.ataglace.molebutter.catalog.api;
/** Intentionally misplaced and leaking an implementation through a generic signature. */
public interface LeakingContractFixture {
    java.util.List<? extends cc.ataglace.molebutter.catalog.internal.InternalTypeFixture> leaking();
    interface Bounded {
        <T extends cc.ataglace.molebutter.catalog.internal.InternalTypeFixture> void leakingBound();
    }
}
