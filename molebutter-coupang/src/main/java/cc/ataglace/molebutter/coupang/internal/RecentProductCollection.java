package cc.ataglace.molebutter.coupang.internal;

import java.time.*;
import java.util.*;
import java.math.BigInteger;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.*;

/** Page checkpoints and candidate selection are committed together; no global deletion reconciliation. */
final class RecentProductCollection {
    static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    record Conditions(LocalDate from,LocalDate to,int maximum,LocalDateTime cutoff) {}
    static Conditions validate(Collect input,Instant now) {
        try {
            LocalDate from=LocalDate.parse(input.createdDateFrom()),to=LocalDate.parse(input.createdDateTo());
            int n=Objects.requireNonNull(input.maxProducts());
            if(!"RECENT".equals(input.mode()) || input.sellerProductIds()!=null&&!input.sellerProductIds().isEmpty()
                || from.isAfter(to) || from.plusDays(89).isBefore(to) || to.isAfter(now.atZone(KOREA).toLocalDate()) || n<1||n>500)throw new IllegalArgumentException();
            return new Conditions(from,to,n,now.atZone(KOREA).toLocalDateTime());
        }catch(RuntimeException failure){throw new InputValidationFailure("등록 기간은 한국 날짜 기준 최대 90일, 상품 수는 1~500개로 지정해 주세요. 최근 조건과 선택 ID는 함께 사용할 수 없습니다.");}
    }
    static final class SelectionFailure extends RuntimeException {
        final String code;SelectionFailure(String code){super(code);this.code=code;}
    }
    private final RegisteredProductStore store;
    RecentProductCollection(RegisteredProductStore store){this.store=store;}
    void create(long job,Conditions c) {
        store.db.update("INSERT INTO coupang_collection_recent(job_id,created_date_from,created_date_to,max_products,cutoff_at,current_date_value) VALUES(?,?,?,?,?,?)",job,c.from().toString(),c.to().toString(),c.maximum(),c.cutoff().toString(),c.to().toString());
    }
    RecentCollection snapshot(long job) {
        var rows=store.db.query("SELECT r.*,(SELECT COUNT(*) FROM coupang_collection_candidate c WHERE c.job_id=r.job_id) candidates,(SELECT COUNT(*) FROM coupang_collection_candidate c WHERE c.job_id=r.job_id AND c.selected_rank IS NOT NULL) selected FROM coupang_collection_recent r WHERE job_id=?",
            (r,n)->new RecentCollection(r.getString("created_date_from"),r.getString("created_date_to"),r.getInt("max_products"),r.getString("cutoff_at"),r.getString("current_date_value"),r.getLong("api_pages"),r.getLong("candidates"),r.getLong("selected"),r.getBoolean("selection_complete")),job);
        if(rows.isEmpty())throw new SelectionFailure("RECENT_STATE_MISSING");return rows.getFirst();
    }
    static LocalDateTime created(String value) {
        try{return LocalDateTime.parse(value);}catch(RuntimeException failure){throw new SelectionFailure("RECENT_CREATED_AT_INVALID");}
    }
    static boolean deleted(CoupangCatalog.Product p) {return Set.of("DELETED","상품삭제","삭제").contains(Objects.toString(p.statusName(),""));}
    void page(long job,Long actor,String account,String owner,Map<String,Object> state,CoupangCatalog catalog) {
        var scope=snapshot(job);
        if(scope.selectionComplete())throw new SelectionFailure("RECENT_ALREADY_SELECTED");
        String token=Objects.toString(state.get("next_token"),"");
        if(!(catalog instanceof RecentProductCandidates port))throw new SelectionFailure("RECENT_PORT_UNAVAILABLE");
        var page=port.candidates(actor,scope.currentDate(),token);
        var tokens=new ArrayList<String>();
        if(state.get("tokens_json")!=null)for(var t:store.json.readTree((String)state.get("tokens_json")))tokens.add(t.asString());
        if(page==null||page.items().size()>100||page.hasNext()&&(page.nextToken()==null||page.nextToken().isBlank()||page.nextToken().equals(token)||tokens.contains(page.nextToken())))throw new SelectionFailure("RECENT_PAGE_INVALID");
        tokens.add(token);
        // Reject the whole page before any checkpoint is advanced.
        for(var c:page.items()) {
            var p=c.product();if(p==null||p.sellerProductId()==null||!p.sellerProductId().matches("[0-9]{1,30}")||c.order()<=0||c.raw()==null||c.observedAt()==null)throw new SelectionFailure("RECENT_CANDIDATE_INVALID");
            if(!created(p.createdAt()).toLocalDate().toString().equals(scope.currentDate()))throw new SelectionFailure("RECENT_DATE_MISMATCH");
        }
        store.tx.executeWithoutResult(t->{
            var locked=store.db.queryForMap("SELECT status,lease_owner,cancel_requested FROM coupang_product_collection WHERE id=? FOR UPDATE",job);
            if(!owner.equals(locked.get("lease_owner"))||!"RUNNING".equals(locked.get("status"))||Boolean.TRUE.equals(locked.get("cancel_requested")))return;
            for(var c:page.items()) {
                var p=c.product();String timestamp=created(p.createdAt()).toString();
                var prior=store.db.queryForList("SELECT created_at_value FROM coupang_collection_candidate WHERE job_id=? AND seller_product_id=?",String.class,job,p.sellerProductId());
                if(!prior.isEmpty()&&!timestamp.equals(prior.getFirst()))throw new SelectionFailure("RECENT_CREATED_AT_CONFLICT");
                boolean eligible=!deleted(p)&&!created(p.createdAt()).isAfter(created(scope.cutoffAt()));
                store.db.update("INSERT INTO coupang_collection_candidate(job_id,seller_product_id,created_at_value,product_json,summary_json,observation_order,observed_at,eligible) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE product_json=IF(observation_order<VALUES(observation_order),VALUES(product_json),product_json),summary_json=IF(observation_order<VALUES(observation_order),VALUES(summary_json),summary_json),eligible=IF(observation_order<VALUES(observation_order),VALUES(eligible),eligible),observed_at=IF(observation_order<VALUES(observation_order),VALUES(observed_at),observed_at),observation_order=GREATEST(observation_order,VALUES(observation_order))",job,p.sellerProductId(),timestamp,store.json.writeValueAsString(p),c.raw(),c.order(),c.observedAt(),eligible);
            }
            store.db.update("UPDATE coupang_collection_recent SET api_pages=api_pages+1 WHERE job_id=?",job);
            if(page.hasNext()) {
                store.db.update("UPDATE coupang_product_collection SET next_token=?,tokens_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",page.nextToken(),store.json.writeValueAsString(tokens),job);return;
            }
            long count=store.db.queryForObject("SELECT COUNT(*) FROM coupang_collection_candidate WHERE job_id=? AND eligible=TRUE",Long.class,job);
            if(count>=scope.maxProducts()||scope.currentDate().equals(scope.createdDateFrom()))freeze(job,actor,account,scope.maxProducts());
            else store.db.update("UPDATE coupang_collection_recent SET current_date_value=? WHERE job_id=?",LocalDate.parse(scope.currentDate()).minusDays(1).toString(),job);
            store.db.update("UPDATE coupang_product_collection SET next_token='',tokens_json=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",job);
        });
    }
    private void freeze(long job,Long actor,String account,int maximum) {
        var candidates=store.db.queryForList("SELECT * FROM coupang_collection_candidate WHERE job_id=? AND eligible=TRUE",job);
        candidates.sort((a,b)->{int time=created((String)b.get("created_at_value")).compareTo(created((String)a.get("created_at_value")));return time!=0?time:new BigInteger((String)b.get("seller_product_id")).compareTo(new BigInteger((String)a.get("seller_product_id")));});
        int rank=0;
        for(var c:candidates.subList(0,Math.min(maximum,candidates.size()))) {
            String id=(String)c.get("seller_product_id");
            var product=store.json.readValue((String)c.get("product_json"),CoupangCatalog.Product.class);
            store.record(new CoupangObservations.Read(account,actor,((Number)c.get("observation_order")).longValue(),"SUMMARY",id,null,(String)c.get("summary_json"),product,null,null,null));
            store.db.update("INSERT INTO coupang_product_collection_item(job_id,seller_product_id,seen) VALUES(?,?,TRUE)",job,id);
            store.db.update("UPDATE coupang_collection_candidate SET selected_rank=? WHERE job_id=? AND seller_product_id=?",++rank,job,id);
        }
        store.db.update("UPDATE coupang_collection_recent SET selection_complete=TRUE WHERE job_id=?",job);
        store.db.update("UPDATE coupang_product_collection SET phase='DETAIL' WHERE id=?",job);
    }
}
