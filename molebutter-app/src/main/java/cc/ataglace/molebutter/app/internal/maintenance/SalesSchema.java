package cc.ataglace.molebutter.app.internal.maintenance;

import java.sql.*;
import java.util.*;

/** Explicit ownership boundary: catalog, procurement, inventory and identity are excluded. */
public final class SalesSchema {
    private SalesSchema() {}
    public static final Set<String> TABLES=Set.of(
        "marketplace_draft",
        "marketplace_asset",
        "marketplace_draft_asset",
        "marketplace_submission_preview",
        "marketplace_execution",
        "marketplace_execution_target",
        "marketplace_execution_step",
        "marketplace_execution_attempt",
        "marketplace_listing_mapping",
        "marketplace_submission_account",
        "marketplace_execution_asset",
        "marketplace_asset_publication",
        "marketplace_edit_session",
        "marketplace_order",
        "marketplace_order_item",
        "marketplace_order_claim",
        "marketplace_order_job",
        "marketplace_order_checkpoint",
        "marketplace_order_job_order",
        "marketplace_order_cursor",
        "marketplace_order_account_lock",
        "marketplace_order_failed_detail",
        "marketplace_order_job_claim",
        "marketplace_draft_target_edit",
        "marketplace_editor_history_link",
        "marketplace_image_edit",
        "marketplace_listing_state",
        "marketplace_listing_history",
        "marketplace_listing_history_asset",
        "marketplace_target_saved",
        "marketplace_comparison_resolution",
        "marketplace_media_comparison");
    public static List<String> existing(Connection connection)throws SQLException {
        var found=new ArrayList<String>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND LEFT(table_name,12)='marketplace_'")){
            while(r.next()){String name=r.getString(1);if(!TABLES.contains(name))throw new SQLException("Unrecognized sales table; reset stopped");found.add(name);}
        }
        return found;
    }
    public static List<String> deletionOrder(Connection c)throws SQLException {
        var remaining=new LinkedHashSet<>(existing(c));var order=new ArrayList<String>();var dependencies=new HashMap<String,Set<String>>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT table_name,referenced_table_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_name IS NOT NULL")){
            while(r.next()){String child=r.getString(1),parent=r.getString(2);if(remaining.contains(parent)&&!remaining.contains(child))throw new SQLException("Non-sales table references sales data; reset stopped");if(remaining.contains(child)&&remaining.contains(parent)&&!child.equals(parent))dependencies.computeIfAbsent(parent,k->new HashSet<>()).add(child);}
        }
        while(!remaining.isEmpty()){
            var ready=remaining.stream().filter(t->Collections.disjoint(dependencies.getOrDefault(t,Set.of()),remaining)).toList();
            if(ready.isEmpty())throw new SQLException("Cyclic sales relation; reset stopped");order.addAll(ready);remaining.removeAll(ready);
        }
        return order;
    }
}
