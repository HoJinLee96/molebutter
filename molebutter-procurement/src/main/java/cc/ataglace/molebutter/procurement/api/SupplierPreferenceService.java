package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;
import java.util.*;
import org.springframework.transaction.annotation.*;


public interface SupplierPreferenceService {
    Preferences get(Long actor);
    Store saveStore(Long actor,Long id,StoreInput input);
    Store registerBrandStore(Long actor,StoreInput input,ChannelPreview verified);
    Preferences saveMall(Long actor,ProcurementMall mall,MallPreferenceInput input);
    Preferences deleteMall(Long actor,ProcurementMall mall,Long revision);
    List<IdentityCandidate> identityCandidates(Long actor);
    Store bindIdentity(Long actor,long id,IdentityInput input);
    void deleteStore(Long actor,long id,Long revision);
    Rule saveRule(Long actor,Long id,RuleInput input);
    void deleteRule(Long actor,long id,Long revision);
}
