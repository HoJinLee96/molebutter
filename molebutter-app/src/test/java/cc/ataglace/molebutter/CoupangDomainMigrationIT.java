package cc.ataglace.molebutter;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import db.migration.V46__coupang_domain;
import cc.ataglace.molebutter.app.internal.maintenance.SalesSchema;

class CoupangDomainMigrationIT {
    @Test void populatedV45PreservesRowsEvidenceTokensAndBindingsAndRejectsUnsafeState()throws Exception{
        String main=System.getenv("MOLEBUTTER_TEST_DB_URL");if(main==null||!main.contains("/molebutter_test?"))throw new IllegalStateException("Run scripts/test-integration.sh");
        String url=main.replace("/molebutter_test?","/molebutter_domain_test?"),user="test_migrator",password="isolated-test-migration-password";
        Flyway.configure().dataSource(url,user,password).target("45").load().migrate();
        String prepared="{\"accountKey\":\"account\",\"market\":\"COUPANG\",\"schemaVersion\":1,\"mapping\":{\"accountKey\":\"account\",\"sellerProductId\":\"123\",\"options\":[],\"channelProductId\":null},\"steps\":[],\"changes\":[],\"preparedAt\":\"2026-10-15T00:00:00Z\",\"expectedSkus\":[]}";
        String mapping="{\"accountKey\":\"account\",\"sellerProductId\":\"123\",\"options\":[],\"channelProductId\":null}";
        try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
            st.executeUpdate("INSERT INTO `user`(id,email,name,password_hash,user_role,user_status,created_at) VALUES(1,'preserve@example.test','preserved user','fixture','ADMIN','ACTIVE',NOW(6))");
            st.executeUpdate("INSERT INTO marketplace_draft(id,document_json,created_by,updated_by,created_at,updated_at,editor_kind) VALUES(9,'{}',1,1,NOW(6),NOW(6),'MARKET_ONLY')");
            st.executeUpdate("INSERT INTO marketplace_submission_preview(id,draft_id,draft_revision,created_by,requested,document_json,prepared_json,created_at,expires_at) VALUES(10,9,0,1,FALSE,'{}','{\"market\":\"NAVER\"}',NOW(6),NOW(6))");
            st.executeUpdate("INSERT INTO marketplace_execution(id,preview_id,draft_id,draft_revision,idempotency_key,created_by,status,created_at,updated_at) VALUES(11,10,9,0,'00000000-0000-4000-8000-000000000011',1,'UNKNOWN',NOW(6),NOW(6))");
            assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).target("46").load().migrate()).hasStackTraceContaining("Non-Coupang");assertThat(SalesSchema.existing(c)).hasSize(32);
            try(var update=c.prepareStatement("UPDATE marketplace_submission_preview SET prepared_json=? WHERE id=10")){update.setString(1,prepared);update.executeUpdate();}
            Flyway.configure().dataSource(url,user,password).load().repair();
            st.executeUpdate("UPDATE marketplace_execution SET status='RUNNING' WHERE id=11");
            assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).target("46").load().migrate()).hasStackTraceContaining("active Coupang jobs");assertThat(SalesSchema.existing(c)).hasSize(32);
            st.executeUpdate("UPDATE marketplace_execution SET status='UNKNOWN' WHERE id=11");Flyway.configure().dataSource(url,user,password).load().repair();
            st.executeUpdate("UPDATE marketplace_execution SET status='QUEUED' WHERE id=11");
            assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).target("46").load().migrate()).hasStackTraceContaining("active Coupang jobs");
            st.executeUpdate("UPDATE marketplace_execution SET status='UNKNOWN' WHERE id=11");Flyway.configure().dataSource(url,user,password).load().repair();
            st.executeUpdate("INSERT INTO marketplace_order_account_lock(account_key,lease_owner,lease_until) VALUES('preserved-lock','owner',DATE_ADD(NOW(6),INTERVAL 1 HOUR))");
            assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).target("46").load().migrate()).hasStackTraceContaining("active Coupang account leases");
            st.executeUpdate("UPDATE marketplace_order_account_lock SET lease_until='2020-01-01' WHERE account_key='preserved-lock'");Flyway.configure().dataSource(url,user,password).load().repair();
            st.executeUpdate("INSERT INTO marketplace_submission_account(account_key,lease_owner,lease_until) VALUES(REPEAT('b',64),'owner',DATE_ADD(NOW(6),INTERVAL 1 HOUR))");
            assertThatThrownBy(()->Flyway.configure().dataSource(url,user,password).target("46").load().migrate()).hasStackTraceContaining("active Coupang account leases");
            st.executeUpdate("UPDATE marketplace_submission_account SET lease_until='2020-01-01'");Flyway.configure().dataSource(url,user,password).load().repair();
            st.executeUpdate("INSERT INTO marketplace_order(id,account_key,order_id,collected_at) VALUES(30,'preserved-lock','90071992547409930001',NOW(6))");
            st.executeUpdate("INSERT INTO marketplace_order_item(id,parent_id,shipment_box_id,sequence_no,vendor_item_id,status,product_name,snapshot_json) VALUES(31,30,'90071992547409930002','1','90071992547409930003','ACCEPT','보존 상품','{\"quantity\":0}')");
            st.executeUpdate("INSERT INTO marketplace_order_claim(id,account_key,claim_type,claim_id,order_id,shipment_box_id,vendor_item_id,snapshot_json) VALUES(32,'preserved-lock','RETURN','90071992547409930004','90071992547409930001','90071992547409930002','90071992547409930003','{\"quantity\":0}')");
            String session="{\"id\":\"20\",\"draftId\":\"9\",\"revision\":0,\"expiresAt\":\"2030-01-01T00:00:00Z\",\"target\":{\"mode\":\"UPDATE\",\"status\":\"READY\"}}";
            try(var insert=c.prepareStatement("INSERT INTO marketplace_edit_session(id,draft_id,draft_revision,created_by,session_json,target_bindings_json,created_at,expires_at) VALUES(20,9,0,1,?,?,NOW(6),'2030-01-01')")){insert.setString(1,session);insert.setString(2,"{\"targets\":{\"COUPANG\":{\"account\":\"account\",\"mapping\":"+mapping+",\"listingVersion\":1}}}");insert.executeUpdate();}
            st.executeUpdate("INSERT INTO marketplace_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('00000000-0000-4000-8000-000000000001','original.png','image/png',100,100,100,1,NOW(6))");
            st.executeUpdate("INSERT INTO marketplace_draft_asset(draft_id,asset_id) VALUES(9,'00000000-0000-4000-8000-000000000001')");
            st.executeUpdate("INSERT INTO marketplace_asset_publication(asset_id,token,created_at) VALUES('00000000-0000-4000-8000-000000000001',REPEAT('a',64),NOW(6))");
            var before=V46__coupang_domain.snapshot(c);Flyway.configure().dataSource(url,user,password).target("46").load().migrate();assertThat(V46__coupang_domain.snapshot(c)).isEqualTo(before);
            assertThat(SalesSchema.existing(c)).isEmpty();assertThat(count(c,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,8)='coupang_'" )).isEqualTo(32);
            try(var r=st.executeQuery("SELECT prepared_json FROM coupang_submission_preview WHERE id=10")){r.next();assertThat(r.getString(1)).isEqualTo(prepared);}
            try(var r=st.executeQuery("SELECT binding_json FROM coupang_edit_session WHERE id=20")){r.next();assertThat(r.getString(1)).contains("\"account\":\"account\"").doesNotContain("targets","channelProductId");}
            assertThat(count(c,"SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name LIKE '%marketplace%'" )).isZero();
            assertThat(count(c,"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name LIKE '%marketplace%'" )).isZero();
            Flyway.configure().dataSource(url,user,password).target("46").load().migrate();assertThat(V46__coupang_domain.snapshot(c)).isEqualTo(before);
        }
    }
    private long count(Connection c,String sql)throws Exception{try(var st=c.createStatement();var r=st.executeQuery(sql)){r.next();return r.getLong(1);}}
}
