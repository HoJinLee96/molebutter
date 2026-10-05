package cc.ataglace.molebutter.inventory.internal;


import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import cc.ataglace.molebutter.common.api.BusinessRevision;
import cc.ataglace.molebutter.common.api.BusinessTime;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.BusinessIds;
import cc.ataglace.molebutter.common.api.NamedSettingInput;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DefaultInventoryPaymentMethodService implements cc.ataglace.molebutter.inventory.api.InventoryPaymentMethodService {
    private final BusinessAccess access;
    private final CatalogConsistencyGuard guard;
    private final JdbcTemplate jdbc;
    private final BusinessTime time;

    public List<Method> list(Long actor) {
        access.productActor(actor,true);
        return jdbc.query("SELECT id,name,revision FROM inventory_payment_method WHERE deleted_at IS NULL ORDER BY id",
            (r,n)->new Method(r.getString("id"),r.getString("name"),r.getLong("revision")));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Method save(Long actor,Long id,NamedSettingInput input) {
        access.productActor(actor,true);guard.exclusive();String name=input.name()==null?"":input.name().trim();
        if(name.isEmpty()||name.length()>100)throw new InputValidationFailure("결제 수단 이름은 1~100자로 입력해 주세요.");
        if(id!=null)BusinessRevision.check(find(actor,id).revision(),input.revision());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_payment_method WHERE deleted_at IS NULL AND name=? AND id<>?",Long.class,name,id==null?0:id)>0)
            throw new InputValidationFailure("이미 등록된 결제 수단입니다.");
        if(id==null){id=BusinessIds.next();jdbc.update("INSERT INTO inventory_payment_method(id,name,created_at,updated_at) VALUES(?,?,?,?)",id,name,time.now(),time.now());}
        else jdbc.update("UPDATE inventory_payment_method SET name=?,revision=revision+1,updated_at=? WHERE id=?",name,time.now(),id);
        return find(actor,id);
    }
    private Method find(Long actor,long id) {return list(actor).stream().filter(m->m.id().equals(Long.toString(id))).findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void delete(Long actor,long id,Long revision) {
        access.productActor(actor,true);guard.exclusive();BusinessRevision.check(find(actor,id).revision(),revision);
        jdbc.update("UPDATE inventory_payment_method SET deleted_at=?,updated_at=?,revision=revision+1 WHERE id=?",time.now(),time.now(),id);
    }
}
