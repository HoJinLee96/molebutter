package cc.ataglace.molebutter.coupang.api;
/** Cancels only this administrator's in-flight read operation. */
public interface CoupangRequests {
    void cancel(Long actor, String requestId);
}
