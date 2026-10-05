package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.ProductCodePolicy;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupStatusPolicy;
import cc.ataglace.molebutter.procurement.internal.DefaultProductSupplierService;
import cc.ataglace.molebutter.procurement.internal.ProductStore;
import cc.ataglace.molebutter.procurement.internal.DefaultProductChangeService;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultProcurementProductQueries implements cc.ataglace.molebutter.procurement.api.ProcurementProductQueries {
    private final ProductStore db;
    private final DefaultProductChangeService changes;
    private final DefaultProductSupplierService suppliers;
    /**
     * 대표 사진: 선정 판매글의 최신 사진, 없으면 마지막 최신화의 첫 판매글(검색 1순위) 사진. 조회 때 계산해 선정·해제·병합에 바로
     * 맞춰진다.
     */
    private static final String SELECT = """
            SELECT p.id,p.brand_id,p.product_code,p_pm.comparison_code,p_pm.code_type,p_pm.search_query,p_pm.search_mode,
            p_pm.managed,p.revision,p_pm.lookup_revision,COALESCE(ss.image_url,p_pm.image_url) image_url,p_pm.latest_status,p_pm.latest_at,p_pm.latest_result,
            COALESCE(b.name,'') brand_name,COALESCE(b.code_brand,'') brand_key,
            (SELECT COUNT(*) FROM catalog_product d WHERE p.product_code<>'' AND UPPER(TRIM(d.product_code))=UPPER(TRIM(p.product_code)) AND d.id<>p.id AND d.merged_into IS NULL AND d.deleted_at IS NULL) duplicate_count
            FROM catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id LEFT JOIN product_brand b ON b.id=p.brand_id
            LEFT JOIN product_supplier_selection sel ON sel.product_id=p.id LEFT JOIN product_supplier ss ON ss.id=sel.supplier_id
            """;

    public PageResponse<CatalogProduct> list(Long actor, String q, String mode, String status, int page) {
        return list(actor, q, mode, status, page, 20);
    }

    public PageResponse<CatalogProduct> list(Long actor, String q, String mode, String status, int page, int size) {
        return list(actor, q, mode, status, page, size, "ALL");
    }

    public PageResponse<CatalogProduct> list(Long actor, String q, String mode, String status, int page, int size,
            String change) {
        if (!List.of("ALL", "SELECTED", "ANY").contains(change))
            throw new InputValidationFailure("변동 필터를 확인해 주세요.");
        db.authorize(actor, false);
        if (page < 0 || !List.of(20, 50, 100).contains(size))
            throw new InputValidationFailure("페이지와 표시 개수(20·50·100)를 확인해 주세요.");
        q = ProductStore.text(q, 255, false);
        status = ProductStore.text(status, 30, false);
        if (!List.of("ALL", "AUTO", "MANUAL").contains(mode))
            throw new InputValidationFailure("관리 구분을 확인해 주세요.");
        String where = " WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND (?='' OR LOCATE(?,COALESCE(b.name,''))>0 OR LOCATE(?,p.product_code)>0 OR LOCATE(?,p_pm.search_query)>0) AND (?='ALL' OR p_pm.managed=(?='AUTO')) AND (?='' OR (CASE p_pm.latest_status WHEN 'NO_MATCH' THEN 'SOLD_OUT' WHEN 'STALE' THEN 'NOT_CHECKED' WHEN 'BLOCKED' THEN 'FAILED' ELSE p_pm.latest_status END)=?)";
        if (!change.equals("ALL"))
            where += " AND EXISTS(SELECT 1 FROM product_change_summary d WHERE d.product_id=p.id AND d."
                    + (change.equals("SELECTED") ? "selected_changed" : "any_changed") + "=TRUE)";
        Object[] args = { q, q, q, q, mode, mode, status, status };
        long count = db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id LEFT JOIN product_brand b ON b.id=p.brand_id" + where,
                Long.class, args);
        var params = new ArrayList<>(List.of(args));
        params.add(size);
        params.add((long) page * size);
        String summary = SELECT.replace("p_pm.latest_result,",
                "JSON_SET(p_pm.latest_result,'$.suppliers',JSON_ARRAY()) latest_result,");
        return new PageResponse<>(catalogs(summary + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?", params.toArray()),
                page, (int) ((count + size - 1) / size), count);
    }

    public cc.ataglace.molebutter.procurement.api.ChangeDtos.Counts changeCounts(Long actor, String q, String mode,
            String status) {
        db.authorize(actor, false);
        q = ProductStore.text(q, 255, false);
        status = ProductStore.text(status, 30, false);
        if (!List.of("ALL", "AUTO", "MANUAL").contains(mode))
            throw new InputValidationFailure("관리 구분을 확인해 주세요.");
        var row = db.jdbc.queryForMap(
                "SELECT COALESCE(SUM(d.selected_changed),0) selected_count,COALESCE(SUM(d.any_changed),0) all_count FROM catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id LEFT JOIN product_brand b ON b.id=p.brand_id JOIN product_change_summary d ON d.product_id=p.id WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND (?='' OR LOCATE(?,COALESCE(b.name,''))>0 OR LOCATE(?,p.product_code)>0 OR LOCATE(?,p_pm.search_query)>0) AND (?='ALL' OR p_pm.managed=(?='AUTO')) AND (?='' OR (CASE p_pm.latest_status WHEN 'NO_MATCH' THEN 'SOLD_OUT' WHEN 'STALE' THEN 'NOT_CHECKED' WHEN 'BLOCKED' THEN 'FAILED' ELSE p_pm.latest_status END)=?)",
                q, q, q, q, mode, mode, status, status);
        return new cc.ataglace.molebutter.procurement.api.ChangeDtos.Counts(
                ((Number) row.get("selected_count")).longValue(), ((Number) row.get("all_count")).longValue());
    }

    private List<CatalogProduct> catalogs(String sql, Object... args) {
        var rows = db.jdbc.query(sql, (r, n) -> new CatalogProduct(r.getString("id"), r.getString("brand_name"),
                r.getString("brand_id"), r.getString("product_code"), r.getString("comparison_code"),
                r.getString("code_type"), r.getString("brand_key"), r.getString("search_query"),
                r.getString("search_mode"),
                ProductCodePolicy.suggested(r.getString("code_type"), r.getString("product_code")),
                r.getBoolean("managed"), r.getLong("revision"), r.getLong("lookup_revision"), r.getString("image_url"),
                r.getLong("duplicate_count"), SupplierLookupStatusPolicy.display(r.getString("latest_status"), null),
                ProductStore.date(r, "latest_at"), db.decode(r.getString("latest_result"), RefreshResult.class), null),
                args);
        var ids = rows.stream().map(CatalogProduct::id).toList();
        var selections = suppliers.selected(ids);
        var delta = changes.summaries(ids);
        return rows.stream().map(p -> p.withSelection(selections.get(p.id())).withChanges(delta.get(p.id()))).toList();
    }

    public CatalogProduct product(long id) {
        return catalogs(SELECT + " WHERE p.id=? AND p.merged_into IS NULL AND p.deleted_at IS NULL", id).stream()
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public Map<String, Object> detail(Long actor, long id) {
        db.authorize(actor, false);
        var p = product(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", p);
        result.put("activeRefresh", db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE e.product_id=? AND e.lookup_revision=? AND e.status IN ('PENDING','CHECKING') AND r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')",
                Long.class, id, p.lookupRevision()) > 0);
        result.put("suppliers", db.jdbc.queryForList(
                "SELECT CAST(id AS CHAR) id,mall,mall_product_id mallProductId,naver_product_id naverProductId,url,image_url imageUrl,last_seen_at lastSeenAt FROM product_supplier WHERE product_id=? ORDER BY id",
                id));
        result.put("lastGoodResult", db.decode(
                db.jdbc.queryForObject("SELECT last_good_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?", String.class, id),
                RefreshResult.class));
        result.put("assessmentSettingsChanged", Boolean.TRUE.equals(db.jdbc.queryForObject(
                "SELECT COALESCE((SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(r.preference_snapshot,'$.revision')) AS UNSIGNED)<>(SELECT preference_revision FROM procurement_settings WHERE id=1) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE e.product_id=? ORDER BY e.run_id DESC LIMIT 1),FALSE)",
                Boolean.class, id)));
        result.put("comparison", suppliers.comparison(id));
        result.put("changes", changes.summary(id));
        result.put("changeSuppliers", changes.historySuppliers(id));
        result.put("history", history(actor, id, 0));
        return result;
    }

    public PageResponse<Map<String, Object>> history(Long actor, long id, int page) {
        db.authorize(actor, false);
        product(id);
        if (page < 0)
            throw new InputValidationFailure("페이지를 확인해 주세요.");
        long count = db.jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history WHERE product_id=?",
                Long.class, id);
        var rows = db.jdbc.queryForList(
                "SELECT CAST(id AS CHAR) id,CAST(run_id AS CHAR) runId,legacy,created_at createdAt,payload FROM product_lookup_history WHERE product_id=? ORDER BY id DESC LIMIT 20 OFFSET ?",
                id, (long) page * 20);
        rows.forEach(r -> r.put("payload", db.json.readTree(r.get("payload").toString())));
        return new PageResponse<>(rows, page, (int) ((count + 19) / 20), count);
    }

    public List<String> brands(Long actor) {
        db.authorize(actor, false);
        return db.jdbc.queryForList("SELECT name FROM product_brand ORDER BY name,id", String.class);
    }

    public List<CatalogProduct> duplicates(Long actor, long id) {
        db.authorize(actor, false);
        var p = product(id);
        return p.productCode().isBlank() ? List.of()
                : catalogs(SELECT
                        + " WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND UPPER(TRIM(p.product_code))=? AND p.id<>? ORDER BY p.id",
                        ProductCodePolicy.normalize(p.productCode()), id);
    }

    public void validateSupplierReference(long product,long supplier) {
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier WHERE id=? AND product_id=?",Long.class,supplier,product)==0)throw new InputValidationFailure("연결 상품의 매입처 판매글을 선택해 주세요.");
    }
}
