package cc.ataglace.molebutter.coupang.api;

/** Called inside the collection state transaction; the app supplies the inbox adapter. */
public interface CoupangCollectionNotifications {
    record Result(String key,long actor,CoupangRegisteredProducts.Collection collection,java.util.List<String> reasons) {}
    void publish(Result result);
}
