package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationUpgradeIT {
    @Test
    void v1CreatesNoAdminAndLaterMigrationsPreserveExistingAccounts() throws Exception {
        String url = System.getenv("MOLEBUTTER_TEST_DB_URL");
        if (url == null || !url.contains("/molebutter_test?")) {
            throw new IllegalStateException("Run scripts/test-integration.sh");
        }
        url = url.replace("/molebutter_test?", "/molebutter_upgrade_test?");
        String user = "test_migrator", password = "isolated-test-migration-password";
        Flyway.configure().dataSource(url, user, password).target("1").load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM `user`")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            statement.executeUpdate("""
                    INSERT INTO `user` (id, email, name, password_hash, user_role, user_status, created_at)
                    VALUES (1, 'owner@example.com', 'Test admin', 'custom-password-hash', 'ADMIN', 'ACTIVE', NOW(6))
                    """);
        }
        // V1/V2만 있는 기존 설치와 이미 근태를 사용하는 V4 설치를 순서대로 검증한다.
        assertThat(Flyway.configure().dataSource(url, user, password).target("2").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(url, user, password).target("4").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO attendance(id,user_id,work_date,clock_in,clock_out,status,revision,created_at) VALUES (10,1,'2026-02-01','2026-02-01 09:00:00','2026-02-01 18:00:00','COMPLETED',2,NOW(6))");
            statement.executeUpdate("INSERT INTO attendance_break VALUES (10,0,'2026-02-01 12:00:00','2026-02-01 13:00:00')");
        }
        Flyway flyway = Flyway.configure().dataSource(url, user, password).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT email, password_hash, user_status, auth_version FROM `user` WHERE id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("owner@example.com");
            assertThat(result.getString(2)).isEqualTo("custom-password-hash");
            assertThat(result.getString(3)).isEqualTo("ACTIVE");
            assertThat(result.getLong(4)).isZero();
            try (var record = connection.createStatement().executeQuery("SELECT revision, status FROM attendance WHERE id=10")) {
                assertThat(record.next()).isTrue(); assertThat(record.getLong(1)).isEqualTo(2); assertThat(record.getString(2)).isEqualTo("COMPLETED");
            }
            try (var breaks = connection.createStatement().executeQuery("SELECT COUNT(*) FROM attendance_break WHERE attendance_id=10")) {
                assertThat(breaks.next()).isTrue(); assertThat(breaks.getLong(1)).isEqualTo(1);
            }
        }
    }
}
