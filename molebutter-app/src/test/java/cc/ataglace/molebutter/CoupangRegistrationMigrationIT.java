package cc.ataglace.molebutter;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import cc.ataglace.molebutter.app.internal.maintenance.*;
import static org.assertj.core.api.Assertions.*;
class CoupangRegistrationMigrationIT {
    @Test void populatedV50RequiresOfflineBackupAndPreservesExistingData()throws Exception{
        String base=System.getenv("MOLEBUTTER_TEST_DB_URL");Assumptions.assumeTrue(base!=null);
        String url=base.replace("/molebutter_test?","/molebutter_registration_upgrade_test?");
        Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("47").load().migrate();
        try(var c=DriverManager.getConnection(url,"test_migrator","isolated-test-migration-password");var s=c.createStatement()){
            CoupangIntentFixture.seed(c);System.setProperty("molebutter.intent.verified","true");System.setProperty("molebutter.products.verified","true");System.setProperty("molebutter.pricing.verified","true");
            try{Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("50").load().migrate();}finally{System.clearProperty("molebutter.intent.verified");System.clearProperty("molebutter.products.verified");System.clearProperty("molebutter.pricing.verified");}
            var before=SalesDataMaintenance.digests(c);var flyway=Flyway.configure().dataSource(url,"test_migrator","isolated-test-migration-password").target("51").load();
            assertThatThrownBy(()->new cc.ataglace.molebutter.app.internal.CoupangMigrationConfiguration().coupangMigrations().migrate(flyway)).hasStackTraceContaining("verified offline backup/restore");
            assertThat(SalesDataMaintenance.digests(c)).isEqualTo(before);
            System.setProperty("molebutter.registration.verified","true");try{
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);var after=SalesDataMaintenance.digests(c);
                before.forEach((k,v)->{if(!k.equals("flyway_schema_history"))assertThat(after.get(k)).as(k).isEqualTo(v);});
                assertThat(CoupangSchema.existing(c)).contains("coupang_registration_defaults");
                try(var rows=s.executeQuery("SELECT COUNT(*) FROM coupang_registration_defaults")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();}
                assertThat(flyway.migrate().migrationsExecuted).isZero();
            }finally{System.clearProperty("molebutter.registration.verified");}
        }
    }
}
