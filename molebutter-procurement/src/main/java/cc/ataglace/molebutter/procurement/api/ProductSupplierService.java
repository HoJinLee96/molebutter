package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import java.util.*;
import org.springframework.transaction.annotation.*;



public interface ProductSupplierService {
    Map<String,Listing> selected(List<String> productIds);
    Comparison comparison(Long actor,long product);
    Comparison assign(Long actor,long product,long id,AssignmentInput input);
    Comparison select(Long actor,long product,SelectionInput input);
    String mergeChoice(List<Long> products,String requested);
    String moveListings(long target,long source,String chosen);
    void mergeSelections(Long actor,long target,List<Long> ids,String chosen);
}
