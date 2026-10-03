package cc.ataglace.molebutter.service.inventory;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.exception.*;
import cc.ataglace.molebutter.service.product.ProductStore;
import cc.ataglace.molebutter.service.product.ProductTime;
import cc.ataglace.molebutter.service.product.SharedSettingsService.NameInput;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class InventoryPaymentMethodService {
    private final ProductStore db;
    private final JdbcTemplate jdbc;
    private final ProductTime time;
    public record Method(String id,String name,long revision) {}
    public List<Method> list(Long actor) {
        db.authorize(actor,true);
        return jdbc.query("SELECT id,name,revision FROM inventory_payment_method WHERE deleted_at IS NULL ORDER BY id",
            (r,n)->new Method(r.getString("id"),r.getString("name"),r.getLong("revision")));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Method save(Long actor,Long id,NameInput input) {
        db.authorize(actor,true);db.lock();String name=input.name()==null?"":input.name().trim();
        if(name.isEmpty()||name.length()>100)throw new IllegalArgumentException("결제 수단 이름은 1~100자로 입력해 주세요.");
        if(id!=null)ProductStore.revision(find(actor,id).revision(),input.revision());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_payment_method WHERE deleted_at IS NULL AND name=? AND id<>?",Long.class,name,id==null?0:id)>0)
            throw new IllegalArgumentException("이미 등록된 결제 수단입니다.");
        if(id==null){id=ProductStore.id();jdbc.update("INSERT INTO inventory_payment_method(id,name,created_at,updated_at) VALUES(?,?,?,?)",id,name,time.now(),time.now());}
        else jdbc.update("UPDATE inventory_payment_method SET name=?,revision=revision+1,updated_at=? WHERE id=?",name,time.now(),id);
        return find(actor,id);
    }
    private Method find(Long actor,long id) {return list(actor).stream().filter(m->m.id().equals(Long.toString(id))).findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void delete(Long actor,long id,Long revision) {
        db.authorize(actor,true);db.lock();ProductStore.revision(find(actor,id).revision(),revision);
        jdbc.update("UPDATE inventory_payment_method SET deleted_at=?,updated_at=?,revision=revision+1 WHERE id=?",time.now(),time.now(),id);
    }
}
