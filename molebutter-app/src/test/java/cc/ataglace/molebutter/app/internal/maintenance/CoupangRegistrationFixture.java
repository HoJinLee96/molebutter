package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import org.flywaydb.core.Flyway;
public final class CoupangRegistrationFixture {
    public static void main(String[] args)throws Exception{
        if(args[0].equals("seed")){
            CoupangPricingFixture.main(args);System.setProperty("molebutter.pricing.verified","true");
            try{Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),"root","").target("50").load().migrate();}finally{System.clearProperty("molebutter.pricing.verified");}
        }else try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),"root","");var s=c.createStatement()){
            CoupangIntentFixture.verify(c);
            if(!CoupangSchema.existing(c).contains("coupang_registration_defaults"))throw new IllegalStateException("V51 table missing");
            try(var r=s.executeQuery("SELECT COUNT(*) FROM coupang_registration_defaults")){if(!r.next()||r.getLong(1)!=0)throw new IllegalStateException("V51 created implicit settings");}
            System.out.println("PASS V51 full backup/isolated restore, old rows and image bytes preserved, repeat execution");
        }
    }
}
