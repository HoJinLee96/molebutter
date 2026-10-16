package db.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.app.internal.maintenance.SalesSchema;

/** Preserves every business row and immutable submission document while naming the Coupang domain. */
public final class V46__coupang_domain extends BaseJavaMigration {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Override public void migrate(Context context)throws Exception {
        var c=context.getConnection();var old=SalesSchema.existing(c);
        if(!new HashSet<>(old).equals(SalesSchema.TABLES))throw new SQLException("V46 requires the complete V45 sales schema");
        for(String table:List.of("marketplace_execution","marketplace_execution_step","marketplace_order_job"))
            try(var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM "+quote(table)+" WHERE status IN ('QUEUED','RUNNING','CANCELLING')")){rows.next();if(rows.getLong(1)>0)throw new SQLException("Stop and settle active Coupang jobs before V46");}
        for(String table:List.of("marketplace_order_account_lock","marketplace_submission_account"))
            try(var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM "+quote(table)+" WHERE lease_owner IS NOT NULL AND lease_until>CURRENT_TIMESTAMP(6)")){rows.next();if(rows.getLong(1)>0)throw new SQLException("Stop and settle active Coupang account leases before V46");}
        for(String table:old){
            if(tableExists(c,renamed(table)))throw new SQLException("Both historical and Coupang table names exist; restore or inspect before continuing");
            for(String column:jsonColumns(c,table))try(var st=c.createStatement();var rows=st.executeQuery("SELECT "+quote(column)+" FROM "+quote(table)+" WHERE "+quote(column)+" IS NOT NULL")){
                while(rows.next())validate(JSON.readTree(rows.getString(1)));
            }
        }
        var before=snapshot(c);
        try(var st=c.createStatement()){
            st.execute("RENAME TABLE "+old.stream().sorted().map(name->quote(name)+" TO "+quote(renamed(name))).collect(java.util.stream.Collectors.joining(", ")));
        }
        try(var st=c.createStatement()){st.execute("ALTER TABLE coupang_edit_session RENAME COLUMN target_bindings_json TO binding_json");}
        try(var select=c.createStatement();var rows=select.executeQuery("SELECT id,account_key,mapping_json,binding_json FROM coupang_edit_session");var update=c.prepareStatement("UPDATE coupang_edit_session SET binding_json=? WHERE id=?")){
            while(rows.next()){update.setString(1,binding(rows.getString(4),rows.getString(2),rows.getString(3)));update.setLong(2,rows.getLong(1));update.addBatch();}update.executeBatch();
        }
        try(var st=c.createStatement()){st.execute("ALTER TABLE coupang_edit_session DROP COLUMN account_key,DROP COLUMN mapping_json");}
        try(var st=c.createStatement()){st.executeUpdate("UPDATE coupang_draft SET editor_kind='COUPANG_ONLY' WHERE editor_kind='MARKET_ONLY'");}
        renameConstraints(c);
        if(!before.equals(snapshot(c)))throw new SQLException("Coupang domain migration preservation check failed; restore the verified backup");
    }
    private static String renamed(String name){return name.startsWith("marketplace_")?"coupang_"+name.substring(12):name;}
    private static String quote(String name){if(!name.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("Invalid SQL identifier");return "`"+name+"`";}
    private static boolean tableExists(Connection c,String name)throws SQLException{try(var s=c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")){s.setString(1,name);try(var r=s.executeQuery()){r.next();return r.getInt(1)>0;}}}
    private static List<String> jsonColumns(Connection c,String table)throws SQLException{
        var result=new ArrayList<String>();try(var s=c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name LIKE '%\\_json'")){s.setString(1,table);try(var r=s.executeQuery()){while(r.next())result.add(r.getString(1));}}return result;
    }
    private static void validate(JsonNode node)throws SQLException{
        if(node==null)return;
        if(node.isObject())for(var field:node.properties()){
            String key=field.getKey();var value=field.getValue();
            if(Set.of("market","originMarket","importMarket").contains(key)&&!value.isNull()&&(!value.isString()||!"COUPANG".equals(value.asString())))throw new SQLException("Non-Coupang discriminator in stored evidence");
            if(key.equals("channelProductId")&&!value.isNull())throw new SQLException("Non-Coupang channel identifier in stored evidence");
            if(key.equals("selectedMarkets")&&value.isArray())for(var item:value)if(!"COUPANG".equals(item.asString()))throw new SQLException("Non-Coupang selected target");
            if(key.equals("targets")&&value.isArray()&&value.size()>1)throw new SQLException("Multiple targets in stored evidence");
            if((key.equals("markets")||key.equals("targets"))&&value.isObject())for(var item:value.properties())if(!item.getKey().equals("COUPANG"))throw new SQLException("Non-Coupang target binding");
            validate(value);
        }else if(node.isArray())for(var child:node)validate(child);
    }
    /** The only mutable JSON transformation: an edit session's one account binding. */
    private static String binding(String value,String account,String mapping)throws SQLException{
        JsonNode node=value==null?null:JSON.readTree(value);
        if(node!=null&&node.has("targets")){validate(node);node=node.path("targets").get("COUPANG");}
        if(node==null||node.isNull()){
            if(account==null)return null;
            var created=JSON.createObjectNode();created.put("account",account);created.set("mapping",mapping==null?JSON.nullNode():JSON.readTree(mapping));created.put("listingVersion",1L);node=created;
        }
        validate(node);var copy=node.deepCopy();stripChannel(copy);return JSON.writeValueAsString(copy);
    }
    private static void stripChannel(JsonNode node){if(node instanceof ObjectNode object){object.remove("channelProductId");for(var field:object.properties())stripChannel(field.getValue());}else if(node.isArray())for(var child:node)stripChannel(child);}
    /** Canonical row fingerprints, unchanged by table names or the equivalent single-binding encoding. */
    public static TreeMap<String,String> snapshot(Connection c)throws Exception{
        var tables=new ArrayList<String>();try(var st=c.createStatement();var r=st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name")){while(r.next())tables.add(r.getString(1));}
        var result=new TreeMap<String,String>();
        for(String table:tables){var rows=new ArrayList<String>();boolean session=renamed(table).equals("coupang_edit_session");
            try(var st=c.createStatement();var r=st.executeQuery("SELECT * FROM "+quote(table))){var meta=r.getMetaData();var names=new HashSet<String>();for(int i=1;i<=meta.getColumnCount();i++)names.add(meta.getColumnLabel(i));
                while(r.next()){var digest=MessageDigest.getInstance("SHA-256");
                    for(int i=1;i<=meta.getColumnCount();i++){String column=meta.getColumnLabel(i);if(session&&Set.of("account_key","mapping_json").contains(column))continue;byte[] value=r.getBytes(i);if(column.equals("editor_kind")&&"MARKET_ONLY".equals(r.getString(i)))value="COUPANG_ONLY".getBytes(StandardCharsets.UTF_8);
                        if(session&&Set.of("target_bindings_json","binding_json").contains(column)){column="binding_json";String normalized=binding(r.getString(i),names.contains("account_key")?r.getString("account_key"):null,names.contains("mapping_json")?r.getString("mapping_json"):null);value=normalized==null?null:normalized.getBytes(StandardCharsets.UTF_8);}
                        digest.update(column.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(ByteBuffer.allocate(4).putInt(value==null?-1:value.length).array());if(value!=null)digest.update(value);
                    }
                    rows.add(HexFormat.of().formatHex(digest.digest()));
                }
            }
            Collections.sort(rows);result.put(renamed(table),rows.size()+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",rows).getBytes(StandardCharsets.UTF_8))));
        }
        return result;
    }
    private static void renameConstraints(Connection c)throws SQLException{
        record ForeignKey(String table,String name,String parent,String columns,String references,String deleteRule,String updateRule){}
        var keys=new ArrayList<ForeignKey>();
        try(var st=c.createStatement();var rows=st.executeQuery("SELECT k.table_name,k.constraint_name,k.referenced_table_name,GROUP_CONCAT(CONCAT('`',k.column_name,'`') ORDER BY k.ordinal_position),GROUP_CONCAT(CONCAT('`',k.referenced_column_name,'`') ORDER BY k.ordinal_position),r.delete_rule,r.update_rule FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name WHERE k.table_schema=DATABASE() AND k.referenced_table_name IS NOT NULL AND k.constraint_name LIKE '%marketplace%' GROUP BY k.table_name,k.constraint_name,k.referenced_table_name,r.delete_rule,r.update_rule")){
            while(rows.next())keys.add(new ForeignKey(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7)));
        }
        for(var key:keys)try(var st=c.createStatement()){st.execute("ALTER TABLE "+quote(key.table())+" DROP FOREIGN KEY "+quote(key.name())+", ADD CONSTRAINT "+quote(key.name().replace("marketplace","coupang"))+" FOREIGN KEY ("+key.columns()+") REFERENCES "+quote(key.parent())+" ("+key.references()+") ON DELETE "+key.deleteRule()+" ON UPDATE "+key.updateRule());}
        var indexes=new ArrayList<String[]>();try(var st=c.createStatement();var rows=st.executeQuery("SELECT DISTINCT table_name,index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name LIKE '%marketplace%'")){while(rows.next())indexes.add(new String[]{rows.getString(1),rows.getString(2)});}
        for(var index:indexes)try(var st=c.createStatement()){st.execute("ALTER TABLE "+quote(index[0])+" RENAME INDEX "+quote(index[1])+" TO "+quote(index[1].replace("marketplace","coupang")));}
        var checks=new ArrayList<String[]>();try(var st=c.createStatement();var rows=st.executeQuery("SELECT t.table_name,t.constraint_name,k.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints k ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name WHERE t.constraint_schema=DATABASE() AND t.constraint_type='CHECK' AND t.constraint_name LIKE '%marketplace%'")){while(rows.next())checks.add(new String[]{rows.getString(1),rows.getString(2),rows.getString(3)});}
        for(var check:checks)try(var st=c.createStatement()){st.execute("ALTER TABLE "+quote(check[0])+" DROP CHECK "+quote(check[1])+", ADD CONSTRAINT "+quote(check[1].replace("marketplace","coupang"))+" CHECK ("+check[2]+")");}

    }
}
