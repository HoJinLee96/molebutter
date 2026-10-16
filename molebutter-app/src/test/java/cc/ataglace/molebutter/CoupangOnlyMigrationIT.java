package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.app.internal.maintenance.*;

class CoupangOnlyMigrationIT {
    @Test void populatedV44RequiresExplicitResetAndUpgradePreservesOtherDomains()throws Exception {
        String main=System.getenv("MOLEBUTTER_TEST_DB_URL");if(main==null||!main.contains("/molebutter_test?"))throw new IllegalStateException("Run scripts/test-integration.sh");
        String url=main.replace("/molebutter_test?","/molebutter_sales_reset_test?");String user="test_migrator",password="isolated-test-migration-password";
        Flyway.configure().dataSource(url,user,password).target("44").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
            s.executeUpdate("INSERT INTO `user`(id,email,name,password_hash,user_role,user_status,created_at) VALUES(1,'preserved@example.test','보존 회원','fixture','ADMIN','ACTIVE',NOW(6))");
            s.executeUpdate("INSERT INTO marketplace_draft(id,document_json,created_by,updated_by,created_at,updated_at) VALUES(9,'{}',1,1,NOW(6),NOW(6))");
            s.executeUpdate("INSERT INTO marketplace_submission_preview(id,draft_id,draft_revision,created_by,requested,document_json,created_at,expires_at) VALUES(10,9,0,1,FALSE,'{}',NOW(6),NOW(6))");
            s.executeUpdate("INSERT INTO marketplace_execution(id,preview_id,draft_id,draft_revision,idempotency_key,created_by,status,created_at,updated_at) VALUES(11,10,9,0,'00000000-0000-4000-8000-000000000011',1,'UNKNOWN',NOW(6),NOW(6))");
            s.executeUpdate("INSERT INTO marketplace_media_comparison(draft_id,market,account_key,listing_version,external_product_id,option_id,field_path,evidence_json,execution_id,updated_at) VALUES(9,'COUPANG','account',1,'100','00000000-0000-4000-8000-000000000001','media.images','{}',11,NOW(6))");
            var before=SalesDataMaintenance.digests(c);SalesSchema.TABLES.forEach(before::remove);before.remove("flyway_schema_history");
            assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).target("46").load().migrate()).hasStackTraceContaining("reset-sales-data.sh");
            assertThat(count(c,"marketplace_draft")).isEqualTo(1);assertThat(count(c,"marketplace_execution")).isEqualTo(1);
            c.setAutoCommit(false);for(String table:SalesSchema.deletionOrder(c))s.executeUpdate("DELETE FROM `"+table+"`");c.commit();c.setAutoCommit(true);
            for(String table:SalesSchema.deletionOrder(c))s.executeUpdate("DELETE FROM `"+table+"`"); // empty reset is idempotent
            // MySQL records a rejected nontransactional migration as failed; only that record is repaired.
            Flyway.configure().dataSource(url,user,password).load().repair();Flyway.configure().dataSource(url,user,password).target("45").load().migrate();
            var after=SalesDataMaintenance.digests(c);SalesSchema.TABLES.forEach(after::remove);after.remove("flyway_schema_history");assertThat(after).isEqualTo(before);
            for(String table:SalesSchema.existing(c))assertThat(count(c,table)).isZero();
            try(var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LEFT(table_name,12)='marketplace_' AND column_name IN ('market','import_market')")){r.next();assertThat(r.getLong(1)).isZero();}
            assertThat(SalesSchema.deletionOrder(c)).hasSize(32);
        }
    }
    private long count(Connection c,String table)throws Exception {try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){r.next();return r.getLong(1);}}
}
