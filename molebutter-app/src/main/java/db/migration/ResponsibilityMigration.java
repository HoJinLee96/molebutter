package db.migration;


import java.sql.*;
import java.util.*;

/** Stop-the-world transition: compare data before any destructive DDL. */
final class ResponsibilityMigration {
    static final List<String> PRODUCT = List.of("search_query", "search_mode", "managed", "code_type", "comparison_code", "lookup_revision", "change_version", "latest_status", "latest_result", "last_good_result", "latest_at", "image_url");
    static final List<String> SETTINGS = List.of("revision", "schedule_enabled", "schedule_time", "preference_revision");
    static final List<String> RUNTIME = List.of("last_schedule_date", "worker_owner", "worker_until", "next_search_at", "stock_lookup_blocked_job", "search_cooldown_until", "search_manual_resume_required", "search_gate_attempt_id", "search_gate_run_id", "search_gate_version");
    static void execute(Connection c, String sql) throws SQLException { try (var s=c.createStatement()) { s.execute(sql); } }
    static long count(Connection c, String sql) throws SQLException { try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);} }
    static void require(boolean condition, String message) throws SQLException { if(!condition)throw new SQLException(message); }
    static void idle(Connection c) throws SQLException {
        require(count(c,"SELECT COUNT(*) FROM product_refresh_run WHERE status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')")==0,"Complete or explicitly cancel active refresh runs before migration");
        require(count(c,"SELECT COUNT(*) FROM supplier_stock_lookup WHERE status IN ('PENDING','RUNNING','BLOCKED')")==0,"Complete or explicitly cancel supplier stock jobs before migration");
        require(count(c,"SELECT COUNT(*) FROM product_settings WHERE id=1")==1,"Missing singleton product settings");
        require(count(c,"SELECT COUNT(*) FROM product_settings WHERE worker_owner IS NOT NULL AND worker_until>CURRENT_TIMESTAMP(6)")==0,"Stop workers and wait for their lease to expire before migration");
    }
    static void copy(Connection c,String target,String source,String key,List<String> columns) throws SQLException {
        String cols=String.join(",",columns);
        execute(c,"INSERT INTO "+target+"("+key+","+cols+") SELECT id,"+cols+" FROM "+source);
    }
    static void verify(Connection c,String target,String source,String key,List<String> columns) throws SQLException {
        require(count(c,"SELECT COUNT(*) FROM "+target)==count(c,"SELECT COUNT(*) FROM "+source),"Row count mismatch for "+target);
        String equal=columns.stream().map(x->"(CAST(n."+x+" AS BINARY) <=> CAST(o."+x+" AS BINARY))").collect(java.util.stream.Collectors.joining(" AND "));
        require(count(c,"SELECT COUNT(*) FROM "+source+" o LEFT JOIN "+target+" n ON n."+key+"=o.id WHERE n."+key+" IS NULL OR NOT ("+equal+")")==0,"Value/reference mismatch for "+target);
    }
    static void verifyAll(Connection c) throws SQLException {
        verify(c,"procurement_product","catalog_product","product_id",PRODUCT);
        verify(c,"procurement_settings","product_settings","id",SETTINGS);
        verify(c,"procurement_runtime","product_settings","id",RUNTIME);
        require(count(c,"SELECT COUNT(*) FROM catalog_consistency_guard WHERE id=1")==1,"Missing catalog guard");
    }
}
