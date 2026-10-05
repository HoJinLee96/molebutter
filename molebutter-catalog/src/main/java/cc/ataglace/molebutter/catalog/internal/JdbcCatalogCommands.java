package cc.ataglace.molebutter.catalog.internal;


import cc.ataglace.molebutter.catalog.api.CatalogCommands;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.BusinessException;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcCatalogCommands implements CatalogCommands {
    private final JdbcTemplate jdbc;
    public void checkRevision(long id, Long expected) {
        long current=jdbc.queryForObject("SELECT revision FROM catalog_product WHERE id=?",Long.class,id);
        cc.ataglace.molebutter.common.api.BusinessRevision.check(current,expected);
    }
    public void create(long id,String brand,Long brandId,String code,String names,LocalDateTime at) {
        jdbc.update("INSERT INTO catalog_product(id,brand,brand_id,product_code,registration_names,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",id,brand,brandId,code,names,at,at);
    }
    public void edit(long id,long expected,String brand,Long brandId,String code,LocalDateTime at) {
        if(jdbc.update("UPDATE catalog_product SET brand=?,brand_id=?,product_code=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",brand,brandId,code,at,id,expected)!=1)throw new BusinessException(ErrorCode.OPTIMISTIC_LOCKING_FAILURE);
    }
    public void bump(long id,LocalDateTime at){jdbc.update("UPDATE catalog_product SET revision=revision+1,updated_at=? WHERE id=?",at,id);}
    public void bump(long id){jdbc.update("UPDATE catalog_product SET revision=revision+1 WHERE id=?",id);}
    public void delete(long id,Long actor,LocalDateTime at){jdbc.update("UPDATE catalog_product SET deleted_at=?,deleted_by=?,revision=revision+1,updated_at=? WHERE id=?",at,actor,at,id);}
    public void mergeReference(long target,long source){jdbc.update("UPDATE catalog_product SET merged_into=?,revision=revision+1 WHERE id=?",target,source);}
    public void mergeHistory(long id,long target,Long actor,LocalDateTime at,String before,String after){jdbc.update("INSERT INTO catalog_merge_history(id,target_id,actor_id,created_at,before_snapshot,after_snapshot) VALUES(?,?,?,?,?,?)",id,target,actor,at,before,after);}
    public void registrationNames(long id,String json){jdbc.update("UPDATE catalog_product SET registration_names=? WHERE id=?",json,id);}
}
