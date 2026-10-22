package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
public final class CoupangProductsFixture {
    public static void main(String[] args)throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(args[0].equals("seed")) {
            Flyway.configure().dataSource(url,"root","").target("47").load().migrate();
            try(var c=DriverManager.getConnection(url,"root","")){CoupangIntentFixture.seed(c);}
            System.setProperty("molebutter.intent.verified","true");
            try{Flyway.configure().dataSource(url,"root","").target("48").load().migrate();}finally{System.clearProperty("molebutter.intent.verified");}
            try(var c=DriverManager.getConnection(url,"root","")){Files.writeString(Path.of(args[1]),SalesDataMaintenance.digests(c).toString());}
        } else if (args[0].equals("fail-before-ddl")) {
            try {
                Flyway.configure().dataSource(url,"root","").target("49").load().migrate();
                throw new IllegalStateException("Unverified V49 unexpectedly applied");
            } catch (org.flywaydb.core.api.FlywayException expected) {
                if (!expected.getMessage().contains("verified offline backup/restore") &&
                        !expected.getCause().getMessage().contains("verified offline backup/restore")) throw expected;
            }
            try (var c=DriverManager.getConnection(url,"root","")) {
                if (CoupangProductsRecovery.failedRank(c)==null) throw new IllegalStateException("Expected failed V49 marker");
            }
        } else try(var c=DriverManager.getConnection(url,"root","")){
            CoupangIntentFixture.verify(c);
            if(!CoupangSchema.existing(c).contains("coupang_registered_product"))throw new IllegalStateException("V49 registry missing");
            System.out.println("PASS V49 verified full restore, immutable old rows and image bytes, repeat execution");
        }
    }
}
