package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.internal.ProductStore;
import cc.ataglace.molebutter.procurement.internal.ProductStatusRepair;
import cc.ataglace.molebutter.common.api.BusinessTime;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.json.JsonMapper;

/** Standalone JDBC maintenance: no web server, scheduler, migrations or external search requests. */
public final class ProductStatusRepairCommand {
    public static void main(String[] args)throws Exception {
        if(args.length<4||!Set.of("preview","apply").contains(args[0]))throw new InputValidationFailure("Usage: preview|apply manifest.json application.properties db.properties");
        Properties p=new Properties();for(int i=2;i<args.length;i++)try(var reader=Files.newBufferedReader(Path.of(args[i]))){p.load(reader);}
        var source=new DriverManagerDataSource(p.getProperty("spring.datasource.url"),p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
        var store=new ProductStore(new JdbcTemplate(source),JsonMapper.builder().build(),new BusinessTime());
        var repair=new ProductStatusRepair(store,new DataSourceTransactionManager(source));
        var mapper=JsonMapper.builder().build();var manifest=Path.of(args[1]);
        List<ProductStatusRepair.Change> changes;
        if("preview".equals(args[0])){
            var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));tx.setReadOnly(true);
            changes=tx.execute(status->repair.preview());
            Files.writeString(manifest,mapper.writeValueAsString(changes));
        }else changes=List.of(mapper.readValue(Files.readString(manifest),ProductStatusRepair.Change[].class));
        int applied=0;
        for(var c:changes){System.out.printf("%s product=%s code=%s run=%s %s -> %s reason=%s notice=%s%n",args[0],c.productId(),c.productCode(),c.runId(),c.before(),c.after(),c.reason(),c.message());
            if("apply".equals(args[0])&&repair.apply(c))applied++;
        }
        System.out.printf("candidates=%d applied=%d%n",changes.size(),applied);
    }
}
