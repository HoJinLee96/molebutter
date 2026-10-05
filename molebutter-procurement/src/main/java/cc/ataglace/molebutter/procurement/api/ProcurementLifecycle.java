package cc.ataglace.molebutter.procurement.api;
import java.util.*;
public interface ProcurementLifecycle {
    void create(long id,String type,String comparison,String query,String mode);
    void searchQuery(long id,String query);
    void initialSearch(long id);
    String validateSearchQuery(String query);
    void invalidate(long id);
    void managed(long id,boolean managed);
    void deleted(long id);
    void mergedSource(long id);
    void moveHistory(long target,long source);
    void ensureIdle(List<Long> ids);
}
