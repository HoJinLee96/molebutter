package db.migration;
import db.migration.ResponsibilityMigration;

import org.flywaydb.core.api.migration.*;

public class V30__remove_shared_product_columns extends BaseJavaMigration {
    @Override public boolean canExecuteInTransaction() { return false; }
    @Override public void migrate(Context context) throws Exception {
        var c=context.getConnection();
        ResponsibilityMigration.idle(c);
        ResponsibilityMigration.verifyAll(c); // Includes deleted/merged products, SQL NULL and JSON null.
        ResponsibilityMigration.execute(c,"ALTER TABLE catalog_product DROP INDEX ix_catalog_comparison, DROP INDEX ix_catalog_lookup_status, "+ResponsibilityMigration.PRODUCT.stream().map(x->"DROP COLUMN "+x).collect(java.util.stream.Collectors.joining(", ")));
        ResponsibilityMigration.execute(c,"DROP TABLE product_settings");
    }
}
