package cc.ataglace.molebutter.coupang.internal;

import java.util.*;
import java.sql.Timestamp;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.*;

@Component
public class RegisteredProductStore implements CoupangObservations {
    private String publicBase="";
    @org.springframework.beans.factory.annotation.Value("${coupang.assets.public-base-url:}")
    void publicBase(String base){publicBase=base==null?"":base.replaceFirst("/+$","");}
    final JdbcTemplate db;
    final ObjectMapper json;
    final TransactionTemplate tx;
    private final ThreadLocal<Long> jobContext=new ThreadLocal<>();
    private final ThreadLocal<Long> executionContext=new ThreadLocal<>();
    public RegisteredProductStore(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db;this.json=json;tx=new TransactionTemplate(manager);
    }
    void jobContext(Long id){if(id==null)jobContext.remove();else jobContext.set(id);}
    void executionContext(Long id){if(id==null)executionContext.remove();else executionContext.set(id);}
    record Saved(long id,String account,String product,Row row,String raw,String detailJson) {}
    Saved find(String account,String product,boolean lock) {
        var rows=db.query("SELECT * FROM coupang_registered_product WHERE account_key=? AND seller_product_id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Saved(r.getLong("id"),account,product,new Row(json.readValue(r.getString("product_json"),CoupangCatalog.Product.class),
            r.getLong("revision"),instant(r.getTimestamp("last_success_at")),instant(r.getTimestamp("last_attempt_at")),r.getString("error_code"),
            r.getBoolean("deleted"),r.getBoolean("deletion_candidate"),r.getString("detail_json")!=null),r.getString("raw_detail_json"),r.getString("detail_json")),account,product);
        if(rows.isEmpty())throw new InputValidationFailure("DB에 저장된 쿠팡 상품이 없습니다. 먼저 가져오기를 실행해 주세요.");
        return rows.getFirst();
    }
    Saved optional(String account,String product){try{return find(account,product,false);}catch(InputValidationFailure absent){return null;}}
    static String instant(Timestamp t){return t==null?null:t.toInstant().toString();}
    @Override public void record(Read read) {tx.executeWithoutResult(t->observe(read));}
    private void observe(Read r) {
        if(r.account()==null || !r.account().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid observation account");
        if(r.kind().equals("INVENTORY") || r.kind().equals("INVENTORY_ERROR")) {
            var ids=db.queryForList("SELECT p.seller_product_id FROM coupang_registered_option o JOIN coupang_registered_product p ON p.id=o.product_id WHERE p.account_key=? AND o.vendor_item_id=? AND o.active=TRUE",String.class,r.account(),r.optionId());
            for(String id:ids)inventory(find(r.account(),id,true),r);
            return;
        }
        if(r.productId()==null)return;
        if(r.summary()!=null) {
            var p=r.summary();
            db.update("INSERT IGNORE INTO coupang_registered_product(id,account_key,seller_product_id,product_json,product_name,status_name,created_at_external) VALUES(?,?,?,?,?,?,?)",
                BusinessIds.next(),r.account(),r.productId(),json.writeValueAsString(p),Objects.toString(p.sellerProductName(),""),p.statusName(),p.createdAt());
        }
        Saved saved;
        try{saved=find(r.account(),r.productId(),true);}catch(InputValidationFailure absent){return;}
        logRead(saved,r);
        String orderColumn=r.kind().equals("SUMMARY")?"summary_order":"detail_order";
        long last=db.queryForObject("SELECT "+orderColumn+" FROM coupang_registered_product WHERE id=?",Long.class,saved.id());
        if(r.order()<last)return;
        if(r.kind().equals("DELETE")) {
            snapshot(saved,r,r.raw());
            event(saved.id(),"APP_DELETE_CONFIRMED","","lifecycle",Boolean.toString(saved.row().deleted()),"true",r.actor());
            db.update("UPDATE coupang_registered_product SET deleted=TRUE,deletion_candidate=FALSE,deletion_reason='APP_DELETE',deletion_actor=?,deletion_at=CURRENT_TIMESTAMP(6),lifecycle_order=?,revision=revision+1 WHERE id=?",r.actor(),r.order(),saved.id());return;
        }
        if(r.error()!=null) {
            db.update("UPDATE coupang_registered_product SET detail_order=?,last_attempt_at=CURRENT_TIMESTAMP(6),error_code=? WHERE id=?",r.order(),r.error(),saved.id());
            return;
        }
        snapshot(saved,r,r.raw());
        var p=r.summary();
        if(r.kind().equals("SUMMARY")) {
            // A summary seeds an uncollected product; it never replaces richer detail fields.
            db.update("UPDATE coupang_registered_product SET summary_order=?,product_json=IF(detail_json IS NULL,?,product_json),product_name=IF(detail_json IS NULL,?,product_name),status_name=IF(detail_json IS NULL,?,status_name),last_success_at=CURRENT_TIMESTAMP(6),last_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                r.order(),json.writeValueAsString(p),Objects.toString(p.sellerProductName(),""),p.statusName(),saved.id());
        } else if(r.detail()!=null) {
            db.update("UPDATE coupang_registered_product SET detail_order=?,product_json=?,product_name=?,status_name=?,created_at_external=?,detail_json=?,raw_detail_json=?,last_success_at=CURRENT_TIMESTAMP(6),last_attempt_at=CURRENT_TIMESTAMP(6),error_code=NULL WHERE id=?",
                r.order(),json.writeValueAsString(p),p.sellerProductName(),p.statusName(),p.createdAt(),json.writeValueAsString(r.detail()),r.raw(),saved.id());
            db.update("UPDATE coupang_registered_option SET active=FALSE WHERE product_id=?",saved.id());
            var raw=json.readTree(r.raw());
            for(var option:r.detail().items()) {
                JsonNode nativeOption=null;
                for(var item:raw.path("items"))if(option.sellerProductItemId().equals(item.path("sellerProductItemId").asString()))nativeOption=item;
                String sku=nativeOption==null?null:nativeOption.path("externalVendorSku").asString(null);
                String model=nativeOption==null?null:nativeOption.path("modelNo").asString(null);
                db.update("INSERT INTO coupang_registered_option(product_id,seller_product_item_id,vendor_item_id,sku,model_no,option_json,outbound_shipping_days) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE current_json=IF(vendor_item_id <=> VALUES(vendor_item_id),current_json,NULL),current_price=IF(vendor_item_id <=> VALUES(vendor_item_id),current_price,NULL),current_stock=IF(vendor_item_id <=> VALUES(vendor_item_id),current_stock,NULL),on_sale=IF(vendor_item_id <=> VALUES(vendor_item_id),on_sale,NULL),last_success_at=IF(vendor_item_id <=> VALUES(vendor_item_id),last_success_at,NULL),inventory_order=IF(vendor_item_id <=> VALUES(vendor_item_id),inventory_order,0),error_code=IF(vendor_item_id <=> VALUES(vendor_item_id),error_code,'IDENTITY_CHANGED'),vendor_item_id=VALUES(vendor_item_id),sku=VALUES(sku),model_no=VALUES(model_no),option_json=VALUES(option_json),outbound_shipping_days=VALUES(outbound_shipping_days),active=TRUE",
                    saved.id(),option.sellerProductItemId(),option.vendorItemId(),sku,model,json.writeValueAsString(option),shippingDays(nativeOption));
            }
        }
        long lifecycleOrder=db.queryForObject("SELECT lifecycle_order FROM coupang_registered_product WHERE id=?",Long.class,saved.id());
        if(r.order()>=lifecycleOrder && p!=null) {
            boolean deleted=Set.of("DELETED","상품삭제").contains(Objects.toString(p.statusName(),""));
            // A list entry alone is not enough to resurrect a confirmed deletion.
            if(deleted || r.kind().equals("DETAIL")) {
                if(deleted!=saved.row().deleted() || saved.row().deletionCandidate())
                    event(saved.id(),deleted?"DELETED":"EXISTENCE_CONFIRMED","","lifecycle",Boolean.toString(saved.row().deleted()),Boolean.toString(deleted),r.actor());
                db.update("UPDATE coupang_registered_product SET deleted=?,deletion_candidate=FALSE,deletion_reason=?,deletion_at=IF(?,CURRENT_TIMESTAMP(6),NULL),lifecycle_order=?,missing_job_id=NULL WHERE id=?",
                    deleted,deleted?"COUPANG_STATUS":null,deleted,r.order(),saved.id());
            }
        }
    }
    private static Integer shippingDays(JsonNode option) {
        if(option==null)return null;
        try{String v=option.path("outboundShippingTimeDay").asString(null);if(v==null || !v.matches("[0-9]{1,6}"))return null;return Integer.valueOf(v);}catch(RuntimeException malformed){return null;}
    }
    private void inventory(Saved saved,Read r) {
        logRead(saved,r);
        var rows=db.queryForList("SELECT seller_product_item_id,inventory_order FROM coupang_registered_option WHERE product_id=? AND vendor_item_id=? AND active=TRUE FOR UPDATE",saved.id(),r.optionId());
        for(var row:rows) {
            if(r.order()<((Number)row.get("inventory_order")).longValue())continue;
            if(r.error()==null) {
                snapshot(saved,r,r.raw());
                db.update("UPDATE coupang_registered_option SET current_json=?,current_price=?,current_stock=?,on_sale=?,inventory_order=?,last_success_at=CURRENT_TIMESTAMP(6),last_attempt_at=CURRENT_TIMESTAMP(6),error_code=NULL WHERE product_id=? AND seller_product_item_id=?",
                    json.writeValueAsString(r.current()),r.current().salePrice(),r.current().amountInStock(),r.current().onSale(),r.order(),saved.id(),row.get("seller_product_item_id"));
            }else db.update("UPDATE coupang_registered_option SET inventory_order=?,last_attempt_at=CURRENT_TIMESTAMP(6),error_code=? WHERE product_id=? AND seller_product_item_id=?",r.order(),r.error(),saved.id(),row.get("seller_product_item_id"));
        }
    }
    private void logRead(Saved saved,Read r) {
        db.update("INSERT IGNORE INTO coupang_product_read(request_order,product_id,kind,subject,error_code,actor_id,job_id) VALUES(?,?,?,?,?,?,?)",
            r.order(),saved.id(),r.kind(),Objects.toString(r.optionId(),""),r.error(),r.actor(),jobContext.get());
    }
    private void snapshot(Saved saved,Read r,String raw) {
        if(raw==null)return;
        var node=json.readTree(raw);
        String subject=Objects.toString(r.optionId(),"");
        String hash=CoupangHashes.sha256(canonical(node));
        var prior=db.queryForList("SELECT raw_json,canonical_hash FROM coupang_product_snapshot WHERE product_id=? AND kind=? AND subject=? ORDER BY id DESC LIMIT 1",saved.id(),r.kind(),subject);
        if(!prior.isEmpty() && hash.equals(prior.getFirst().get("canonical_hash")))return;
        db.update("INSERT INTO coupang_product_snapshot(id,product_id,kind,subject,raw_json,canonical_hash,request_order) VALUES(?,?,?,?,?,?,?)",
            BusinessIds.next(),saved.id(),r.kind(),subject,raw,hash,r.order());
        JsonNode before=prior.isEmpty()?json.missingNode():json.readTree((String)prior.getFirst().get("raw_json"));
        diff(saved.id(),r.kind(),subject,"$",before,node,r.actor());
        db.update("UPDATE coupang_registered_product SET revision=revision+1 WHERE id=?",saved.id());
        pin(saved.id(),raw);
    }
    private static final ObjectMapper HASH_JSON=new ObjectMapper();
    static String canonical(JsonNode n) {
        if(n.isObject()){var keys=new TreeMap<String,JsonNode>();n.properties().forEach(e->keys.put(e.getKey(),e.getValue()));var parts=new ArrayList<String>();keys.forEach((k,v)->parts.add(HASH_JSON.writeValueAsString(k)+":"+canonical(v)));return "{"+String.join(",",parts)+"}";}
        if(n.isArray()){var parts=new ArrayList<String>();n.forEach(v->parts.add(canonical(v)));return "["+String.join(",",parts)+"]";}
        return n.toString();
    }
    private void diff(long product,String kind,String subject,String path,JsonNode before,JsonNode after,Long actor) {
        if(before.equals(after))return;
        if(before.isObject() && after.isObject()) {
            var keys=new TreeSet<String>();before.properties().forEach(e->keys.add(e.getKey()));after.properties().forEach(e->keys.add(e.getKey()));
            for(String key:keys)diff(product,kind,subject,path+"/"+key.replace("~","~0").replace("/","~1"),before.path(key),after.path(key),actor);
        }else if(before.isArray() && after.isArray() && keyed(before)!=null && keyed(after)!=null) {
            var b=keyed(before);var a=keyed(after);var keys=new TreeSet<String>();keys.addAll(b.keySet());keys.addAll(a.keySet());
            for(String key:keys)diff(product,kind,subject,path+"["+key+"]",b.getOrDefault(key,json.missingNode()),a.getOrDefault(key,json.missingNode()),actor);
            if(!new ArrayList<>(b.keySet()).equals(new ArrayList<>(a.keySet())))event(product,kind,subject,path+"/@order",json.writeValueAsString(b.keySet()),json.writeValueAsString(a.keySet()),actor);
        }else event(product,kind,subject,path,before.isMissingNode()?null:before.toString(),after.isMissingNode()?null:after.toString(),actor);
    }
    private static Map<String,JsonNode> keyed(JsonNode array) {
        var out=new LinkedHashMap<String,JsonNode>();
        for(var v:array) {
            String key=v.has("id")?v.path("id").asString():v.has("sellerProductItemId")?v.path("sellerProductItemId").asString():v.has("attributeTypeName")?v.path("attributeTypeName").asString()+":"+v.path("exposed").asString():v.has("noticeCategoryName")?v.path("noticeCategoryName").asString()+":"+v.path("noticeCategoryDetailName").asString():v.has("certificationType")?v.path("certificationType").asString():null;
            if(key==null || out.putIfAbsent(key,v)!=null)return null;
        }return out;
    }
    void event(long product,String kind,String subject,String path,String before,String after,Long actor) {
        db.update("INSERT INTO coupang_product_change(id,product_id,kind,subject,field_path,before_json,after_json,actor_id,job_id,execution_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            BusinessIds.next(),product,kind,subject,path,before,after,actor,jobContext.get(),executionContext.get());
    }
    private void pin(long product,String raw) {
        for(var row:db.queryForList("SELECT asset_id,token FROM coupang_asset_publication"))
            if(raw.contains("/coupang/images/"+row.get("token"))) {
                String asset=(String)row.get("asset_id");
                pinAsset(product,asset);
            }
    }
    private void pinAsset(long product,String asset) {
        db.queryForList("SELECT id FROM coupang_asset WHERE id=? FOR UPDATE",String.class,asset);
        db.update("INSERT IGNORE INTO coupang_product_asset_pin(product_id,asset_id) VALUES(?,?)",product,asset);
        db.update("UPDATE coupang_asset SET pending_delete_at=NULL WHERE id=?",asset);
    }
    CoupangCatalog.ProductDetail detail(Saved saved) {
        var d=saved.detailJson()==null?new CoupangCatalog.ProductDetail(saved.row().product(),null,null,null,null,null,null,null,List.of(),List.of(),List.of()):json.readValue(saved.detailJson(),CoupangCatalog.ProductDetail.class);
        var options=new ArrayList<CoupangCatalog.ProductOption>();
        for(var o:d.items()) {
            var rows=db.queryForList("SELECT current_json,error_code FROM coupang_registered_option WHERE product_id=? AND seller_product_item_id=?",saved.id(),o.sellerProductItemId());
            var row=rows.isEmpty()?Map.<String,Object>of():rows.getFirst();
            String current=(String)row.get("current_json");String error=(String)row.get("error_code");
            options.add(o.withCurrent(current==null?null:json.readValue(current,CoupangCatalog.CurrentInventory.class),error==null && current==null?"현재 판매값 미수집":error));
        }
        return d.withItems(options);
    }
    CoupangEditor.EditorDocument observation(String account,String product,CoupangProductSource source) {
        var saved=find(account,product,false);if(saved.raw()==null)throw new InputValidationFailure("상세를 가져온 뒤 다시 확인해 주세요.");
        var current=new HashMap<String,CoupangCatalog.CurrentInventory>();var errors=new HashMap<String,String>();
        db.query("SELECT vendor_item_id,current_json,error_code FROM coupang_registered_option WHERE product_id=? AND active=TRUE",r->{
            String vendor=r.getString("vendor_item_id");if(vendor==null)return;
            String value=r.getString("current_json"),error=r.getString("error_code");
            if(value!=null)current.put(vendor,json.readValue(value,CoupangCatalog.CurrentInventory.class));
            if(error!=null)errors.put(vendor,error);
        },saved.id());
        return source.project(saved.raw(),current,errors);
    }
    CoupangWorkingCopies.Document document(Saved saved,WorkingCopyStore.Copy copy,CoupangProductSource source) {
        if(saved.raw()==null || copy.mapping()==null)throw new InputValidationFailure("상세를 갱신한 뒤 다시 확인해 주세요.");
        var current=new HashMap<String,CoupangCatalog.CurrentInventory>();var errors=new HashMap<String,String>();
        for(var o:detail(saved).items())if(o.vendorItemId()!=null){if(o.current()!=null)current.put(o.vendorItemId(),o.current());if(o.currentError()!=null)errors.put(o.vendorItemId(),o.currentError());}
        var fresh=CoupangInputs.from(source.project(saved.raw(),current,errors),copy.mapping());
        var tokens=new WorkingCopyStore(db,json).publications(CoupangWorkingCopyDocuments.assetIds(copy.document()));
        var urls=new HashMap<String,String>();
        if(publicBase.startsWith("https://"))tokens.forEach((asset,token)->urls.put(asset,publicBase+"/coupang/images/"+token));
        return CoupangInputs.preserveMedia(fresh,copy.document(),tokens,urls);
    }
    static String key(CoupangEditing.Change c){return CoupangHashes.sha256(c.path()+":"+c.optionId()+":"+Objects.toString(c.rowKey(),""));}
    String value(CoupangWorkingCopies.Document d,CoupangEditing.Change c){var v=CoupangInputs.value(json,d,c);return v.isMissingNode()?"null":v.toString();}
    void saved(WorkingCopyStore.Copy copy,CoupangWorkingCopies.Document baseline,Long actor) {
        if(copy.product()==null)return;
        Saved p;try{p=find(copy.account(),copy.product(),true);}catch(InputValidationFailure absent){return;}
        var keys=new HashSet<String>();
        for(var change:copy.changes()) {
            if(CoupangInputs.legacyList(change))continue;
            String k=key(change);keys.add(k);
            db.update("INSERT IGNORE INTO coupang_product_intent_base(working_copy_id,change_key,base_json) VALUES(?,?,?)",Long.parseLong(copy.id()),k,value(baseline,change));
        }
        for(String old:db.queryForList("SELECT change_key FROM coupang_product_intent_base WHERE working_copy_id=?",String.class,Long.parseLong(copy.id())))
            if(!keys.contains(old))db.update("DELETE FROM coupang_product_intent_base WHERE working_copy_id=? AND change_key=?",Long.parseLong(copy.id()),old);
        // Complete local revision is retained, including changed media identities and discarded inputs.
        var previous=db.queryForList("SELECT after_json FROM coupang_product_change WHERE product_id=? AND kind='WORKING_COPY_SAVE' AND subject=? ORDER BY id DESC LIMIT 1",String.class,p.id(),copy.id());
        event(p.id(),"WORKING_COPY_SAVE",copy.id(),"document",previous.isEmpty()?json.writeValueAsString(baseline):previous.getFirst(),json.writeValueAsString(copy.document()),actor);
        for(String asset:CoupangWorkingCopyDocuments.assetIds(copy.document()))pinAsset(p.id(),asset);
        pin(p.id(),json.writeValueAsString(copy.document()));
    }
    List<Conflict> conflicts(Saved saved,WorkingCopyStore.Copy copy,CoupangProductSource source) {
        if(copy==null || copy.changes().isEmpty())return List.of();
        CoupangWorkingCopies.Document fresh;
        try{fresh=document(saved,copy,source);}catch(RuntimeException unavailable){return copy.changes().stream().map(c->new Conflict(key(c),c,null,null,c.value(),"UNVERIFIED")).toList();}
        var out=new ArrayList<Conflict>();
        for(var c:copy.changes()) {
            String k=key(c);String remote;
            try{remote=value(fresh,c);}catch(RuntimeException invalid){out.add(new Conflict(k,c,null,null,c.value(),"UNVERIFIED"));continue;}
            var b=db.queryForList("SELECT base_json,acknowledged_json FROM coupang_product_intent_base WHERE working_copy_id=? AND change_key=?",Long.parseLong(copy.id()),k);
            String state="UNVERIFIED",base=b.isEmpty()?null:(String)b.getFirst().get("base_json");
            if(verified(saved,c,copy)) {
                if(CoupangInputs.matches(json,fresh,c))state="MATCHED";
                else if(base!=null && remote.equals(base))state="PENDING";
                else if(!b.isEmpty() && remote.equals(b.getFirst().get("acknowledged_json")))state="KEEP_LOCAL";
                else if(base!=null)state="CONFLICT";
                else state="BASELINE_MISSING";
            }
            out.add(new Conflict(k,c,base==null?null:json.readTree(base),json.readTree(remote),c.value(),state));
        }return List.copyOf(out);
    }
    private boolean verified(Saved saved,CoupangEditing.Change c,WorkingCopyStore.Copy copy) {
        if(saved.row().error()!=null)return false;
        if(c.path().equals("options.registration.salePrice") || c.path().equals("options.registration.maximumBuyCount")) {
            var mapping=copy.mapping().options().stream().filter(o->o.optionId().equals(c.optionId())).findFirst().orElse(null);
            if(mapping==null)return false;
            return db.queryForObject("SELECT COUNT(*) FROM coupang_registered_option WHERE product_id=? AND seller_product_item_id=? AND current_json IS NOT NULL AND error_code IS NULL AND active=TRUE",Long.class,saved.id(),mapping.sellerProductItemId())==1;
        }return true;
    }
}
