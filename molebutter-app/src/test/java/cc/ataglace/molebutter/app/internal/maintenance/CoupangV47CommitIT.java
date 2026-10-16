package cc.ataglace.molebutter.app.internal.maintenance;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class CoupangV47CommitIT {
    static final String USER = "test_migrator", PASSWORD = "isolated-test-migration-password";
    private static Connection failingCommit(Connection c, boolean committed, boolean rollbackFailure) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        if (committed)
                            c.commit();
                        throw new SQLException("synthetic commit acknowledgement failure");
                    }
                    if (method.getName().equals("rollback") && rollbackFailure)
                        throw new SQLException("synthetic rollback failure");
                    try {
                        return method.invoke(c, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
    private static void assertPending(String url, Map<String, String> expected) throws Exception {
        try (var fresh = DriverManager.getConnection(url, USER, PASSWORD)) {
            assertThatThrownBy(() -> CoupangV47Preparation.requireComplete(fresh)).hasMessageContaining("완료되지 않은 V47");
            assertThat(SalesDataMaintenance.digests(fresh)).isEqualTo(expected);
        }
    }
    @Test
    void uncertainCommitsCannotDiscardProtectionBeforeAuditVerification() throws Exception {
        String base = System.getenv("MOLEBUTTER_TEST_DB_URL");
        Assumptions.assumeTrue(base != null);
        String url = base.replace("/molebutter_test?", "/molebutter_v47_commit_test?");
        String account = "a".repeat(64), option = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
        Flyway.configure().dataSource(url, USER, PASSWORD).target("46").load().migrate();
        try (var c = DriverManager.getConnection(url, USER, PASSWORD); var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('"
                            + asset + "','original.png','image/png',500,500,25,1,NOW(6))");
            s.executeUpdate("INSERT INTO coupang_asset_publication VALUES('" + asset + "',REPEAT('b',64),NOW(6))");
            CoupangWorkingCopyFixture.seedLinked(c, account, option, asset);
            var original = SalesDataMaintenance.digests(c);
            System.setProperty("molebutter.working-copy.verified", "true");
            System.setProperty("molebutter.working-copy.account", account);
            try {
                for (boolean committed : List.of(false, true))
                    for (boolean rollbackFailure : List.of(false, true)) {
                        assertThatThrownBy(() -> CoupangV47Preparation.prepare(
                                failingCommit(c, committed, rollbackFailure), account, "https://app.example.test"))
                                .hasMessageContaining("commit acknowledgement");
                        assertThat(c.getAutoCommit()).isEqualTo(!rollbackFailure);
                        if (!c.getAutoCommit()) {
                            c.rollback();
                            c.setAutoCommit(true);
                        }
                        try (var fresh = DriverManager.getConnection(url, USER, PASSWORD)) {
                            if (committed)
                                assertThatThrownBy(() -> CoupangV47Preparation.requireComplete(fresh))
                                        .hasMessageContaining("완료되지 않은 V47");
                            else {
                                CoupangV47Preparation.requireComplete(fresh);
                                assertThat(SalesDataMaintenance.digests(fresh)).isEqualTo(original);
                            }
                        }
                        // Restore only this synthetic fixture between commit outcomes; production uses
                        // the full backup.
                        s.executeUpdate("DELETE FROM coupang_draft_target_edit WHERE draft_id=11");
                        assertThat(SalesDataMaintenance.digests(c)).isEqualTo(original);
                    }
                var prepared = CoupangV47Preparation.prepare(c, account, "https://app.example.test");
                Flyway.configure().dataSource(url, USER, PASSWORD).target("47").load().migrate();
                var pending = SalesDataMaintenance.digests(c);
                var proofRan = new AtomicBoolean();
                Callable<Void> verification = () -> {
                    var after = SalesDataMaintenance.digests(c);
                    assertThat(after.get("legacy_coupang_working_copy_source"))
                            .isEqualTo(original.get("coupang_draft"));
                    assertThat(after.get("legacy_coupang_target_edit"))
                            .isEqualTo(original.get("coupang_draft_target_edit"));
                    for (String table : original.keySet())
                        if (table.startsWith("coupang_execution") || table.equals("coupang_submission_preview"))
                            assertThat(after.get(table)).as(table).isEqualTo(original.get(table));
                    CoupangWorkingCopyFixture.verifyLinked(c, asset);
                    proofRan.set(true);
                    return null;
                };
                assertThatThrownBy(() -> CoupangV47Preparation.finish(c, prepared, () -> {
                    throw new SQLException("synthetic audit mismatch");
                })).hasMessageContaining("audit mismatch");
                assertPending(url, pending);
                for (boolean rollbackFailure : List.of(false, true)) {
                    assertThatThrownBy(() -> CoupangV47Preparation.finish(failingCommit(c, false, rollbackFailure),
                            prepared, verification)).hasMessageContaining("commit acknowledgement");
                    assertThat(c.getAutoCommit()).isEqualTo(!rollbackFailure);
                    if (!c.getAutoCommit()) {
                        c.rollback();
                        c.setAutoCommit(true);
                    }
                    assertPending(url, pending);
                }
                var stopped = Files.createTempFile("coupang-v47-stopped-", ".log");
                var signal = Files.createTempFile("coupang-v47-proof-", ".txt");
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        StoppedPreparation.class.getName(), url, signal.toString()).redirectErrorStream(true)
                        .redirectOutput(stopped.toFile()).start();
                try {
                    assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("V47 cleanup subprocess stopped").isTrue();
                    assertThat(process.exitValue()).as(Files.readString(stopped)).isZero();
                    assertThat(Files.readString(signal)).isEqualTo("cleanup deleted rows but has not committed");
                    assertPending(url, pending);
                } finally {
                    if (process.isAlive())
                        process.destroyForcibly();
                    Files.deleteIfExists(stopped);
                    Files.deleteIfExists(signal);
                }
                proofRan.set(false);
                assertThatThrownBy(
                        () -> CoupangV47Preparation.finish(failingCommit(c, true, false), prepared, verification))
                        .hasMessageContaining("commit acknowledgement");
                try (var fresh = DriverManager.getConnection(url, USER, PASSWORD)) {
                    CoupangV47Preparation.requireComplete(fresh);
                    assertThat(proofRan)
                            .as("Audit verification must precede a committed cleanup even when its response is lost")
                            .isTrue();
                    var after = SalesDataMaintenance.digests(fresh);
                    assertThat(after.get("legacy_coupang_working_copy_source"))
                            .isEqualTo(original.get("coupang_draft"));
                    assertThat(after.get("legacy_coupang_target_edit"))
                            .isEqualTo(original.get("coupang_draft_target_edit"));
                }
            } finally {
                System.clearProperty("molebutter.working-copy.verified");
                System.clearProperty("molebutter.working-copy.account");
            }
        }
    }
    public static final class StoppedPreparation {
        public static void main(String[] args) throws Exception {
            if (!args[0].contains("/molebutter_v47_commit_test?"))
                throw new IllegalStateException("Isolated DB required");
            try (var c = DriverManager.getConnection(args[0], USER, PASSWORD);
                    var s = c.createStatement();
                    var r = s.executeQuery(
                            "SELECT draft_id,account_key,draft_revision,updated_by,mapping_json,intent_json FROM legacy_coupang_target_edit WHERE draft_id=11")) {
                if (!r.next())
                    throw new IllegalStateException("Prepared fixture missing");
                var row = new CoupangV47Preparation.Observation(r.getLong(1), r.getString(2), r.getLong(3),
                        r.getLong(4), r.getString(5), r.getString(6));
                CoupangV47Preparation.finish(c, List.of(row), () -> {
                    Files.writeString(Path.of(args[1]), "cleanup deleted rows but has not committed");
                    Runtime.getRuntime().halt(0);
                    return null;
                });
                throw new IllegalStateException("Expected to stop before commit");
            }
        }
    }
}
