package cc.ataglace.molebutter.inventory.internal;


import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.BusinessTime;
import lombok.RequiredArgsConstructor;

/** Called only after the shared product lock, inside the product mutation transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class DefaultInventoryLinkService implements cc.ataglace.molebutter.inventory.api.InventoryLinkService {
    private final JdbcTemplate jdbc;
    private final BusinessTime time;
    public void assertDeletable(List<Long> ids) {
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item WHERE product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND (on_hand>0 OR pending>0)",Long.class,ids.toArray());
        if(count>0)throw new OperationFailure("보유 재고 또는 미입고 수량이 남아 있는 상품은 삭제할 수 없습니다. 재고에서 수량을 먼저 처리해 주세요.");
    }
    public void merge(long target,long source) {
        jdbc.update("UPDATE inventory_item SET product_id=?,revision=revision+1,updated_at=? WHERE product_id=?",target,time.now(),source);
    }
}
