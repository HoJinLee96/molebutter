package cc.ataglace.molebutter.coupang.internal;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.Collection;
import static cc.ataglace.molebutter.coupang.api.CoupangFailure.Kind.*;

@Service
public class DefaultCoupangRegisteredProducts implements CoupangRegisteredProducts {
    private cc.ataglace.molebutter.media.api.ImageAssets assets;
    @org.springframework.beans.factory.annotation.Autowired void assets(cc.ataglace.molebutter.media.api.ImageAssets assets){this.assets=assets;}
    private final RegisteredProductStore store;
    private final BusinessAccess access;
    private final CoupangCatalog catalog;
    private final CoupangProductSource source;
    private final CoupangWriteGateway writer;
    private final WorkingCopyStore copies;
    private final SubmissionStore submissions;
    private final DefaultCoupangWorkingCopies editing;
    private final DefaultCoupangSubmissions sending;
    private final CoupangCollectionNotifications notifications;
    private final boolean enabled;
    private final CoupangRecommendations recommendations;
    private final String owner=UUID.randomUUID().toString();
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile Long currentJob;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"coupang-product-collection");t.setDaemon(true);return t;});
    public DefaultCoupangRegisteredProducts(RegisteredProductStore store,BusinessAccess access,CoupangCatalog catalog,
        CoupangProductSource source,CoupangWriteGateway writer,WorkingCopyStore copies,SubmissionStore submissions,
        DefaultCoupangWorkingCopies editing,DefaultCoupangSubmissions sending,CoupangCollectionNotifications notifications,CoupangRecommendations recommendations,
        @Value("${coupang.products.worker-enabled:true}") boolean enabled) {
        this.store=store;this.access=access;this.catalog=catalog;this.source=source;this.writer=writer;this.copies=copies;
        this.submissions=submissions;this.editing=editing;this.sending=sending;this.notifications=notifications;this.recommendations=recommendations;this.enabled=enabled;
    }
    private String account(Long actor){access.productActor(actor,true);String a=writer.accountKey();if(a==null)throw new CoupangFailure(CONFIGURATION);return a;}
    static void productId(String id){if(id==null || !id.matches("[0-9]{1,30}"))throw new InputValidationFailure("등록상품 ID를 확인해 주세요.");}
    static long id(String id){productId(id);try{return Long.parseLong(id);}catch(NumberFormatException e){throw new InputValidationFailure("식별자를 확인해 주세요.");}}
    static void page(int page,int size){if(page<0 || page>1000000 || !Set.of(10,20,50,100).contains(size))throw new InputValidationFailure("페이지를 확인해 주세요.");}
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Listing list(Long actor,Search search,boolean deleted,int page,int size) {
        page(page,size);String account=account(actor);if(search==null)throw new InputValidationFailure("검색 조건이 필요합니다.");
        String direction=switch(Objects.toString(search.sort(),"CREATED_DESC")){
            case "CREATED_DESC"->"DESC";case "CREATED_ASC"->"ASC";
            default->throw new InputValidationFailure("등록일 정렬을 확인해 주세요.");};
        var args=new ArrayList<Object>();args.add(account);args.add(deleted);
        String where=" WHERE p.account_key=? AND p.deleted=?";
        String group=Objects.toString(search.group(),"ALL"),reason=Objects.toString(search.reason(),"ALL");
        if(!Set.of("ALL","PRICE_HIGH","NEGATIVE_MARGIN","RESTOCK","MARK_SOLD_OUT","OWNED_STOCK_MISMATCH").contains(reason)||!group.equals("RECOMMENDED")&&!reason.equals("ALL"))throw new InputValidationFailure("추천 유형을 확인해 주세요.");
        if(deleted && !group.equals("ALL"))throw new InputValidationFailure("삭제 목록에는 판매 구분을 적용할 수 없습니다.");
        where+=switch(group){case "ALL"->"";case "SELLING"->" AND o.on_sale=TRUE AND o.current_stock>=1";
            case "RECOMMENDED"->" AND o.on_sale=TRUE";case "SOLD_OUT"->" AND o.on_sale=TRUE AND o.current_stock=0";case "STOPPED"->" AND o.on_sale=FALSE";
            default->throw new InputValidationFailure("상품 구분을 확인해 주세요.");};
        String input=Objects.toString(search.id(),"").trim();
        if(!input.isEmpty()){productId(input);where+=" AND (p.seller_product_id=? OR JSON_UNQUOTE(JSON_EXTRACT(p.product_json,'$.productId'))=? OR o.vendor_item_id=? OR o.seller_product_item_id=?)";args.addAll(List.of(input,input,input,input));}
        if(search.brand()!=null && !search.brand().isBlank()){if(search.brand().length()>255)throw new InputValidationFailure("브랜드를 확인해 주세요.");where+=" AND JSON_UNQUOTE(JSON_EXTRACT(p.product_json,'$.brand'))=?";args.add(search.brand());}
        if(search.name()!=null && !search.name().isBlank()){where+=" AND p.product_name LIKE ? ESCAPE '!'";args.add(pattern(search.name()));}
        if(search.sku()!=null && !search.sku().isBlank()){where+=" AND o.sku LIKE ? ESCAPE '!'";args.add(pattern(search.sku()));}
        if(search.stock()!=null){if(search.stock()<0)throw new InputValidationFailure("재고는 0 이상의 정수로 입력해 주세요.");where+=" AND o.current_stock "+operator(search.stockComparison())+" ?";args.add(search.stock());}
        if(search.shippingDays()!=null){if(search.shippingDays()<0)throw new InputValidationFailure("출고소요일은 0 이상의 정수로 입력해 주세요.");where+=" AND o.outbound_shipping_days "+operator(search.shippingComparison())+" ?";args.add(search.shippingDays());}
        if(search.saleStatus()!=null && !search.saleStatus().isBlank()) {
            where+=switch(search.saleStatus()){case "ON_SALE"->" AND o.on_sale=TRUE";case "STOPPED"->" AND o.on_sale=FALSE";case "UNVERIFIED"->" AND (o.on_sale IS NULL OR o.error_code IS NOT NULL)";default->throw new InputValidationFailure("판매상태를 확인해 주세요.");};
        }
        String from=" FROM coupang_registered_product p LEFT JOIN coupang_registered_option o ON o.product_id=p.id AND o.active=TRUE"+where;
        var recommended=recommendations.account(actor,account);
        String order=" ORDER BY NULLIF(TRIM(p.created_at_external),'') IS NULL, NULLIF(TRIM(p.created_at_external),'') "+direction+",p.id DESC,o.seller_product_item_id";
        long total,products;List<Row> rows;
        if(group.equals("RECOMMENDED")) {
            // Filter lightweight keys across the entire searched/sorted account BEFORE pagination.
            var keys=store.db.query("SELECT p.seller_product_id,o.seller_product_item_id"+from+order,
                (r,n)->new CoupangRecommendations.Key(r.getString(1),r.getString(2)),args.toArray()).stream().filter(recommended::containsKey).filter(key->reason.equals("ALL")||recommended.get(key).reasons().contains(reason)).toList();
            total=keys.size();products=keys.stream().map(CoupangRecommendations.Key::seller).distinct().count();
            int start=(int)Math.min((long)page*size,total),end=Math.min(start+size,keys.size());
            if(start==end)rows=List.of();
            else {
                var selected=new ArrayList<>(args);
                for(var key:keys.subList(start,end)){selected.add(key.seller());selected.add(key.item());}
                rows=store.db.query(LIST_COLUMNS+from+" AND (p.seller_product_id,o.seller_product_item_id) IN ("+
                    String.join(",",Collections.nCopies(end-start,"(?,?)"))+")"+order,(r,n)->listRow(r,recommended),selected.toArray());
            }
        } else {
            total=store.db.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
            products=store.db.queryForObject("SELECT COUNT(DISTINCT p.id)"+from,Long.class,args.toArray());
            var selected=new ArrayList<>(args);selected.add(size);selected.add((long)page*size);
            rows=store.db.query(LIST_COLUMNS+from+order+" LIMIT ? OFFSET ?",(r,n)->listRow(r,recommended),selected.toArray());
        }
        var counts=store.db.queryForObject("""
            SELECT COALESCE(SUM(p.deleted=FALSE),0),
              COALESCE(SUM(p.deleted=FALSE AND o.on_sale=TRUE AND o.current_stock>=1),0),
              COALESCE(SUM(p.deleted=FALSE AND o.on_sale=TRUE AND o.current_stock=0),0),
              COALESCE(SUM(p.deleted=FALSE AND o.on_sale=FALSE),0),COALESCE(SUM(p.deleted=TRUE),0)
            FROM coupang_registered_product p LEFT JOIN coupang_registered_option o ON o.product_id=p.id AND o.active=TRUE
            WHERE p.account_key=?
            """,(r,n)->new Counts(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),recommended.size(),new RecommendationCounts(
                recommended.values().stream().filter(v->v.reasons().contains("PRICE_HIGH")).count(),
                recommended.values().stream().filter(v->v.reasons().contains("NEGATIVE_MARGIN")).count(),
                recommended.values().stream().filter(v->v.reasons().contains("RESTOCK")).count(),
                recommended.values().stream().filter(v->v.reasons().contains("MARK_SOLD_OUT")).count(),
                recommended.values().stream().filter(v->v.reasons().contains("OWNED_STOCK_MISMATCH")).count())),account);
        return new Listing(rows,page,(int)((total+size-1)/size),total,products,counts);
    }
    // List pages never need detail/raw JSON. Hydrate the page in its single ordered query.
    private static final String LIST_COLUMNS="""
        SELECT p.seller_product_id,o.seller_product_item_id,o.option_json,o.current_json,o.error_code,o.outbound_shipping_days,
          p.product_json,p.revision,p.last_success_at,p.last_attempt_at,p.error_code product_error,
          p.deleted,p.deletion_candidate,(p.detail_json IS NOT NULL) detail_collected
        """;
    private Row listRow(java.sql.ResultSet r,Map<CoupangRecommendations.Key,Recommendation> recommended) throws java.sql.SQLException {
        var option=r.getString("option_json")==null?null:store.json.readValue(r.getString("option_json"),CoupangCatalog.ProductOption.class);
        if(option!=null)option=option.withCurrent(r.getString("current_json")==null?null:store.json.readValue(r.getString("current_json"),CoupangCatalog.CurrentInventory.class),r.getString("error_code"));
        return new Row(store.json.readValue(r.getString("product_json"),CoupangCatalog.Product.class),r.getLong("revision"),
            RegisteredProductStore.instant(r.getTimestamp("last_success_at")),RegisteredProductStore.instant(r.getTimestamp("last_attempt_at")),
            r.getString("product_error"),r.getBoolean("deleted"),r.getBoolean("deletion_candidate"),r.getBoolean("detail_collected"),option,
            (Integer)r.getObject("outbound_shipping_days"),recommended.get(new CoupangRecommendations.Key(r.getString("seller_product_id"),r.getString("seller_product_item_id"))));
    }
    private static String operator(String value){return switch(Objects.toString(value,"EQ")){case "LE"->"<=";case "GE"->">=";case "EQ"->"=";default->throw new InputValidationFailure("이하·이상·일치 조건을 확인해 주세요.");};}
    private static String pattern(String value){if(value.length()>255)throw new InputValidationFailure("검색어는 255자 이하로 입력해 주세요.");return "%"+value.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public String optionProductUrl(Long actor,String vendor) {
        productId(vendor);String account=account(actor);
        var products=store.db.queryForList("""
            SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(p.product_json,'$.productId'))
            FROM coupang_registered_product p JOIN coupang_registered_option o ON o.product_id=p.id
            WHERE p.account_key=? AND o.vendor_item_id=?
            """,String.class,account,vendor);
        if(products.size()!=1||products.getFirst()==null||!products.getFirst().matches("[0-9]{1,30}"))
            throw new InputValidationFailure("이 옵션의 노출상품 ID를 확인할 수 없습니다. 쿠팡 등록상품을 가져오거나 갱신해 주세요.");
        return "https://www.coupang.com/vp/products/"+products.getFirst()+"?vendorItemId="+vendor;
    }
    public List<String> brands(Long actor){return store.db.queryForList("SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(product_json,'$.brand')) brand FROM coupang_registered_product WHERE account_key=? AND JSON_TYPE(JSON_EXTRACT(product_json,'$.brand'))='STRING' AND JSON_UNQUOTE(JSON_EXTRACT(product_json,'$.brand'))<>'' ORDER BY brand",String.class,account(actor));}
    public Detail get(Long actor,String product) {productId(product);String a=account(actor);return detail(a,product);}
    private Detail detail(String a,String product) {
        var p=store.find(a,product,false);var copy=copies.product(a,product);
        return new Detail(p.row(),store.detail(p),copy==null?null:copy.id(),copy==null?null:copy.revision(),store.conflicts(p,copy,source),copy!=null && submissions.active(id(copy.id()),a,-1));
    }
    public PageResponse<Event> history(Long actor,String product,int page,int size) {
        var p=store.find(account(actor),product,false);page(page,size);
        long count=store.db.queryForObject("SELECT COUNT(*) FROM coupang_product_change WHERE product_id=?",Long.class,p.id());
        var rows=store.db.query("SELECT * FROM coupang_product_change WHERE product_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(r,n)->new Event(r.getString("id"),r.getString("kind"),r.getString("subject"),r.getString("field_path"),r.getString("before_json"),r.getString("after_json"),(Long)r.getObject("actor_id"),r.getString("job_id"),r.getString("execution_id"),RegisteredProductStore.instant(r.getTimestamp("created_at"))),p.id(),size,(long)page*size);
        return new PageResponse<>(rows,page,(int)((count+size-1)/size),count);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public PageResponse<OptionEvent> optionHistory(Long actor,String product,String vendor,String kind,int page,int size) {
        productId(product);productId(vendor);page(page,size);
        if(!Set.of("PRICE","STOCK").contains(Objects.toString(kind,"")))throw new InputValidationFailure("수정 종류를 확인해 주세요.");
        String account=account(actor);var p=store.find(account,product,false);
        var sellers=store.db.queryForList("SELECT seller_product_item_id FROM coupang_registered_option WHERE product_id=? AND vendor_item_id=?",String.class,p.id(),vendor);
        if(sellers.size()!=1)throw new InputValidationFailure("옵션 식별자를 확인해 주세요.");
        var copy=copies.product(account,product);String option=null;
        if(copy!=null&&copy.mapping()!=null) {
            var matches=copy.mapping().options().stream().filter(o->vendor.equals(o.vendorItemId())&&sellers.getFirst().equals(o.sellerProductItemId())).toList();
            if(matches.size()==1)option=matches.getFirst().optionId();
        }
        var events=store.db.query("SELECT * FROM coupang_product_change WHERE product_id=? AND (kind='INVENTORY' OR kind='WORKING_COPY_SAVE' OR kind LIKE 'EXECUTION_%') ORDER BY id",(r,n)->new Event(r.getString("id"),r.getString("kind"),r.getString("subject"),r.getString("field_path"),r.getString("before_json"),r.getString("after_json"),(Long)r.getObject("actor_id"),r.getString("job_id"),r.getString("execution_id"),RegisteredProductStore.instant(r.getTimestamp("created_at"))),p.id());
        var filtered=OptionHistory.project(store.json,events,vendor,option,copy==null?null:copy.id(),kind);
        int start=(int)Math.min((long)page*size,filtered.size()),end=Math.min(start+size,filtered.size());
        return new PageResponse<>(filtered.subList(start,end),page,(filtered.size()+size-1)/size,filtered.size());
    }
    public Collection collect(Long actor,Collect input) {
        String a=account(actor);if(input==null)throw new InputValidationFailure("수집 대상을 확인해 주세요.");
        boolean recent="RECENT".equals(input.mode());
        var conditions=recent?RecentProductCollection.validate(input,Instant.now()):null;
        if(!recent && (input.mode()!=null || input.createdDateFrom()!=null || input.createdDateTo()!=null || input.maxProducts()!=null)
            || !recent&&input.sellerProductIds()==null || input.sellerProductIds()!=null&&input.sellerProductIds().size()>500)throw new InputValidationFailure("수집 조건을 확인해 주세요.");
        var ids=new LinkedHashSet<>(Objects.requireNonNullElse(input.sellerProductIds(),List.<String>of()));ids.forEach(DefaultCoupangRegisteredProducts::productId);
        long job=BusinessIds.next();
        store.tx.executeWithoutResult(t->{
            lockAccount(a);
            if(store.db.queryForObject("SELECT COUNT(*) FROM coupang_product_collection WHERE account_key=? AND status IN ('QUEUED','RUNNING')",Long.class,a)>0)throw new InputValidationFailure("진행 중인 상품 수집을 먼저 완료하거나 취소해 주세요.");
            for(String id:ids)store.find(a,id,false);
            store.db.update("INSERT INTO coupang_product_collection(id,account_key,actor_id,mode,status,phase) VALUES(?,?,?,?,'QUEUED',?)",job,a,actor,recent?"RECENT":ids.isEmpty()?"FULL":"SELECTED",ids.isEmpty()?"LIST":"DETAIL");
            if(recent)new RecentProductCollection(store).create(job,conditions);
            for(String id:ids)store.db.update("INSERT INTO coupang_product_collection_item(job_id,seller_product_id,seen) VALUES(?,?,TRUE)",job,id);
        });return collection(actor,Long.toString(job));
    }
    private void lockAccount(String a){store.db.update("INSERT IGNORE INTO coupang_submission_account(account_key) VALUES(?)",a);store.db.queryForList("SELECT account_key FROM coupang_submission_account WHERE account_key=? FOR UPDATE",String.class,a);}
    public Collection latestCollection(Long actor){String a=account(actor);var ids=store.db.queryForList("SELECT id FROM coupang_product_collection WHERE account_key=? ORDER BY id DESC LIMIT 1",String.class,a);return ids.isEmpty()?null:collection(actor,ids.getFirst());}
    public Collection collection(Long actor,String value) {
        String a=account(actor);long id=id(value);
        return collectionSnapshot(id,a);
    }
    private Collection collectionSnapshot(long id,String a) {
        var rows=store.db.query("SELECT j.*,(SELECT COUNT(*) FROM coupang_product_collection_item i WHERE i.job_id=j.id) total,(SELECT COUNT(*) FROM coupang_product_collection_item i WHERE i.job_id=j.id AND i.status='DONE') done,(SELECT COUNT(*) FROM coupang_product_collection_item i WHERE i.job_id=j.id AND i.status='FAILED') failed FROM coupang_product_collection j WHERE j.id=? AND j.account_key=?",
            (r,n)->new Collection(r.getString("id"),r.getString("mode"),r.getString("status"),r.getString("phase"),r.getBoolean("list_complete"),r.getLong("total"),r.getLong("done"),r.getLong("failed"),r.getString("error_code"),RegisteredProductStore.instant(r.getTimestamp("updated_at")),"RECENT".equals(r.getString("mode"))?new RecentProductCollection(store).snapshot(id):null),id,a);
        if(rows.isEmpty())throw new InputValidationFailure("수집 작업이 없습니다.");return rows.getFirst();
    }
    public Collection cancel(Long actor,String value){String a=account(actor);collection(actor,value);
        store.tx.executeWithoutResult(t->{
            var state=store.db.queryForMap("SELECT status FROM coupang_product_collection WHERE id=? AND account_key=? FOR UPDATE",id(value),a);
            int changed=store.db.update("UPDATE coupang_product_collection SET cancel_requested=TRUE,status=IF(status='QUEUED','CANCELLED',status),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND account_key=? AND status IN ('QUEUED','RUNNING')",id(value),a);
            if(changed==1 && "QUEUED".equals(state.get("status")))notifyResult(id(value));
        });return collection(actor,value);}
    public Collection resume(Long actor,String value){String a=account(actor);var original=collection(actor,value);
        if(!Set.of("PARTIAL","FAILED","CANCELLED","INTERRUPTED","PAUSED").contains(original.status()))throw new InputValidationFailure("재개할 수 없는 수집입니다.");
        store.tx.executeWithoutResult(t->{lockAccount(a);if(store.db.queryForObject("SELECT COUNT(*) FROM coupang_product_collection WHERE account_key=? AND status IN ('QUEUED','RUNNING')",Long.class,a)>0)throw new InputValidationFailure("진행 중인 수집이 있습니다.");
            store.db.update("UPDATE coupang_product_collection_item SET status='PENDING',error_code=NULL WHERE job_id=? AND status='FAILED'",id(value));
            store.db.update("UPDATE coupang_product_collection SET status='QUEUED',phase=IF(phase='DONE','DETAIL',phase),cancel_requested=FALSE,lease_owner=NULL,lease_until=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND account_key=?",id(value),a);
        });return collection(actor,value);}
    @Scheduled(fixedDelay=1000) public void poll(){if(enabled && running.compareAndSet(false,true))executor.execute(()->{try{runPending();}finally{running.set(false);}});}
    @Scheduled(fixedDelay=5000) public void heartbeat(){Long job=currentJob;if(job!=null)store.db.update("UPDATE coupang_product_collection SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 60 SECOND WHERE id=? AND lease_owner=? AND status='RUNNING'",job,owner);}
    public void runPending() {
        var claimed=store.tx.execute(t->{
            var expired=store.db.queryForList("SELECT id FROM coupang_product_collection WHERE status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6) FOR UPDATE SKIP LOCKED",Long.class);
            for(long job:expired) {
                if(store.db.update("UPDATE coupang_product_collection SET status='INTERRUPTED',lease_owner=NULL,lease_until=NULL,error_code='INTERRUPTED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6)",job)==1)notifyResult(job);
            }
            var rows=store.db.queryForList("SELECT id,account_key,actor_id FROM coupang_product_collection WHERE status='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(rows.isEmpty())return null;var j=rows.getFirst();
            store.db.update("UPDATE coupang_product_collection SET status='RUNNING',lease_owner=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 60 SECOND,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",owner,j.get("id"));return j;
        });if(claimed==null)return;
        long job=((Number)claimed.get("id")).longValue();Long actor=((Number)claimed.get("actor_id")).longValue();String a=(String)claimed.get("account_key");
        currentJob=job;store.jobContext(job);
        try {
            if(!a.equals(account(actor)))throw new CoupangFailure(CONFIGURATION);
            while(true){
                if(Thread.currentThread().isInterrupted()){finish(job,"INTERRUPTED","INTERRUPTED");return;}
                access.productActor(actor,true);
                if(!a.equals(writer.accountKey()))throw new CoupangFailure(CONFIGURATION);
                var state=store.db.queryForMap("SELECT * FROM coupang_product_collection WHERE id=?",job);
                if(!owner.equals(state.get("lease_owner")) || !"RUNNING".equals(state.get("status")))return;
                if(Boolean.TRUE.equals(state.get("cancel_requested"))){finish(job,"CANCELLED",null);return;}
                String phase=(String)state.get("phase");
                if(phase.equals("LIST") && "RECENT".equals(state.get("mode"))) {
                    new RecentProductCollection(store).page(job,actor,a,owner,state,catalog);continue;
                }
                if(phase.equals("LIST") || phase.equals("DELETED")) {
                    String token=(String)state.get("next_token");
                    var page=catalog.products(actor,100,token,phase.equals("LIST")?CoupangCatalog.ProductSearch.empty():new CoupangCatalog.ProductSearch(null,null,"DELETED",null));
                    var tokens=new ArrayList<String>();if(state.get("tokens_json")!=null)for(var v:store.json.readTree((String)state.get("tokens_json")))tokens.add(v.asString());
                    if(page.hasNext() && (page.nextToken().equals(token) || tokens.contains(page.nextToken())))throw new CoupangFailure(RESPONSE);
                    tokens.add(token);
                    store.tx.executeWithoutResult(t->{
                        for(var p:page.items())store.db.update("INSERT INTO coupang_product_collection_item(job_id,seller_product_id,seen) VALUES(?,?,TRUE) ON DUPLICATE KEY UPDATE seen=TRUE",job,p.sellerProductId());
                        store.db.update("UPDATE coupang_product_collection SET next_token=?,tokens_json=?,phase=?,list_complete=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND lease_owner=?",
                            page.hasNext()?page.nextToken():"",page.hasNext()?store.json.writeValueAsString(tokens):null,page.hasNext()?phase:phase.equals("LIST")?"DELETED":"DETAIL",!page.hasNext() && phase.equals("DELETED"),job,owner);
                    });continue;
                }
                var items=store.db.queryForList("SELECT seller_product_id,seen FROM coupang_product_collection_item WHERE job_id=? AND status='PENDING' ORDER BY seller_product_id LIMIT 1",job);
                if(items.isEmpty()) {
                    if(phase.equals("DETAIL") && "FULL".equals(state.get("mode")) && Boolean.TRUE.equals(state.get("list_complete"))) {
                        store.tx.executeWithoutResult(t->{
                            store.db.update("UPDATE coupang_registered_product p SET missing_job_id=? WHERE p.account_key=? AND p.deleted=FALSE AND p.summary_order<? AND p.detail_order<? AND NOT EXISTS(SELECT 1 FROM coupang_product_collection_item i WHERE i.job_id=? AND i.seller_product_id=p.seller_product_id AND i.seen=TRUE)",job,a,job,job,job);
                            store.db.update("INSERT IGNORE INTO coupang_product_collection_item(job_id,seller_product_id,seen) SELECT ?,seller_product_id,FALSE FROM coupang_registered_product WHERE account_key=? AND missing_job_id=?",job,a,job);
                            store.db.update("UPDATE coupang_product_collection SET phase='MISSING' WHERE id=? AND lease_owner=?",job,owner);
                        });continue;
                    }
                    long failed=store.db.queryForObject("SELECT COUNT(*) FROM coupang_product_collection_item WHERE job_id=? AND status='FAILED'",Long.class,job);
                    finish(job,failed>0?"PARTIAL":"SUCCEEDED",null);return;
                }
                String product=(String)items.getFirst().get("seller_product_id");boolean seen=Boolean.TRUE.equals(items.getFirst().get("seen"));
                try {
                    var known=store.find(a,product,false);
                    {
                        var detail=catalog.productForCollection(actor,product);
                        if(detail.items().stream().anyMatch(o->o.vendorItemId()!=null && o.currentError()!=null))throw new CoupangFailure(RESPONSE);
                        settle(a,product);
                    }
                    item(job,product,"DONE",null);
                }catch(CoupangListingMissing deleted){item(job,product,"DONE",null);}
                catch(CoupangFailure failure) {
                    if(Set.of(AUTHENTICATION,PERMISSION,RATE_LIMIT,BUSY,CONFIGURATION).contains(failure.kind()))throw failure;
                    if(!seen && Boolean.TRUE.equals(state.get("list_complete")) && "FULL".equals(state.get("mode")) && failure.kind()==PRODUCT_ABSENT) {
                        store.tx.executeWithoutResult(t->{var p=store.find(a,product,true);var copy=copies.product(a,product);
                            if(!p.row().deleted() && (copy==null || !submissions.active(id(copy.id()),a,-1))) {
                                store.db.update("UPDATE coupang_registered_product SET deletion_candidate=TRUE,revision=revision+1 WHERE id=? AND missing_job_id=?",p.id(),job);
                                store.event(p.id(),"DELETION_CANDIDATE","","lifecycle",null,"true",actor);
                            }
                        });item(job,product,"DONE",null);
                    }else item(job,product,"FAILED",failure.kind().name());
                }
            }
        }catch(RecentProductCollection.SelectionFailure failure){finish(job,"FAILED",failure.code);}
        catch(CoupangFailure failure){finish(job,"PAUSED",failure.kind().name());}
        catch(RuntimeException unexpected){finish(job,"FAILED","INTERNAL");}
        finally{store.jobContext(null);currentJob=null;}
    }
    private void item(long job,String product,String status,String error){store.db.update("UPDATE coupang_product_collection_item SET status=?,error_code=? WHERE job_id=? AND seller_product_id=?",status,error,job,product);}
    private void finish(long job,String status,String error){
        try {
            store.tx.executeWithoutResult(t->{
                int changed=store.db.update("UPDATE coupang_product_collection SET status=?,phase=IF(? IN ('SUCCEEDED','PARTIAL'),'DONE',phase),error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND lease_owner=? AND status='RUNNING'",status,status,error,job,owner);
                if(changed==1)notifyResult(job);
            });
        }catch(RuntimeException failure){
            // The result and inbox both rolled back. Keep the lease for explicit interruption recovery.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("쿠팡 수집 결과 기록 실패 jobId={} type={}",job,failure.getClass().getSimpleName());
        }
    }
    private void notifyResult(long job) {
        var row=store.db.queryForMap("SELECT account_key,actor_id FROM coupang_product_collection WHERE id=?",job);
        var result=collectionSnapshot(job,(String)row.get("account_key"));
        var reasons=new LinkedHashSet<String>();if(result.error()!=null)reasons.add(result.error());
        reasons.addAll(store.db.queryForList("SELECT DISTINCT error_code FROM coupang_product_collection_item WHERE job_id=? AND status='FAILED' AND error_code IS NOT NULL ORDER BY error_code LIMIT 3",String.class,job));
        notifications.publish(new CoupangCollectionNotifications.Result("coupang-collection:"+job+":"+result.status()+":"+result.updatedAt(),((Number)row.get("actor_id")).longValue(),result,List.copyOf(reasons)));
    }
    private void settle(String a,String product){store.tx.executeWithoutResult(t->{var copy=copies.product(a,product);if(copy==null)return;copy=copies.find(id(copy.id()),true);if(submissions.active(id(copy.id()),a,-1))return;
        var p=store.find(a,product,true);var conflicts=store.conflicts(p,copy,source);var matched=conflicts.stream().filter(c->c.state().equals("MATCHED")).map(Conflict::key).collect(java.util.stream.Collectors.toSet());
        if(matched.isEmpty())return;var remaining=copy.changes().stream().filter(c->!matched.contains(RegisteredProductStore.key(c))).toList();
        store.db.update("UPDATE coupang_product_working_copy SET changes_json=? WHERE id=?",store.json.writeValueAsString(remaining),id(copy.id()));
        for(String key:matched){store.db.update("DELETE FROM coupang_product_intent_base WHERE working_copy_id=? AND change_key=?",id(copy.id()),key);store.event(p.id(),"INTENT_OBSERVED",copy.id(),key,null,"true",null);}
    });}
    public Detail resolve(Long actor,String product,Resolve input){String a=account(actor);if(input==null || !Set.of("COUPANG","LOCAL").contains(Objects.toString(input.choice(),"")))throw new InputValidationFailure("선택을 확인해 주세요.");
        store.tx.executeWithoutResult(t->{var copy=copies.product(a,product);if(copy==null)throw new InputValidationFailure("저장된 수정값이 없습니다.");copy=copies.find(id(copy.id()),true);var p=store.find(a,product,true);
            if(copy.revision()!=input.revision() || p.row().version()!=input.observedVersion() || submissions.active(id(copy.id()),a,-1))throw new InputValidationFailure("값이 변경되었거나 전송 중입니다. 다시 확인해 주세요.");
            var conflicts=store.conflicts(p,copy,source);var chosen=conflicts.stream().filter(c->c.key().equals(input.key())).findFirst().orElseThrow(()->new InputValidationFailure("수정 항목이 없습니다."));
            if(chosen.state().equals("UNVERIFIED"))throw new InputValidationFailure("최신값을 갱신한 뒤 다시 확인해 주세요.");
            var fresh=store.document(p,copy,source);var remaining=copy.changes();
            if(input.choice().equals("COUPANG")){remaining=remaining.stream().filter(c->!RegisteredProductStore.key(c).equals(input.key())).toList();store.db.update("DELETE FROM coupang_product_intent_base WHERE working_copy_id=? AND change_key=?",id(copy.id()),input.key());}
            else {
                String observed=store.value(fresh,chosen.change());
                store.db.update("INSERT INTO coupang_product_intent_base(working_copy_id,change_key,base_json,acknowledged_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE acknowledged_json=VALUES(acknowledged_json)",id(copy.id()),input.key(),observed,observed);
                if(chosen.state().equals("BASELINE_MISSING"))store.event(p.id(),"BASELINE_RECONFIRMED",copy.id(),chosen.change().path(),null,observed,actor);
            }
            var updated=copies.update(actor,copy,CoupangInputs.apply(store.json,fresh,remaining),remaining,null);
            if(assets!=null)assets.replaceReferences(actor,updated.id(),CoupangWorkingCopyDocuments.assetIds(updated.document()));
            store.event(p.id(),"CONFLICT_"+input.choice(),copy.id(),chosen.change().path(),store.json.writeValueAsString(chosen.desired()),store.json.writeValueAsString(input.choice().equals("COUPANG")?chosen.current():chosen.desired()),actor);
            store.db.update("UPDATE coupang_edit_session SET revoked=TRUE WHERE working_copy_id=?",id(copy.id()));
        });return detail(a,product);
    }
    public Detail confirmDeletion(Long actor,String product,DeleteConfirmation input){String a=account(actor);if(input==null)throw new InputValidationFailure("삭제 확인값이 필요합니다.");
        store.tx.executeWithoutResult(t->{var copy=copies.product(a,product);if(copy!=null){copy=copies.find(id(copy.id()),true);if(submissions.active(id(copy.id()),a,-1))throw new InputValidationFailure("전송 결과를 먼저 확인해 주세요.");}
            var p=store.find(a,product,true);if(p.row().version()!=input.observedVersion() || !p.row().deletionCandidate())throw new InputValidationFailure("삭제 후보를 다시 확인해 주세요.");
            store.db.update("UPDATE coupang_registered_product SET deleted=?,deletion_candidate=FALSE,deletion_reason=?,deletion_actor=?,deletion_at=IF(?,CURRENT_TIMESTAMP(6),NULL),revision=revision+1,lifecycle_order=? WHERE id=?",input.deleted(),input.deleted()?"USER_CONFIRMED":"USER_REJECTED",actor,input.deleted(),BusinessIds.next(),p.id());
            store.event(p.id(),input.deleted()?"USER_DELETION_CONFIRMED":"USER_DELETION_REJECTED","","lifecycle",Boolean.toString(p.row().deleted()),Boolean.toString(input.deleted()),actor);
        });return detail(a,product);
    }
    public CoupangSubmissions.Preview prepareAction(Long actor,String product,Action input){String a=account(actor);if(input==null || !Set.of("SALE_STOP","SALE_RESUME","DELETE").contains(Objects.toString(input.type(),"")))throw new InputValidationFailure("상품 작업을 확인해 주세요.");
        var p=store.find(a,product,false);if(p.row().deleted() || p.row().version()!=input.observedVersion())throw new InputValidationFailure("상품 상태를 다시 확인해 주세요.");
        var entry=editing.open(actor,product,null);if(entry.blocked())throw new InputValidationFailure("최신 상품을 확인할 수 없습니다.");
        if(entry.workingCopyId()==null)entry=editing.save(actor,null,new CoupangWorkingCopies.Save(entry.sessionId(),null,entry.document(),entry.changes()));
        return sending.prepareAction(actor,new CoupangEditing.PrepareRequest(entry.workingCopyId(),entry.revision(),entry.sessionId(),entry.changes()),input.type(),input.vendorItemId());
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
