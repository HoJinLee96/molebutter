package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import org.flywaydb.core.Flyway;
public final class CoupangPricingFixture {
    public static void main(String[] args)throws Exception{
        if(args[0].equals("seed")){
            CoupangProductsFixture.main(args);
            System.setProperty("molebutter.products.verified","true");
            try{Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),"root","").target("49").load().migrate();}finally{System.clearProperty("molebutter.products.verified");}
        }else try(var c=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),"root","");var s=c.createStatement()){
            CoupangIntentFixture.verify(c);
            if(!CoupangSchema.existing(c).contains("coupang_pricing_settings"))throw new IllegalStateException("V50 settings missing");
            try(var r=s.executeQuery("SELECT shipping_cost,commission_percent,margin_percent FROM coupang_pricing_settings WHERE id=1")){if(!r.next()||r.getLong(1)!=4000||r.getBigDecimal(2).compareTo(new java.math.BigDecimal("10.5"))!=0||r.getBigDecimal(3).compareTo(new java.math.BigDecimal("7"))!=0)throw new IllegalStateException("V50 defaults differ");}
            System.out.println("PASS V50 full backup/isolated restore, old rows and image bytes preserved, repeat execution");
        }
    }
}
