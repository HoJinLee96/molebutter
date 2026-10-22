package db.migration;
import java.sql.*;
import org.flywaydb.core.api.migration.*;
/** Additive account defaults; only verified offline rollout on a populated database. */
public final class V51__coupang_registration_defaults extends BaseJavaMigration {
    public static void check(Connection c)throws SQLException{V50__coupang_pricing_settings.check(c);}
    public static void requireVerifiedBackup(Connection c)throws SQLException{
        if(Boolean.getBoolean("molebutter.registration.verified"))return;
        for(String table:java.util.List.of("user","catalog_product","inventory_item","coupang_product_working_copy","coupang_execution","coupang_asset","coupang_order","coupang_registered_product"))
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){r.next();if(r.getLong(1)>0)throw new SQLException("V51 requires verified offline backup/restore; use upgrade-coupang-registration.sh");}
    }
    public void migrate(Context context)throws Exception{var c=context.getConnection();check(c);requireVerifiedBackup(c);try(var s=c.createStatement()){
        s.execute("CREATE TABLE coupang_registration_defaults(account_key CHAR(64) PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0,defaults_json JSON NOT NULL,updated_by BIGINT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
    }}
}
