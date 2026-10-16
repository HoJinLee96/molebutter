package cc.ataglace.molebutter;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import cc.ataglace.molebutter.app.internal.maintenance.*;
import static org.assertj.core.api.Assertions.*;
class CoupangIntentMigrationIT {
    @Test
    void populatedV47ArchivesHistoryAndPreservesAllOtherRowsAndEvidence() throws Exception {
        String base = System.getenv("MOLEBUTTER_TEST_DB_URL");
        Assumptions.assumeTrue(base != null);
        String url = base.replace("/molebutter_test?", "/molebutter_intent_test?");
        Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").target("47").load()
                .migrate();
        try (var c = DriverManager.getConnection(url, "test_migrator", "isolated-test-migration-password");
                var s = c.createStatement()) {
            CoupangIntentFixture.seed(c);
            assertThatThrownBy(() -> migrate(url)).hasStackTraceContaining("verified offline backup/restore");
            repair(url);
            s.executeUpdate(
                    "INSERT INTO coupang_submission_account(account_key,lease_owner,lease_until) VALUES(REPEAT('a',64),'worker',DATE_ADD(NOW(6),INTERVAL 1 HOUR))");
            System.setProperty("molebutter.intent.verified", "true");
            try {
                assertThatThrownBy(() -> migrate(url)).hasStackTraceContaining("active jobs or leases");
                repair(url);
                s.executeUpdate("DELETE FROM coupang_submission_account");
                s.executeUpdate("UPDATE coupang_execution_step SET status='RUNNING' WHERE execution_id=15");
                assertThatThrownBy(() -> migrate(url)).hasStackTraceContaining("active jobs or leases");
                repair(url);
                s.executeUpdate("UPDATE coupang_execution_step SET status='UNKNOWN' WHERE execution_id=15");
                var before = SalesDataMaintenance.digests(c);
                String previews = SalesDataMaintenance.queryDigest(c,
                        "SELECT p.* FROM coupang_submission_preview p JOIN coupang_execution e ON e.preview_id=p.id");
                assertThat(migrate(url)).isEqualTo(1);
                CoupangIntentFixture.verify(c);
                var after = SalesDataMaintenance.digests(c);
                var rename = Map.of("coupang_listing_history", "legacy_coupang_listing_history",
                        "coupang_listing_history_asset", "legacy_coupang_listing_history_asset",
                        "coupang_editor_history_link", "legacy_coupang_editor_history_link");
                before.forEach((k, v) -> {
                    if (!Set.of("flyway_schema_history", "coupang_edit_session", "coupang_submission_preview")
                            .contains(k))
                        assertThat(after.get(rename.getOrDefault(k, k))).as(k).isEqualTo(v);
                });
                assertThat(previews).isEqualTo(SalesDataMaintenance.queryDigest(c,
                        "SELECT p.* FROM coupang_submission_preview p JOIN coupang_execution e ON e.preview_id=p.id"));
                assertThat(after.keySet().stream().filter(k -> k.startsWith("coupang_")).count()).isEqualTo(24);
                assertThat(after.keySet().stream().filter(k -> k.startsWith("legacy_coupang_")).count()).isEqualTo(11);
                assertThat(migrate(url)).isZero();
            } finally {
                System.clearProperty("molebutter.intent.verified");
            }
        }
    }
    static int migrate(String url) {
        return Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").load()
                .migrate().migrationsExecuted;
    }
    static void repair(String url) {
        Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").load().repair();
    }
}
