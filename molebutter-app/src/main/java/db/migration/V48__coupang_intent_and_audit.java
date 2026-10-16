package db.migration;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.*;
/**
 * Archives obsolete write-side history without changing its values or
 * identifiers.
 */
public final class V48__coupang_intent_and_audit extends BaseJavaMigration {
    public static void check(Connection c) throws SQLException {
        for (String sql : List.of("SELECT COUNT(*) FROM coupang_execution_step WHERE status IN ('QUEUED','RUNNING')",
                "SELECT COUNT(*) FROM coupang_submission_account WHERE lease_until>CURRENT_TIMESTAMP(6)",
                "SELECT COUNT(*) FROM coupang_order_job WHERE status IN ('QUEUED','RUNNING')",
                "SELECT COUNT(*) FROM coupang_order_account_lock WHERE lease_until>CURRENT_TIMESTAMP(6)"))
            try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
                r.next();
                if (r.getLong(1) > 0)
                    throw new SQLException("V48: active jobs or leases must finish before offline upgrade");
            }
        for (String name : List.of("listing_history", "listing_history_asset", "editor_history_link"))
            try (var s = c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
                s.setString(1, "legacy_coupang_" + name);
                try (var r = s.executeQuery()) {
                    r.next();
                    if (r.getLong(1) > 0)
                        throw new SQLException("V48: audit destination already exists; inspect backup before retry");
                }
            }
    }
    @Override
    public void migrate(Context context) throws Exception {
        var c = context.getConnection();
        check(c);
        if (!Boolean.getBoolean("molebutter.intent.verified"))
            for (String table : List.of("user", "catalog_product", "inventory_item", "coupang_product_working_copy",
                    "coupang_execution", "coupang_asset", "coupang_order", "coupang_listing_history",
                    "coupang_editor_history_link"))
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                    r.next();
                    if (r.getLong(1) > 0)
                        throw new SQLException(
                                "V48 requires verified offline backup/restore; use upgrade-coupang-intent.sh");
                }
        try (var s = c.createStatement()) {
            s.execute(
                    "RENAME TABLE coupang_listing_history TO legacy_coupang_listing_history,coupang_listing_history_asset TO legacy_coupang_listing_history_asset,coupang_editor_history_link TO legacy_coupang_editor_history_link");
            s.executeUpdate("UPDATE coupang_edit_session SET revoked=TRUE");
            s.executeUpdate(
                    "UPDATE coupang_submission_preview p SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY WHERE NOT EXISTS(SELECT 1 FROM coupang_execution e WHERE e.preview_id=p.id)");
        }
    }
}
