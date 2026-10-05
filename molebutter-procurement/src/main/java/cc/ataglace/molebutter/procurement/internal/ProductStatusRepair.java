package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.SearchCompletion;
import cc.ataglace.molebutter.procurement.internal.SupplierLookupStatusPolicy;
import cc.ataglace.molebutter.procurement.internal.ProductStore;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cc.ataglace.molebutter.procurement.api.SupplierDtos.Preferences;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Explicit maintenance operation; never runs at application startup. */
@Service @RequiredArgsConstructor @Slf4j
public class ProductStatusRepair {
    private final ProductStore db;
    private final PlatformTransactionManager transactions;
    public record Change(long productId,String productCode,long revision,long runId,String before,String after,String message,String reason,String originalJson) {}
    private static final String ELIGIBLE="""
        SELECT p.id,p.product_code,p.revision,p_pm.latest_status,p_pm.latest_result,p_pm.latest_at,
               e.run_id,r.preference_snapshot,e.selection_snapshot,c.result search_result
        FROM catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id
        JOIN product_refresh_entry e ON e.product_id=p.id AND e.lookup_revision=p_pm.lookup_revision
            AND e.query=p_pm.search_query AND e.product_code=p.product_code AND e.checked_at=p_pm.latest_at
        JOIN product_refresh_run r ON r.id=e.run_id
        JOIN product_refresh_search c ON c.run_id=e.run_id AND c.query_hash=SHA2(e.query,256) AND c.checked_at<=e.checked_at
        WHERE p.deleted_at IS NULL AND p.merged_into IS NULL AND p_pm.latest_status IN ('SUCCESS','PARTIAL','SOLD_OUT','NO_MATCH','UNCONFIRMED')
          AND e.status IN ('SUCCESS','PARTIAL','SOLD_OUT','NO_MATCH','UNCONFIRMED')
          AND JSON_UNQUOTE(JSON_EXTRACT(e.selection_snapshot,'$.lookupPolicy'))='SEARCH_QUERY'
          AND r.status IN ('COMPLETED','CANCELLED')
          AND NOT EXISTS(SELECT 1 FROM product_refresh_entry a JOIN product_refresh_run ar ON ar.id=a.run_id
              WHERE a.product_id=p.id AND ar.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT'))
          AND NOT EXISTS(SELECT 1 FROM supplier_stock_lookup q WHERE q.product_id=p.id AND q.status IN ('PENDING','RUNNING','BLOCKED'))
          AND NOT EXISTS(SELECT 1 FROM product_supplier s WHERE s.product_id=p.id AND s.manual_store_id IS NOT NULL)
          AND NOT EXISTS(SELECT 1 FROM product_supplier_change h WHERE h.product_id=p.id AND h.change_type='ASSIGN_STORE' AND h.created_at>=p_pm.latest_at)
        """;
    public List<Change> preview(){return candidates(null);}
    private List<Change> candidates(Long id){
        var rows=id==null?db.jdbc.queryForList(ELIGIBLE):db.jdbc.queryForList(ELIGIBLE+" AND p.id=?",id);
        var changes=new ArrayList<Change>();
        var counts=new HashMap<Long,Integer>();for(var row:rows)counts.merge(((Number)row.get("id")).longValue(),1,Integer::sum);
        for(var row:rows){
            long product=((Number)row.get("id")).longValue();if(counts.get(product)!=1)continue;
            try{
                String original=(String)row.get("latest_result");var latest=db.decode(original,RefreshResult.class);
                var preferences=db.decode((String)row.get("preference_snapshot"),Preferences.class);
                var search=db.decode((String)row.get("search_result"),SearchResult.class);
                String reason=SearchCompletion.reason(search);
                if(latest==null||preferences==null||!SearchCompletion.normal(reason)||latest.checkedAt()==null
                    ||!latest.checkedAt().equals(row.get("latest_at") instanceof java.sql.Timestamp t?t.toLocalDateTime():row.get("latest_at")))continue;
                var next=SearchCompletion.summarize(latest.suppliers(),reason,latest.checkedAt(),preferences,Map.of(),latest.recommendationLimited(),latest.recommendationDiagnostics(),db.decode((String)row.get("selection_snapshot"),cc.ataglace.molebutter.procurement.api.SupplierDtos.SelectionBasis.class));
                String notice=next.message();
                if(Objects.equals(latest.statusPolicyVersion(),SupplierLookupStatusPolicy.VERSION)&&latest.statusReasons().equals(next.statusReasons())&&latest.status().equals(next.status())&&Objects.equals(latest.message(),notice)&&Objects.equals(latest.completionReason(),reason))continue;
                changes.add(new Change(product,(String)row.get("product_code"),((Number)row.get("revision")).longValue(),((Number)row.get("run_id")).longValue(),(String)row.get("latest_status"),next.status(),notice,reason,original));
            }catch(RuntimeException ex){log.warn("[PRODUCT_STATUS_REPAIR_SKIPPED] productId={} exception={}",product,ex.getClass().getSimpleName());}
        }
        return List.copyOf(changes);
    }
    private cc.ataglace.molebutter.procurement.api.ProductDtos.RefreshResult reassessment(Change expected){
        var row=db.jdbc.queryForMap("SELECT r.preference_snapshot,e.selection_snapshot FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE e.run_id=? AND e.product_id=?",expected.runId(),expected.productId());
        var original=db.decode(expected.originalJson(),RefreshResult.class);
        return SearchCompletion.summarize(original.suppliers(),expected.reason(),original.checkedAt(),db.decode((String)row.get("preference_snapshot"),Preferences.class),Map.of(),original.recommendationLimited(),original.recommendationDiagnostics(),db.decode((String)row.get("selection_snapshot"),cc.ataglace.molebutter.procurement.api.SupplierDtos.SelectionBasis.class));
    }
    public boolean apply(Change expected){
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(tx->{
            db.lock(); // Same lock order as refresh, selection and manual stock result commits.
            var current=candidates(expected.productId());
            if(current.size()!=1||!current.getFirst().equals(expected))return false;
            int changed=db.jdbc.update("""
                UPDATE procurement_product SET latest_status=?,latest_result=JSON_SET(latest_result,'$.status',?,'$.message',?,'$.completionReason',?,'$.statusPolicyVersion',?,'$.statusReasons',CAST(? AS JSON))
                WHERE product_id=? AND (SELECT revision FROM catalog_product WHERE id=product_id)=? AND latest_status=?
                """,expected.after(),expected.after(),expected.message(),expected.reason(),SupplierLookupStatusPolicy.VERSION,db.encode(reassessment(expected).statusReasons()),expected.productId(),expected.revision(),expected.before());
            if(changed==1)db.catalog.bump(expected.productId());
            if(changed==1)log.info("[PRODUCT_STATUS_REPAIRED] productId={} runId={} before={} after={} reason={}",expected.productId(),expected.runId(),expected.before(),expected.after(),expected.reason());
            return changed==1;
        }));
    }
}
