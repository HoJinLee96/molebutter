package cc.ataglace.molebutter.inventory.api;
import java.util.*;
import org.springframework.transaction.annotation.*;
public interface InventoryLinkService {
    void assertDeletable(List<Long> ids);
    void merge(long target,long source);
}
