package cc.ataglace.molebutter.procurement.internal;

import cc.ataglace.molebutter.catalog.api.CatalogCommands.BrandSelection;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.procurement.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@lombok.RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DefaultProcurementLifecycle implements ProcurementLifecycle {
    private final ProductStore db;
    private final DefaultProductChangeService changes;

    private void managedProduct(long id) {
        db.requireExclusive();
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM procurement_product WHERE product_id=?", Long.class, id) != 1)
            throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    public String validateSearchQuery(String query) {
        return BusinessText.checked(query, 255, true, "필수 입력값과 입력 길이를 확인해 주세요.");
    }
    public void create(long id, String type, String comparison, String query, String mode) {
        db.requireExclusive();
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL", Long.class, id) != 1)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!Set.of("GENERAL", "LF_ACCESSORY").contains(type) || !Set.of("AUTO", "MANUAL").contains(mode))
            throw new InputValidationFailure("조회 기준을 확인해 주세요.");
        db.jdbc.update("INSERT INTO procurement_product(product_id,code_type,comparison_code,search_query,search_mode) VALUES(?,?,?,?,?)",
                id, type, BusinessText.checked(comparison,100,false,"필수 입력값과 입력 길이를 확인해 주세요."), validateSearchQuery(query), mode);
    }
    public void searchQuery(long id, String query) {
        managedProduct(id);
        db.jdbc.update("UPDATE procurement_product SET search_query=? WHERE product_id=?", validateSearchQuery(query), id);
    }
    public void initialSearch(long id) {
        managedProduct(id);
        var base = db.jdbc.queryForMap("SELECT product_code,brand_id FROM catalog_product WHERE id=?", id);
        var brand = db.catalog.resolveBrand(new BrandSelection(base.get("brand_id") == null ? "" : base.get("brand_id").toString(), null));
        String query = ProductCodePolicy.suggested(brand.key().isBlank() ? "GENERAL" : "LF_ACCESSORY", base.get("product_code").toString());
        db.jdbc.update("UPDATE procurement_product SET search_query=?,search_mode='MANUAL' WHERE product_id=?", validateSearchQuery(query), id);
    }
    public void invalidate(long id) {
        managedProduct(id); changes.reset(id);
        db.jdbc.update("UPDATE procurement_product SET lookup_revision=lookup_revision+1,latest_status='NOT_CHECKED',latest_result=NULL,latest_at=NULL WHERE product_id=?", id);
    }
    public void managed(long id, boolean value) {
        managedProduct(id); db.jdbc.update("UPDATE procurement_product SET managed=? WHERE product_id=?", value, id);
    }
    public void deleted(long id) {
        managedProduct(id); db.jdbc.update("UPDATE procurement_product SET managed=FALSE,lookup_revision=lookup_revision+1 WHERE product_id=?", id);
    }
    public void mergedSource(long id) {
        managedProduct(id); db.jdbc.update("UPDATE procurement_product SET lookup_revision=lookup_revision+1 WHERE product_id=?", id);
    }
    public void moveHistory(long target, long source) {
        managedProduct(target); managedProduct(source);
        db.jdbc.update("UPDATE product_lookup_history SET product_id=? WHERE product_id=?", target, source);
    }
    public void ensureIdle(List<Long> ids) {
        db.requireExclusive();
        if (ids == null || ids.isEmpty()) throw new InputValidationFailure("상품을 선택해 주세요.");
        long active = db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT') AND e.product_id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", Long.class, ids.toArray());
        if (active > 0) throw new OperationFailure("해당 상품의 최신화 작업을 완료하거나 취소한 뒤 변경해 주세요.");
    }
}
