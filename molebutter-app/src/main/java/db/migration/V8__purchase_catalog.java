package db.migration;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 기존 실행을 조용히 취소하지 않고, DDL 실행 전에 전환 가능 여부를 확인한다. */
public class V8__purchase_catalog extends BaseJavaMigration {
    private String sql() {
        try (var in = getClass().getResourceAsStream("/db/product-catalog-v8.sql")) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public Integer getChecksum() { return sql().hashCode(); }
    @Override public void migrate(Context context) throws Exception {
        var c = context.getConnection();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM product_refresh_run WHERE status IN ('RUNNING','PAUSED','BLOCKED')")) {
            r.next();
            if (r.getLong(1) > 0) throw new IllegalStateException("상품 구조 전환 전 기존 최신화 작업을 완료하거나 취소해 주세요. 기존 버전에서 처리한 뒤 다시 실행하세요.");
        }
        for (String statement : sql().split(";")) {
            if (!statement.isBlank()) try (var s = c.createStatement()) { s.execute(statement); }
        }
    }
}
