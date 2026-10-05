package cc.ataglace.molebutter.operations.api;

public interface NotificationTargets {
    boolean ownsCorrection(long user,Long correction);
    String resolve(String type,Long targetId,long user);
}
