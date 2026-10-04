package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.service.common.BusinessTime;

import cc.ataglace.molebutter.exception.InputValidationFailure;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.json.JsonMapper;

/** Standalone JDBC maintenance: no web server, scheduler, migrations or external search requests. */
public final class SearchStatusRepairCommand {
    public static void main(String[] args)throws Exception {
        if(args.length<2||!Set.of("preview","apply").contains(args[0]))throw new InputValidationFailure("Usage: preview|apply application.properties db.properties");
        Properties p=new Properties();for(int i=1;i<args.length;i++)try(var reader=Files.newBufferedReader(Path.of(args[i]))){p.load(reader);}
        var source=new DriverManagerDataSource(p.getProperty("spring.datasource.url"),p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
        var store=new ProductStore(new JdbcTemplate(source),JsonMapper.builder().build(),new BusinessTime());
        var repair=new ProductSearchStatusRepair(store,new DataSourceTransactionManager(source));
        var changes=repair.preview();int applied=0;
        for(var c:changes){System.out.printf("%s product=%s code=%s run=%s %s -> %s reason=%s notice=%s%n",args[0],c.productId(),c.productCode(),c.runId(),c.before(),c.after(),c.reason(),c.message());
            if("apply".equals(args[0])&&repair.apply(c))applied++;
        }
        System.out.printf("candidates=%d applied=%d%n",changes.size(),applied);
    }
}
