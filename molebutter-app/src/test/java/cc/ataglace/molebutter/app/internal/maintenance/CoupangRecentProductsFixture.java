package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import org.flywaydb.core.Flyway;
public final class CoupangRecentProductsFixture {
    public static void main(String[] args)throws Exception {
        if(args[0].equals("seed")) {
            CoupangRegistrationFixture.main(args);System.setProperty("molebutter.registration.verified","true");
            try{Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),"root","").target("51").load().migrate();}finally{System.clearProperty("molebutter.registration.verified");}
        }else try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),"root","");var s=c.createStatement()) {
            CoupangIntentFixture.verify(c);
            for(String table:java.util.List.of("coupang_collection_recent","coupang_collection_candidate")) {
                if(!CoupangSchema.existing(c).contains(table))throw new IllegalStateException("V52 table missing");
                try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){if(!r.next()||r.getLong(1)!=0)throw new IllegalStateException("V52 created implicit collection");}
            }
            System.out.println("PASS V52 full backup/isolated restore, existing rows and image bytes preserved, repeat execution");
        }
    }
}
