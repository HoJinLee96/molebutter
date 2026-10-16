package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import db.migration.V46__coupang_domain;

/** Only present in test classes. Creates a populated real V45 for the offline command test. */
public final class CoupangDomainFixture {
    public static void main(String[] args)throws Exception{
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:")||!url.contains("/coupang_upgrade_source?"))throw new IllegalStateException("Isolated command fixture only");
        if(args[0].equals("seed"))Flyway.configure().dataSource(url,"root","").target("45").load().migrate();
        try(var c=DriverManager.getConnection(url,"root","");var st=c.createStatement()){
            if(args[0].equals("seed")){
                st.executeUpdate("INSERT INTO user(id,email,name,password_hash,user_role,user_status,created_at) VALUES(1,'preserve@example.test','preserved','fixture','ADMIN','ACTIVE',NOW(6))");
                st.executeUpdate("INSERT INTO marketplace_draft(id,document_json,created_by,updated_by,created_at,updated_at) VALUES(9,'{}',1,1,NOW(6),NOW(6))");
                st.executeUpdate("INSERT INTO marketplace_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('00000000-0000-4000-8000-000000000001','original.png','image/png',100,100,100,1,NOW(6))");
                st.executeUpdate("INSERT INTO marketplace_draft_asset(draft_id,asset_id) VALUES(9,'00000000-0000-4000-8000-000000000001')");
                st.executeUpdate("INSERT INTO marketplace_asset_publication(asset_id,token,created_at) VALUES('00000000-0000-4000-8000-000000000001',REPEAT('a',64),NOW(6))");
                st.executeUpdate("INSERT INTO marketplace_order_account_lock(account_key) VALUES('preserved-account')");
                Files.writeString(Path.of(args[1]),V46__coupang_domain.snapshot(c).toString());
            }else if(!Files.readString(Path.of(args[1])).equals(V46__coupang_domain.snapshot(c).toString()))throw new IllegalStateException("All business rows must be preserved");
        }
    }
}
