package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
@Service
public class RegisteredOptionCounts implements CoupangOptionCounts {
    private final RegisteredProductStore store;private final CoupangWriteGateway writer;private final BusinessAccess access;
    public RegisteredOptionCounts(RegisteredProductStore store,CoupangWriteGateway writer,BusinessAccess access){this.store=store;this.writer=writer;this.access=access;}
    @Transactional(readOnly=true)
    public int byCode(Long actor,String code){
        access.productActor(actor,true);String account=writer.accountKey();if(account==null)return 0;
        return store.db.queryForObject("SELECT COUNT(*) FROM coupang_registered_option o JOIN coupang_registered_product p ON p.id=o.product_id WHERE p.account_key=? AND p.deleted=FALSE AND o.active=TRUE AND CAST(UPPER(TRIM(o.sku)) AS BINARY)=CAST(? AS BINARY)",Integer.class,account,CoupangRecommendations.normalize(code));
    }
}
