package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ChangeDtos.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import java.util.*;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;




public interface ProductChangeService {
    public record SeedCandidate(String productId, String code, long revision, int listings) {
    }
    List<SeedCandidate> previewInitial();
    boolean seed(SeedCandidate candidate);
    void reset(long product);
    void merge(long target, long source);
    Summary review(Long actor, long product, ReviewInput input);
    Summary summary(long product);
    Map<String, Summary> summaries(List<String> ids);
    List<Map<String, Object>> historySuppliers(long product);
    PageResponse<HistoryItem> history(Long actor, long product, String supplier, String kind, int page);
}
