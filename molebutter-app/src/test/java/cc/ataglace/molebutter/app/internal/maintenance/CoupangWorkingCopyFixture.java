package cc.ataglace.molebutter.app.internal.maintenance;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
public final class CoupangWorkingCopyFixture {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL"), user = System.getenv("SPRING_DATASOURCE_USERNAME"),
                pass = System.getenv("SPRING_DATASOURCE_PASSWORD");
        var json = new ObjectMapper();
        String asset = "00000000-0000-4000-8000-000000000001", option = "00000000-0000-4000-8000-000000000002";
        String account = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("A-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (args[0].equals("seed")) {
            Flyway.configure().dataSource(url, user, pass).target("46").load().migrate();
            try (var c = DriverManager.getConnection(url, user, pass); var st = c.createStatement()) {
                st.executeUpdate(
                        "INSERT INTO `user`(id,email,name,password_hash,user_role,user_status,created_at) VALUES(1,'fixture@example.test','preserved member','unused','ADMIN','ACTIVE',NOW(6))");
                var old = json
                        .readTree(
                                """
                                        {"common":{"name":"internal title","brand":"brand","productName":"product","manufacturer":"maker"},"options":[],"stockMode":"OPTION","media":{"images":[],"contents":[]},"delivery":{},"configuration":{"categoryCode":"10","coupang":{"settings":[],"delivery":[],"options":[],"documents":[]}}}
                                        """)
                        .asObject();
                old.path("options").asArray().add(json.readTree("{\"id\":\"" + option
                        + "\",\"name\":\"option\",\"price\":\"120000\",\"quantity\":\"9\",\"sku\":\"SKU\"}"));
                old.path("media").path("images").asArray()
                        .add(json.readTree(
                                "{\"id\":\"" + asset + "\",\"assetId\":\"" + asset + "\",\"url\":\"/api/coupang/assets/"
                                        + asset + "\",\"representative\":true,\"order\":0}"));
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_draft(id,revision,document_json,created_by,updated_by,created_at,updated_at) VALUES(?,7,?,1,1,NOW(6),NOW(6))")) {
                    for (long id : List.of(9L, 10L)) {
                        q.setLong(1, id);
                        q.setString(2, old.toString());
                        q.executeUpdate();
                    }
                }
                var observed = old.deepCopy();
                observed.path("common").asObject().put("name", "coupang input");
                var intent = json.createObjectNode();
                intent.set("observed", observed);
                var edit = intent.putArray("changes").addObject();
                edit.put("path", "options.price");
                edit.put("optionId", option);
                edit.put("value", "130000");
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_draft_target_edit(draft_id,account_key,draft_revision,mapping_json,intent_json,updated_by,updated_at) VALUES(10,?,7,'null',?,1,NOW(6))")) {
                    q.setString(1, account);
                    q.setString(2, intent.toString());
                    q.executeUpdate();
                }
                st.executeUpdate(
                        "INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('"
                                + asset + "','original.png','image/png',500,500,25,1,NOW(6))");
                st.executeUpdate("INSERT INTO coupang_draft_asset VALUES(9,'" + asset + "'),(10,'" + asset + "')");
                st.executeUpdate("INSERT INTO coupang_asset_publication VALUES('" + asset + "',REPEAT('b',64),NOW(6))");
                seedLinked(c, account, option, asset);
                Files.writeString(Path.of(args[1]), SalesDataMaintenance.digests(c).toString());
            }
        } else if (args[0].equals("verify"))
            try (var c = DriverManager.getConnection(url, user, pass); var st = c.createStatement()) {
                try (var r = st.executeQuery(
                        "SELECT COUNT(*) FROM coupang_product_working_copy WHERE id IN (9,10) AND revision=7 AND created_by=1 AND account_key='"
                                + account + "'")) {
                    r.next();
                    if (r.getLong(1) != 2)
                        throw new IllegalStateException("Working copy identity changed");
                }
                try (var r = st.executeQuery("SELECT document_json FROM coupang_product_working_copy WHERE id=10")) {
                    r.next();
                    var d = json.readTree(r.getString(1));
                    if (!d.path("options").get(0).path("registration").path("salePrice").asString().equals("130000"))
                        throw new IllegalStateException("Saved input changed");
                }
                try (var r = st.executeQuery(
                        "SELECT COUNT(*) FROM coupang_working_copy_asset WHERE asset_id='" + asset + "'")) {
                    r.next();
                    if (r.getLong(1) != 3)
                        throw new IllegalStateException("Asset references changed");
                }
                try (var r = st
                        .executeQuery("SELECT token FROM coupang_asset_publication WHERE asset_id='" + asset + "'")) {
                    r.next();
                    if (!r.getString(1).equals("b".repeat(64)))
                        throw new IllegalStateException("Public token changed");
                }
                try (var r = st.executeQuery("SELECT name FROM `user` WHERE id=1")) {
                    r.next();
                    if (!r.getString(1).equals("preserved member"))
                        throw new IllegalStateException("Preserved data changed");
                }
                verifyLinked(c, asset);
                try (var r = st.executeQuery("SELECT COUNT(*) FROM legacy_coupang_target_edit")) {
                    r.next();
                    if (r.getLong(1) != 1)
                        throw new IllegalStateException("Preparation changed original saved-intent evidence");
                }
                System.out.println(
                        "PASS V47 duplicate media identity, full backup/isolated restore, working copy inputs/IDs/revisions, asset/token references, preservation and idempotency");
            }
    }
    static final String SECOND_IMAGE = "00000000-0000-4000-8000-000000000021",
            FIRST_CONTENT = "00000000-0000-4000-8000-000000000022",
            SECOND_CONTENT = "00000000-0000-4000-8000-000000000023";
    static String seedLinked(Connection c, String account, String option, String asset) throws Exception {
        var json = new ObjectMapper();
        var old = json
                .readTree(
                        """
                                {"common":{"name":"internal title","brand":"brand","productName":"product","manufacturer":"maker"},"options":[],"stockMode":"OPTION","media":{"images":[],"contents":[]},"delivery":{},"configuration":{"categoryCode":"10","coupang":{"settings":[],"delivery":[],"options":[],"documents":[]}}}
                                """)
                .asObject();
        old.path("options").asArray().addObject().put("id", option).put("name", "option").put("price", "120000")
                .put("quantity", "9").put("sku", "SKU");
        int order = 0;
        for (String id : List.of(asset, SECOND_IMAGE))
            old.path("media").path("images").asArray().addObject().put("id", id).put("assetId", asset)
                    .put("url", "/api/coupang/assets/" + asset).put("representative", order == 0).put("order", order++);
        for (String id : List.of(FIRST_CONTENT, SECOND_CONTENT))
            old.path("media").path("contents").asArray().addObject().put("id", id).putNull("assetId")
                    .put("type", "TEXT").put("value", "same text");
        String published = "https://app.example.test/coupang/images/" + "b".repeat(64);
        var source = new CoupangEditor.EditorDocument(
                new CoupangEditor.Basic(
                        "555", null, "fresh Coupang title", "display name", "product", "brand", null, "10", "승인완료"),
                new CoupangEditor.Limits(true, true, true),
                List.of(new CoupangEditor.EditOption("111", "222", "option",
                        new CoupangCatalog.CurrentInventory("222", 180000L, 4L, true), null, true, List.of(),
                        List.of(new CoupangCatalog.Image(0, "REPRESENTATION", published),
                                new CoupangCatalog.Image(1, "DETAIL", published)),
                        List.of(new CoupangCatalog.Content("TEXT", "TEXT", "same text"),
                                new CoupangCatalog.Content("TEXT", "TEXT", "same text")),
                        List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());
        var cp = old.path("configuration").path("coupang").asObject();
        cp.put("sellerProductId", "555").set("source", json.valueToTree(source));
        cp.path("options").asArray().addObject().put("optionId", option).put("sellerProductItemId", "111")
                .put("vendorItemId", "222");
        try (var q = c.prepareStatement(
                "INSERT INTO coupang_draft(id,revision,document_json,import_account,import_product_id,created_by,updated_by,created_at,updated_at) VALUES(11,8,?,?,'555',1,1,NOW(6),NOW(6))")) {
            q.setString(1, old.toString());
            q.setString(2, account);
            q.executeUpdate();
        }
        try (var q = c.prepareStatement("INSERT INTO coupang_draft_asset VALUES(11,?)")) {
            q.setString(1, asset);
            q.executeUpdate();
        }
        seedEvidence(c, account, option, asset, old.toString());
        return old.toString();
    }

    static void seedEvidence(Connection c, String account, String option, String asset, String document)
            throws Exception {
        try (var q = c.prepareStatement(
                "INSERT INTO coupang_submission_preview(id,draft_id,draft_revision,created_by,requested,document_json,prepared_json,created_at,expires_at) VALUES(14,11,8,1,FALSE,?,?,NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY))")) {
            q.setString(1, document);
            q.setString(2, "{\"schemaVersion\":1,\"proof\":\"unchanged\"}");
            q.executeUpdate();
        }
        try (var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO coupang_execution(id,preview_id,draft_id,draft_revision,idempotency_key,created_by,status,created_at,updated_at) VALUES(15,14,11,8,'00000000-0000-4000-8000-000000000005',1,'UNKNOWN',NOW(6),NOW(6))");
            s.executeUpdate(
                    "INSERT INTO coupang_execution_target(execution_id,account_key,mode,status,external_product_id) VALUES(15,'"
                            + account + "','UPDATE','UNKNOWN','555')");
            s.executeUpdate(
                    "INSERT INTO coupang_execution_step(execution_id,step_id,sequence_no,step_type,option_id,status,action,attempts,updated_at) VALUES(15,'price',0,'PRICE','"
                            + option + "','UNKNOWN','WRITE',1,NOW(6))");
            s.executeUpdate(
                    "INSERT INTO coupang_execution_attempt(id,execution_id,step_id,action,status,started_at,request_json) VALUES(16,15,'price','WRITE','UNKNOWN',NOW(6),'{\"wire\":true,\"hash\":\"unchanged\"}')");
            s.executeUpdate("INSERT INTO coupang_execution_asset VALUES(14,'" + asset + "')");
        }
    }
    static void verifyLinked(Connection c, String asset) throws Exception {
        try (var st = c.createStatement();
                var r = st.executeQuery(
                        "SELECT document_json,changes_json FROM coupang_product_working_copy WHERE id=11")) {
            if (!r.next())
                throw new IllegalStateException("Linked working copy missing");
            var d = new ObjectMapper().readValue(r.getString(1), CoupangWorkingCopies.Document.class);
            var o = d.options().getFirst();
            if (!o.images().stream().map(CoupangWorkingCopies.Image::id).toList().equals(List.of(asset, SECOND_IMAGE))
                    || !o.contents().stream().map(CoupangWorkingCopies.Content::id).toList()
                            .equals(List.of(FIRST_CONTENT, SECOND_CONTENT)))
                throw new IllegalStateException("Repeated media identities changed");
            if (o.images().stream().anyMatch(i -> !asset.equals(i.assetId())))
                throw new IllegalStateException("Local image references changed");
            if (!d.basic().get("sellerProductName").equals("fresh Coupang title")
                    || !o.registration().get("salePrice").equals("180000") || !r.getString(2).equals("[]"))
                throw new IllegalStateException("Internal values or invented changes replaced the server observation");
        }
    }

}
