package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.exception.InputValidationFailure;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class SupplierPreferenceService {
    private final ProductStore db;
    private final ProductChangeService changes;
    public Preferences get(Long actor){db.authorize(actor,false);return snapshot();}
    Preferences snapshot(){return new Preferences(db.jdbc.queryForObject("SELECT preference_revision FROM product_settings WHERE id=1",Long.class),stores(),db.jdbc.query("SELECT * FROM supplier_preference ORDER BY mall,id",(r,n)->new Rule(r.getString("id"),ProcurementMall.valueOf(r.getString("mall")),r.getString("store_id"),r.getLong("revision"))),branchRequirements());}
    private Map<ProcurementMall,Boolean> branchRequirements(){
        var policies=new EnumMap<ProcurementMall,Boolean>(ProcurementMall.class);
        db.jdbc.query("SELECT mall,branch_required FROM supplier_mall_policy",r->{policies.put(ProcurementMall.valueOf(r.getString("mall")),r.getBoolean("branch_required"));});
        return policies;
    }
    List<Store> stores(){
        var identities=new HashMap<String,List<ExternalIdentity>>();
        db.jdbc.query("SELECT * FROM supplier_store_identity",r->{identities.computeIfAbsent(r.getString("store_id"),k->new ArrayList<>()).add(new ExternalIdentity(r.getString("namespace"),r.getString("external_id")));});
        return db.jdbc.query("SELECT * FROM supplier_store ORDER BY mall,name,id",(r,n)->new Store(r.getString("id"),ProcurementMall.valueOf(r.getString("mall")),r.getString("kind"),r.getString("name"),r.getString("identity_key"),List.of(db.decode(r.getString("aliases"),String[].class)),r.getLong("revision"),r.getString("retailer"),identities.getOrDefault(r.getString("id"),List.of())));
    }
    Store store(String id){return stores().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new InputValidationFailure("등록된 매장을 선택해 주세요."));}
    private void changed(){db.jdbc.update("UPDATE product_settings SET preference_revision=preference_revision+1 WHERE id=1");changes.preferenceChanged();}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Store saveStore(Long actor,Long id,StoreInput input) {
        return saveStoreInternal(actor,id,input,false);
    }
    Store resolveNewStore(Long actor,ProcurementMall mall,StoreInput input){
        if(input==null||input.mall()!=mall)throw new InputValidationFailure("해당 쇼핑몰의 매장을 입력해 주세요.");
        return saveStoreInternal(actor,null,input,true);
    }
    private Store saveStoreInternal(Long actor,Long id,StoreInput input,boolean reuse){
        db.authorize(actor,true);db.lock();if(input.mall()==null||!List.of("ONLINE","BRANCH","SELLER","COMPANY","BRAND_STORE").contains(Objects.toString(input.kind(),"")))throw new InputValidationFailure("쇼핑몰과 매장 구분을 선택해 주세요.");
        if(id==null&&"SELLER".equals(input.kind()))throw new InputValidationFailure("쇼핑윈도 백화점 지점 또는 공식몰을 등록해 주세요.");
        if(input.mall()==ProcurementMall.NAVER_SMART_STORE&&!List.of("SELLER","BRANCH","BRAND_STORE").contains(input.kind()))throw new InputValidationFailure("네이버 쇼핑윈도는 실제 판매자별 매장을 등록해 주세요.");
        if(input.mall()!=ProcurementMall.NAVER_SMART_STORE&&"SELLER".equals(input.kind()))throw new InputValidationFailure("판매자 주소 등록은 네이버 쇼핑윈도에서 사용해 주세요.");
        if("BRAND_STORE".equals(input.kind())&&(id==null||input.mall()!=ProcurementMall.NAVER_SMART_STORE||input.retailer()!=null&&!input.retailer().isBlank()||input.sellerKey()!=null&&!input.sellerKey().isBlank()||input.productUrl()!=null))throw new InputValidationFailure("공식몰 상품 링크로 판매채널을 확인해 등록해 주세요.");
        String name=ProductStore.text(input.name(),120,true);
        if("COMPANY".equals(input.kind())&&(input.mall()!=ProcurementMall.LOTTE_ON||!"주식회사 LF".equals(name)||input.sellerKey()!=null&&!input.sellerKey().isBlank()||input.retailer()!=null&&!input.retailer().isBlank()))throw new InputValidationFailure("롯데ON의 주식회사 LF만 회사 매장으로 선택할 수 있습니다.");
        if(id!=null&&"COMPANY".equals(input.kind()))throw new InputValidationFailure("회사 매장 이름은 변경할 수 없습니다.");
        String retailer="BRANCH".equals(input.kind())?ProductStore.text(input.retailer(),120,false):"";
        if(input.mall()==ProcurementMall.HI_THEHYUNDAI&&"BRANCH".equals(input.kind()))retailer="현대백화점";
        if(id==null&&"BRANCH".equals(input.kind())&&Set.of(ProcurementMall.LOTTE_ON,ProcurementMall.NAVER_SMART_STORE).contains(input.mall())&&(retailer==null||retailer.isBlank()))throw new InputValidationFailure("백화점 이름을 입력해 주세요.");
        if(id!=null){var old=store(id.toString());ProductStore.revision(old.revision(),input.revision());if(old.mall()!=input.mall()||!old.kind().equals(input.kind()))throw new InputValidationFailure("매장의 쇼핑몰과 구분은 변경할 수 없습니다.");
            if(retailer==null||retailer.isBlank())retailer=old.retailer();
            if(!old.identities().isEmpty()&&!SupplierStorePolicy.normalize(old.retailer()).equals(SupplierStorePolicy.normalize(retailer)))throw new InputValidationFailure("외부 식별자가 연결된 매장의 백화점은 변경할 수 없습니다.");
            final String retailerName=retailer;
            if(old.kind().equals("BRANCH")&&stores().stream().anyMatch(s->!s.id().equals(old.id())&&s.mall()==old.mall()&&s.kind().equals("BRANCH")&&SupplierStorePolicy.normalize(s.retailer()).equals(SupplierStorePolicy.normalize(retailerName))&&s.aliases().stream().anyMatch(a->SupplierStorePolicy.normalize(a).equals(SupplierStorePolicy.normalize(name)))))throw new cc.ataglace.molebutter.exception.OperationFailure("다른 매장에서 사용 중인 이름입니다.");
            var aliases=new LinkedHashSet<>(old.aliases());aliases.add(old.name());aliases.add(name);
            if(aliases.size()>100)throw new InputValidationFailure("매장 이름 변경 이력이 너무 많습니다.");
            db.jdbc.update("UPDATE supplier_store SET name=?,aliases=?,retailer=?,revision=revision+1,updated_at=? WHERE id=?",name,db.encode(aliases),retailer,db.time.now(),id);changed();return store(id.toString());}
        String key=switch(input.kind()){case "COMPANY"->"company:lf";case "ONLINE"->"online";case "SELLER"->{String seller=ProductStore.text(input.sellerKey(),200,true).replaceFirst("^https://","").replaceAll("/+$", "");if(!seller.matches("(?:smartstore|brand)\\.naver\\.com/[A-Za-z0-9_-]+"))throw new InputValidationFailure("판매자 주소를 smartstore.naver.com/판매자ID 형식으로 입력해 주세요.");yield "seller:"+SupplierStorePolicy.normalize(seller);}default->"branch:"+(retailer==null||retailer.isBlank()?"":SupplierStorePolicy.normalize(retailer)+":")+SupplierStorePolicy.normalize(name);};
        if(reuse){var exact=stores().stream().filter(s->s.mall()==input.mall()&&s.kind().equals(input.kind())&&s.identityKey().equals(key)
                &&SupplierStorePolicy.normalize(s.retailer()).equals(SupplierStorePolicy.normalize(retailerFor(input)))).findFirst();
            if(exact.isPresent())return exact.get();}
        final String newRetailer=retailer;
        if(stores().stream().anyMatch(s->s.mall()==input.mall()&&(s.identityKey().equals(key)||SupplierStorePolicy.normalize(s.retailer()).equals(SupplierStorePolicy.normalize(newRetailer))&&s.aliases().stream().anyMatch(a->SupplierStorePolicy.normalize(a).equals(SupplierStorePolicy.normalize(name))))))throw new cc.ataglace.molebutter.exception.OperationFailure("이미 등록된 매장입니다. 기존 매장을 사용해 주세요.");
        Store saved=insert(input.mall(),input.kind(),name,key);db.jdbc.update("UPDATE supplier_store SET retailer=? WHERE id=?",retailer,saved.id());changed();return store(saved.id());
    }
    /** 조회는 트랜잭션 밖에서 완료하고, 확인한 식별자의 등록·전환만 원자적으로 수행한다. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Store registerBrandStore(Long actor,StoreInput input,ChannelPreview verified){
        db.authorize(actor,true);db.lock();checkPreferenceRevision(input.preferenceRevision());
        String name=ProductStore.text(input.name(),120,true);
        var external=new ExternalIdentity("NAVER_CHANNEL",verified.channelUid());
        var owners=stores().stream().filter(s->s.mall()==ProcurementMall.NAVER_SMART_STORE&&s.identities().contains(external)).toList();
        if(owners.size()>1)throw new InputValidationFailure("판매채널 연결을 확인해 주세요.");
        Store saved;
        if(!owners.isEmpty()){
            saved=owners.getFirst();
            if("BRAND_STORE".equals(saved.kind()))return saved;
            if(!"SELLER".equals(saved.kind())||saved.identities().stream().anyMatch(i->!i.equals(external)))throw new InputValidationFailure("기존 매장 식별자와 충돌합니다.");
            var aliases=new LinkedHashSet<>(saved.aliases());aliases.add(saved.name());aliases.add(name);
            db.jdbc.update("UPDATE supplier_store SET kind='BRAND_STORE',name=?,aliases=?,revision=revision+1,updated_at=? WHERE id=?",name,db.encode(aliases),db.time.now(),saved.id());
        }else{
            saved=insert(ProcurementMall.NAVER_SMART_STORE,"BRAND_STORE",name,"external:NAVER_CHANNEL:"+verified.channelUid());
            db.jdbc.update("INSERT INTO supplier_store_identity(mall,namespace,external_id,store_id) VALUES(?,?,?,?)",ProcurementMall.NAVER_SMART_STORE.name(),external.namespace(),external.externalId(),saved.id());
        }
        changed();return store(saved.id());
    }
    private String retailerFor(StoreInput input){return !"BRANCH".equals(input.kind())?"":input.mall()==ProcurementMall.HI_THEHYUNDAI?"현대백화점":input.retailer();}
    void checkPreferenceRevision(Long revision){ProductStore.revision(snapshot().revision(),revision);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Preferences saveMall(Long actor,ProcurementMall mall,MallPreferenceInput input){
        db.authorize(actor,true);db.lock();checkPreferenceRevision(input.revision());
        if(!Set.of("ALL","STORES").contains(Objects.toString(input.scope(),"")))throw new InputValidationFailure("허용 범위를 선택해 주세요.");
        boolean branchRequired=input.branchRequired()==null?branchRequirements().getOrDefault(mall,true):input.branchRequired();
        String scope=branchRequired?input.scope():"ALL";
        if(!branchRequired&&((input.newStores()!=null&&!input.newStores().isEmpty())||(input.storeIds()!=null&&!input.storeIds().isEmpty())))throw new InputValidationFailure("지점 구분을 끄면 매장 선택을 비워 주세요.");
        var ids=new LinkedHashSet<String>();
        if("ALL".equals(scope)){
            if(input.storeIds()!=null&&!input.storeIds().isEmpty()||input.newStores()!=null&&!input.newStores().isEmpty())throw new InputValidationFailure("전체 허용에는 특정 매장을 함께 저장할 수 없습니다.");
        }else{
            for(String id:input.storeIds()==null?List.<String>of():input.storeIds()){
                if(store(id).mall()!=mall)throw new InputValidationFailure("해당 쇼핑몰의 매장을 선택해 주세요.");ids.add(id);
            }
            for(StoreInput draft:input.newStores()==null?List.<StoreInput>of():input.newStores())ids.add(resolveNewStore(actor,mall,draft).id());
            if(ids.isEmpty())throw new InputValidationFailure("매장을 한 곳 이상 선택해 주세요.");
        }
        db.jdbc.update("DELETE FROM supplier_preference WHERE mall=?",mall.name());
        if("ALL".equals(scope))insertRule(mall,null);else ids.forEach(id->insertRule(mall,id));
        db.jdbc.update("INSERT INTO supplier_mall_policy(mall,branch_required) VALUES(?,?) ON DUPLICATE KEY UPDATE branch_required=VALUES(branch_required)",mall.name(),branchRequired);
        changed();return snapshot();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Preferences deleteMall(Long actor,ProcurementMall mall,Long revision){
        db.authorize(actor,true);db.lock();checkPreferenceRevision(revision);
        db.jdbc.update("DELETE FROM supplier_preference WHERE mall=?",mall.name());changed();return snapshot();
    }
    void addPreferred(Long actor,Store target){
        db.authorize(actor,true);
        if(!snapshot().allowed(target.mall(),target.id())){insertRule(target.mall(),target.id());changed();}
    }
    private void insertRule(ProcurementMall mall,String storeId){db.jdbc.update("INSERT INTO supplier_preference(id,mall,store_id,scope_key) VALUES(?,?,?,?)",ProductStore.id(),mall.name(),storeId,storeId==null?"ALL":storeId);}
    private Store insert(ProcurementMall mall,String kind,String name,String key){long id=ProductStore.id();db.jdbc.update("INSERT INTO supplier_store(id,mall,kind,name,identity_key,aliases,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",id,mall.name(),kind,name,key,db.encode(List.of(name)),db.time.now(),db.time.now());return new Store(Long.toString(id),mall,kind,name,key,List.of(name),0);}
    /** 검색 원문의 확인된 지점/실제 판매자만 등록한다. 이름을 바꾸어도 기존 식별자를 재사용한다. */
    Store observed(ProcurementMall mall,SupplierStorePolicy.Identity identity){
        if(identity==null)return null;var all=stores();if(SupplierStorePolicy.conflicts(all,mall,identity))return null;var existing=SupplierStorePolicy.resolve(all,mall,identity);
        if(existing==null&&!"SELLER".equals(identity.kind())&&all.stream().anyMatch(s->s.mall()==mall&&s.kind().equals(identity.kind())&&SupplierStorePolicy.normalize(s.retailer()).isBlank()&&s.aliases().stream().anyMatch(a->SupplierStorePolicy.normalize(a).equals(SupplierStorePolicy.normalize(identity.name())))))return null;
        var saved=existing!=null?existing:insert(mall,identity.kind(),identity.name(),identity.key());
        if(identity.retailer()!=null&&(saved.retailer()==null||saved.retailer().isBlank()))db.jdbc.update("UPDATE supplier_store SET retailer=? WHERE id=?",identity.retailer(),saved.id());
        var identities=new LinkedHashSet<>(saved.identities());
        for(var external:identity.identities()){
            if(identities.contains(external))continue;
            var owners=db.jdbc.queryForList("SELECT CAST(store_id AS CHAR) FROM supplier_store_identity WHERE mall=? AND namespace=? AND external_id=?",String.class,mall.name(),external.namespace(),external.externalId());
            if(owners.isEmpty())db.jdbc.update("INSERT INTO supplier_store_identity(mall,namespace,external_id,store_id) VALUES(?,?,?,?)",mall.name(),external.namespace(),external.externalId(),saved.id());
            else if(!owners.getFirst().equals(saved.id()))throw new cc.ataglace.molebutter.exception.OperationFailure("매장 식별자 연결이 충돌합니다.");
            identities.add(external);
        }
        return new Store(saved.id(),saved.mall(),saved.kind(),saved.name(),saved.identityKey(),saved.aliases(),saved.revision(),
            saved.retailer()==null||saved.retailer().isBlank()?identity.retailer():saved.retailer(),List.copyOf(identities));
    }
    public List<IdentityCandidate> identityCandidates(Long actor){
        db.authorize(actor,false);var all=stores();
        return db.jdbc.query("SELECT id,mall,observation,observed_run_id FROM product_supplier WHERE merged_into IS NULL AND observation IS NOT NULL",(r,n)->{
            var result=db.decode(r.getString("observation"),cc.ataglace.molebutter.dto.product.ProductDtos.SupplierResult.class);
            if(result==null||result.branch()==null||!"CONFIRMED".equals(result.branch().state())||result.branch().store()==null)return null;
            var e=result.branch().store();if(e.namespace()==null||e.externalId()==null)return null;
            var mall=ProcurementMall.valueOf(r.getString("mall"));if(all.stream().anyMatch(s->s.mall()==mall&&s.identities().contains(new ExternalIdentity(e.namespace(),e.externalId()))))return null;
            return new IdentityCandidate(r.getString("id"),r.getString("observed_run_id"),mall,e);
        }).stream().filter(Objects::nonNull).toList();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Store bindIdentity(Long actor,long id,IdentityInput input){
        db.authorize(actor,true);db.lock();var target=store(Long.toString(id));ProductStore.revision(target.revision(),input.revision());
        var candidate=identityCandidates(actor).stream().filter(c->c.supplierId().equals(input.supplierId())&&Objects.equals(c.observedRunId(),input.observedRunId())).findFirst().orElseThrow(()->new cc.ataglace.molebutter.exception.OperationFailure("조회 결과가 변경되었거나 이미 연결되었습니다. 다시 조회해 주세요."));
        var e=candidate.evidence();
        if("COMPANY".equals(target.kind())&&(!"주식회사 LF".equals(e.name())||!"COMPANY".equals(e.kind())))throw new InputValidationFailure("확인된 LF 업체만 연결할 수 있습니다.");
        if(target.mall()!=candidate.mall()||!target.kind().equals(e.kind())||!SupplierStorePolicy.normalize(target.retailer()).isBlank()&&!SupplierStorePolicy.normalize(target.retailer()).equals(SupplierStorePolicy.normalize(e.retailer())))throw new InputValidationFailure("동일한 쇼핑몰·백화점·매장 종류만 연결할 수 있습니다.");
        db.jdbc.update("INSERT INTO supplier_store_identity(mall,namespace,external_id,store_id) VALUES(?,?,?,?)",target.mall().name(),e.namespace(),e.externalId(),id);
        var aliases=new LinkedHashSet<>(target.aliases());aliases.add(e.name());
        db.jdbc.update("UPDATE supplier_store SET retailer=?,aliases=?,revision=revision+1,updated_at=? WHERE id=?",e.retailer(),db.encode(aliases),db.time.now(),id);changed();return store(Long.toString(id));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void deleteStore(Long actor,long id,Long revision){db.authorize(actor,true);db.lock();ProductStore.revision(store(Long.toString(id)).revision(),revision);long used=db.jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM product_supplier WHERE auto_store_id=? OR manual_store_id=?)+(SELECT COUNT(*) FROM supplier_preference WHERE store_id=?)",Long.class,id,id,id);if(used>0)throw new cc.ataglace.molebutter.exception.OperationFailure("판매글 또는 선호 목록에서 사용 중인 매장입니다.");db.jdbc.update("DELETE FROM supplier_store WHERE id=?",id);changed();}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Rule saveRule(Long actor,Long id,RuleInput input){db.authorize(actor,true);db.lock();if(input.mall()==null)throw new InputValidationFailure("쇼핑몰을 선택해 주세요.");String storeId=input.storeId()==null||input.storeId().isBlank()?null:input.storeId();if(storeId!=null&&store(storeId).mall()!=input.mall())throw new InputValidationFailure("해당 쇼핑몰의 매장을 선택해 주세요.");
        var current=snapshot();if(storeId!=null&&!current.branchRequired(input.mall()))throw new InputValidationFailure("지점 구분을 끈 쇼핑몰은 전체 허용으로 저장해 주세요.");final Long requested=id;if(id!=null){var old=current.rules().stream().filter(r->r.id().equals(requested.toString())).findFirst().orElseThrow(()->new InputValidationFailure("선호 항목을 다시 조회해 주세요."));ProductStore.revision(old.revision(),input.revision());}
        final Long target=id;
        if(current.rules().stream().anyMatch(r->!r.id().equals(Objects.toString(target,""))&&r.mall()==input.mall()&&((r.storeId()==null)!=(storeId==null))))throw new InputValidationFailure("전체와 특정 매장 조건은 함께 저장할 수 없습니다. 쇼핑몰 설정에서 변경해 주세요.");
        if(current.rules().stream().anyMatch(r->!r.id().equals(Objects.toString(target,""))&&r.mall()==input.mall()&&Objects.equals(r.storeId(),storeId)))throw new cc.ataglace.molebutter.exception.OperationFailure("이미 등록된 선호 매입처입니다.");
        if(id==null){id=ProductStore.id();db.jdbc.update("INSERT INTO supplier_preference(id,mall,store_id,scope_key) VALUES(?,?,?,?)",id,input.mall().name(),storeId,storeId==null?"ALL":storeId);}else db.jdbc.update("UPDATE supplier_preference SET mall=?,store_id=?,scope_key=?,revision=revision+1 WHERE id=?",input.mall().name(),storeId,storeId==null?"ALL":storeId,id);
        changed();long saved=id;return snapshot().rules().stream().filter(r->r.id().equals(Long.toString(saved))).findFirst().orElseThrow();}
    @Transactional(isolation=Isolation.READ_COMMITTED) public void deleteRule(Long actor,long id,Long revision){db.authorize(actor,true);db.lock();var r=snapshot().rules().stream().filter(v->v.id().equals(Long.toString(id))).findFirst().orElseThrow(()->new InputValidationFailure("선호 항목을 다시 조회해 주세요."));ProductStore.revision(r.revision(),revision);db.jdbc.update("DELETE FROM supplier_preference WHERE id=?",id);changed();}
}
