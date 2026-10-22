package db.migration;
import java.sql.*;
import java.util.List;
import org.flywaydb.core.api.migration.*;
/** Additive registry; existing data and immutable execution evidence are not rewritten. */
public final class V49__coupang_registered_products extends BaseJavaMigration {
    public static void check(Connection c) throws SQLException {
        for (String sql : List.of("SELECT COUNT(*) FROM coupang_execution_step WHERE status IN ('QUEUED','RUNNING')",
                "SELECT COUNT(*) FROM coupang_submission_account WHERE lease_until>CURRENT_TIMESTAMP(6)",
                "SELECT COUNT(*) FROM coupang_order_job WHERE status IN ('QUEUED','RUNNING')",
                "SELECT COUNT(*) FROM coupang_order_account_lock WHERE lease_until>CURRENT_TIMESTAMP(6)"))
            try (var s=c.createStatement();var r=s.executeQuery(sql)) { r.next(); if(r.getLong(1)>0)
                throw new SQLException("V49: stop app and workers; active jobs or leases remain"); }
    }
    public static void requireVerifiedBackup(Connection c) throws SQLException {
        if(!Boolean.getBoolean("molebutter.products.verified"))
            for(String table:List.of("user","catalog_product","inventory_item","coupang_product_working_copy","coupang_execution","coupang_asset","coupang_order"))
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")) {r.next();if(r.getLong(1)>0)
                    throw new SQLException("V49 requires verified offline backup/restore; use upgrade-coupang-products.sh");}
    }
    public void migrate(Context context) throws Exception {
        var c=context.getConnection();check(c);requireVerifiedBackup(c);
        try(var s=c.createStatement()) {
            s.execute("""
                CREATE TABLE coupang_registered_product (
                id BIGINT PRIMARY KEY, account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                seller_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                product_json LONGTEXT NOT NULL, detail_json LONGTEXT NULL, raw_detail_json LONGTEXT NULL,
                product_name VARCHAR(255) NOT NULL, status_name VARCHAR(64), created_at_external VARCHAR(40),
                summary_order BIGINT NOT NULL DEFAULT 0, detail_order BIGINT NOT NULL DEFAULT 0,
                lifecycle_order BIGINT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 0,
                last_success_at TIMESTAMP(6) NULL, last_attempt_at TIMESTAMP(6) NULL, error_code VARCHAR(64),
                deleted BOOLEAN NOT NULL DEFAULT FALSE, deletion_candidate BOOLEAN NOT NULL DEFAULT FALSE,
                deletion_reason VARCHAR(64), deletion_actor BIGINT NULL, deletion_at TIMESTAMP(6) NULL,
                missing_job_id BIGINT NULL, UNIQUE KEY uq_registered_product(account_key,seller_product_id),
                KEY ix_registered_search(account_key,deleted,product_name))
                """);
            s.execute("""
                CREATE TABLE coupang_registered_option (
                product_id BIGINT NOT NULL, seller_product_item_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                vendor_item_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL, sku VARCHAR(255), model_no VARCHAR(255),
                option_json LONGTEXT NOT NULL,outbound_shipping_days INT NULL, current_price BIGINT NULL,current_stock BIGINT NULL,on_sale BOOLEAN NULL,
                current_json LONGTEXT NULL, inventory_order BIGINT NOT NULL DEFAULT 0,
                last_success_at TIMESTAMP(6) NULL,last_attempt_at TIMESTAMP(6) NULL,error_code VARCHAR(64),
                active BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(product_id,seller_product_item_id),
                KEY ix_registered_vendor(vendor_item_id),KEY ix_registered_sku(sku),
                FOREIGN KEY(product_id) REFERENCES coupang_registered_product(id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_snapshot (
                id BIGINT PRIMARY KEY,product_id BIGINT NOT NULL,kind VARCHAR(20) NOT NULL,subject VARCHAR(40) NOT NULL,
                raw_json LONGTEXT NOT NULL,canonical_hash CHAR(64) NOT NULL,request_order BIGINT NOT NULL,
                created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                KEY ix_product_snapshot(product_id,kind,subject,id),FOREIGN KEY(product_id) REFERENCES coupang_registered_product(id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_change (
                id BIGINT PRIMARY KEY,product_id BIGINT NOT NULL,kind VARCHAR(40) NOT NULL,subject VARCHAR(80),
                field_path TEXT NOT NULL,before_json LONGTEXT NULL,after_json LONGTEXT NULL,actor_id BIGINT NULL,
                job_id BIGINT NULL,execution_id BIGINT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                KEY ix_product_change(product_id,id),FOREIGN KEY(product_id) REFERENCES coupang_registered_product(id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_read (
                request_order BIGINT NOT NULL,product_id BIGINT NOT NULL,kind VARCHAR(20) NOT NULL,subject VARCHAR(40) NOT NULL,
                error_code VARCHAR(64),actor_id BIGINT NULL,job_id BIGINT NULL,
                observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                PRIMARY KEY(request_order,product_id,kind,subject),FOREIGN KEY(product_id) REFERENCES coupang_registered_product(id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_collection (
                id BIGINT PRIMARY KEY,account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,actor_id BIGINT NOT NULL,
                mode VARCHAR(20) NOT NULL,status VARCHAR(20) NOT NULL,phase VARCHAR(20) NOT NULL DEFAULT 'LIST',
                next_token VARCHAR(512) NOT NULL DEFAULT '',tokens_json LONGTEXT NULL,list_complete BOOLEAN NOT NULL DEFAULT FALSE,
                cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,lease_owner VARCHAR(36),lease_until TIMESTAMP(6) NULL,
                error_code VARCHAR(64),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                KEY ix_product_collection(account_key,id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_collection_item (
                job_id BIGINT NOT NULL,seller_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                seen BOOLEAN NOT NULL DEFAULT FALSE,status VARCHAR(20) NOT NULL DEFAULT 'PENDING',error_code VARCHAR(64),
                PRIMARY KEY(job_id,seller_product_id),FOREIGN KEY(job_id) REFERENCES coupang_product_collection(id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_intent_base (
                working_copy_id BIGINT NOT NULL,change_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                base_json LONGTEXT NOT NULL,acknowledged_json LONGTEXT NULL,
                PRIMARY KEY(working_copy_id,change_key),FOREIGN KEY(working_copy_id) REFERENCES coupang_product_working_copy(id))
                """);
            s.execute("""
                CREATE TABLE coupang_product_asset_pin (
                product_id BIGINT NOT NULL,asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                PRIMARY KEY(product_id,asset_id),FOREIGN KEY(product_id) REFERENCES coupang_registered_product(id),
                FOREIGN KEY(asset_id) REFERENCES coupang_asset(id))
                """);
        }
    }
}
