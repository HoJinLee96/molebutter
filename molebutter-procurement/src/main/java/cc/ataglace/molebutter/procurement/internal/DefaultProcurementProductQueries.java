package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.ProductCodePolicy;

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
    public SelectedStock selectedStock(Long actor,long id) {
        return selectedStocks(actor,List.of(product(id))).get(Long.toString(id));
    }
    @Transactional(readOnly=true)
    public Map<String,SelectedStock> selectedStocks(Long actor,List<ProcurementProductView> products) {
        db.authorize(actor,false);
        var result=new LinkedHashMap<String,SelectedStock>();
        var pending=new LinkedHashMap<String,ProcurementProductView>();
        for(var p:products){
            var l=p.selectedSupplier();
            if(l==null)result.put(p.id(),new SelectedStock(List.of(),true,null));
            else if(stockKnown(l.result())&&l.current()&&Set.of("SUCCESS","PARTIAL","SOLD_OUT").contains(Objects.toString(p.latestStatus(),"")))result.put(p.id(),new SelectedStock(l.result().options(),false,normalStockAt(l.result(),p.latestAt()==null?null:p.latestAt().toString())));
            else pending.put(p.id(),p);
        }
        if(pending.isEmpty())return searchEvidence(products,result);
        var retained=retainedStocks(pending);
        pending.forEach((id,p)->{
            var selected=p.selectedSupplier();var latest=selected.result();var old=retained.getOrDefault(id,new SelectedStock(List.of(),true,null));
            if(stockKnown(latest)){result.put(id,new SelectedStock(latest.options(),true,normalStockAt(latest,Objects.equals(old.options(),latest.options())?old.checkedAt():null)));return;}
            var config=latest==null?List.<SourceOption>of():latest.options();
            if(config!=null&&!config.isEmpty()){
                var ids=config.stream().map(SourceOption::id).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
                result.put(id,new SelectedStock(old.options().stream().filter(o->o.id()!=null&&ids.contains(o.id())).toList(),true,old.checkedAt(),config.size()));
            }else result.put(id,old);
        });
        return searchEvidence(products,result);
    }
    private record SearchRow(String product,long run,long latest,java.time.LocalDateTime at,boolean normal,boolean found) {}
    private Map<String,SelectedStock> searchEvidence(List<ProcurementProductView> products,Map<String,SelectedStock> stocks) {
        var eligible=products.stream().filter(p->p.selectedSupplier()!=null).toList();
        var searches=new HashMap<String,SearchEvidence>();
        for(int offset=0;offset<eligible.size();offset+=100) {
            var batch=new LinkedHashMap<String,ProcurementProductView>();
            eligible.subList(offset,Math.min(eligible.size(),offset+100)).forEach(p->batch.put(p.id(),p));
            var cursors=new LinkedHashMap<String,Long>();batch.keySet().forEach(id->cursors.put(id,Long.MAX_VALUE));
            while(!cursors.isEmpty()) {
                var args=new ArrayList<Object>();cursors.forEach((id,before)->{args.add(id);args.add(before);args.add(batch.get(id).selectedSupplier().id());});
                var rows=db.jdbc.query("""
                    SELECT wanted.product_id,h.run_id,
                      (SELECT MAX(e.run_id) FROM product_refresh_entry e WHERE e.product_id=wanted.product_id) latest_run,
                      c.checked_at,
                      JSON_OBJECT('complete',JSON_EXTRACT(c.result,'$.complete'),'completionReason',JSON_EXTRACT(c.result,'$.completionReason')) search_json,
                      JSON_TYPE(JSON_EXTRACT(c.result,'$.searchKeys'))='ARRAY' keys_known,
                      (JSON_CONTAINS(c.result,JSON_QUOTE(CONCAT(s.mall,':',s.mall_product_id,':',COALESCE(s.naver_product_id,'null'))),'$.searchKeys')
                        OR (s.naver_product_id IS NOT NULL AND JSON_CONTAINS(c.result,JSON_QUOTE(CONCAT('naver:',s.naver_product_id)),'$.searchKeys'))) found
                    FROM (
                    """+String.join(" UNION ALL ",Collections.nCopies(cursors.size(),
                        "SELECT CAST(? AS UNSIGNED) product_id,CAST(? AS UNSIGNED) before_id,CAST(? AS UNSIGNED) supplier_id"))+"""
                    ) wanted JOIN catalog_product p ON p.id=wanted.product_id
                    JOIN procurement_product pp ON pp.product_id=p.id
                    JOIN product_supplier s ON s.id=wanted.supplier_id AND s.product_id=p.id AND s.merged_into IS NULL
                    JOIN LATERAL (SELECT e.run_id,e.query,e.checked_at FROM product_refresh_entry e
                      WHERE e.product_id=wanted.product_id AND e.run_id<=wanted.before_id
                        AND e.lookup_revision=pp.lookup_revision AND e.query=pp.search_query AND e.product_code=p.product_code
                        AND e.status IN ('SUCCESS','PARTIAL','SOLD_OUT','NO_MATCH','UNCONFIRMED') AND e.result IS NOT NULL
                      ORDER BY e.run_id DESC LIMIT 20) h ON TRUE
                    LEFT JOIN product_refresh_search c ON c.run_id=h.run_id AND c.query_hash=SHA2(h.query,256) AND c.checked_at<=h.checked_at
                    ORDER BY wanted.product_id,h.run_id DESC
                    """,(r,n)->{
                        var search=db.decode(r.getString("search_json"),SearchResult.class);
                        return new SearchRow(r.getString("product_id"),r.getLong("run_id"),r.getLong("latest_run"),
                            ProductStore.date(r,"checked_at"),r.getBoolean("keys_known")&&SearchCompletion.normal(SearchCompletion.reason(search)),r.getBoolean("found"));
                    },args.toArray());
                var seen=new HashSet<String>();var done=new HashSet<String>();
                for(var row:rows) {
                    seen.add(row.product());cursors.put(row.product(),row.run()-1);
                    if(done.contains(row.product())||!row.normal()||row.at()==null)continue;
                    var evidence=searches.get(row.product());
                    if(evidence==null){evidence=new SearchEvidence(!row.found(),row.run()!=row.latest(),row.at().toString(),null);searches.put(row.product(),evidence);}
                    if(!row.found()) {
                        searches.put(row.product(),new SearchEvidence(evidence.selectedMissing(),evidence.previous(),evidence.checkedAt(),row.at().toString()));done.add(row.product());
                    } else {
                        String stockAt=stocks.get(row.product()).checkedAt();
                        // Older absences cannot invalidate a newer normal stock observation.
                        if(stockAt==null||!row.at().isAfter(java.time.LocalDateTime.parse(stockAt)))done.add(row.product());
                    }
                }
                cursors.keySet().removeIf(id->done.contains(id)||!seen.contains(id));
            }
        }
        var result=new LinkedHashMap<String,SelectedStock>();stocks.forEach((id,stock)->result.put(id,
            new SelectedStock(stock.options(),stock.previous(),stock.checkedAt(),stock.optionCount(),searches.get(id))));
        return Map.copyOf(result);
    }
    private record HistoryStock(String product,long id,String checkedAt,List<SupplierResult> matches) {}
    private Map<String,SelectedStock> retainedStocks(Map<String,ProcurementProductView> pending) {
        var retained=new HashMap<String,SelectedStock>();
        var batch=new LinkedHashMap<String,ProcurementProductView>();
        for(var entry:pending.entrySet()) {
            batch.put(entry.getKey(),entry.getValue());
            if(batch.size()==100){retained.putAll(retainedStockBatch(batch));batch.clear();}
        }
        if(!batch.isEmpty())retained.putAll(retainedStockBatch(batch));
        return retained;
    }
    private Map<String,SelectedStock> retainedStockBatch(Map<String,ProcurementProductView> pending) {
        var retained=new HashMap<String,SelectedStock>();
        var cursors=new LinkedHashMap<String,Long>();pending.keySet().forEach(id->cursors.put(id,Long.MAX_VALUE));
        while(!cursors.isEmpty()) {
            // Per-product keyset reads use ix_lookup_history(product_id,id); no repeated window sort.
            // Transfer only the selected listing's stock evidence, never the full search payload.
            var args=new ArrayList<Object>();
            cursors.forEach((id,before)->{args.add(id);args.add(before);args.add(pending.get(id).selectedSupplier().id());});
            var histories=new LinkedHashMap<Long,HistoryStock>();
            db.jdbc.query("""
                SELECT h.product_id,h.id,h.created_at,
                  CASE WHEN t.ord IS NULL THEN NULL ELSE JSON_OBJECT('match',JSON_EXTRACT(t.supplier,'$.match'),
                    'state',JSON_EXTRACT(t.supplier,'$.state'),'options',JSON_EXTRACT(t.supplier,'$.options'),'stockEvidence',JSON_EXTRACT(t.supplier,'$.stockEvidence')) END stock_json
                FROM (
                """+String.join(" UNION ALL ",Collections.nCopies(cursors.size(),
                    "SELECT CAST(? AS UNSIGNED) product_id,CAST(? AS UNSIGNED) before_id,CAST(? AS UNSIGNED) supplier_id"))+"""
                ) wanted JOIN product_supplier s ON s.id=wanted.supplier_id
                JOIN LATERAL (SELECT x.product_id,x.id,x.created_at,x.payload FROM product_lookup_history x
                  WHERE x.product_id=wanted.product_id AND x.id<=wanted.before_id ORDER BY x.id DESC LIMIT 20) h ON TRUE
                LEFT JOIN JSON_TABLE(h.payload,'$.suppliers[*]' COLUMNS(
                  ord FOR ORDINALITY,supplier JSON PATH '$',mall JSON PATH '$.offer.mall',
                  mall_product_id JSON PATH '$.offer.mallProductId',naver_product_id JSON PATH '$.offer.naverProductId')) t
                  ON CAST(JSON_UNQUOTE(t.mall) AS BINARY)=CAST(s.mall AS BINARY)
                    AND CAST(JSON_UNQUOTE(t.mall_product_id) AS BINARY)=CAST(s.mall_product_id AS BINARY)
                    AND CAST(COALESCE(JSON_UNQUOTE(t.naver_product_id),'') AS BINARY)=CAST(COALESCE(s.naver_product_id,'') AS BINARY)
                ORDER BY h.product_id,h.id DESC,t.ord
                """,r->{
                    long historyId=r.getLong("id");
                    var history=histories.computeIfAbsent(historyId,ignored->{try{return new HistoryStock(r.getString("product_id"),historyId,
                        ProductStore.date(r,"created_at").toString(),new ArrayList<>());}catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}});
                    String stock=r.getString("stock_json");if(stock!=null){var match=db.decode(stock,SupplierResult.class);if(stockKnown(match))history.matches().add(match);}
                },args.toArray());
            var seen=new HashSet<String>();
            for(var history:histories.values()) {
                String id=history.product();seen.add(id);cursors.put(id,history.id()-1);
                if(!retained.containsKey(id)&&history.matches().size()==1)
                    retained.put(id,new SelectedStock(history.matches().getFirst().options(),true,normalStockAt(history.matches().getFirst(),history.checkedAt())));
            }
            cursors.keySet().removeIf(id->retained.containsKey(id)||!seen.contains(id));
        }
        return retained;
    }
    private static String normalStockAt(SupplierResult observation,String fallback) {
        var proof=observation.stockEvidence();
        return proof!=null&&proof.directVerified()&&proof.checkedAt()!=null?proof.checkedAt().toString():fallback;
    }
    private static boolean stockKnown(SupplierResult result) {
        return result!=null&&result.accepted()&&Set.of("CONFIRMED","OPTIONS_PARTIAL","SOLD_OUT").contains(Objects.toString(result.state(),""))
            &&result.options()!=null&&result.options().stream().anyMatch(o->o.stock()!=null&&o.stock()>=0||Set.of("SOLD_OUT","UNAVAILABLE").contains(Objects.toString(o.state(),"")));
    }
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

    public PageResponse<ProcurementProductView> list(Long actor, String q, String mode, String status, int page) {
        return list(actor, q, mode, status, page, 20);
    }

    public PageResponse<ProcurementProductView> list(Long actor, String q, String mode, String status, int page, int size) {
        return list(actor, q, mode, status, page, size, "ALL");
    }

    public PageResponse<ProcurementProductView> list(Long actor, String q, String mode, String status, int page, int size,
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

    public PageResponse<ProcurementProductView> byCode(Long actor,String code,int page,int size) {
        db.authorize(actor,false);
        if(page<0 || page>1000000 || !List.of(20,50,100).contains(size))throw new InputValidationFailure("페이지와 표시 개수를 확인해 주세요.");
        String normalized=ProductCodePolicy.normalize(ProductStore.text(code,100,true));
        String where=" WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND CAST(UPPER(TRIM(p.product_code)) AS BINARY)=CAST(? AS BINARY)";
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id"+where,Long.class,normalized);
        String summary=SELECT.replace("p_pm.latest_result,","JSON_SET(p_pm.latest_result,'$.suppliers',JSON_ARRAY()) latest_result,");
        return new PageResponse<>(catalogs(summary+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",normalized,size,(long)page*size),page,(int)((count+size-1)/size),count);
    }

    public List<ProcurementProductView> byCodes(Long actor,Set<String> codes) {
        db.authorize(actor,false);
        if(codes==null)throw new InputValidationFailure("상품코드를 확인해 주세요.");
        var normalized=codes.stream().map(c->ProductCodePolicy.normalize(ProductStore.text(c,100,true))).distinct().toList();
        var result=new ArrayList<ProcurementProductView>();
        // Count exact active codes once per batch, rather than a correlated scan for every product.
        String summary="""
            WITH exact_codes AS (
              SELECT CAST(UPPER(TRIM(product_code)) AS BINARY) code,COUNT(*) matches
              FROM catalog_product WHERE merged_into IS NULL AND deleted_at IS NULL
              GROUP BY CAST(UPPER(TRIM(product_code)) AS BINARY)
            )
            SELECT p.id,p.brand_id,p.product_code,p_pm.comparison_code,p_pm.code_type,p_pm.search_query,p_pm.search_mode,
              p_pm.managed,p.revision,p_pm.lookup_revision,COALESCE(ss.image_url,p_pm.image_url) image_url,
              p_pm.latest_status,p_pm.latest_at,JSON_SET(p_pm.latest_result,'$.suppliers',JSON_ARRAY()) latest_result,
              COALESCE(b.name,'') brand_name,COALESCE(b.code_brand,'') brand_key,exact_codes.matches-1 duplicate_count
            FROM catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id
              LEFT JOIN product_brand b ON b.id=p.brand_id
              LEFT JOIN product_supplier_selection sel ON sel.product_id=p.id LEFT JOIN product_supplier ss ON ss.id=sel.supplier_id
              JOIN exact_codes ON exact_codes.code=CAST(UPPER(TRIM(p.product_code)) AS BINARY)
            """;
        // Bound IN parameters and hydrate selected listings in batches, never per Coupang option.
        for(int start=0;start<normalized.size();start+=100) {
            var batch=normalized.subList(start,Math.min(start+100,normalized.size()));
            result.addAll(catalogs(summary+" WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND CAST(UPPER(TRIM(p.product_code)) AS BINARY) IN ("+String.join(",",Collections.nCopies(batch.size(),"CAST(? AS BINARY)"))+") ORDER BY p.id DESC",batch.toArray()));
        }
        return List.copyOf(result);
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

    private List<ProcurementProductView> catalogs(String sql, Object... args) {
        var rows = db.jdbc.query(sql, (r, n) -> new ProcurementProductView(r.getString("id"), r.getString("brand_name"),
                r.getString("brand_id"), r.getString("product_code"), r.getString("comparison_code"),
                r.getString("code_type"), r.getString("brand_key"), r.getString("search_query"),
                r.getString("search_mode"),
                ProductCodePolicy.suggested(r.getString("code_type"), r.getString("product_code")),
                r.getBoolean("managed"), r.getLong("revision"), r.getLong("lookup_revision"), r.getString("image_url"),
                r.getLong("duplicate_count"), SupplierLookupStatusPolicy.display(r.getString("latest_status"), null),
                ProductStore.date(r, "latest_at"), db.decode(r.getString("latest_result"), RefreshResult.class), null),
                args);
        var ids = rows.stream().map(ProcurementProductView::id).toList();
        var selections = suppliers.selected(ids);
        var delta = changes.summaries(ids);
        return rows.stream().map(p -> p.withSelection(selections.get(p.id())).withChanges(delta.get(p.id()))).toList();
    }

    public ProcurementProductView product(long id) {
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

    public List<ProcurementProductView> duplicates(Long actor, long id) {
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
