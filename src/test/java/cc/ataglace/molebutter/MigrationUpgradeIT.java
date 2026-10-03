package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationUpgradeIT {
    @Test
    void v1CreatesNoAdminAndLaterMigrationsPreserveExistingAccounts() throws Exception {
        String url = System.getenv("MOLEBUTTER_TEST_DB_URL");
        if (url == null || !url.contains("/molebutter_test?")) {
            throw new IllegalStateException("Run scripts/test-integration.sh");
        }
        url = url.replace("/molebutter_test?", "/molebutter_upgrade_test?");
        String user = "test_migrator", password = "isolated-test-migration-password";
        Flyway.configure().dataSource(url, user, password).target("1").load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM `user`")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
            statement.executeUpdate("""
                    INSERT INTO `user` (id, email, name, password_hash, user_role, user_status, created_at)
                    VALUES (1, 'owner@example.com', 'Test admin', 'custom-password-hash', 'ADMIN', 'ACTIVE', NOW(6))
                    """);
        }
        // V1/V2만 있는 기존 설치와 이미 근태를 사용하는 V4 설치를 순서대로 검증한다.
        assertThat(Flyway.configure().dataSource(url, user, password).target("2").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(Flyway.configure().dataSource(url, user, password).target("4").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO attendance(id,user_id,work_date,clock_in,clock_out,status,revision,created_at) VALUES (10,1,'2026-02-01','2026-02-01 09:00:00','2026-02-01 18:00:00','COMPLETED',2,NOW(6))");
            statement.executeUpdate("INSERT INTO attendance_break VALUES (10,0,'2026-02-01 12:00:00','2026-02-01 13:00:00')");
        }
        assertThat(Flyway.configure().dataSource(url,user,password).target("6").load().migrate().migrationsExecuted).isEqualTo(2);
        try (var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO product_import VALUES(1,'existing.xlsx',X'00',1,NOW(6))");
            statement.executeUpdate("INSERT INTO product_item(id,vendor_item_id,name,option_name,approval_status,sale_status,import_id,import_row,updated_at) VALUES(1,'9007199254740993','기존 상품','네이비 / FREE','승인완료','판매중',1,4,NOW(6))");
            statement.executeUpdate("INSERT INTO product_source_link VALUES(1,1,'HAZZYS','P1','N1','S1','네이비 / FREE','https://www.hazzys.com/product.do')");
        }
        assertThat(Flyway.configure().dataSource(url,user,password).target("7").load().migrate().migrationsExecuted).isEqualTo(1);
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE product_item SET product_code='SAME',shipping_override=9999,latest_status='SUCCESS',latest_result=JSON_OBJECT('status','SUCCESS','purchasePrice',100000,'observations',JSON_ARRAY()) WHERE id=1");
            statement.executeUpdate("INSERT INTO product_item(id,vendor_item_id,product_code,name,option_name,approval_status,sale_status,import_id,import_row,updated_at) VALUES(2,'9007199254740994','SAME','다른 옵션','블랙 / M','승인완료','판매중',1,5,NOW(6)),(3,'9007199254740995','','코드 없음','','승인완료','판매중',1,6,NOW(6))");
            statement.executeUpdate("INSERT INTO product_refresh_run(id,status,trigger_type,created_at) VALUES(1,'PAUSED','MANUAL',NOW(6))");
        }
        Flyway flyway = Flyway.configure().dataSource(url, user, password).target("9").load();
        assertThatThrownBy(flyway::migrate).hasStackTraceContaining("기존 최신화 작업을 완료하거나 취소");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            try(var tables=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='catalog_product'")){tables.next();assertThat(tables.getInt(1)).isZero();}
            statement.executeUpdate("UPDATE product_refresh_run SET status='CANCELLED' WHERE id=1");
        }
        // 이 격리 테스트 DB에서만 실패 이력을 정리해 전환 재시도를 검증한다.
        flyway.repair();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        flyway.validate();assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM catalog_product WHERE brand='' AND merged_into IS NULL")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM catalog_product WHERE deleted_at IS NULL AND deleted_by IS NULL")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM purchase_option WHERE latest_result IS NULL AND latest_status='NOT_CHECKED'")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
            try(var r=statement.executeQuery("SELECT last_good_result FROM purchase_option WHERE id=1")){r.next();assertThat(r.getString(1)).contains("100000");}
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM channel_listing l JOIN purchase_option o ON o.id=l.purchase_option_id JOIN catalog_product p ON p.id=o.product_id")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
            try(var r=statement.executeQuery("SELECT option_label FROM purchase_source_link WHERE purchase_option_id=1")){r.next();assertThat(r.getString(1)).isEqualTo("네이비 / FREE");}
            try(var r=statement.executeQuery("SELECT shipping_override FROM product_item WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(9999);}
        }
        // V9의 동일 브랜드 두 상품·미지정 상품을 V10으로 전환한다.
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE catalog_product SET brand='기존 브랜드' WHERE id IN (1,2)");
            statement.executeUpdate("UPDATE catalog_product SET deleted_at=NOW(6),deleted_by=1 WHERE id=2");
        }
        var current=Flyway.configure().dataSource(url,user,password).target("10").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);current.validate();
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("10");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM product_brand WHERE name='기존 브랜드'")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            try(var r=statement.executeQuery("SELECT COUNT(DISTINCT brand_id),COUNT(brand_id) FROM catalog_product WHERE id IN(1,2)")){r.next();assertThat(r.getInt(1)).isEqualTo(1);assertThat(r.getInt(2)).isEqualTo(2);}
            try(var r=statement.executeQuery("SELECT brand_id FROM catalog_product WHERE id=3")){r.next();assertThat(r.getObject(1)).isNull();}
            try(var r=statement.executeQuery("SELECT name FROM sales_channel WHERE code='COUPANG'")){r.next();assertThat(r.getString(1)).isEqualTo("쿠팡");}
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM channel_listing WHERE channel='COUPANG'")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
        }
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT email, password_hash, user_status, auth_version FROM `user` WHERE id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("owner@example.com");
            assertThat(result.getString(2)).isEqualTo("custom-password-hash");
            assertThat(result.getString(3)).isEqualTo("ACTIVE");
            assertThat(result.getLong(4)).isZero();
            try (var record = connection.createStatement().executeQuery("SELECT revision, status FROM attendance WHERE id=10")) {
                assertThat(record.next()).isTrue(); assertThat(record.getLong(1)).isEqualTo(2); assertThat(record.getString(2)).isEqualTo("COMPLETED");
            }
            try (var breaks = connection.createStatement().executeQuery("SELECT COUNT(*) FROM attendance_break WHERE attendance_id=10")) {
                assertThat(breaks.next()).isTrue(); assertThat(breaks.getLong(1)).isEqualTo(1);
            }
            try(var link=connection.createStatement().executeQuery("SELECT option_label,image_url FROM product_source_link WHERE id=1")) {
                assertThat(link.next()).isTrue();assertThat(link.getString(1)).isEqualTo("네이비 / FREE");assertThat(link.getString(2)).isNull();
            }
        }
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("UPDATE product_brand SET name='헤지스'");
            st.executeUpdate("UPDATE catalog_product SET product_code='ABCD6F123BK',search_query='수동 검색어' WHERE id=1");
            st.executeUpdate("INSERT INTO channel_listing(id,channel,external_option_id,product_code,name,option_name,approval_status,sale_status,import_id,import_row,updated_at) VALUES(4,'COUPANG','UNLINKED','ABCD6E123BK','헤지스 이전 시즌','','','','1',7,NOW(6))");
            st.executeUpdate("UPDATE channel_listing SET purchase_option_id=NULL,product_code='ABCD6E123BK' WHERE id=1");
            st.executeUpdate("INSERT INTO purchase_refresh_item(run_id,purchase_option_id,option_revision,query,links,status,result,checked_at) VALUES(1,1,0,'ABCD123',JSON_ARRAY(),'SUCCESS',JSON_OBJECT('purchasePrice',120000),NOW(6))");
            st.executeUpdate("UPDATE product_refresh_run SET status='BLOCKED' WHERE id=1");
        }
        var lookup=Flyway.configure().dataSource(url,user,password).target("11").load();
        assertThatThrownBy(lookup::migrate).hasStackTraceContaining("완료하거나 취소");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='catalog_product' AND column_name='comparison_code'")){r.next();assertThat(r.getInt(1)).isZero();}
            st.executeUpdate("UPDATE product_refresh_run SET status='CANCELLED' WHERE id=1");
        }
        lookup.repair();assertThat(lookup.migrate().migrationsExecuted).isEqualTo(1);lookup.validate();
        assertThat(lookup.info().current().getVersion().getVersion()).isEqualTo("11");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM catalog_product")){r.next();assertThat(r.getInt(1)).isEqualTo(5);}
            try(var r=st.executeQuery("SELECT comparison_code,search_query,search_mode FROM catalog_product WHERE id=1")){r.next();assertThat(r.getString(1)).isEqualTo("ABCD123");assertThat(r.getString(2)).isEqualTo("수동 검색어");assertThat(r.getString(3)).isEqualTo("MANUAL");}
            try(var r=st.executeQuery("SELECT product_code,registration_names FROM catalog_product WHERE id=5")){r.next();assertThat(r.getString(1)).isEqualTo("ABCD6E123BK");assertThat(r.getString(2)).contains("헤지스 이전 시즌");}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM catalog_product WHERE product_code='ABCD6E123BK'")){r.next();assertThat(r.getInt(1)).isEqualTo(2);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_supplier")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_lookup_history WHERE legacy=TRUE")){r.next();assertThat(r.getInt(1)).isEqualTo(4);}
            try(var r=st.executeQuery("SELECT payload FROM product_lookup_history WHERE run_id=1")){r.next();assertThat(r.getString(1)).contains("120000");}
            try(var r=st.executeQuery("SELECT payload FROM product_lookup_history WHERE product_id=1")){r.next();assertThat(r.getString(1)).contains("100000","네이비 / FREE");}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('product_import','product_item','channel_listing','purchase_option','sales_channel')")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=st.executeQuery("SELECT email,password_hash FROM `user` WHERE id=1")){r.next();assertThat(r.getString(1)).isEqualTo("owner@example.com");assertThat(r.getString(2)).isEqualTo("custom-password-hash");}
        }
        // V12는 기존 상품·매입처·조회 이력을 그대로 두고 선호/선정만 빈 상태로 시작한다.
        long sources,history;
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_supplier")){r.next();sources=r.getLong(1);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_lookup_history")){r.next();history=r.getLong(1);}
            st.executeUpdate("UPDATE catalog_product SET latest_at=NOW(6),latest_result=JSON_OBJECT('suppliers',JSON_ARRAY(JSON_OBJECT('offer',JSON_OBJECT('mall','HAZZYS','mallProductId','P1','naverProductId','N1','price',94000,'deliveryFee',0)))) WHERE id=1");
        }
        var preferred=Flyway.configure().dataSource(url,user,password).target("12").load();
        assertThat(preferred.migrate().migrationsExecuted).isEqualTo(1);preferred.validate();
        assertThat(preferred.info().current().getVersion().getVersion()).isEqualTo("12");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_supplier")){r.next();assertThat(r.getLong(1)).isEqualTo(sources);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_lookup_history")){r.next();assertThat(r.getLong(1)).isEqualTo(history);}
            try(var r=st.executeQuery("SELECT last_price,observation,manual_store_id FROM product_supplier WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(94000);assertThat(r.getString(2)).contains("94000");assertThat(r.getObject(3)).isNull();}
            for(String table:java.util.List.of("supplier_preference","supplier_store","product_supplier_selection","product_supplier_change"))try(var r=st.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();assertThat(r.getLong(1)).isZero();}
        }

        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("INSERT INTO supplier_store(id,mall,kind,name,identity_key,aliases,revision,created_at,updated_at) VALUES(99,'HI_THEHYUNDAI','BRANCH','신촌점','branch:신촌점',JSON_ARRAY('신촌점'),3,NOW(6),NOW(6))");
            st.executeUpdate("UPDATE product_supplier SET manual_store_id=99 WHERE id=1");
            st.executeUpdate("INSERT INTO supplier_preference(id,mall,store_id,scope_key) VALUES(99,'HI_THEHYUNDAI',99,'99')");
            st.executeUpdate("INSERT INTO product_supplier_selection(product_id,supplier_id,selected_by,selected_at) VALUES(1,1,1,NOW(6))");
        }
        var identity=Flyway.configure().dataSource(url,user,password).target("13").load();assertThat(identity.migrate().migrationsExecuted).isEqualTo(1);identity.validate();
        assertThat(identity.info().current().getVersion().getVersion()).isEqualTo("13");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT name,retailer,revision FROM supplier_store WHERE id=99")){r.next();assertThat(r.getString(1)).isEqualTo("신촌점");assertThat(r.getString(2)).isEqualTo("현대백화점");assertThat(r.getInt(3)).isEqualTo(3);}
            try(var r=st.executeQuery("SELECT manual_store_id,last_price FROM product_supplier WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(99);assertThat(r.getLong(2)).isEqualTo(94000);}
            try(var r=st.executeQuery("SELECT supplier_id FROM product_supplier_selection WHERE product_id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT store_id FROM supplier_preference WHERE id=99")){r.next();assertThat(r.getLong(1)).isEqualTo(99);}
        }

        var branchPolicy=Flyway.configure().dataSource(url,user,password).target("14").load();
        assertThat(branchPolicy.migrate().migrationsExecuted).isEqualTo(1);branchPolicy.validate();
        assertThat(branchPolicy.info().current().getVersion().getVersion()).isEqualTo("14");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT mall,branch_required FROM supplier_mall_policy")){
                int count=0;while(r.next()){count++;assertThat(r.getBoolean(2)).isEqualTo(!java.util.Set.of("LFMALL","HAZZYS").contains(r.getString(1)));}assertThat(count).isEqualTo(7);
            }
            try(var r=st.executeQuery("SELECT manual_store_id,last_price FROM product_supplier WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(99);assertThat(r.getLong(2)).isEqualTo(94000);}
            try(var r=st.executeQuery("SELECT supplier_id FROM product_supplier_selection WHERE product_id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT store_id FROM supplier_preference WHERE id=99")){r.next();assertThat(r.getLong(1)).isEqualTo(99);}
        }

    }
}
