package cc.ataglace.molebutter.service.product;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import lombok.RequiredArgsConstructor;

/** Short durable writes; never holds a transaction across a supplier HTTP request. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RecommendationLookupService {
    private final ProductStore db;
    public Map<ProcurementMall,RecommendationDiagnostic> restrictions(long run){
        var result=new EnumMap<ProcurementMall,RecommendationDiagnostic>(ProcurementMall.class);
        db.jdbc.queryForList("SELECT payload FROM recommendation_lookup_diagnostic WHERE run_id=? AND restricted=TRUE ORDER BY id",String.class,run)
            .forEach(v->{var d=db.decode(v,RecommendationDiagnostic.class);result.putIfAbsent(d.mall(),d);});
        return result;
    }
    public boolean restricted(long run,ProcurementMall mall){return db.jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_lookup_diagnostic WHERE run_id=? AND mall=? AND restricted=TRUE",Long.class,run,mall.name())>0;}
    public List<RecommendationDiagnostic> list(long run,long product){return db.jdbc.queryForList("SELECT payload FROM recommendation_lookup_diagnostic WHERE run_id=? AND product_id=? ORDER BY id",String.class,run,product).stream().map(v->db.decode(v,RecommendationDiagnostic.class)).toList();}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void record(String owner,SupplierRefreshService.Work work,RecommendationDiagnostic diagnostic,boolean restricted){
        db.lock();
        if(owner==null||db.jdbc.queryForObject("SELECT COUNT(*) FROM product_settings WHERE id=1 AND worker_owner=? AND worker_until>?",Long.class,owner,db.time.now())!=1
            ||db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_run r JOIN product_refresh_entry e ON e.run_id=r.id JOIN catalog_product p ON p.id=e.product_id WHERE r.id=? AND r.status IN ('RUNNING','PAUSED') AND e.product_id=? AND e.status='CHECKING' AND e.lookup_revision=? AND p.lookup_revision=e.lookup_revision AND p.deleted_at IS NULL AND p.merged_into IS NULL",Long.class,work.runId(),work.productId(),work.revision())!=1)
            throw new IllegalStateException("작업 소유권 또는 조회 기준이 변경되었습니다.");
        db.jdbc.update("INSERT INTO recommendation_lookup_diagnostic(id,run_id,product_id,listing_key,mall,kind,cause_code,http_status,restricted,created_at,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE restricted=(restricted OR VALUES(restricted)),kind=IF(VALUES(restricted),VALUES(kind),kind),cause_code=IF(VALUES(restricted),VALUES(cause_code),cause_code),http_status=IF(VALUES(restricted),VALUES(http_status),http_status),payload=IF(VALUES(restricted),VALUES(payload),payload)",
            ProductStore.id(),work.runId(),work.productId(),diagnostic.listingKey(),diagnostic.mall().name(),diagnostic.kind(),diagnostic.causeCode(),diagnostic.httpStatus(),restricted,diagnostic.createdAt(),db.encode(diagnostic));
    }
}
