package cc.ataglace.molebutter.inventory.internal;


import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.BusinessTime;
import lombok.RequiredArgsConstructor;

/** Called only after the exclusive catalog guard, inside the product mutation transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class DefaultInventoryLinkService implements cc.ataglace.molebutter.inventory.api.InventoryLinkService {
    private final JdbcTemplate jdbc;
    private final cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard guard;
    private final BusinessTime time;
    public void assertDeletable(List<Long> ids) {
        guard.requireExclusive();
        if(ids==null||ids.isEmpty())throw new cc.ataglace.molebutter.common.api.InputValidationFailure("상품을 선택해 주세요.");
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item WHERE product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND (on_hand>0 OR pending>0)",Long.class,ids.toArray());
        if(count>0)throw new OperationFailure("보유 재고 또는 미입고 수량이 남아 있는 상품은 삭제할 수 없습니다. 재고에서 수량을 먼저 처리해 주세요.");
    }
    public void merge(long target,long source) {
        guard.requireExclusive();
        if(target==source||jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE id IN (?,?) AND deleted_at IS NULL AND merged_into IS NULL",Long.class,target,source)!=2)throw new cc.ataglace.molebutter.common.api.BusinessException(cc.ataglace.molebutter.common.api.ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE inventory_item SET product_id=?,revision=revision+1,updated_at=? WHERE product_id=?",target,time.now(),source);
    }
}
