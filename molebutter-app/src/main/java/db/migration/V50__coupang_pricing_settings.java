package db.migration;
import java.sql.*;
import org.flywaydb.core.api.migration.*;
public final class V50__coupang_pricing_settings extends BaseJavaMigration {
    public static void check(Connection c)throws SQLException{
        V49__coupang_registered_products.check(c);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM coupang_product_collection WHERE status IN ('QUEUED','RUNNING') OR lease_until>CURRENT_TIMESTAMP(6)")){r.next();if(r.getLong(1)>0)throw new SQLException("V50: stop collection workers before migration");}
    }
    public static void requireVerifiedBackup(Connection c)throws SQLException{
        if(Boolean.getBoolean("molebutter.pricing.verified"))return;
        for(String table:java.util.List.of("user","catalog_product","inventory_item","coupang_product_working_copy","coupang_execution","coupang_asset","coupang_order","coupang_registered_product"))
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){r.next();if(r.getLong(1)>0)throw new SQLException("V50 requires verified offline backup/restore; use upgrade-coupang-pricing.sh");}
    }
    public void migrate(Context context)throws Exception{
        var c=context.getConnection();check(c);requireVerifiedBackup(c);
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE coupang_pricing_settings(id INT PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0,shipping_cost BIGINT NOT NULL,commission_percent DECIMAL(8,4) NOT NULL,margin_percent DECIMAL(8,4) NOT NULL,updated_by BIGINT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
            s.execute("INSERT INTO coupang_pricing_settings(id,shipping_cost,commission_percent,margin_percent) VALUES(1,4000,10.5,7)");
        }
    }
}
