package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.domain.*;
import cc.ataglace.molebutter.repository.UserRepository;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "product.refresh.worker-enabled=false", "spring.config.import=classpath:bootstrap-admin-test.properties",
    "spring.datasource.username=test_app", "spring.datasource.password=isolated-test-app-password",
    "spring.flyway.user=test_migrator", "spring.flyway.password=isolated-test-migration-password",
    "spring.data.redis.host=127.0.0.1", "spring.data.redis.password=", "mail.provider=test",
    "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes", "auth.cookie.secure=false",
    "auth.signin.rate-limit-max-attempts=1000"
})
@ActiveProfiles("bootstrap-admin")
@Import(AuthenticationFlowIT.MailConfiguration.class)
class ProductFlowIT {
    @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);}
    @Autowired cc.ataglace.molebutter.service.notification.NotificationService notifications;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired cc.ataglace.molebutter.service.attendance.AttendanceService attendance;
    @Autowired UserRepository users; @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @LocalServerPort int port;
    Long actor;
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM user_notification");jdbc.update("DELETE FROM notification_event");
        actor=users.findByEmail("admin@example.com").orElseThrow().getId();
    }
    void notificationTransaction(Runnable work){new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->work.run());}
    User notificationUser(UserRole role){return users.saveAndFlush(User.builder().name("알림 검증").email("notice-"+UUID.randomUUID()+"@example.com").passwordHash(encoder.encode("Product123!")).role(role).status(UserStatus.ACTIVE).build());}
    void notice(String key,Long recipient){notifications.publish(key,"TEST","INFO","검증 결과","처리 완료","PRODUCTS",null,List.of(recipient));}
    @Test void notificationsAreAtomicDeduplicatedAndOnlyExplicitlyDismissed(){
        assertThatThrownBy(()->notificationTransaction(()->{notice("rollback",actor);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.summary(actor).count()).isZero();
        notificationTransaction(()->notice("one",actor));notificationTransaction(()->notice("one",actor));
        var first=notifications.list(actor,null,20).items().getFirst();
        assertThat(notifications.summary(actor).count()).isEqualTo(1);assertThat(notifications.target(actor,Long.parseLong(first.id())).url()).isEqualTo("/products");
        assertThat(notifications.summary(actor).count()).isEqualTo(1);
        notifications.dismiss(actor,Long.parseLong(first.id()));notifications.dismiss(actor,Long.parseLong(first.id()));notificationTransaction(()->notice("one",actor));
        assertThat(notifications.summary(actor).count()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_event",Long.class)).isEqualTo(1);
    }
    @Test void notificationCursorRemainsStableWhenNewEventsArrive(){
        for(int i=0;i<25;i++){String key="page-"+i;notificationTransaction(()->notice(key,actor));}
        var first=notifications.list(actor,null,20);assertThat(first.items()).hasSize(20);assertThat(first.nextCursor()).isNotNull();
        notificationTransaction(()->notice("new",actor));
        var second=notifications.list(actor,first.nextCursor(),20);assertThat(second.items()).hasSize(5);assertThat(second.nextCursor()).isNull();
        var ids=new HashSet<String>();first.items().forEach(n->ids.add(n.id()));second.items().forEach(n->assertThat(ids.add(n.id())).isTrue());assertThat(ids).hasSize(25);
        assertThat(notifications.summary(actor).count()).isEqualTo(26);
    }
    @Test void notificationPermissionsCsrfDeletedTargetsAndRevocation()throws Exception {
        var staff=notificationUser(UserRole.PRODUCT);var other=notificationUser(UserRole.PRODUCT);long product=com.github.f4b6a3.tsid.TsidCreator.getTsid().toLong();
        jdbc.update("INSERT INTO catalog_product(id,product_code,created_at,updated_at) VALUES(?,'ABCD6F123BK',NOW(6),NOW(6))",product);
        notificationTransaction(()->notifications.publish("product-notice","TEST","INFO","상품 결과","비공개 내용","PRODUCT",product,List.of(staff.getId())));
        String id=notifications.summary(staff.getId()).latestId();var browser=login(staff);var stranger=login(other);
        status(stranger.get("/api/notifications/"+id+"/target"),404);status(new Browser().get("/api/notifications/summary"),401);
        browser.csrf();status(browser.send("DELETE","/api/notifications/"+id,null,null),403);
        stranger.csrf();status(stranger.send("DELETE","/api/notifications/"+id,null,stranger.csrf),404);
        assertThat(notifications.target(staff.getId(),Long.parseLong(id)).url()).contains(Long.toString(product));
        jdbc.update("UPDATE catalog_product SET deleted_at=NOW(6) WHERE id=?",product);assertThat(notifications.target(staff.getId(),Long.parseLong(id)).url()).isNull();
        jdbc.update("UPDATE `user` SET user_role='VIEWER' WHERE id=?",staff.getId());
        var masked=notifications.list(staff.getId(),null,20).items().getFirst();assertThat(masked.title()).isEqualTo("접근 권한 없음");assertThat(masked.message()).isEmpty();
        notifications.dismiss(staff.getId(),Long.parseLong(id));assertThat(notifications.summary(staff.getId()).count()).isZero();
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",staff.getId());assertThatThrownBy(()->notifications.summary(staff.getId())).isInstanceOf(cc.ataglace.molebutter.exception.BusinessException.class);
    }
    @Test void correctionEventsCommitWithBusinessChangesAndNotifyOwnerAndReviewer(){
        var owner=notificationUser(UserRole.VIEWER);var date=LocalDate.of(2025,1,2);
        var request=new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.CorrectionRequest(date,null,null,date.atTime(9,0),date.atTime(18,0),List.of(),"누락 보완");
        var c=attendance.requestCorrection(owner.getId(),request);
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(1);
        attendance.review(actor,Long.parseLong(c.id()),true,new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.ReviewRequest(c.revision(),null));
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(2);
        String ownerNotice=notifications.summary(owner.getId()).latestId(),adminNotice=notifications.summary(actor).latestId();
        assertThat(notifications.target(owner.getId(),Long.parseLong(ownerNotice)).url()).isEqualTo("/attendance?correction="+c.id());
        assertThat(notifications.target(actor,Long.parseLong(adminNotice)).url()).isEqualTo("/attendance-manage?correction="+c.id());
        var cancelled=attendance.requestCorrection(owner.getId(),new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.CorrectionRequest(date.plusDays(1),null,null,date.plusDays(1).atTime(9,0),date.plusDays(1).atTime(18,0),List.of(),"누락"));
        attendance.cancel(owner.getId(),Long.parseLong(cancelled.id()),new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.ReviewRequest(cancelled.revision(),null));
        assertThat(notifications.list(owner.getId(),null,20).items().getFirst().type()).isEqualTo("CORRECTION_CANCELLED");
    }
    @Test void correctionFailureIsSavedAfterRollbackAndDeduplicatedButValidationIsNot()throws Exception{
        var owner=notificationUser(UserRole.VIEWER);var date=LocalDate.of(2025,2,2);
        var c=attendance.requestCorrection(owner.getId(),new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.CorrectionRequest(date,null,null,date.atTime(9,0),date.atTime(18,0),List.of(),"누락"));
        var browser=login(owner);browser.operationId=UUID.randomUUID().toString();
        String route="/api/attendance/corrections/"+c.id()+"/cancel";
        var invalid=new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.ReviewRequest(999L,null);
        status(browser.post(route,invalid),409);status(browser.post(route,invalid),409);
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(2);
        assertThat(notifications.list(owner.getId(),null,20).items()).filteredOn(n->n.type().equals("OPERATION_FAILED")).hasSize(1);
        status(browser.get("/api/notifications?size=0"),400);status(browser.post(route,Map.of("revision",-1)),400);
        assertThat(notifications.summary(owner.getId()).count()).isEqualTo(2);
        attendance.cancel(owner.getId(),Long.parseLong(c.id()),new cc.ataglace.molebutter.dto.attendance.AttendanceDtos.ReviewRequest(c.revision(),null));
        assertThat(notifications.list(owner.getId(),null,20).items().getFirst().type()).isEqualTo("CORRECTION_CANCELLED");
    }
    Browser login(User u)throws Exception{String password=u.getEmail().equals("admin@example.com")?"IntegrationAdmin123!":"Product123!";Browser b=new Browser();status(b.post("/api/auth/signin",Map.of("email",u.getEmail(),"password",password)),200);b.csrf=null;return b;}
    void status(HttpResponse<String> r,int expected){assertThat(r.statusCode()).withFailMessage("HTTP %s: %s",r.statusCode(),r.body()).isEqualTo(expected);}
    class Browser {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final Map<String, String> cookies = new HashMap<>();
        String csrf;String operationId;
        void csrf() throws Exception {
            var response = get("/api/auth/csrf");
            status(response, 200);
            csrf = json.readTree(response.body()).path("data").path("token").asText();
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> post(String path, Object body) throws Exception {
            if (csrf == null) csrf();
            var response = send("POST", path, body, csrf);
            if (response.statusCode() == 403 && json.readTree(response.body()).path("code").asText()
                    .equals("INVALID_CSRF_TOKEN")) {
                csrf();
                return send("POST", path, body, csrf);
            }
            return response;
        }
        HttpResponse<String> send(String method, String path, Object body, String csrfHeader) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            if (!cookies.isEmpty()) request.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            if (csrfHeader != null) request.header("X-XSRF-TOKEN", csrfHeader);
            if(operationId!=null)request.header("X-Operation-Id",operationId);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String value : response.headers().allValues("set-cookie")) {
                for (HttpCookie cookie : HttpCookie.parse(value)) {
                    if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                    else cookies.put(cookie.getName(), cookie.getValue());
                }
            }
            return response;
        }

    }
}
