package cc.ataglace.molebutter;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import cc.ataglace.molebutter.app.internal.maintenance.*;
import static org.assertj.core.api.Assertions.*;
class CoupangRegisteredProductsMigrationIT {
    @Test void populatedV48RequiresVerifiedBackupAndPreservesEveryExistingRow()throws Exception {
        String base=System.getenv("MOLEBUTTER_TEST_DB_URL");Assumptions.assumeTrue(base!=null);
        String url=base.replace("/molebutter_test?","/molebutter_products_upgrade_test?");
        Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("47").load().migrate();
        try(var c=DriverManager.getConnection(url,"test_migrator","isolated-test-migration-password");var s=c.createStatement()) {
            CoupangIntentFixture.seed(c);System.setProperty("molebutter.intent.verified","true");
            try{Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("48").load().migrate();}finally{System.clearProperty("molebutter.intent.verified");}
            var before=SalesDataMaintenance.digests(c);
            var flyway=Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("49").load();
            var strategy = new cc.ataglace.molebutter.app.internal.CoupangMigrationConfiguration().coupangMigrations();
            assertThatThrownBy(() -> strategy.migrate(flyway)).hasStackTraceContaining("verified offline backup/restore");
            assertThat(SalesDataMaintenance.digests(c)).isEqualTo(before);
            assertThatThrownBy(flyway::migrate).hasStackTraceContaining("verified offline backup/restore");flyway.repair();
            System.setProperty("molebutter.products.verified","true");
            try {
                s.executeUpdate("UPDATE coupang_execution_step SET status='RUNNING'");assertThatThrownBy(flyway::migrate).hasStackTraceContaining("active jobs or leases");flyway.repair();s.executeUpdate("UPDATE coupang_execution_step SET status='UNKNOWN'");
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                var after=SalesDataMaintenance.digests(c);before.forEach((k,v)->{if(!k.equals("flyway_schema_history"))assertThat(after.get(k)).as(k).isEqualTo(v);});
                assertThat(CoupangSchema.existing(c)).contains("coupang_registered_product","coupang_registered_option","coupang_product_asset_pin");
                assertThat(flyway.migrate().migrationsExecuted).isZero();
            }finally{System.clearProperty("molebutter.products.verified");}
        }
    }
}
