package cc.ataglace.molebutter.inventory.api;
import java.util.List;
import org.springframework.transaction.annotation.*;

import cc.ataglace.molebutter.common.api.NamedSettingInput;
public interface InventoryPaymentMethodService {
    public record Method(String id,String name,long revision) {}
    List<Method> list(Long actor);
    Method save(Long actor,Long id,NamedSettingInput input);
    void delete(Long actor,long id,Long revision);
}
