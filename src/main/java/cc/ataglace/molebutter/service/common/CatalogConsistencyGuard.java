package cc.ataglace.molebutter.service.common;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
/** Catalog mutations are exclusive; inventory transactions share the guard and lock their own rows next. */
@Component @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class CatalogConsistencyGuard {
    private final JdbcTemplate jdbc;
    public void exclusive() { jdbc.queryForList("SELECT id FROM product_settings WHERE id=1 FOR UPDATE"); }
    public void shared() { jdbc.queryForList("SELECT id FROM product_settings WHERE id=1 FOR SHARE"); }
}
