package db.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import cc.ataglace.molebutter.app.internal.maintenance.SalesSchema;

/** Never deletes business data during startup. Run reset-sales-data.sh before upgrading. */
public final class V45__coupang_only extends BaseJavaMigration {
    @Override public void migrate(Context context)throws Exception {
        var c=context.getConnection();var tables=SalesSchema.existing(c);
        for(String table:tables)try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){
            r.next();if(r.getLong(1)!=0)throw new SQLException("Sales data must be backed up and reset with scripts/reset-sales-data.sh before V45");
        }
        for(String table:tables){var columns=new ArrayList<String>();
            try(var s=c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name IN ('market','import_market')")){s.setString(1,table);try(var r=s.executeQuery()){while(r.next())columns.add(r.getString(1));}}
            for(String column:columns)try(var s=c.createStatement()){s.execute("ALTER TABLE `"+table+"` DROP COLUMN `"+column+"`");}
        }
    }
}
