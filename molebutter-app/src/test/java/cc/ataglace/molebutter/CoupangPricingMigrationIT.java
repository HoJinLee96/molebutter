package cc.ataglace.molebutter;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import cc.ataglace.molebutter.app.internal.maintenance.*;
import static org.assertj.core.api.Assertions.*;
class CoupangPricingMigrationIT {
    @Test void populatedV49RequiresOfflineBackupAndPreservesExistingData()throws Exception{
        String base=System.getenv("MOLEBUTTER_TEST_DB_URL");Assumptions.assumeTrue(base!=null);
        String url=base.replace("/molebutter_test?","/molebutter_pricing_upgrade_test?");
        Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("47").load().migrate();
        try(var c=DriverManager.getConnection(url,"test_migrator","isolated-test-migration-password");var s=c.createStatement()){
            CoupangIntentFixture.seed(c);System.setProperty("molebutter.intent.verified","true");System.setProperty("molebutter.products.verified","true");
            try{Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("49").load().migrate();}finally{System.clearProperty("molebutter.intent.verified");System.clearProperty("molebutter.products.verified");}
            var before=SalesDataMaintenance.digests(c);var flyway=Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("50").load();
            assertThatThrownBy(()->new cc.ataglace.molebutter.app.internal.CoupangMigrationConfiguration().coupangMigrations().migrate(flyway)).hasStackTraceContaining("verified offline backup/restore");
            assertThat(SalesDataMaintenance.digests(c)).isEqualTo(before);
            System.setProperty("molebutter.pricing.verified","true");try{
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);var after=SalesDataMaintenance.digests(c);
                before.forEach((k,v)->{if(!k.equals("flyway_schema_history"))assertThat(after.get(k)).as(k).isEqualTo(v);});
                assertThat(CoupangSchema.existing(c)).contains("coupang_pricing_settings");
                try(var rows=s.executeQuery("SELECT shipping_cost,commission_percent,margin_percent,revision FROM coupang_pricing_settings")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(4000);assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("10.5");assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("7");assertThat(rows.getLong(4)).isZero();}
                assertThat(flyway.migrate().migrationsExecuted).isZero();
            }finally{System.clearProperty("molebutter.pricing.verified");}
        }
    }
}
