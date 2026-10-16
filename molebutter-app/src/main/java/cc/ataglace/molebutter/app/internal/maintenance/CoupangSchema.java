package cc.ataglace.molebutter.app.internal.maintenance;

import java.sql.*;
import java.util.*;

/**
 * Explicit ownership boundary: catalog, procurement, inventory and identity are
 * excluded.
 */
public final class CoupangSchema {
    private CoupangSchema() {
    }
    public static final Set<String> TABLES = Set.of("coupang_product_working_copy", "coupang_asset",
            "coupang_working_copy_asset", "coupang_submission_preview", "coupang_execution", "coupang_execution_target",
            "coupang_execution_step", "coupang_execution_attempt", "coupang_submission_account",
            "coupang_execution_asset", "coupang_asset_publication", "coupang_edit_session", "coupang_order",
            "coupang_order_item", "coupang_order_claim", "coupang_order_job", "coupang_order_checkpoint",
            "coupang_order_job_order", "coupang_order_cursor", "coupang_order_account_lock",
            "coupang_order_failed_detail", "coupang_order_job_claim", "coupang_image_edit", "coupang_listing_state");
    public static List<String> existing(Connection connection) throws SQLException {
        var found = new ArrayList<String>();
        try (var s = connection.createStatement();
                var r = s.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND LEFT(table_name,8)='coupang_'")) {
            while (r.next()) {
                String name = r.getString(1);
                if (!TABLES.contains(name))
                    throw new SQLException("Unrecognized sales table; reset stopped");
                found.add(name);
            }
        }
        return found;
    }
    public static List<String> deletionOrder(Connection c) throws SQLException {
        var remaining = new LinkedHashSet<>(existing(c));
        var order = new ArrayList<String>();
        var dependencies = new HashMap<String, Set<String>>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT table_name,referenced_table_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_name IS NOT NULL")) {
            while (r.next()) {
                String child = r.getString(1), parent = r.getString(2);
                if (remaining.contains(parent) && !remaining.contains(child))
                    throw new SQLException("Non-sales table references sales data; reset stopped");
                if (remaining.contains(child) && remaining.contains(parent) && !child.equals(parent))
                    dependencies.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
            }
        }
        while (!remaining.isEmpty()) {
            var ready = remaining.stream()
                    .filter(t -> Collections.disjoint(dependencies.getOrDefault(t, Set.of()), remaining)).toList();
            if (ready.isEmpty())
                throw new SQLException("Cyclic sales relation; reset stopped");
            order.addAll(ready);
            remaining.removeAll(ready);
        }
        return order;
    }
}
