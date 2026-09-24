package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationUpgradeIT {
    @Test
    void v1CreatesNoAdminAndV2PreservesExistingAccounts() throws Exception {
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
        Flyway flyway = Flyway.configure().dataSource(url, user, password).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT email, password_hash, user_status, auth_version FROM `user` WHERE id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("owner@example.com");
            assertThat(result.getString(2)).isEqualTo("custom-password-hash");
            assertThat(result.getString(3)).isEqualTo("ACTIVE");
            assertThat(result.getLong(4)).isZero();
        }
    }
}
