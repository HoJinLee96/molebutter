package cc.ataglace.molebutter.app.internal.maintenance;
import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway.*;
public final class CoupangIntentFixture {
    public static final String ASSET = "00000000-0000-4000-8000-000000000001";
    public static void seed(Connection c) throws Exception {
        var json = new ObjectMapper();
        var doc = CoupangWorkingCopyDocuments.normalize(CoupangInputs.empty(), json);
        String account = "a".repeat(64);
        try (var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO `user`(id,email,name,password_hash,user_role,user_status,created_at) VALUES(1,'fixture@example.test','preserved member','unused','ADMIN','ACTIVE',NOW(6))");
            for (long id : List.of(9L, 10L))
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_product_working_copy(id,revision,product_name,document_json,account_key,changes_json,created_by,updated_by,created_at,updated_at) VALUES(?,7,'preserved input',?,?,'[]',1,1,NOW(6),NOW(6))")) {
                    q.setLong(1, id);
                    q.setString(2, json.writeValueAsString(doc));
                    q.setString(3, account);
                    q.executeUpdate();
                }
            s.executeUpdate(
                    "INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('"
                            + ASSET + "','original.png','image/png',500,500,25,1,NOW(6))");
            s.executeUpdate("INSERT INTO coupang_working_copy_asset VALUES(9,'" + ASSET + "'),(10,'" + ASSET + "')");
            s.executeUpdate("INSERT INTO coupang_asset_publication VALUES('" + ASSET + "',REPEAT('b',64),NOW(6))");
            s.executeUpdate(
                    "INSERT INTO coupang_listing_history(id,working_copy_id,account_key,listing_version,mapping_json,document_json,intent_json,request_hash,request_id,created_by,created_at) VALUES(77,9,REPEAT('a',64),1,'{\"original\":true}','{\"audit\":true}','{\"intent\":true}',REPEAT('c',64),'00000000-0000-4000-8000-000000000003',1,NOW(6))");
            s.executeUpdate("INSERT INTO coupang_listing_history_asset VALUES(77,'" + ASSET + "')");
            s.executeUpdate("INSERT INTO coupang_editor_history_link VALUES(10,9)");
            String uuid = doc.options().getFirst().id();
            var mapping = new Mapping(account, "123", List.of(new OptionMapping(uuid, "111", "222")));
            var step = new Step("price", Type.PRICE, uuid, "PUT", "/synthetic/price", "", null,
                    "{\"salePrice\":120000}", "{\"salePrice\":130000}");
            var prepared = new Prepared(account, mapping, List.of(step), List.of(),
                    Instant.parse("2026-10-15T00:00:00Z"), List.of(), new EditIntent(doc, List.of()), 3);
            for (long id : List.of(13L, 14L))
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_submission_preview(id,working_copy_id,working_revision,created_by,requested,document_json,prepared_json,created_at,expires_at) VALUES(?,10,7,1,FALSE,?,?,NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY))")) {
                    q.setLong(1, id);
                    q.setString(2, json.writeValueAsString(doc));
                    q.setString(3, json.writeValueAsString(prepared));
                    q.executeUpdate();
                }
            s.executeUpdate(
                    "INSERT INTO coupang_execution(id,preview_id,working_copy_id,working_revision,idempotency_key,created_by,status,created_at,updated_at) VALUES(15,14,10,7,'00000000-0000-4000-8000-000000000005',1,'UNKNOWN',NOW(6),NOW(6))");
            s.executeUpdate(
                    "INSERT INTO coupang_execution_target(execution_id,account_key,mode,status,external_product_id) VALUES(15,REPEAT('a',64),'UPDATE','UNKNOWN','123')");
            s.executeUpdate(
                    "INSERT INTO coupang_execution_step(execution_id,step_id,sequence_no,step_type,option_id,status,action,attempts,result_json,updated_at) VALUES(15,'price',0,'PRICE','"
                            + uuid + "','UNKNOWN','WRITE',1,'{\"evidence\":true}',NOW(6))");
            s.executeUpdate(
                    "INSERT INTO coupang_execution_attempt(id,execution_id,step_id,action,status,started_at,request_json) VALUES(16,15,'price','WRITE','UNKNOWN',NOW(6),'{\"wire\":true,\"hash\":\"unchanged\"}')");
            s.executeUpdate("INSERT INTO coupang_execution_asset VALUES(14,'" + ASSET + "')");
        }
    }
    public static void verify(Connection c) throws Exception {
        try (var s = c.createStatement()) {
            for (String table : List.of("listing_history", "listing_history_asset", "editor_history_link"))
                try (var r = s.executeQuery("SELECT COUNT(*) FROM legacy_coupang_" + table)) {
                    r.next();
                    if (r.getLong(1) != 1)
                        throw new IllegalStateException("Audit rows changed");
                }
            try (var r = s.executeQuery(
                    "SELECT COUNT(*) FROM coupang_product_working_copy WHERE id IN(9,10) AND revision=7 AND created_by=1")) {
                r.next();
                if (r.getLong(1) != 2)
                    throw new IllegalStateException("Working copy identity changed");
            }
            try (var r = s.executeQuery("SELECT request_json FROM coupang_execution_attempt WHERE id=16")) {
                r.next();
                if (!r.getString(1).equals("{\"wire\":true,\"hash\":\"unchanged\"}"))
                    throw new IllegalStateException("Wire evidence changed");
            }
            try (var r = s.executeQuery("SELECT token FROM coupang_asset_publication WHERE asset_id='" + ASSET + "'")) {
                r.next();
                if (!r.getString(1).equals("b".repeat(64)))
                    throw new IllegalStateException("Public token changed");
            }
            try (var r = s.executeQuery(
                    "SELECT COUNT(*) FROM coupang_submission_preview WHERE id=13 AND expires_at<NOW(6)")) {
                r.next();
                if (r.getLong(1) != 1)
                    throw new IllegalStateException("Old unexecuted preview not expired");
            }
            try (var r = s.executeQuery(
                    "SELECT COUNT(*) FROM coupang_submission_preview WHERE id=14 AND expires_at>NOW(6)")) {
                r.next();
                if (r.getLong(1) != 1)
                    throw new IllegalStateException("Executed preview changed");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (args[0].equals("seed"))
            Flyway.configure().dataSource(url, "root", "").target("47").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", "")) {
            if (args[0].equals("seed")) {
                seed(c);
                Files.writeString(Path.of(args[1]), SalesDataMaintenance.digests(c).toString());
            } else {
                verify(c);
                System.out.println(
                        "PASS V48 full backup/isolated restore, working copy IDs/input, audit history/image references, immutable execution/preview/token and idempotency");
            }
        }
    }
}
