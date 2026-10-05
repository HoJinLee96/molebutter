package cc.ataglace.molebutter.catalog.internal;

import cc.ataglace.molebutter.catalog.internal.CatalogStore;

import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.NamedSettingInput;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSharedSettingsService implements cc.ataglace.molebutter.catalog.api.SharedSettingsService {
    private final CatalogStore db;



    public List<Brand> brands(Long actor) {
        db.authorize(actor, false);
        return db.jdbc.query("""
            SELECT b.*, (SELECT COUNT(*) FROM catalog_product p WHERE p.brand_id=b.id) usage_count
            FROM product_brand b ORDER BY b.name,b.id
            """, (r,n)->new Brand(r.getString("id"),r.getString("name"),r.getLong("revision"),r.getLong("usage_count"),r.getString("code_brand")));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Brand saveBrand(Long actor, Long id, NamedSettingInput input) {
        db.authorize(actor,true); db.lock();
        String name=CatalogStore.text(input.name(),100,true);
        if(id!=null) CatalogStore.revision(brand(actor,id).revision(),input.revision());
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM product_brand WHERE name=? AND id<>?",Long.class,name,id==null?0:id)>0)
            throw new InputValidationFailure("이미 등록된 브랜드 이름입니다.");
        if(id==null) {
            id=CatalogStore.id();
            db.jdbc.update("INSERT INTO product_brand(id,name,code_brand,created_at,updated_at) VALUES(?,?,?,?,?)",id,name,cc.ataglace.molebutter.catalog.api.CatalogCodes.brandKey(name),db.time.now(),db.time.now());
        } else {
            db.jdbc.update("UPDATE product_brand SET name=?,revision=revision+1,updated_at=? WHERE id=?",name,db.time.now(),id);
            // 기존 작업 이력의 표시명도 유지하되 조회 기준/가격은 건드리지 않는다.
            db.jdbc.update("UPDATE catalog_product SET brand=?,revision=revision+1,updated_at=? WHERE brand_id=?",name,db.time.now(),id);
        }
        return brand(actor,id);
    }
    private Brand brand(Long actor,long id) {
        return brands(actor).stream().filter(b->b.id().equals(Long.toString(id))).findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteBrand(Long actor,long id,Long revision) {
        db.authorize(actor,true); db.lock(); var b=brand(actor,id); CatalogStore.revision(b.revision(),revision);
        if(b.usageCount()>0) throw new OperationFailure("보관 중인 상품을 포함해 "+b.usageCount()+"개 상품에서 사용하는 브랜드는 삭제할 수 없습니다.");
        db.jdbc.update("DELETE FROM product_brand WHERE id=?",id);
    }
}
