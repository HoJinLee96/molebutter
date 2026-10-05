package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;


import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DefaultProductSupplierService implements cc.ataglace.molebutter.procurement.api.ProductSupplierService {
    private final ProductStore db;
    private final DefaultSupplierPreferenceService preferences;
    private final DefaultProductChangeService changes;
    List<Listing> allForChanges(long product){return listings(" WHERE p.id=? AND s.merged_into IS NULL ORDER BY s.id",product);}
    private static final String SELECT="""
        SELECT s.*,p_pm.lookup_revision,p_pm.latest_status,p_pm.latest_result,
        (SELECT e.status FROM product_refresh_entry e WHERE e.product_id=p.id ORDER BY e.run_id DESC LIMIT 1) latest_entry_status,

        (SELECT MAX(e.run_id) FROM product_refresh_entry e WHERE e.product_id=p.id) latest_run_id,
        (x.supplier_id=s.id) selected
        FROM product_supplier s JOIN catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id ON p.id=s.product_id
        LEFT JOIN product_supplier_selection x ON x.product_id=p.id
        """;
    private List<Listing> listings(String where,Object... args) {
        return listings(preferences.snapshot(),where,args);
    }
    private List<Listing> listings(Preferences settings,String where,Object... args) {
        var stores=new HashMap<String,Store>();settings.stores().forEach(s->stores.put(s.id(),s));
        var listings=db.jdbc.query(SELECT+where,(r,n)->{
            var result=db.decode(r.getString("observation"),SupplierResult.class);
            Store auto=stores.get(r.getString("auto_store_id")),manual=stores.get(r.getString("manual_store_id"));Store store=manual!=null?manual:auto;
            boolean conflict=manual!=null&&auto!=null&&!manual.id().equals(auto.id())||result!=null&&result.branch()!=null&&"CONFLICT".equals(result.branch().state());
            if(result!=null&&SupplierStorePolicy.conflicts(settings.stores(),result.offer().mall(),SupplierStorePolicy.identity(result.offer(),result.branch())))conflict=true;
            boolean criteria=r.getObject("observation_revision")!=null&&r.getLong("observation_revision")==r.getLong("lookup_revision");
            boolean current=r.getString("latest_result")!=null&&!List.of("PENDING","CHECKING","CANCELLED","STALE").contains(Objects.toString(r.getString("latest_entry_status"),""))&&criteria&&r.getObject("observed_run_id")!=null&&Objects.equals(r.getString("observed_run_id"),r.getString("latest_run_id"))&&!List.of("PENDING","CHECKING","BLOCKED","CANCELLED","NOT_CHECKED").contains(r.getString("latest_status"));
            boolean channelSupported=!"NAVER_SMART_STORE".equals(r.getString("mall"))||result!=null&&NaverChannelPolicy.comparable(result.offer());
            current=current&&channelSupported;
            boolean priced=current&&result!=null&&result.offer().price()!=null&&result.offer().price()>0&&result.accepted();
            String status=!channelSupported?"UNSUPPORTED_CHANNEL":priced?"CONFIRMED":!criteria?"STALE":List.of("PENDING","CHECKING").contains(r.getString("latest_status"))?"CHECKING":List.of("FAILED","BLOCKED","CANCELLED").contains(r.getString("latest_status"))?"FAILED":current?"UNCONFIRMED":"MISSING";
            String inventory=current&&result!=null&&"OPTIONS_PARTIAL".equals(result.state())?"OPTIONS_PARTIAL":!current?"UNCONFIRMED":result==null?"UNCONFIRMED":!result.accepted()?"CODE_REVIEW":result.state().equals("FAILED")?"FAILED":result.options().size()>1?"MULTIPLE":result.options().isEmpty()?"UNCONFIRMED":result.options().getFirst().state();
            ProcurementMall mall=ProcurementMall.valueOf(r.getString("mall"));
            var observed=result==null?null:SupplierStorePolicy.resolve(settings.stores(),mall,SupplierStorePolicy.identity(result.offer(),result.branch()));
            String storeStatus=manual!=null?"MANUAL":conflict?"CONFLICT":(current||"CHECKING".equals(status))&&observed!=null&&auto!=null&&observed.id().equals(auto.id())?"CONFIRMED":store!=null?"HISTORICAL":"UNCONFIRMED";
            return new Listing(r.getString("id"),r.getLong("assignment_revision"),mall,store,manual!=null,conflict,settings.allowed(mall,store==null?null:store.id()),current,r.getBoolean("selected"),status,(Long)r.getObject("last_price"),(Long)r.getObject("last_delivery_fee"),ProductStore.date(r,"price_checked_at"),inventory,r.getString("url"),r.getString("image_url"),result,settings.branchRequired(mall)?storeStatus:"NOT_REQUIRED",settings.branchRequired(mall));
        },args);
        return selectedEvidence(listings);
    }
    private List<Listing> selectedEvidence(List<Listing> listings){
        var ids=listings.stream().filter(Listing::selected).map(Listing::id).toList();if(ids.isEmpty())return listings;
        var evidence=new HashMap<String,Map<String,Object>>();
        var rows=db.jdbc.queryForList("""
            SELECT CAST(s.id AS CHAR) supplier_id,s.mall,s.mall_product_id,s.naver_product_id,c.result,c.checked_at
            FROM product_supplier s JOIN catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id ON p.id=s.product_id
            JOIN product_refresh_entry e ON e.product_id=p.id AND e.run_id=(SELECT MAX(x.run_id) FROM product_refresh_entry x WHERE x.product_id=p.id)
            JOIN product_refresh_run r ON r.id=e.run_id
            JOIN product_refresh_search c ON c.run_id=e.run_id AND c.query_hash=SHA2(e.query,256)
            WHERE e.lookup_revision=p_pm.lookup_revision AND e.query=p_pm.search_query AND e.product_code=p.product_code
            AND p_pm.latest_result IS NOT NULL AND e.result IS NOT NULL AND e.checked_at=p_pm.latest_at
            AND e.status IN ('SUCCESS','PARTIAL','SOLD_OUT','NO_MATCH','UNCONFIRMED')
            AND p_pm.latest_status IN ('SUCCESS','PARTIAL','SOLD_OUT','NO_MATCH','UNCONFIRMED')
            AND c.checked_at<=e.checked_at AND s.id IN (
            """+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
        for(var r:rows)evidence.put(r.get("supplier_id").toString(),r);
        return listings.stream().map(l->{var r=evidence.get(l.id());if(r==null)return l;
            var search=db.decode((String)r.get("result"),SearchResult.class);
            if(search==null||search.searchKeys()==null||!SearchCompletion.normal(SearchCompletion.reason(search)))return l;
            String key=r.get("mall")+":"+r.get("mall_product_id")+":"+r.get("naver_product_id");
            boolean found=search.searchKeys().contains(key)||r.get("naver_product_id")!=null&&search.searchKeys().contains("naver:"+r.get("naver_product_id"));
            return l.withSearchEvidence(!found,r.get("checked_at") instanceof java.sql.Timestamp t?t.toLocalDateTime():(java.time.LocalDateTime)r.get("checked_at"));
        }).toList();
    }
    public Map<String,Listing> selected(List<String> productIds){if(productIds.isEmpty())return Map.of();Map<String,Listing> result=new HashMap<>();var list=listings(" WHERE x.supplier_id=s.id AND p.id IN ("+String.join(",",Collections.nCopies(productIds.size(),"?"))+")",productIds.toArray());if(list.isEmpty())return result;
        Map<String,String> owners=new HashMap<>();db.jdbc.query("SELECT CAST(product_id AS CHAR) product_id,CAST(supplier_id AS CHAR) supplier_id FROM product_supplier_selection WHERE product_id IN ("+String.join(",",Collections.nCopies(productIds.size(),"?"))+")",r->{owners.put(r.getString("supplier_id"),r.getString("product_id"));},productIds.toArray());list.forEach(l->result.put(owners.get(l.id()),l));return result;}
    public Comparison comparison(Long actor,long product){db.authorize(actor,false);visible(product);return comparison(product).withChanges(changes.summary(product));}
    SelectionBasis selectionBasis(long product) {
        String change=db.jdbc.queryForObject("SELECT CAST(MAX(id) AS CHAR) FROM product_supplier_change WHERE product_id=? AND change_type IN ('SELECT','MERGE_SELECTION')",String.class,product);
        var rows=db.jdbc.query("SELECT x.supplier_id,x.selected_at,s.mall,s.mall_product_id,s.naver_product_id FROM product_supplier_selection x JOIN product_supplier s ON s.id=x.supplier_id WHERE x.product_id=?",(r,n)->new SelectionBasis(r.getString("supplier_id"),r.getString("mall")+":"+r.getString("mall_product_id")+":"+r.getString("naver_product_id"),ProductStore.date(r,"selected_at"),change),product);
        return rows.isEmpty()?new SelectionBasis(null,null,null,change):rows.getFirst();
    }
    Comparison comparison(long product) {
        var settings=preferences.snapshot();
        var all=listings(settings," WHERE p.id=? AND s.merged_into IS NULL ORDER BY s.id",product);
        var selected=all.stream().filter(Listing::selected).findFirst().orElse(null);
        var entries=db.jdbc.queryForList("SELECT selection_snapshot,result,status FROM product_refresh_entry WHERE product_id=? ORDER BY run_id DESC LIMIT 1",product);
        SelectionBasis basis=entries.isEmpty()?null:db.decode((String)entries.getFirst().get("selection_snapshot"),SelectionBasis.class);
        RefreshResult result=entries.isEmpty()?null:db.decode((String)entries.getFirst().get("result"),RefreshResult.class);
        boolean limited=result!=null&&result.recommendationLimited();
        Long reference=selected!=null&&selected.current()&&"CONFIRMED".equals(selected.priceStatus())?selected.result().offer().price():null;
        String state=selected==null?"NO_SELECTION":basis==null||!basis.usesSearchQuery()||!basis.usesWindowOnly()?"REFRESH_REQUIRED":!basis.equals(selectionBasis(product))?"SELECTION_CHANGED":reference==null?"PRICE_UNCONFIRMED":result==null?"REFRESH_REQUIRED":limited?"PARTIAL":"READY";
        boolean awaiting=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id JOIN catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id ON p.id=e.product_id WHERE p.id=? AND e.run_id=(SELECT MAX(x.run_id) FROM product_refresh_entry x WHERE x.product_id=p.id) AND e.lookup_revision=p_pm.lookup_revision AND e.status IN ('PENDING','CHECKING') AND r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')",Long.class,product)>0;
        var normal=all.stream().filter(l->(l.current()||awaiting)&&l.preferred()&&!l.requiresReview()).toList();
        var recommendations=(state.equals("READY")||state.equals("PARTIAL"))?all.stream().filter(l->SupplierRecommendationPolicy.eligible(l,reference)).map(l->l.recommended(reference-l.result().offer().price())).toList():List.<Listing>of();
        return new Comparison(groups(normal),List.of(),List.of(),selected,settings.stores(),!settings.rules().isEmpty(),groups(recommendations),new RecommendationStatus(state,selected==null?null:selected.id(),reference,limited),stockJobs(product));
    }
    private List<StockLookupJob> stockJobs(long product){
        return db.jdbc.query("SELECT j.*,((SELECT stock_lookup_blocked_job FROM procurement_runtime WHERE id=1)=j.id) blocking FROM supplier_stock_lookup j WHERE j.product_id=? AND (j.id=(SELECT MAX(k.id) FROM supplier_stock_lookup k WHERE k.supplier_id=j.supplier_id) OR j.id=(SELECT stock_lookup_blocked_job FROM procurement_runtime WHERE id=1)) ORDER BY j.id",(r,n)->new StockLookupJob(r.getString("id"),r.getString("product_id"),r.getString("supplier_id"),r.getString("status"),r.getLong("revision"),r.getString("message"),ProductStore.date(r,"created_at"),ProductStore.date(r,"finished_at"),r.getBoolean("blocking")),product);
    }
    void recordStock(Long actor,DefaultSupplierRefreshService.Work work,long supplier,SupplierResult result){
        var before=db.jdbc.queryForObject("SELECT observation FROM product_supplier WHERE id=?",String.class,supplier);
        var identity=SupplierStorePolicy.identity(result.offer(),result.branch());
        Store auto=identity==null?null:preferences.observed(result.offer().mall(),identity);
        db.jdbc.update("UPDATE product_supplier SET observation=?,auto_store_id=? WHERE id=?",db.encode(result),auto==null?null:auto.id(),supplier);
        // Search metadata is only a grouping proof. An explicit conflicting response revokes that proof.
        String channel=result.offer().searchStore()==null?null:result.offer().searchStore().channelId();
        if(channel!=null&&!"FAILED".equals(result.state())&&!SupplierGroupStockPolicy.verified(result)){
            var peers=db.jdbc.queryForList("SELECT id,observation FROM product_supplier WHERE product_id=? AND observed_run_id=? AND id<>?",work.productId(),work.runId(),supplier);
            for(var peer:peers){var r=db.decode((String)peer.get("observation"),SupplierResult.class);if(r==null||!r.skipped()||r.stockEvidence()==null||!channel.equals(r.stockEvidence().channelId()))continue;
                var invalid=new SupplierResult(r.offer(),r.match(),"GROUP_UNCONFIRMED",List.of(),"판매채널 근거가 달라졌습니다. 다시 최신화해 주세요.",null,null,null,new BranchInfo(null,"CONFLICT","SAME_CHANNEL",null,null),r.stockEvidence());
                db.jdbc.update("UPDATE product_supplier SET observation=?,auto_store_id=NULL WHERE id=?",db.encode(invalid),peer.get("id"));
            }
        }
        var latest=db.decode(db.jdbc.queryForObject("SELECT latest_result FROM catalog_product JOIN procurement_product ON procurement_product.product_id=catalog_product.id WHERE id=?",String.class,work.productId()),RefreshResult.class);
        if(latest!=null){
            var observations=db.jdbc.queryForList("SELECT observation FROM product_supplier WHERE product_id=? AND observed_run_id=? AND merged_into IS NULL",String.class,work.productId(),work.runId()).stream().filter(Objects::nonNull).map(v->db.decode(v,SupplierResult.class)).toList();
            String reason=latest.completionReason();
            if(reason==null){
                var cached=db.jdbc.queryForList("SELECT c.result FROM product_refresh_search c JOIN product_refresh_entry e ON e.run_id=c.run_id AND c.query_hash=SHA2(e.query,256) JOIN catalog_product p JOIN procurement_product p_pm ON p_pm.product_id=p.id ON p.id=e.product_id AND p_pm.search_query=e.query AND p_pm.lookup_revision=e.lookup_revision WHERE e.run_id=? AND e.product_id=? AND e.lookup_revision=? AND e.checked_at=?",String.class,work.runId(),work.productId(),work.revision(),latest.checkedAt());
                if(cached.size()==1)reason=SearchCompletion.reason(db.decode(cached.getFirst(),SearchResult.class));
            }
            var summary=SearchCompletion.summarize(observations,reason,latest.checkedAt(),work.preferences(),work.manualStores(),latest.recommendationLimited(),latest.recommendationDiagnostics(),work.selectionBasis());
            var updated=new RefreshResult(summary.status(),latest.searchPrice(),latest.searchMall(),latest.searchDeliveryFee(),observations,latest.checkedAt(),summary.message(),latest.recommendationLimited(),latest.recommendationDiagnostics(),reason,summary.statusPolicyVersion(),summary.statusReasons());
            db.jdbc.update("UPDATE procurement_product SET latest_result=?,latest_status=? WHERE product_id=?",db.encode(updated),updated.status(),work.productId());db.catalog.bump(work.productId());
        }
        var history=new RefreshResult("FAILED".equals(result.state())?"FAILED":"SUCCESS",null,null,null,List.of(result),db.time.now(),"개별 재고 조회");
        db.jdbc.update("INSERT INTO product_lookup_history(id,product_id,run_id,legacy,created_at,payload) VALUES(?,?,?,FALSE,?,?)",ProductStore.id(),work.productId(),work.runId(),db.time.now(),db.encode(history));
        history(actor,work.productId(),"STOCK_LOOKUP",db.decode(before,SupplierResult.class),result);
    }
    /** 화면 카드에 보이는 가격. 이번 조회 결과가 없으면 마지막으로 확인한 가격을 쓴다. */
    private static Long shownPrice(Listing l){return l.result()!=null&&l.result().offer().price()!=null?l.result().offer().price():l.referencePrice();}
    private List<Group> groups(List<Listing> listings) {
        var grouped=new LinkedHashMap<String,List<Listing>>();
        for(var l:listings){String key=l.branchRequired()?l.mall()+":"+l.store().id():"mall:"+l.mall();grouped.computeIfAbsent(key,k->new ArrayList<>()).add(l);}
        List<Group> rows=new ArrayList<>();
        for(var e:grouped.entrySet()){
            var list=e.getValue();list.sort(Comparator.comparing(DefaultProductSupplierService::shownPrice,Comparator.nullsLast(Long::compareTo)).thenComparing(Listing::id));
            var first=list.getFirst();var prices=list.stream().filter(Listing::current).map(l->l.result().offer().price()).filter(Objects::nonNull).toList();
            rows.add(new Group(e.getKey(),first.mall(),first.branchRequired()?first.store():null,list.stream().anyMatch(Listing::selected),prices.stream().min(Long::compareTo).orElse(null),prices.stream().max(Long::compareTo).orElse(null),List.copyOf(list),first.branchRequired()));
        }
        // 선정 여부·조회 시점과 무관하게 묶음의 가장 낮은 표시 가격순. minPrice는 이번 조회분만 담아 대기·중단 중에는 비므로 정렬에 쓰지 않는다.
        rows.sort(Comparator.comparing((Group g)->shownPrice(g.listings().getFirst()),Comparator.nullsLast(Long::compareTo)).thenComparing(Group::id));
        return rows;
    }
    private long visible(long product){var rows=db.jdbc.queryForList("SELECT revision FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",Long.class,product);if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);return rows.getFirst();}
    private Listing listing(long product,long id){return listings(" WHERE p.id=? AND s.id=?",product,id).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    private void history(Long actor,long product,String kind,Object before,Object after){db.jdbc.update("INSERT INTO product_supplier_change(id,product_id,actor_id,change_type,before_snapshot,after_snapshot,created_at) VALUES(?,?,?,?,?,?,?)",ProductStore.id(),product,actor,kind,db.encode(before),db.encode(after),db.time.now());}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Comparison assign(Long actor,long product,long id,AssignmentInput input){db.authorize(actor,false);db.lock();visible(product);var old=listing(product,id);ProductStore.revision(old.revision(),input.revision());if(old.result()==null||!NaverChannelPolicy.comparable(old.result().offer()))throw new OperationFailure("쇼핑윈도로 확인된 판매글만 매장을 지정할 수 있습니다.");if(!old.branchRequired())throw new OperationFailure("지점 구분을 끈 쇼핑몰에는 매장을 지정하지 않습니다. 설정을 다시 확인해 주세요.");if(input.newStore()!=null&&input.storeId()!=null&&!input.storeId().isBlank())throw new InputValidationFailure("기존 매장과 새 매장 중 하나만 지정해 주세요.");
        if(input.newStore()!=null||input.addPreferred()){db.authorize(actor,true);preferences.checkPreferenceRevision(input.preferenceRevision());}
        Store target=input.newStore()!=null?preferences.resolveNewStore(actor,old.mall(),input.newStore()):input.storeId()==null||input.storeId().isBlank()?null:preferences.store(input.storeId());if(target!=null&&target.mall()!=old.mall())throw new InputValidationFailure("해당 쇼핑몰의 매장을 선택해 주세요.");
        if(SupplierStorePolicy.storeContradiction(target,old.result()==null?null:old.result().branch()))throw new InputValidationFailure("COMPANY".equals(target.kind())?"확인된 판매업체와 다르므로 LF로 지정할 수 없습니다.":"판매채널 근거가 해당 공식몰과 일치하지 않습니다.");
        if(input.addPreferred()){if(target==null)throw new InputValidationFailure("선호 목록에 추가할 매장을 선택해 주세요.");preferences.addPreferred(actor,target);}
        db.jdbc.update("UPDATE product_supplier SET manual_store_id=?,assignment_revision=assignment_revision+1 WHERE id=?",target==null?null:target.id(),id);db.catalog.bump(product);history(actor,product,"ASSIGN_STORE",old,listing(product,id));changes.reproject(product);return comparison(product).withChanges(changes.summary(product));}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Comparison select(Long actor,long product,SelectionInput input){db.authorize(actor,false);db.lock();visible(product);db.catalog.checkRevision(product,input.revision());var comparison=comparison(product);var old=comparison.selected();Listing chosen=null;
        if(input.supplierId()!=null&&!input.supplierId().isBlank()){listing(product,Long.parseLong(input.supplierId()));chosen=java.util.stream.Stream.concat(comparison.groups().stream(),comparison.recommendations().stream()).flatMap(g->g.listings().stream()).filter(l->l.id().equals(input.supplierId())).findFirst().orElseThrow(()->new OperationFailure("선호 매입처 또는 현재 유효한 추천을 선택해 주세요."));if(!chosen.selectable())throw new OperationFailure(chosen.selectionUnavailableReason());}
        writeSelection(actor,product,chosen==null?null:chosen.id());history(actor,product,"SELECT",old,chosen);db.catalog.bump(product);changes.reproject(product);return comparison(product).withChanges(changes.summary(product));}
    private void writeSelection(Long actor,long product,String supplier){db.jdbc.update("DELETE FROM product_supplier_selection WHERE product_id=?",product);if(supplier!=null)db.jdbc.update("INSERT INTO product_supplier_selection(product_id,supplier_id,selected_by,selected_at) VALUES(?,?,?,?)",product,supplier,actor,db.time.now());}
    /** 결과 확정은 사용자 선정·수동 지정 컬럼을 갱신하지 않는다. */
    void record(DefaultSupplierRefreshService.Work work,RefreshResult result){
        String image=null;
        var searchTimes=db.jdbc.queryForList("SELECT c.checked_at FROM product_refresh_search c JOIN product_refresh_entry e ON e.run_id=c.run_id AND c.query_hash=SHA2(e.query,256) WHERE e.run_id=? AND e.product_id=?",java.sql.Timestamp.class,work.runId(),work.productId());
        var priceAt=searchTimes.size()==1?searchTimes.getFirst().toLocalDateTime():result.checkedAt();
        var observed=new HashMap<Map.Entry<ProcurementMall,SupplierStorePolicy.Identity>,Store>();
        for(var s:result.suppliers()){
        var o=s.offer();if(o.mall()==null||o.mallProductId()==null||o.mallProductId().isBlank())continue;
        var identity=SupplierStorePolicy.identity(o,s.branch());
        Store auto=identity==null?null:observed.computeIfAbsent(Map.entry(o.mall(),identity),key->preferences.observed(key.getKey(),key.getValue()));boolean priced=o.price()!=null&&o.price()>0&&s.accepted()&&NaverChannelPolicy.comparable(o);
        db.jdbc.update("""
            INSERT INTO product_supplier(id,product_id,mall,mall_product_id,naver_product_id,url,image_url,last_seen_at,
                auto_store_id,observation,observation_revision,observed_run_id,last_price,last_delivery_fee,price_checked_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE url=VALUES(url),image_url=VALUES(image_url),
                last_seen_at=VALUES(last_seen_at),auto_store_id=COALESCE(VALUES(auto_store_id),auto_store_id),observation=VALUES(observation),
                observation_revision=VALUES(observation_revision),observed_run_id=VALUES(observed_run_id),
                last_price=IF(VALUES(price_checked_at) IS NOT NULL,VALUES(last_price),last_price),
                last_delivery_fee=IF(VALUES(price_checked_at) IS NOT NULL,VALUES(last_delivery_fee),last_delivery_fee),
                price_checked_at=COALESCE(VALUES(price_checked_at),price_checked_at)
            """,ProductStore.id(),work.productId(),o.mall().name(),o.mallProductId(),Objects.toString(o.naverProductId(),""),o.url(),o.imageUrl(),result.checkedAt(),auto==null?null:auto.id(),db.encode(s),work.revision(),work.runId(),priced?o.price():null,priced?o.deliveryFee():null,priced?priceAt:null);
        if(image==null&&o.imageUrl()!=null)image=o.imageUrl(); // 첫 판매글(선정 판매글 또는 검색 1순위). 뒤에 오는 비선호 추천 사진은 대표로 쓰지 않는다.
    }
        if(image!=null)db.jdbc.update("UPDATE procurement_product SET image_url=? WHERE product_id=?",image,work.productId());
    }
    Map<String,String> manualAssignments(long product){var map=new HashMap<String,String>();db.jdbc.query("SELECT mall,mall_product_id,naver_product_id,manual_store_id FROM product_supplier WHERE product_id=? AND manual_store_id IS NOT NULL",r->{map.put(r.getString("mall")+":"+r.getString("mall_product_id")+":"+r.getString("naver_product_id"),r.getString("manual_store_id"));},product);return map;}
    /** 병합 전에 사용자가 유지할 선정을 결정한다. 선택하지 않은 판매글과 변경 이력도 보존한다. */
    public String mergeChoice(List<Long> products,String requested){var rows=db.jdbc.queryForList("SELECT CAST(supplier_id AS CHAR) FROM product_supplier_selection WHERE product_id IN ("+String.join(",",Collections.nCopies(products.size(),"?"))+")",String.class,products.toArray());if(rows.size()>1&&(requested==null||!rows.contains(requested)))throw new OperationFailure("선정 매입처가 서로 다릅니다. 통합 후 유지할 판매글을 선택해 주세요.");if(requested!=null&&!rows.contains(requested))throw new InputValidationFailure("통합 대상의 선정 판매글 중에서 선택해 주세요.");return requested!=null?requested:rows.isEmpty()?null:rows.getFirst();}
    @Transactional(propagation=Propagation.MANDATORY)
    public String moveListings(long target,long source,String chosen){
        db.requireExclusive();
        var duplicates=db.jdbc.queryForList("SELECT s.id source_id,t.id target_id FROM product_supplier s JOIN product_supplier t ON t.product_id=? AND t.merged_into IS NULL AND t.mall=s.mall AND t.mall_product_id=s.mall_product_id AND t.naver_product_id=s.naver_product_id WHERE s.product_id=? AND s.merged_into IS NULL",target,source);
        for(var row:duplicates){String old=row.get("source_id").toString(),canonical=row.get("target_id").toString();
            if(old.equals(chosen)){db.jdbc.update("UPDATE product_supplier t JOIN product_supplier s ON s.id=? SET t.manual_store_id=s.manual_store_id,t.assignment_revision=t.assignment_revision+1 WHERE t.id=?",old,canonical);chosen=canonical;}
            db.jdbc.update("UPDATE product_supplier SET merged_into=? WHERE id=?",canonical,old);
        }
        db.jdbc.update("UPDATE product_supplier SET product_id=? WHERE product_id=? AND merged_into IS NULL",target,source);return chosen;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void mergeSelections(Long actor,long target,List<Long> ids,String chosen){db.requireExclusive();var before=ids.stream().map(this::comparison).toList();for(long id:ids)db.jdbc.update("DELETE FROM product_supplier_selection WHERE product_id=?",id);writeSelection(actor,target,chosen);history(actor,target,"MERGE_SELECTION",before,chosen);}
}
