package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;


public interface SupplierRefreshService {
    public record Work(long runId,long productId,long revision,String query,String productCode,String codeType,String brandKey,Preferences preferences,Map<String,String> manualStores,SelectionBasis selectionBasis) {
        public Work(long run,long product,long revision,String query,String code,String type,String brand,Preferences preferences,Map<String,String> manual){this(run,product,revision,query,code,type,brand,preferences,manual,null);}
        public Work(long run,long product,long revision,String query,String code,String type,String brand){this(run,product,revision,query,code,type,brand,null,Map.of());}
    }
    RefreshStatus status(Long actor,long id);
    String start(Long actor,RefreshInput input);
    void schedule();
    Work claim(String worker);
    void finish(String worker,Work w,RefreshResult result);
    Long searchStarted(String owner,Work work);
    boolean searchSucceeded(String owner,Work work,long attempt);
    void searchFailed(String owner,Work work,long attempt,Exception error);
    PageResponse<Map<String,Object>> searchAttempts(Long actor,long run,int page,String code,Long product);
    void resumeSearchGate(Long actor,long version);
    boolean searchFinished(String worker,Work w,boolean success);
    void blocked(String worker,Work w,String message);
    void blocked(String worker,Work w,String reason,String message);
    boolean heartbeat(String worker,Work work);
    SearchResult cached(Work w);
    void cache(Work w,SearchResult r);
    void control(Long actor,long run,String action);
    String retry(Long actor,long run);
    RefreshRunPage runs(Long actor,RefreshRunQuery q);
    PageResponse<Map<String,Object>> items(Long actor,long run,int page);
}
