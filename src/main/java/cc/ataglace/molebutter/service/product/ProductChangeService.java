package cc.ataglace.molebutter.service.product;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.product.ChangeDtos.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.exception.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class ProductChangeService {
    private final ProductStore db;
    private final ObjectProvider<ProductSupplierService> suppliers;
    private final ObjectProvider<SupplierPreferenceService> preferences;
    private record Stored(long revision,long version,State state,Summary summary) {}
    private Stored stored(long product){
        return db.jdbc.query("SELECT * FROM product_change_summary WHERE product_id=?",(r,n)->new Stored(r.getLong("lookup_revision"),r.getLong("version"),db.decode(r.getString("state"),State.class),db.decode(r.getString("summary"),Summary.class)),product).stream().findFirst().orElse(null);
    }
    private long revision(long product){return db.jdbc.queryForObject("SELECT lookup_revision FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",Long.class,product);}
    private void visible(long product){if(db.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",Long.class,product)==0)throw new BusinessException(ErrorCode.NOT_FOUND);}
    private boolean idle(long product){return db.jdbc.queryForObject("""
        SELECT (SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE e.product_id=? AND e.status IN ('PENDING','CHECKING') AND r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT'))
             + (SELECT COUNT(*) FROM supplier_stock_lookup WHERE product_id=? AND status IN ('PENDING','RUNNING','BLOCKED'))
        """,Long.class,product,product)==0;}
    private static State empty(){return new State(Map.of(),Map.of(),Map.of(),Set.of(),Set.of(),false,null,null,null,"SYSTEM_INITIAL");}
    private record SearchEvidence(Set<String> keys,boolean normal,String reason,LocalDateTime at) {}
    private SearchEvidence search(long run,long product){
        var rows=db.jdbc.queryForList("SELECT c.result,c.checked_at FROM product_refresh_search c JOIN product_refresh_entry e ON e.run_id=c.run_id AND c.query_hash=SHA2(e.query,256) WHERE e.run_id=? AND e.product_id=?",run,product);
        if(rows.size()!=1)return new SearchEvidence(Set.of(),false,null,null);
        var r=rows.getFirst();var result=db.decode((String)r.get("result"),SearchResult.class);
        if(result==null)return new SearchEvidence(Set.of(),false,null,null);
        String reason=SearchCompletion.reason(result);var keys=new HashSet<String>();
        if(result.offers()!=null)for(var o:result.offers())if(o.mall()!=null&&o.mallProductId()!=null)keys.add(SupplierStorePolicy.listingKey(o));
        if(result.searchKeys()!=null)keys.addAll(result.searchKeys());
        return new SearchEvidence(Set.copyOf(keys),SearchCompletion.normal(reason)&&result.searchKeys()!=null,reason,date(r.get("checked_at")));
    }
    private static LocalDateTime date(Object value){return value instanceof java.sql.Timestamp t?t.toLocalDateTime():value instanceof LocalDateTime t?t:null;}
    private Observation observation(Listing l,LocalDateTime priceAt,LocalDateTime observedAt,boolean initial){
        var r=l.result();if(r==null)return null;
        boolean verified=!l.requiresReview()&&l.current()&&!l.conflict();
        var evidence=r.branch()==null?null:r.branch().store();
        String channel=evidence==null?"":Objects.toString(evidence.namespace(),"")+":"+Objects.toString(evidence.externalId(),"");
        String identity=l.mall()+":"+(l.store()==null?"":l.store().id())+":"+channel+":"+(evidence==null?"":Objects.toString(evidence.references().get("channelId"),""));
        var options=new ArrayList<OptionValue>();
        LocalDateTime stockAt=r.stockEvidence()==null?initial?l.priceCheckedAt():observedAt:r.stockEvidence().checkedAt();
        if(verified&&!r.skipped()&&!"FAILED".equals(r.state())&&stockAt!=null)
            for(var o:r.options())options.add(new OptionValue(o.id(),o.label(),Objects.toString(o.stockScope(),"OPTION"),o.stock(),o.state(),stockAt));
        boolean priced=verified&&"CONFIRMED".equals(l.priceStatus());
        boolean recommendable=verified&&SupplierRecommendationPolicy.permittedSeller(l.mall(),l.store(),r);
        return new Observation(l.id(),SupplierStorePolicy.listingKey(r.offer()),l.mall(),l.store()==null?null:l.store().id(),identity,
            l.mall().getDisplayName()+(l.store()==null?"":" · "+(l.store().retailer()==null?"":l.store().retailer()+" · ")+l.store().name()),l.url(),
            priced?r.offer().price():null,priced?r.offer().deliveryFee():null,priced?(priceAt==null?l.priceCheckedAt():priceAt):null,List.copyOf(options),
            !"CONFIRMED".equals(r.state()),verified,l.current(),recommendable,observedAt).withLookupState(r.state());
    }
    /** Called under the existing settings lock, before refresh clears the current result. */
    void initialize(long product){
        if(stored(product)!=null||!idle(product))return;
        var p=db.jdbc.queryForMap("SELECT latest_status,latest_at,lookup_revision FROM catalog_product WHERE id=?",product);
        if(Set.of("PENDING","CHECKING","BLOCKED","CANCELLED","NOT_CHECKED").contains(p.get("latest_status")))return;
        var map=new LinkedHashMap<String,Observation>();
        for(var l:suppliers.getObject().allForChanges(product)){
            var o=observation(l,null,date(p.get("latest_at")),true);if(o!=null&&o.verified())map.put(o.id(),o);
        }
        if(map.isEmpty())return;
        Set<String> found=new HashSet<>();map.values().forEach(o->found.add(o.key()));
        var state=new State(Map.copyOf(map),Map.copyOf(map),Map.copyOf(map),Set.copyOf(found),Set.copyOf(found),false,null,db.time.now(),null,"SYSTEM_INITIAL");
        save(product,((Number)p.get("lookup_revision")).longValue(),state);
        writeReview(product,state,"SYSTEM_INITIAL",null);
    }
    public record SeedCandidate(String productId,String code,long revision,int listings) {}
    @Transactional(readOnly=true) public List<SeedCandidate> previewInitial(){
        var result=new ArrayList<SeedCandidate>();
        for(var p:db.jdbc.queryForList("SELECT id,product_code,revision FROM catalog_product WHERE deleted_at IS NULL AND merged_into IS NULL AND latest_status IN ('SUCCESS','PARTIAL','SOLD_OUT','NO_MATCH','UNCONFIRMED') AND id NOT IN (SELECT product_id FROM product_change_summary)")){
            long id=((Number)p.get("id")).longValue();if(!idle(id))continue;
            int count=(int)suppliers.getObject().allForChanges(id).stream().filter(l->l.current()&&!l.requiresReview()&&!l.conflict()).count();
            if(count>0)result.add(new SeedCandidate(Long.toString(id),(String)p.get("product_code"),((Number)p.get("revision")).longValue(),count));
        }return List.copyOf(result);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public boolean seed(SeedCandidate candidate){
        db.lock();long id=Long.parseLong(candidate.productId());
        var rows=db.jdbc.queryForList("SELECT revision FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",Long.class,id);
        if(rows.size()!=1||rows.getFirst()!=candidate.revision()||!idle(id)||stored(id)!=null)return false;
        initialize(id);return stored(id)!=null;
    }
    void automatic(SupplierRefreshService.Work work,RefreshResult result){capture(work.productId(),work.revision(),"RUN:"+work.runId(),"REFRESH",result.checkedAt(),null,search(work.runId(),work.productId()));}
    void manual(SupplierRefreshService.Work work,long supplier,long job){capture(work.productId(),work.revision(),"STOCK:"+job,"STOCK_LOOKUP",db.time.now(),Long.toString(supplier),null);}
    private void capture(long product,long revision,String source,String sourceType,LocalDateTime at,String only,SearchEvidence search){
        var saved=stored(product);boolean initial=saved==null||saved.revision()!=revision;
        State old=initial?empty():saved.state();
        var current=new LinkedHashMap<>(old.current());var baseline=new LinkedHashMap<>(old.baseline());var valid=new LinkedHashMap<>(old.lastValid());
        if(only==null)current.replaceAll((id,o)->inactive(o));
        boolean inserted=false;
        for(var l:suppliers.getObject().allForChanges(product)){
            if(only!=null&&!only.equals(l.id())||only==null&&!l.current())continue;
            var o=observation(l,search==null?null:search.at(),at,false);if(o==null)continue;
            if(only!=null){var previous=old.current().get(l.id());if(previous!=null)o=new Observation(o.id(),o.key(),o.mall(),o.storeId(),o.identity(),o.name(),o.url(),previous.price(),previous.fee(),previous.priceAt(),o.options(),o.partial(),o.verified(),o.current(),o.recommendable(),at,previous.feeAt()).withLookupState(o.lookupState());}
            var before=valid.get(o.id());var deltas=new ArrayList<>(ProductChangePolicy.compare(before,o));
            if(!initial&&before==null&&o.verified())deltas.add(event("NEW",at,"신규 판매글"));
            if(before!=null&&o.verified()&&!ProductChangePolicy.sameIdentity(before,o))deltas.add(event("IDENTITY",at,"매장·채널 변경 · 새 비교 기준"));
            if(!initial&&old.searchNormal()&&!found(old.searchFound(),o)&&search!=null&&search.normal()&&found(search.keys(),o))deltas.add(event("REAPPEARED",at,"이번 검색에서 재발견"));
            var kinds=new LinkedHashSet<String>();deltas.forEach(d->kinds.add(category(d.kind())));
            int n=db.jdbc.update("INSERT IGNORE INTO product_value_observation(id,product_id,origin_product_id,lookup_revision,supplier_id,source_key,source_type,observed_at,snapshot,changes,categories) VALUES(?,?,?,?,?,?,?,?,?,?,?)",ProductStore.id(),product,product,revision,Long.valueOf(o.id()),source,sourceType,at,db.encode(o),db.encode(deltas),db.encode(kinds));
            if(n==0)continue;inserted=true;current.put(o.id(),o);
            if(o.verified()){
                var known=ProductChangePolicy.mergeValid(before,o);valid.put(o.id(),known);
                if(initial)baseline.put(o.id(),known);
                else if(baseline.containsKey(o.id()))baseline.put(o.id(),ProductChangePolicy.fillBaseline(baseline.get(o.id()),o));
            }
        }
        if(initial&&valid.isEmpty())return;
        if(only==null&&search!=null&&search.normal())for(var previous:old.current().values()){
            if(found(search.keys(),previous)||old.searchNormal()&&!found(old.searchFound(),previous))continue;
            db.jdbc.update("INSERT IGNORE INTO product_value_observation(id,product_id,origin_product_id,lookup_revision,supplier_id,source_key,source_type,observed_at,snapshot,changes,categories) VALUES(?,?,?,?,?,?,?,?,?,?,?)",ProductStore.id(),product,product,revision,Long.valueOf(previous.id()),source,sourceType,at,db.encode(inactive(previous)),db.encode(List.of(event("MISSING",at,"이번 검색에서 미발견 · 판매 종료를 뜻하지 않습니다."))),db.encode(List.of("STATE")));
        }
        // Empty successful searches still update membership and invalidate a stale review token.
        if(!inserted&&only!=null)return;
        Set<String> found=search==null?old.searchFound():search.keys();
        Set<String> baselineFound=initial?new HashSet<>(current.values().stream().map(Observation::key).toList()):old.baselineFound();
        var state=new State(Map.copyOf(baseline),Map.copyOf(current),Map.copyOf(valid),Set.copyOf(baselineFound),found,
            search==null?old.searchNormal():search.normal(),search==null?old.completionReason():search.reason(),
            initial?at:old.reviewedAt(),old.reviewer(),initial?"SYSTEM_INITIAL":old.reviewKind());
        save(product,revision,state);
        if(initial)writeReview(product,state,"SYSTEM_INITIAL",null);
    }
    private static boolean found(Set<String> keys,Observation o){
        if(keys.contains(o.key()))return true;
        String nv=o.key().substring(o.key().lastIndexOf(':')+1);
        return !nv.isBlank()&&keys.contains("naver:"+nv);
    }
    private Observation inactive(Observation o){return new Observation(o.id(),o.key(),o.mall(),o.storeId(),o.identity(),o.name(),o.url(),o.price(),o.fee(),o.priceAt(),o.options(),o.partial(),o.verified(),false,o.recommendable(),o.observedAt(),o.feeAt()).withLookupState(o.lookupState());}
    private static Delta event(String kind,LocalDateTime at,String note){return new Delta(kind,null,null,null,null,null,null,null,null,null,at,note);}
    private static String category(String kind){return switch(kind){case "PRICE","DELIVERY","STOCK"->kind;default->"STATE";};}
    private boolean eligible(Observation o,Preferences prefs,String selected,Long reference){return o.id().equals(selected)||prefs.allowed(o.mall(),o.storeId())||o.recommendable()&&reference!=null&&SupplierRecommendationPolicy.cheaper(o.price(),reference);}
    private Summary project(long product,long version,State state){
        var comparison=suppliers.getObject().comparison(product);var prefs=preferences.getObject().snapshot();
        String selected=comparison.selected()==null?null:comparison.selected().id();
        Long reference=comparison.selected()!=null&&comparison.selected().current()&&"CONFIRMED".equals(comparison.selected().priceStatus())?comparison.selected().referencePrice():null;
        var shown=new LinkedHashMap<String,Listing>();
        comparison.groups().forEach(g->g.listings().forEach(l->shown.put(l.id(),l)));comparison.recommendations().forEach(g->g.listings().forEach(l->shown.put(l.id(),l)));
        if(comparison.selected()!=null)shown.put(selected,comparison.selected());
        var changes=new ArrayList<ListingChange>();
        for(var id:state.current().keySet()){
            var now=state.current().get(id);var base=state.baseline().get(id);
            boolean identityChanged=shown.containsKey(id)&&!Objects.equals(now.storeId(),shown.get(id).store()==null?null:shown.get(id).store().id());
            if(shown.containsKey(id)&&(!shown.get(id).current()||shown.get(id).conflict()||identityChanged))now=inactive(now);
            boolean missing=base!=null&&state.baselineFound().contains(base.key())&&state.searchNormal()&&!found(state.searchFound(),base)&&eligible(base,prefs,selected,reference);
            if(!shown.containsKey(id)&&!missing)continue;
            boolean fresh=base==null&&now.verified()&&now.current();
            var deltas=ProductChangePolicy.compare(base,now);
            String note=identityChanged||base!=null&&!ProductChangePolicy.sameIdentity(base,now)?"매장·채널 변경 · 새 비교 기준":base==null?"이전 비교 기준 없음":null;
            var last=state.lastValid().get(id);
            LocalDateTime stockAt=last==null?null:last.options().stream().map(OptionValue::checkedAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            changes.add(new ListingChange(id,now.name(),now.url(),fresh||missing||!deltas.isEmpty(),fresh,missing,note,stockAt,deltas));
        }
        var groups=new ArrayList<GroupChange>();
        for(var g:java.util.stream.Stream.concat(comparison.groups().stream(),comparison.recommendations().stream()).toList()){
            var old=state.baseline().values().stream().filter(o->o.verified()&&o.price()!=null&&o.mall()==g.mall()&&(!g.branchRequired()||g.store()!=null&&Objects.equals(o.storeId(),g.store().id()))&&eligible(o,prefs,selected,reference)&&state.baselineFound().contains(o.key())).sorted(Comparator.comparing(Observation::price).thenComparing(Observation::id)).toList();
            var cur=g.listings().stream().filter(l->l.current()&&"CONFIRMED".equals(l.priceStatus())).sorted(Comparator.comparing(Listing::referencePrice).thenComparing(Listing::id)).toList();
            Long b=old.isEmpty()?null:old.getFirst().price(),a=cur.isEmpty()?null:cur.getFirst().referencePrice();
            int count=(int)changes.stream().filter(ListingChange::changed).filter(c->g.listings().stream().anyMatch(l->l.id().equals(c.supplierId()))).count();
            groups.add(new GroupChange(g.id(),b,a,b==null||a==null?null:a-b,!old.isEmpty()&&!cur.isEmpty()&&!old.getFirst().id().equals(cur.getFirst().id()),count));
        }
        var alternatives=new ArrayList<Alternative>();
        if(reference!=null)for(var l:shown.values())if(!l.selected()&&l.current()&&l.selectable()&&"CONFIRMED".equals(l.priceStatus())&&SupplierRecommendationPolicy.cheaper(l.referencePrice(),reference)&&l.result().options().stream().anyMatch(o->o.stock()!=null&&o.stock()>0&&"AVAILABLE".equals(o.state())))alternatives.add(new Alternative(l.id(),l.mall().getDisplayName()+(l.store()==null?"":" · "+l.store().name()),l.referencePrice(),l.deliveryFee(),reference-l.referencePrice()));
        alternatives.sort(Comparator.comparing(Alternative::price).thenComparing(Alternative::supplierId));
        boolean selectedChanged=changes.stream().anyMatch(c->c.supplierId().equals(selected)&&c.changed());
        return new Summary(version,state.reviewedAt(),state.reviewer(),state.reviewKind(),selectedChanged,changes.stream().anyMatch(ListingChange::changed),selected,List.copyOf(changes),List.copyOf(groups),List.copyOf(alternatives),state.completionReason(),idle(product));
    }
    private void save(long product,long revision,State state){
        db.jdbc.update("UPDATE catalog_product SET change_version=change_version+1 WHERE id=?",product);
        long version=db.jdbc.queryForObject("SELECT change_version FROM catalog_product WHERE id=?",Long.class,product);Summary summary=project(product,version,state);
        db.jdbc.update("INSERT INTO product_change_summary(product_id,lookup_revision,version,selected_changed,any_changed,state,summary,updated_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE lookup_revision=VALUES(lookup_revision),version=VALUES(version),selected_changed=VALUES(selected_changed),any_changed=VALUES(any_changed),state=VALUES(state),summary=VALUES(summary),updated_at=VALUES(updated_at)",product,revision,version,summary.selectedChanged(),summary.anyChanged(),db.encode(state),db.encode(summary),db.time.now());
    }
    /** Reproject under the caller's settings lock; never generates observation events. */
    void reproject(long product){var s=stored(product);if(s!=null)save(product,s.revision(),s.state());}
    void preferenceChanged(){for(long id:db.jdbc.queryForList("SELECT s.product_id FROM product_change_summary s JOIN catalog_product p ON p.id=s.product_id WHERE p.deleted_at IS NULL AND p.merged_into IS NULL",Long.class))reproject(id);}
    void reset(long product){db.jdbc.update("DELETE FROM product_change_summary WHERE product_id=?",product);}
    void merge(long target,long source){db.jdbc.update("UPDATE product_value_observation SET product_id=? WHERE product_id=?",target,source);db.jdbc.update("UPDATE product_change_review SET product_id=? WHERE product_id=?",target,source);reset(source);}
    private void writeReview(long product,State state,String kind,Long actor){
        String name=actor==null?null:db.jdbc.queryForObject("SELECT name FROM `user` WHERE id=?",String.class,actor);
        db.jdbc.update("INSERT INTO product_change_review(id,product_id,origin_product_id,lookup_revision,kind,actor_id,actor_name,created_at,basis) VALUES(?,?,?,?,?,?,?,?,?)",ProductStore.id(),product,product,revision(product),kind,actor,name,db.time.now(),db.encode(state.baseline()));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Summary review(Long actor,long product,ReviewInput input){
        db.authorize(actor,false);db.lock();visible(product);var s=stored(product);
        if(s==null)throw new OperationFailure("아직 비교 기준이 없습니다. 최신화 후 확인해 주세요.");
        ProductStore.revision(s.version(),input.version());
        if(!idle(product))throw new OperationFailure("조회 작업이 진행 중입니다. 완료 후 결과를 다시 확인해 주세요.");
        var b=new LinkedHashMap<>(s.state().baseline());
        var eligible=new HashSet<String>();
        for(var l:suppliers.getObject().allForChanges(product))if(l.current()&&!l.conflict()&&!l.requiresReview()){
            var o=s.state().current().get(l.id());if(o!=null&&Objects.equals(o.storeId(),l.store()==null?null:l.store().id()))eligible.add(l.id());
        }
        for(var o:s.state().current().values())if(o.current()&&o.verified()&&eligible.contains(o.id()))b.put(o.id(),ProductChangePolicy.mergeValid(b.get(o.id()),o));
        String name=db.jdbc.queryForObject("SELECT name FROM `user` WHERE id=?",String.class,actor);
        var membership=new HashSet<>(s.state().searchNormal()?s.state().searchFound():s.state().baselineFound());
        for(var o:s.state().current().values())if(o.current()&&o.verified())membership.add(o.key());
        if(s.state().searchNormal())for(var o:b.values())if(found(s.state().searchFound(),o))membership.add(o.key());
        var state=new State(Map.copyOf(b),s.state().current(),s.state().lastValid(),membership,s.state().searchFound(),s.state().searchNormal(),s.state().completionReason(),db.time.now(),name,"REVIEWED");
        writeReview(product,state,"REVIEWED",actor);save(product,s.revision(),state);return summary(product);
    }
    public Summary summary(long product){var rows=db.jdbc.queryForList("SELECT summary FROM product_change_summary WHERE product_id=?",String.class,product);return rows.isEmpty()?Summary.empty():db.decode(rows.getFirst(),Summary.class);}
    public Map<String,Summary> summaries(List<String> ids){
        if(ids.isEmpty())return Map.of();var result=new HashMap<String,Summary>();
        db.jdbc.query("SELECT CAST(product_id AS CHAR),summary FROM product_change_summary WHERE product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",r->{result.put(r.getString(1),db.decode(r.getString(2),Summary.class));},ids.toArray());return result;
    }
    public List<Map<String,Object>> historySuppliers(long product){
        return db.jdbc.queryForList("""
            SELECT CAST(o.supplier_id AS CHAR) supplierId,JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.name')) name
            FROM product_value_observation o JOIN (
              SELECT supplier_id,MAX(id) id FROM product_value_observation WHERE product_id=? GROUP BY supplier_id
            ) latest ON latest.id=o.id ORDER BY o.supplier_id
            """,product);
    }
    @Transactional(readOnly=true) public PageResponse<HistoryItem> history(Long actor,long product,String supplier,String kind,int page){
        db.authorize(actor,false);visible(product);if(page<0||!Set.of("ALL","PRICE","DELIVERY","STOCK","STATE","REVIEW").contains(kind))throw new IllegalArgumentException("변동 조회 조건을 확인해 주세요.");
        if(supplier!=null&&!supplier.isBlank())Long.parseLong(supplier);else supplier="";
        String where=" WHERE product_id=? AND JSON_LENGTH(changes)>0 AND (?='' OR supplier_id=?) AND (?='ALL' OR JSON_CONTAINS(categories,JSON_QUOTE(?)))";
        String union="SELECT id,source_type source,CAST(supplier_id AS CHAR) supplier,CAST(origin_product_id AS CHAR) origin,observed_at at,snapshot,changes,NULL actor FROM product_value_observation"+where+
            " UNION ALL SELECT id,kind,NULL,CAST(origin_product_id AS CHAR),created_at,NULL,JSON_ARRAY(),actor_name FROM product_change_review WHERE product_id=? AND ?='' AND ? IN ('ALL','REVIEW')";
        Object[] args={product,supplier,supplier,kind,kind,product,supplier,kind};
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM ("+union+") h",Long.class,args);
        var params=new ArrayList<>(Arrays.asList(args));params.add((long)page*20);
        var list=db.jdbc.query("SELECT * FROM ("+union+") h ORDER BY id DESC LIMIT 20 OFFSET ?",(r,n)->{
            var o=db.decode(r.getString("snapshot"),Observation.class);
            return new HistoryItem(r.getString("id"),r.getString("source"),r.getString("supplier"),r.getString("origin"),ProductStore.date(r,"at"),o==null?null:o.name(),o==null?null:o.url(),r.getString("actor"),List.of(db.decode(r.getString("changes"),Delta[].class)));
        },params.toArray());return new PageResponse<>(list,page,(int)((count+19)/20),count);
    }
}
