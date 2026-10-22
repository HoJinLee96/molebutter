package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import java.util.*;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;


public interface ProcurementProductQueries {
    PageResponse<ProcurementProductView> list(Long actor, String q, String mode, String status, int page);
    PageResponse<ProcurementProductView> list(Long actor, String q, String mode, String status, int page, int size);
    PageResponse<ProcurementProductView> list(Long actor, String q, String mode, String status, int page, int size,
            String change);
    cc.ataglace.molebutter.procurement.api.ChangeDtos.Counts changeCounts(Long actor, String q, String mode,
            String status);
    PageResponse<ProcurementProductView> byCode(Long actor,String code,int page,int size);
    List<ProcurementProductView> byCodes(Long actor,Set<String> codes);
    ProcurementProductView product(long id);
    record SearchEvidence(boolean selectedMissing,boolean previous,String checkedAt,String lastMissingAt) {}
    record SelectedStock(List<SourceOption> options,boolean previous,String checkedAt,int optionCount,SearchEvidence search) {
        public SelectedStock(List<SourceOption> options,boolean previous,String checkedAt,int optionCount){this(options,previous,checkedAt,optionCount,null);}
        public SelectedStock(List<SourceOption> options,boolean previous,String checkedAt){this(options,previous,checkedAt,options.size());}
    }
    SelectedStock selectedStock(Long actor,long product);
    Map<String,SelectedStock> selectedStocks(Long actor,List<ProcurementProductView> products);
    Map<String, Object> detail(Long actor, long id);
    PageResponse<Map<String, Object>> history(Long actor, long id, int page);
    List<String> brands(Long actor);
    List<ProcurementProductView> duplicates(Long actor, long id);
    void validateSupplierReference(long product,long supplier);
}
