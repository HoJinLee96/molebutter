package cc.ataglace.molebutter.app.internal.maintenance;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Only an untouched V48 schema is eligible; never repairs checksums or partial DDL. */
final class CoupangProductsRecovery {
    static final Set<String> TABLES = Set.of("coupang_registered_product", "coupang_registered_option",
            "coupang_product_snapshot", "coupang_product_change", "coupang_product_read",
            "coupang_product_collection", "coupang_product_collection_item", "coupang_product_intent_base",
            "coupang_product_asset_pin");
    private static final String SCRIPT = "db.migration.V49__coupang_registered_products";

    static Integer failedRank(Connection c) throws SQLException {
        Integer rank = null;
        try (var s = c.createStatement(); var rows = s.executeQuery(
                "SELECT installed_rank,version,script FROM flyway_schema_history WHERE success=FALSE")) {
            while (rows.next()) {
                if (rank != null || !"49".equals(rows.getString(2)) || !SCRIPT.equals(rows.getString(3)))
                    throw new IllegalStateException("V49 외의 실패 이력은 자동 복구하지 않습니다. 전체 백업과 스키마를 확인하세요.");
                rank = rows.getInt(1);
            }
        }
        if (rank == null) return null;
        try (var s = c.createStatement(); var rows = s.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            while (rows.next())
                if (TABLES.contains(rows.getString(1)))
                    throw new IllegalStateException("V49 테이블이 일부 존재합니다. 실패 기록을 지우지 말고 전체 백업 복원 절차를 따르세요.");
        }
        try (var s = c.createStatement(); var rows = s.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE installed_rank>" + rank
                        + " OR (success=TRUE AND CAST(version AS UNSIGNED)>48)")) {
            rows.next();
            if (rows.getLong(1) != 0)
                throw new IllegalStateException("V49 실패 이후의 이력이 있어 자동 복구하지 않습니다.");
        }
        return rank;
    }

    static void remove(Connection c, int rank) throws SQLException {
        try (var s = c.prepareStatement("DELETE FROM flyway_schema_history WHERE installed_rank=? AND version='49' AND script=? AND success=FALSE")) {
            s.setInt(1, rank); s.setString(2, SCRIPT);
            if (s.executeUpdate() != 1) throw new IllegalStateException("V49 실패 이력이 변경되었습니다. 전환을 중단합니다.");
        }
    }

    /** Invoked only after the complete dump and files have passed isolated restoration. */
    static void recover(Connection original, String isolatedUrl, Path backup, int rank) throws Exception {
        try (var restored = DriverManager.getConnection(isolatedUrl, "root", "")) {
            if (!Objects.equals(failedRank(restored), rank))
                throw new IllegalStateException("격리 복원 DB의 V49 실패 기록이 일치하지 않습니다.");
            var before = SalesDataMaintenance.digests(restored);
            remove(restored, rank);
            var flyway = Flyway.configure().dataSource(isolatedUrl, "root", "").target("49")
                    .ignoreMigrationPatterns("*:pending").cleanDisabled(true).load();
            var validation = flyway.validateWithResult();
            if (!validation.validationSuccessful)
                throw new IllegalStateException("격리 복원 DB 마이그레이션 검증 실패: " + validation.invalidMigrations.stream()
                        .map(m -> m.version + ":" + m.errorDetails.errorCode).toList());
            flyway.migrate();
            var after = SalesDataMaintenance.digests(restored);
            for (var entry : before.entrySet())
                if (!entry.getKey().equals("flyway_schema_history") && !Objects.equals(entry.getValue(), after.get(entry.getKey())))
                    throw new IllegalStateException("격리 V49 복구 리허설에서 기존 데이터가 변경되었습니다.");
        }
        Files.writeString(backup.resolve("V49_FAILED_RECORD_RECOVERY.txt"),
                "Full DB/file backup restored and verified; all nine V49 tables absent; isolated validate/migrate preserved existing rows.\n"
                        + "Remove only failed V49 installed_rank=" + rank + "; script=" + SCRIPT + "\n");
        if (!Objects.equals(failedRank(original), rank))
            throw new IllegalStateException("복구 직전 V49 실패 기록이 변경되었습니다.");
        remove(original, rank);
    }
}
