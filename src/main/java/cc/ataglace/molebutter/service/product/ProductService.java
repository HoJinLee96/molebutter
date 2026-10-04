package cc.ataglace.molebutter.service.product;

import java.util.*;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.exception.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ProductService {
    private final ProductStore db;
    private final cc.ataglace.molebutter.service.inventory.InventoryLinkService inventory;
    private final ProductChangeService changes;
    private final cc.ataglace.molebutter.service.notification.NotificationService notifications;
    private final ProductSupplierService suppliers;
    private final ProductRegistrationWorkbook excel;
    /** 대표 사진: 선정 판매글의 최신 사진, 없으면 마지막 최신화의 첫 판매글(검색 1순위) 사진. 조회 때 계산해 선정·해제·병합에 바로 맞춰진다. */
    private static final String SELECT="""
        SELECT p.id,p.brand_id,p.product_code,p.comparison_code,p.code_type,p.search_query,p.search_mode,
        p.managed,p.revision,p.lookup_revision,COALESCE(ss.image_url,p.image_url) image_url,p.latest_status,p.latest_at,p.latest_result,
        COALESCE(b.name,'') brand_name,COALESCE(b.code_brand,'') brand_key,
        (SELECT COUNT(*) FROM catalog_product d WHERE p.product_code<>'' AND UPPER(TRIM(d.product_code))=UPPER(TRIM(p.product_code)) AND d.id<>p.id AND d.merged_into IS NULL AND d.deleted_at IS NULL) duplicate_count
        FROM catalog_product p LEFT JOIN product_brand b ON b.id=p.brand_id
        LEFT JOIN product_supplier_selection sel ON sel.product_id=p.id LEFT JOIN product_supplier ss ON ss.id=sel.supplier_id
        """;
    public PageResponse<CatalogProduct> list(Long actor,String q,String mode,String status,int page){return list(actor,q,mode,status,page,20);}
    public PageResponse<CatalogProduct> list(Long actor,String q,String mode,String status,int page,int size) {return list(actor,q,mode,status,page,size,"ALL");}
    public PageResponse<CatalogProduct> list(Long actor,String q,String mode,String status,int page,int size,String change) {
        if(!List.of("ALL","SELECTED","ANY").contains(change))throw new IllegalArgumentException("변동 필터를 확인해 주세요.");
        db.authorize(actor,false);if(page<0||!List.of(20,50,100).contains(size))throw new IllegalArgumentException("페이지와 표시 개수(20·50·100)를 확인해 주세요.");
        q=ProductStore.text(q,255,false);status=ProductStore.text(status,30,false);
        if(!List.of("ALL","AUTO","MANUAL").contains(mode))throw new IllegalArgumentException("관리 구분을 확인해 주세요.");
        String where=" WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND (?='' OR LOCATE(?,COALESCE(b.name,''))>0 OR LOCATE(?,p.product_code)>0 OR LOCATE(?,p.search_query)>0) AND (?='ALL' OR p.managed=(?='AUTO')) AND (?='' OR (CASE p.latest_status WHEN 'NO_MATCH' THEN 'SOLD_OUT' WHEN 'STALE' THEN 'NOT_CHECKED' WHEN 'BLOCKED' THEN 'FAILED' ELSE p.latest_status END)=?)";
        if(!change.equals("ALL"))where+=" AND EXISTS(SELECT 1 FROM product_change_summary d WHERE d.product_id=p.id AND d."+(change.equals("SELECTED")?"selected_changed":"any_changed")+"=TRUE)";
        Object[] args={q,q,q,q,mode,mode,status,status};
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product p LEFT JOIN product_brand b ON b.id=p.brand_id"+where,Long.class,args);
        var params=new ArrayList<>(List.of(args));params.add(size);params.add((long)page*size);
        String summary=SELECT.replace("p.latest_result,","JSON_SET(p.latest_result,'$.suppliers',JSON_ARRAY()) latest_result,");
        return new PageResponse<>(catalogs(summary+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",params.toArray()),page,(int)((count+size-1)/size),count);
    }
    public cc.ataglace.molebutter.dto.product.ChangeDtos.Counts changeCounts(Long actor,String q,String mode,String status){
        db.authorize(actor,false);q=ProductStore.text(q,255,false);status=ProductStore.text(status,30,false);
        if(!List.of("ALL","AUTO","MANUAL").contains(mode))throw new IllegalArgumentException("관리 구분을 확인해 주세요.");
        var row=db.jdbc.queryForMap("SELECT COALESCE(SUM(d.selected_changed),0) selected_count,COALESCE(SUM(d.any_changed),0) all_count FROM catalog_product p LEFT JOIN product_brand b ON b.id=p.brand_id JOIN product_change_summary d ON d.product_id=p.id WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND (?='' OR LOCATE(?,COALESCE(b.name,''))>0 OR LOCATE(?,p.product_code)>0 OR LOCATE(?,p.search_query)>0) AND (?='ALL' OR p.managed=(?='AUTO')) AND (?='' OR (CASE p.latest_status WHEN 'NO_MATCH' THEN 'SOLD_OUT' WHEN 'STALE' THEN 'NOT_CHECKED' WHEN 'BLOCKED' THEN 'FAILED' ELSE p.latest_status END)=?)",q,q,q,q,mode,mode,status,status);
        return new cc.ataglace.molebutter.dto.product.ChangeDtos.Counts(((Number)row.get("selected_count")).longValue(),((Number)row.get("all_count")).longValue());
    }
    private List<CatalogProduct> catalogs(String sql,Object... args) {
        var rows=db.jdbc.query(sql,(r,n)->new CatalogProduct(r.getString("id"),r.getString("brand_name"),r.getString("brand_id"),r.getString("product_code"),r.getString("comparison_code"),r.getString("code_type"),r.getString("brand_key"),r.getString("search_query"),r.getString("search_mode"),ProductCodePolicy.suggested(r.getString("code_type"),r.getString("product_code")),r.getBoolean("managed"),r.getLong("revision"),r.getLong("lookup_revision"),r.getString("image_url"),r.getLong("duplicate_count"),SupplierLookupStatusPolicy.display(r.getString("latest_status"),null),ProductStore.date(r,"latest_at"),db.decode(r.getString("latest_result"),RefreshResult.class),null),args);
        var ids=rows.stream().map(CatalogProduct::id).toList();var selections=suppliers.selected(ids);var delta=changes.summaries(ids);return rows.stream().map(p->p.withSelection(selections.get(p.id())).withChanges(delta.get(p.id()))).toList();
    }
    public CatalogProduct product(long id){return catalogs(SELECT+" WHERE p.id=? AND p.merged_into IS NULL AND p.deleted_at IS NULL",id).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    public Map<String,Object> detail(Long actor,long id) {
        db.authorize(actor,false);var p=product(id);Map<String,Object> result=new LinkedHashMap<>();result.put("product",p);
        result.put("activeRefresh",db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE e.product_id=? AND e.lookup_revision=? AND e.status IN ('PENDING','CHECKING') AND r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')",Long.class,id,p.lookupRevision())>0);
        result.put("suppliers",db.jdbc.queryForList("SELECT CAST(id AS CHAR) id,mall,mall_product_id mallProductId,naver_product_id naverProductId,url,image_url imageUrl,last_seen_at lastSeenAt FROM product_supplier WHERE product_id=? ORDER BY id",id));
        result.put("lastGoodResult",db.decode(db.jdbc.queryForObject("SELECT last_good_result FROM catalog_product WHERE id=?",String.class,id),RefreshResult.class));
        result.put("assessmentSettingsChanged",Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT COALESCE((SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(r.preference_snapshot,'$.revision')) AS UNSIGNED)<>(SELECT preference_revision FROM product_settings WHERE id=1) FROM product_refresh_entry e JOIN product_refresh_run r ON r.id=e.run_id WHERE e.product_id=? ORDER BY e.run_id DESC LIMIT 1),FALSE)",Boolean.class,id)));
        result.put("comparison",suppliers.comparison(id));result.put("changes",changes.summary(id));result.put("changeSuppliers",changes.historySuppliers(id));result.put("history",history(actor,id,0));return result;
    }
    public PageResponse<Map<String,Object>> history(Long actor,long id,int page) {
        db.authorize(actor,false);product(id);if(page<0)throw new IllegalArgumentException("페이지를 확인해 주세요.");
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_lookup_history WHERE product_id=?",Long.class,id);
        var rows=db.jdbc.queryForList("SELECT CAST(id AS CHAR) id,CAST(run_id AS CHAR) runId,legacy,created_at createdAt,payload FROM product_lookup_history WHERE product_id=? ORDER BY id DESC LIMIT 20 OFFSET ?",id,(long)page*20);
        rows.forEach(r->r.put("payload",db.json.readTree(r.get("payload").toString())));return new PageResponse<>(rows,page,(int)((count+19)/20),count);
    }
    public List<String> brands(Long actor){db.authorize(actor,false);return db.jdbc.queryForList("SELECT name FROM product_brand ORDER BY name,id",String.class);}
    public List<CatalogProduct> duplicates(Long actor,long id){db.authorize(actor,false);var p=product(id);return p.productCode().isBlank()?List.of():catalogs(SELECT+" WHERE p.merged_into IS NULL AND p.deleted_at IS NULL AND UPPER(TRIM(p.product_code))=? AND p.id<>? ORDER BY p.id",ProductCodePolicy.normalize(p.productCode()),id);}
    private record Brand(Long id,String name,String key){}
    private Brand resolve(String id,String name) {
        if(id!=null&&id.isBlank()||id==null&&(name==null||name.isBlank()))return new Brand(null,"","");
        var rows=id!=null?db.jdbc.queryForList("SELECT * FROM product_brand WHERE id=?",Long.valueOf(id)):db.jdbc.queryForList("SELECT * FROM product_brand WHERE name=?",ProductStore.text(name,100,true));
        if(rows.isEmpty())throw new IllegalArgumentException("공통 설정에 등록된 브랜드를 선택해 주세요.");
        var r=rows.getFirst();return new Brand(((Number)r.get("id")).longValue(),r.get("name").toString(),r.get("code_brand").toString());
    }
    private record Identity(String code,String type,String comparison,String query,String mode){}
    private Identity identity(CatalogEdit input) {
        String code=ProductCodePolicy.normalize(ProductStore.text(input.productCode(),100,true));
        String query=ProductStore.text(input.searchQuery(),255,true);
        return new Identity(code,"GENERAL","",query,"MANUAL");
    }
    private Identity existingIdentity(CatalogProduct p) {return new Identity(p.productCode(),p.codeType(),p.comparisonCode(),p.searchQuery(),p.searchMode());}
    // Compatibility endpoint only; registration and edits never apply its suggestion.
    public Map<String,String> codePreview(Long actor,CatalogEdit input) {db.authorize(actor,false);var b=resolve(input.brandId(),input.brand());String code=ProductCodePolicy.normalize(ProductStore.text(input.productCode(),100,false)),type=b.key().isBlank()?"GENERAL":"LF_ACCESSORY";return Map.of("comparisonCode",ProductCodePolicy.comparison(type,code),"codeType",type,"suggestedQuery",ProductCodePolicy.suggested(type,code));}
    @Transactional(isolation=Isolation.READ_COMMITTED) public CatalogProduct create(Long actor,CatalogEdit input) {
        db.authorize(actor,false);db.lock();Brand b=resolve(input.brandId(),input.brand());Identity i=identity(input);
        if(i.code().isBlank())throw new IllegalArgumentException("전체 상품코드를 입력해 주세요.");
        if(!matchingIds(i.code()).isEmpty())throw new cc.ataglace.molebutter.exception.OperationFailure("같은 전체 상품코드가 이미 등록되어 있습니다.");
        return insert(b,i,List.of());
    }
    private CatalogProduct insert(Brand b,Identity i,List<String> names) {
        long id=ProductStore.id();db.jdbc.update("INSERT INTO catalog_product(id,brand,brand_id,product_code,code_type,comparison_code,search_query,search_mode,registration_names,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,b.name(),b.id(),i.code(),i.type(),i.comparison(),i.query(),i.mode(),db.encode(names),db.time.now(),db.time.now());return product(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CatalogProduct edit(Long actor,long id,CatalogEdit input) {
        db.authorize(actor,false);db.lock();var p=product(id);ProductStore.revision(p.revision(),input.revision());
        Brand b=resolve(input.brandId(),input.brand());Identity i=identity(input);
        if(!i.code().equals(p.productCode())&&matchingIds(i.code()).stream().anyMatch(v->v!=id))throw new cc.ataglace.molebutter.exception.OperationFailure("같은 전체 상품코드가 이미 등록되어 있습니다.");
        update(p,b,i);return product(id);
    }
    private void update(CatalogProduct p,Brand b,Identity i) {
        boolean changed=!i.code().equals(p.productCode())||!i.query().equals(p.searchQuery());
        db.jdbc.update("UPDATE catalog_product SET brand=?,brand_id=?,product_code=?,search_query=?,revision=revision+1,updated_at=? WHERE id=?",b.name(),b.id(),i.code(),i.query(),db.time.now(),p.id());
        if(changed)invalidate(Long.parseLong(p.id()));
    }
    private void invalidate(long id){changes.reset(id);db.jdbc.update("UPDATE catalog_product SET lookup_revision=lookup_revision+1,latest_status='NOT_CHECKED',latest_result=NULL,latest_at=NULL WHERE id=?",id);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public void bulk(Long actor,BulkEdit input) {
        db.authorize(actor,false);db.lock();var ids=checkVersions(input.products());
        if(input.brand()==null&&input.brandId()==null&&input.managed()==null)throw new IllegalArgumentException("변경할 값을 입력해 주세요.");
        Brand b=input.brand()!=null||input.brandId()!=null?resolve(input.brandId(),input.brand()):null;
        for(long id:ids) {
            if(b!=null){var p=product(id);update(p,b,existingIdentity(p));}
            if(input.managed()!=null)db.jdbc.update("UPDATE catalog_product SET managed=?,revision=revision+1,updated_at=? WHERE id=?",input.managed(),db.time.now(),id);
        }
    }
    private List<Long> checkVersions(List<VersionedId> values) {
        if(values==null||values.isEmpty()||values.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("상품을 선택해 주세요.");
        var ids=ProductStore.ids(values.stream().map(VersionedId::id).toList());if(ids.size()!=values.size())throw new IllegalArgumentException("선택이 중복되었습니다.");
        for(var v:values)ProductStore.revision(product(Long.parseLong(v.id())).revision(),v.revision());return ids;
    }
    private void ensureIdle(List<Long> ids) {
        long running=db.jdbc.queryForObject("SELECT COUNT(*) FROM product_refresh_entry i JOIN product_refresh_run r ON r.id=i.run_id WHERE r.status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT') AND i.product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",Long.class,ids.toArray());
        if(running>0)throw new cc.ataglace.molebutter.exception.OperationFailure("해당 상품의 최신화 작업을 완료하거나 취소한 뒤 변경해 주세요.");
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void delete(Long actor,DeleteProducts input) {
        db.authorize(actor,false);db.lock();var ids=checkVersions(input.products());ensureIdle(ids);inventory.assertDeletable(ids);
        for(long id:ids)db.jdbc.update("UPDATE catalog_product SET deleted_at=?,deleted_by=?,managed=FALSE,revision=revision+1,lookup_revision=lookup_revision+1,updated_at=? WHERE id=?",db.time.now(),actor,db.time.now(),id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public CatalogProduct merge(Long actor,long target,MergeInput input) {
        db.authorize(actor,false);db.lock();var ids=checkVersions(input.products());ensureIdle(ids);
        if(ids.size()<2||!ids.contains(target)||input.managed()==null)throw new IllegalArgumentException("대표 상품을 포함한 통합 대상을 선택해 주세요.");
        String code=ProductCodePolicy.normalize(input.productCode());
        if(code.isBlank()||ids.stream().anyMatch(id->!ProductCodePolicy.normalize(product(id).productCode()).equals(code)))throw new IllegalArgumentException("동일한 전체 상품코드만 통합할 수 있습니다. 시즌이 다른 상품은 별도로 유지합니다.");
        String chosen=suppliers.mergeChoice(ids,input.selectedSupplierId());var before=ids.stream().map(id->detail(actor,id)).toList();Set<String> names=new LinkedHashSet<>();
        for(long id:ids) {
            names.addAll(registrationNames(id));if(id==target)continue;
            chosen=suppliers.moveListings(target,id,chosen);changes.merge(target,id);inventory.merge(target,id);
            db.jdbc.update("UPDATE product_lookup_history SET product_id=? WHERE product_id=?",target,id);
            db.jdbc.update("UPDATE catalog_product SET merged_into=?,revision=revision+1,lookup_revision=lookup_revision+1 WHERE id=?",target,id);
        }
        suppliers.mergeSelections(actor,target,ids,chosen);
        var b=resolve(input.brandId(),input.brand());var p=product(target);
        update(p,b,identity(new CatalogEdit(p.revision(),input.brand(),code,input.searchQuery(),input.brandId(),null,null)));invalidate(target);
        db.jdbc.update("UPDATE catalog_product SET registration_names=?,managed=? WHERE id=?",db.encode(names),input.managed(),target);
        db.jdbc.update("INSERT INTO catalog_merge_history(id,target_id,actor_id,created_at,before_snapshot,after_snapshot) VALUES(?,?,?,?,?,?)",ProductStore.id(),target,actor,db.time.now(),db.encode(before),db.encode(detail(actor,target)));return product(target);
    }
    private List<Long> matchingIds(String code){if(code.isBlank())return List.of();return db.jdbc.queryForList("SELECT id FROM catalog_product WHERE UPPER(TRIM(product_code))=? AND merged_into IS NULL AND deleted_at IS NULL ORDER BY id",Long.class,code);}
    private List<String> registrationNames(long id){String value=db.jdbc.queryForObject("SELECT registration_names FROM catalog_product WHERE id=?",String.class,id);return value==null?List.of():List.of(db.decode(value,String[].class));}
    private Map<Long,String> brandNames(){Map<Long,String> map=new LinkedHashMap<>();db.jdbc.query("SELECT id,name FROM product_brand ORDER BY id",r->{map.put(r.getLong(1),r.getString(2));});return map;}
    @Transactional(isolation=Isolation.READ_COMMITTED) public BrandInferenceResult inferBrands(Long actor,BrandInferenceInput input){db.authorize(actor,false);db.lock();return assignMissingBrands(checkVersions(input.products()));}
    private BrandInferenceResult assignMissingBrands(List<Long> ids) {
        var matcher=new ProductBrandMatcher(brandNames());int changed=0,preserved=0,unresolved=0;
        for(long id:ids){var p=product(id);if(p.brandId()!=null){preserved++;continue;}Long brand=matcher.matchAll(registrationNames(id));if(brand==null){unresolved++;continue;}
            var b=resolve(brand.toString(),null);update(p,b,existingIdentity(p));changed++;}
        return new BrandInferenceResult(changed,preserved,unresolved);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ImportResult upload(Long actor,String name,byte[] bytes) {
        db.authorize(actor,false);var parsed=excel.parse(bytes);db.lock();
        Map<String,List<ProductRegistrationWorkbook.Row>> groups=new LinkedHashMap<>();var warnings=new ArrayList<>(parsed.warnings());int excluded=0,duplicates=0,created=0,existing=0;
        for(var row:parsed.rows()){String code=ProductCodePolicy.normalize(row.code());if(code.isBlank()){excluded++;continue;}if(code.length()>100||row.title().length()>1000){excluded++;warnings.add(row.index()+"행: 상품코드 또는 등록명이 너무 깁니다.");continue;}groups.computeIfAbsent(code,k->new ArrayList<>()).add(row);}
        List<Long> touched=new ArrayList<>(),newProducts=new ArrayList<>();
        for(var e:groups.entrySet()) {
            var found=matchingIds(e.getKey());if(found.size()>1){excluded+=e.getValue().size();warnings.add(e.getValue().getFirst().index()+"행: 같은 코드의 기존 상품이 여러 개입니다. 통합 후 등록해 주세요.");continue;}
            duplicates+=e.getValue().size()-1;var titles=e.getValue().stream().map(ProductRegistrationWorkbook.Row::title).filter(t->!t.isBlank()).distinct().toList();long id;
            if(found.isEmpty()){var b=new Brand(null,"","");id=Long.parseLong(insert(b,new Identity(e.getKey(),"GENERAL","",e.getKey(),"AUTO"),titles).id());newProducts.add(id);created++;}
            else {id=found.getFirst();Set<String> all=new LinkedHashSet<>(registrationNames(id));all.addAll(titles);db.jdbc.update("UPDATE catalog_product SET registration_names=? WHERE id=?",db.encode(all),id);existing++;}
            touched.add(id);
        }
        int assigned=assignMissingBrands(touched).assigned();
        for(long id:newProducts){var p=product(id);String query=ProductCodePolicy.suggested(p.brandKey().isBlank()?"GENERAL":"LF_ACCESSORY",p.productCode());db.jdbc.update("UPDATE catalog_product SET search_query=?,search_mode='MANUAL' WHERE id=?",query,id);}
        notifications.publish("import:"+ProductStore.id(),"PRODUCT_IMPORT",excluded>0?"WARNING":"INFO","상품 일괄등록 완료","신규 "+created+" / 기존 "+existing+" / 중복 "+duplicates+" / 제외 "+excluded,"IMPORT",null,List.of(actor));
        return new ImportResult(parsed.rows().size(),created,existing,duplicates,excluded,assigned,List.copyOf(warnings));
    }
    public ScheduleSettings schedule(Long actor){db.authorize(actor,false);return db.schedule(false);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public ScheduleSettings updateSchedule(Long actor,ScheduleSettings s) {
        db.authorize(actor,true);var current=db.schedule(true);ProductStore.revision(current.revision(),s.revision());
        try{if(s.scheduleTime()==null||!s.scheduleTime().matches("[0-2][0-9]:[0-5][0-9]"))throw new IllegalArgumentException();LocalTime.parse(s.scheduleTime());}catch(Exception e){throw new IllegalArgumentException("예약 시각을 확인해 주세요.");}
        db.jdbc.update("UPDATE product_settings SET schedule_enabled=?,schedule_time=?,revision=revision+1 WHERE id=1",s.scheduleEnabled(),s.scheduleTime());return db.schedule(false);
    }
}
