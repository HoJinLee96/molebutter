package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import cc.ataglace.molebutter.common.api.BusinessTime;
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

        var notifications=Flyway.configure().dataSource(url,user,password).target("15").load();
        assertThat(notifications.migrate().migrationsExecuted).isEqualTo(1);notifications.validate();
        assertThat(notifications.info().current().getVersion().getVersion()).isEqualTo("15");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            for(String table:java.util.List.of("notification_event","user_notification"))
                try(var r=st.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=st.executeQuery("SELECT supplier_id FROM product_supplier_selection WHERE product_id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT manual_store_id,last_price FROM product_supplier WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(99);assertThat(r.getLong(2)).isEqualTo(94000);}
        }

        var recommendations=Flyway.configure().dataSource(url,user,password).target("16").load();
        assertThat(recommendations.migrate().migrationsExecuted).isEqualTo(1);recommendations.validate();
        assertThat(recommendations.info().current().getVersion().getVersion()).isEqualTo("16");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_refresh_entry WHERE selection_snapshot IS NOT NULL")){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=st.executeQuery("SELECT supplier_id FROM product_supplier_selection WHERE product_id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT manual_store_id,last_price FROM product_supplier WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(99);assertThat(r.getLong(2)).isEqualTo(94000);}
        }

        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("INSERT INTO product_refresh_run(id,status,trigger_type,created_at) VALUES(900,'BLOCKED','MANUAL',NOW(6))");
        }
        var retry=Flyway.configure().dataSource(url,user,password).target("20").load();
        assertThat(retry.migrate().migrationsExecuted).isEqualTo(4);retry.validate();
        assertThat(retry.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(retry.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT status,login_retry_count,next_retry_at,block_reason FROM product_refresh_run WHERE id=900")){
                r.next();assertThat(r.getString(1)).isEqualTo("BLOCKED");assertThat(r.getInt(2)).isZero();assertThat(r.getObject(3)).isNull();assertThat(r.getObject(4)).isNull();
            }
            try(var r=st.executeQuery("SELECT next_search_at FROM product_settings WHERE id=1")){r.next();assertThat(r.getObject(1)).isNull();}
            try(var r=st.executeQuery("SELECT supplier_id FROM product_supplier_selection WHERE product_id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT manual_store_id,last_price FROM product_supplier WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(99);assertThat(r.getLong(2)).isEqualTo(94000);}
        }

        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("INSERT INTO product_refresh_run(id,status,trigger_type,created_at,login_retry_count,block_reason,next_retry_at) VALUES(901,'RETRY_WAIT','MANUAL',NOW(6),2,'LOGIN_REQUIRED','2026-09-30 12:30:00')");
        }
        var recovery=Flyway.configure().dataSource(url,user,password).target("21").load();assertThat(recovery.migrate().migrationsExecuted).isEqualTo(1);recovery.validate();
        assertThat(recovery.info().current().getVersion().getVersion()).isEqualTo("21");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT status,search_retry_count,next_retry_at FROM product_refresh_run WHERE id=901")){r.next();assertThat(r.getString(1)).isEqualTo("RETRY_WAIT");assertThat(r.getInt(2)).isEqualTo(2);assertThat(r.getTimestamp(3).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-09-30T12:30:00"));}
            try(var r=st.executeQuery("SELECT search_gate_run_id,search_cooldown_until,search_manual_resume_required FROM product_settings WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(901);assertThat(r.getTimestamp(2).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-09-30T12:30:00"));assertThat(r.getBoolean(3)).isFalse();}
            try(var r=st.executeQuery("SELECT status,next_retry_at FROM product_refresh_run WHERE id=900")){r.next();assertThat(r.getString(1)).isEqualTo("BLOCKED");assertThat(r.getObject(2)).isNull();}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM product_search_attempt")){r.next();assertThat(r.getInt(1)).isZero();}
        }
        var owned=Flyway.configure().dataSource(url,user,password).target("22").load();
        assertThat(owned.migrate().migrationsExecuted).isEqualTo(1);owned.validate();
        assertThat(owned.info().current().getVersion().getVersion()).isEqualTo("22");
        assertThat(owned.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            for(String table:java.util.List.of("inventory_purchase","inventory_item","inventory_movement")) {
                try(var r=st.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();assertThat(r.getInt(1)).isZero();}
            }
            try(var r=st.executeQuery("SELECT supplier_id FROM product_supplier_selection WHERE product_id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(1);}
        }
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("INSERT INTO inventory_purchase(id,purchased_on,payment_method,payment_alias,private_note,created_by,request_id,request_hash,created_at,updated_at) VALUES(100,'2026-10-01','기존 결제 수단','기존 카드','기존 메모',1,'legacy-order','legacy-hash',NOW(6),NOW(6))");
            st.executeUpdate("INSERT INTO inventory_item(id,purchase_id,product_id,origin_product_id,purchased_code,purchased_name,original_ordered_quantity,ordered_quantity,on_hand,pending,unit_price,created_at,updated_at) VALUES(100,100,1,1,'SNAPSHOT','기존 구매명',3,3,2,1,12345,NOW(6),NOW(6))");
            st.executeUpdate("INSERT INTO inventory_movement(id,item_id,kind,quantity,hand_delta,pending_delta,occurred_at,actor_id,request_id,request_hash,created_at) VALUES(100,100,'RECEIPT',2,2,-2,NOW(6),1,'legacy-receipt','legacy-hash',NOW(6))");
        }
        var payment=Flyway.configure().dataSource(url,user,password).target("23").load();assertThat(payment.migrate().migrationsExecuted).isEqualTo(1);payment.validate();
        assertThat(payment.info().current().getVersion().getVersion()).isEqualTo("23");assertThat(payment.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT payment_method,payment_alias,private_note,payment_method_id,payment_amount FROM inventory_purchase WHERE id=100")){r.next();assertThat(r.getString(1)).isEqualTo("기존 결제 수단");assertThat(r.getString(2)).isEqualTo("기존 카드");assertThat(r.getString(3)).isEqualTo("기존 메모");assertThat(r.getObject(4)).isNull();assertThat(r.getObject(5)).isNull();}
            try(var r=st.executeQuery("SELECT purchased_code,purchased_name,on_hand,pending,unit_price FROM inventory_item WHERE id=100")){r.next();assertThat(r.getString(1)).isEqualTo("SNAPSHOT");assertThat(r.getString(2)).isEqualTo("기존 구매명");assertThat(r.getLong(3)).isEqualTo(2);assertThat(r.getLong(4)).isEqualTo(1);assertThat(r.getLong(5)).isEqualTo(12345);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM inventory_movement WHERE id=100 AND kind='RECEIPT'")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM inventory_payment_method WHERE deleted_at IS NULL")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
        }
        var deletion=Flyway.configure().dataSource(url,user,password).target("24").load();assertThat(deletion.migrate().migrationsExecuted).isEqualTo(1);deletion.validate();
        assertThat(deletion.info().current().getVersion().getVersion()).isEqualTo("24");assertThat(deletion.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT deleted_at,deleted_by,delete_request_id,delete_request_hash FROM inventory_purchase WHERE id=100")){assertThat(r.next()).isTrue();for(int col=1;col<=4;col++)assertThat(r.getObject(col)).isNull();}
            try(var r=st.executeQuery("SELECT on_hand,pending FROM inventory_item WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM inventory_movement WHERE id=100")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
        var productInformation=Flyway.configure().dataSource(url,user,password).target("25").load();
        assertThat(productInformation.migrate().migrationsExecuted).isEqualTo(1);productInformation.validate();
        assertThat(productInformation.info().current().getVersion().getVersion()).isEqualTo("25");assertThat(productInformation.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inventory_item' AND column_name IN ('purchased_code','purchased_name')")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=st.executeQuery("SELECT product_id,origin_product_id,on_hand,pending,unit_price FROM inventory_item WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(1);assertThat(r.getLong(2)).isEqualTo(1);assertThat(r.getLong(3)).isEqualTo(2);assertThat(r.getLong(4)).isEqualTo(1);assertThat(r.getLong(5)).isEqualTo(12345);}
            try(var r=st.executeQuery("SELECT request_hash FROM inventory_purchase WHERE id=100")){r.next();assertThat(r.getString(1)).isEqualTo("legacy-hash");}
            try(var r=st.executeQuery("SELECT quantity,hand_delta,pending_delta,request_hash FROM inventory_movement WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(2);assertThat(r.getLong(3)).isEqualTo(-2);assertThat(r.getString(4)).isEqualTo("legacy-hash");}
        }
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){st.executeUpdate("UPDATE inventory_item SET location='legacy location' WHERE id=100");}
        var noLocation=Flyway.configure().dataSource(url,user,password).target("26").load();assertThat(noLocation.migrate().migrationsExecuted).isEqualTo(1);noLocation.validate();assertThat(noLocation.info().current().getVersion().getVersion()).isEqualTo("26");assertThat(noLocation.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inventory_item' AND column_name='location'")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=st.executeQuery("SELECT product_id,on_hand,pending,unit_price FROM inventory_item WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(1);assertThat(r.getLong(2)).isEqualTo(2);assertThat(r.getLong(3)).isEqualTo(1);assertThat(r.getLong(4)).isEqualTo(12345);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM inventory_movement WHERE id=100")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
        var ready=Flyway.configure().dataSource(url,user,password).target("27").load();
        assertThat(ready.migrate().migrationsExecuted).isEqualTo(1);ready.validate();
        assertThat(ready.info().current().getVersion().getVersion()).isEqualTo("27");assertThat(ready.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("INSERT INTO inventory_request_lock(request_id) VALUES('10000000-1000-4000-8000-100000000000')");
            try(var r=st.executeQuery("SELECT COUNT(*) FROM inventory_request_lock")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
            try(var r=st.executeQuery("SELECT product_id,on_hand,pending,unit_price FROM inventory_item WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(1);assertThat(r.getLong(2)).isEqualTo(2);assertThat(r.getLong(3)).isEqualTo(1);assertThat(r.getLong(4)).isEqualTo(12345);}
            try(var r=st.executeQuery("SELECT quantity,hand_delta,pending_delta,request_hash FROM inventory_movement WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(2);assertThat(r.getLong(3)).isEqualTo(-2);assertThat(r.getString(4)).isEqualTo("legacy-hash");}
        }
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("INSERT INTO operation_audit_log(id,user_role,user_id,email,event_type,success,created_at) VALUES(100,'ADMIN',1,'owner@example.com','EXISTING_AUDIT',TRUE,NOW(6))");
        }
        var audit=Flyway.configure().dataSource(url,user,password).target("28").load();
        assertThat(audit.migrate().migrationsExecuted).isEqualTo(1);audit.validate();
        assertThat(audit.info().current().getVersion().getVersion()).isEqualTo("28");assertThat(audit.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT execution_source,user_id,email FROM operation_audit_log WHERE id=100")){r.next();assertThat(r.getString(1)).isEqualTo("HTTP");assertThat(r.getLong(2)).isEqualTo(1);assertThat(r.getString(3)).isEqualTo("owner@example.com");}
            st.executeUpdate("INSERT INTO operation_audit_log(id,event_type,execution_source,success,created_at) VALUES(101,'BACKGROUND_TEST','BACKGROUND',TRUE,NOW(6))");
            assertThatThrownBy(()->st.executeUpdate("INSERT INTO operation_audit_log(id,event_type,success,created_at) VALUES(102,'INVALID_HTTP',TRUE,NOW(6))")).isInstanceOf(java.sql.SQLException.class);
            try(var r=st.executeQuery("SELECT on_hand,pending,unit_price FROM inventory_item WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(1);assertThat(r.getLong(3)).isEqualTo(12345);}
        }
        // The V28 fixture is an existing installation; only explicit cancellation clears active work.
        var split=Flyway.configure().dataSource(url,user,password).initSql("SET SESSION time_zone='+00:00'").target("29").load();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            st.executeUpdate("UPDATE product_refresh_run SET status='CANCELLED' WHERE status IN ('RUNNING','PAUSED','BLOCKED','RETRY_WAIT')");
            st.executeUpdate("UPDATE supplier_stock_lookup SET status='CANCELLED' WHERE status IN ('PENDING','RUNNING','BLOCKED')");
            st.executeUpdate("UPDATE product_settings SET worker_owner=NULL,worker_until=NULL,revision=17,preference_revision=23,search_gate_version=31,next_search_at='2026-10-01 11:22:33.123456' WHERE id=1");
            st.executeUpdate("UPDATE catalog_product SET revision=37,lookup_revision=41,change_version=43,search_query='수동 검색 원문',latest_result=CAST('null' AS JSON),last_good_result=JSON_OBJECT('status','SUCCESS','price',0),latest_at='2026-10-01 11:22:33.123456',image_url='https://example.com/preserved.png' WHERE id=1");
            st.executeUpdate("UPDATE catalog_product SET merged_into=1 WHERE id=3");
            st.executeUpdate("INSERT INTO product_refresh_run(id,status,trigger_type,created_at) VALUES(9876,'PAUSED','MANUAL',NOW(6))");
        }
        assertThatThrownBy(split::migrate).hasStackTraceContaining("explicitly cancel active refresh");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='procurement_product'")){r.next();assertThat(r.getInt(1)).isZero();}
            st.executeUpdate("UPDATE product_refresh_run SET status='CANCELLED' WHERE id=9876");
        }
        // A UTC migration session must reject live Korea-time leases, but allow expired ones.
        setWorkerLease(url,user,password,BusinessTime.koreaNow().plusMinutes(10),false);
        split.repair();
        assertThatThrownBy(split::migrate).hasStackTraceContaining("wait for their lease to expire");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='procurement_product'")){r.next();assertThat(r.getInt(1)).isZero();}
        }
        setWorkerLease(url,user,password,BusinessTime.koreaNow().minusMinutes(5),false);
        split.repair();assertThat(split.migrate().migrationsExecuted).isEqualTo(1);split.validate();
        // Verification refuses destructive cleanup if even one migrated value was changed.
        var remove=Flyway.configure().dataSource(url,user,password).initSql("SET SESSION time_zone='+00:00'").target("30").load();
        setWorkerLease(url,user,password,BusinessTime.koreaNow().plusMinutes(10),true);
        assertThatThrownBy(remove::migrate).hasStackTraceContaining("wait for their lease to expire");
        LocalDateTime expiredLease=BusinessTime.koreaNow().minusMinutes(5);
        setWorkerLease(url,user,password,expiredLease,true);
        remove.repair();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){st.executeUpdate("UPDATE procurement_product SET lookup_revision=42 WHERE product_id=1");}
        assertThatThrownBy(remove::migrate).hasStackTraceContaining("Value/reference mismatch");
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT lookup_revision FROM catalog_product WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(41);}
            st.executeUpdate("UPDATE procurement_product SET lookup_revision=41 WHERE product_id=1");
        }
        remove.repair();assertThat(remove.migrate().migrationsExecuted).isEqualTo(1);remove.validate();
        assertThat(remove.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()) {
            try(var r=st.executeQuery("SELECT COUNT(*) FROM procurement_product")){r.next();assertThat(r.getInt(1)).isEqualTo(5);}
            try(var r=st.executeQuery("SELECT revision,lookup_revision,change_version,search_query,JSON_TYPE(latest_result),JSON_EXTRACT(last_good_result,'$.price'),latest_at,image_url FROM catalog_product p JOIN procurement_product q ON q.product_id=p.id WHERE p.id=1")){
                r.next();assertThat(r.getLong(1)).isEqualTo(37);assertThat(r.getLong(2)).isEqualTo(41);assertThat(r.getLong(3)).isEqualTo(43);assertThat(r.getString(4)).isEqualTo("수동 검색 원문");assertThat(r.getString(5)).isEqualTo("NULL");assertThat(r.getString(6)).isEqualTo("0");assertThat(r.getTimestamp(7).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-01T11:22:33.123456"));assertThat(r.getString(8)).isEqualTo("https://example.com/preserved.png");
            }
            try(var r=st.executeQuery("SELECT revision,preference_revision FROM procurement_settings WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(17);assertThat(r.getLong(2)).isEqualTo(23);}
            try(var r=st.executeQuery("SELECT search_gate_version,next_search_at FROM procurement_runtime WHERE id=1")){r.next();assertThat(r.getLong(1)).isEqualTo(31);assertThat(r.getTimestamp(2).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-01T11:22:33.123456"));}
            try(var r=st.executeQuery("SELECT worker_owner,worker_until FROM procurement_runtime WHERE id=1")){r.next();assertThat(r.getString(1)).isEqualTo("stopped-worker");assertThat(r.getObject(2,LocalDateTime.class)).isEqualTo(expiredLease);}
            try(var r=st.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='catalog_product' AND column_name IN ('search_query','lookup_revision','latest_result','image_url')")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=st.executeQuery("SELECT on_hand,pending,unit_price FROM inventory_item WHERE id=100")){r.next();assertThat(r.getLong(1)).isEqualTo(2);assertThat(r.getLong(2)).isEqualTo(1);assertThat(r.getLong(3)).isEqualTo(12345);}
        }
    }

    private static void setWorkerLease(String url,String user,String password,LocalDateTime until,boolean copied) throws Exception {
        try(var connection=DriverManager.getConnection(url,user,password)) {
            for(String table:copied ? java.util.List.of("product_settings","procurement_runtime") : java.util.List.of("product_settings")) {
                try(var statement=connection.prepareStatement("UPDATE "+table+" SET worker_owner='stopped-worker',worker_until=? WHERE id=1")) {
                    statement.setObject(1,until);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }
        }
    }
}
