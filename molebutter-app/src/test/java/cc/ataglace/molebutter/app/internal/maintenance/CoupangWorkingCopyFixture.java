package cc.ataglace.molebutter.app.internal.maintenance;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
public final class CoupangWorkingCopyFixture {
 public static void main(String[] args)throws Exception{
  String url=System.getenv("SPRING_DATASOURCE_URL"),user=System.getenv("SPRING_DATASOURCE_USERNAME"),pass=System.getenv("SPRING_DATASOURCE_PASSWORD");var json=new ObjectMapper();String asset="00000000-0000-4000-8000-000000000001",option="00000000-0000-4000-8000-000000000002";String account=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("A-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  if(args[0].equals("seed")){
   Flyway.configure().dataSource(url,user,pass).target("46").load().migrate();
   try(var c=DriverManager.getConnection(url,user,pass);var st=c.createStatement()){
    st.executeUpdate("INSERT INTO `user`(id,email,name,password_hash,user_role,user_status,created_at) VALUES(1,'fixture@example.test','preserved member','unused','ADMIN','ACTIVE',NOW(6))");
    var old=json.readTree("""
    {"common":{"name":"internal title","brand":"brand","productName":"product","manufacturer":"maker"},"options":[],"stockMode":"OPTION","media":{"images":[],"contents":[]},"delivery":{},"configuration":{"categoryCode":"10","coupang":{"settings":[],"delivery":[],"options":[],"documents":[]}}}
    """).asObject();old.path("options").asArray().add(json.readTree("{\"id\":\""+option+"\",\"name\":\"option\",\"price\":\"120000\",\"quantity\":\"9\",\"sku\":\"SKU\"}"));old.path("media").path("images").asArray().add(json.readTree("{\"id\":\""+asset+"\",\"assetId\":\""+asset+"\",\"url\":\"/api/coupang/assets/"+asset+"\",\"representative\":true,\"order\":0}"));
    try(var q=c.prepareStatement("INSERT INTO coupang_draft(id,revision,document_json,created_by,updated_by,created_at,updated_at) VALUES(?,7,?,1,1,NOW(6),NOW(6))")){for(long id:List.of(9L,10L)){q.setLong(1,id);q.setString(2,old.toString());q.executeUpdate();}}
    var observed=old.deepCopy();observed.path("common").asObject().put("name","coupang input");var intent=json.createObjectNode();intent.set("observed",observed);var edit=intent.putArray("changes").addObject();edit.put("path","options.price");edit.put("optionId",option);edit.put("value","130000");
    try(var q=c.prepareStatement("INSERT INTO coupang_draft_target_edit(draft_id,account_key,draft_revision,mapping_json,intent_json,updated_by,updated_at) VALUES(10,?,7,'null',?,1,NOW(6))")){q.setString(1,account);q.setString(2,intent.toString());q.executeUpdate();}
    st.executeUpdate("INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('"+asset+"','original.png','image/png',500,500,25,1,NOW(6))");st.executeUpdate("INSERT INTO coupang_draft_asset VALUES(9,'"+asset+"'),(10,'"+asset+"')");st.executeUpdate("INSERT INTO coupang_asset_publication VALUES('"+asset+"',REPEAT('b',64),NOW(6))");
    Files.writeString(Path.of(args[1]),SalesDataMaintenance.digests(c).toString());
   }
  }else if(args[0].equals("verify"))try(var c=DriverManager.getConnection(url,user,pass);var st=c.createStatement()){
   try(var r=st.executeQuery("SELECT COUNT(*) FROM coupang_product_working_copy WHERE id IN (9,10) AND revision=7 AND created_by=1 AND account_key='"+account+"'")){r.next();if(r.getLong(1)!=2)throw new IllegalStateException("Working copy identity changed");}
   try(var r=st.executeQuery("SELECT document_json FROM coupang_product_working_copy WHERE id=10")){r.next();var d=json.readTree(r.getString(1));if(!d.path("options").get(0).path("registration").path("salePrice").asString().equals("130000"))throw new IllegalStateException("Saved input changed");}
   try(var r=st.executeQuery("SELECT COUNT(*) FROM coupang_working_copy_asset WHERE asset_id='"+asset+"'")){r.next();if(r.getLong(1)!=2)throw new IllegalStateException("Asset references changed");}
   try(var r=st.executeQuery("SELECT token FROM coupang_asset_publication WHERE asset_id='"+asset+"'")){r.next();if(!r.getString(1).equals("b".repeat(64)))throw new IllegalStateException("Public token changed");}
   try(var r=st.executeQuery("SELECT name FROM `user` WHERE id=1")){r.next();if(!r.getString(1).equals("preserved member"))throw new IllegalStateException("Preserved data changed");}
   System.out.println("PASS V47 full backup/isolated restore, working copy inputs/IDs/revisions, asset/token references, preservation and idempotency");
  }
 }
}
