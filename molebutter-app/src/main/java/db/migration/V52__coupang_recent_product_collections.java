package db.migration;
import java.sql.*;
import org.flywaydb.core.api.migration.*;

/** Additive resumable recent collection state. Populated databases require verified offline rollout. */
public final class V52__coupang_recent_product_collections extends BaseJavaMigration {
    public static void check(Connection c)throws SQLException { V51__coupang_registration_defaults.check(c); }
    public static void requireVerifiedBackup(Connection c)throws SQLException {
        if(Boolean.getBoolean("molebutter.recent-products.verified"))return;
        for(String table:java.util.List.of("user","catalog_product","inventory_item","coupang_product_working_copy","coupang_execution","coupang_asset","coupang_order","coupang_registered_product","coupang_product_collection","coupang_registration_defaults"))
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")) {
                r.next();if(r.getLong(1)>0)throw new SQLException("V52 requires verified offline backup/restore; use upgrade-coupang-recent-products.sh");
            }
    }
    public void migrate(Context context)throws Exception {
        var c=context.getConnection();check(c);requireVerifiedBackup(c);
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE coupang_collection_recent(job_id BIGINT PRIMARY KEY,created_date_from DATE NOT NULL,created_date_to DATE NOT NULL,max_products INT NOT NULL,cutoff_at VARCHAR(40) NOT NULL,current_date_value DATE NOT NULL,api_pages BIGINT NOT NULL DEFAULT 0,selection_complete BOOLEAN NOT NULL DEFAULT FALSE,FOREIGN KEY(job_id) REFERENCES coupang_product_collection(id))");
            s.execute("CREATE TABLE coupang_collection_candidate(job_id BIGINT NOT NULL,seller_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,created_at_value VARCHAR(40) NOT NULL,product_json JSON NOT NULL,summary_json JSON NOT NULL,observation_order BIGINT NOT NULL,observed_at VARCHAR(40) NOT NULL,eligible BOOLEAN NOT NULL,selected_rank INT NULL,PRIMARY KEY(job_id,seller_product_id),KEY(job_id,selected_rank),FOREIGN KEY(job_id) REFERENCES coupang_product_collection(id))");
        }
    }
}
