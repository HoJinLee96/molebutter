package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.ProductStore;
import cc.ataglace.molebutter.procurement.internal.DefaultProductChangeService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.OperationFailure;
@Service @lombok.RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class DefaultProcurementLifecycle implements cc.ataglace.molebutter.procurement.api.ProcurementLifecycle {
    private final ProductStore db;
    private final DefaultProductChangeService changes;
    public void create(long id,String type,String comparison,String query,String mode){db.jdbc.update("INSERT INTO procurement_product(product_id,code_type,comparison_code,search_query,search_mode) VALUES(?,?,?,?,?)",id,type,comparison,query,mode);}
    public void searchQuery(long id,String query){db.jdbc.update("UPDATE procurement_product SET search_query=? WHERE product_id=?",query,id);}
    public void initialSearch(long id,String query){db.jdbc.update("UPDATE procurement_product SET search_query=?,search_mode='MANUAL' WHERE product_id=?",query,id);}
    public void invalidate(long id){changes.reset(id);db.jdbc.update("UPDATE procurement_product SET lookup_revision=lookup_revision+1,latest_status='NOT_CHECKED',latest_result=NULL,latest_at=NULL WHERE product_id=?",id);}
    public void managed(long id,boolean managed){db.jdbc.update("UPDATE procurement_product SET managed=? WHERE product_id=?",managed,id);}
    public void deleted(long id){db.jdbc.update("UPDATE procurement_product SET managed=FALSE,lookup_revision=lookup_revision+1 WHERE product_id=?",id);}
    public void mergedSource(long id){db.jdbc.update("UPDATE procurement_product SET lookup_revision=lookup_revision+1 WHERE product_id=?",id);}
    public void moveHistory(long target,long source){db.jdbc.update("UPDATE product_lookup_history SET product_id=? WHERE product_id=?",target,source);}
    public void ensureIdle(List<Long> ids){
        long active=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT') AND e.product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",Long.class,ids.toArray());
        if(active>0)throw new OperationFailure("해당 상품의 최신화 작업을 완료하거나 취소한 뒤 변경해 주세요.");
    }
}
