package cc.ataglace.molebutter.catalog.api;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.catalog.internal.JdbcCatalogCommands;
/** Explicit offline tools supply the outer transaction; this factory does not start an application or worker. */
public final class CatalogMaintenance {
    private CatalogMaintenance() {}
    public static CatalogConsistencyGuard guard(JdbcTemplate jdbc){return new cc.ataglace.molebutter.catalog.internal.JdbcCatalogConsistencyGuard(jdbc);}
    public static CatalogCommands commands(JdbcTemplate jdbc){return new JdbcCatalogCommands(jdbc, guard(jdbc), new tools.jackson.databind.ObjectMapper());}
}
