package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cc.ataglace.molebutter.identity.internal.AdminBootstrap;
import cc.ataglace.molebutter.identity.internal.User;
import cc.ataglace.molebutter.identity.api.UserRole;
import cc.ataglace.molebutter.identity.api.UserStatus;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.identity.internal.UserRepository;
import cc.ataglace.molebutter.identity.api.EmailSender;
import cc.ataglace.molebutter.identity.api.KeyValueStore;
import cc.ataglace.molebutter.identity.internal.DefaultAuthTokenService;
import cc.ataglace.molebutter.identity.api.EmailVerificationPurpose;
import cc.ataglace.molebutter.identity.internal.DefaultEmailVerificationService;
import cc.ataglace.molebutter.identity.internal.DefaultJwtService;
import tools.jackson.databind.ObjectMapper;

/** scripts/test-integration.sh가 준비한 임시 MySQL/Redis에만 연결한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product.refresh.worker-enabled=false", "spring.config.import=classpath:bootstrap-admin-test.properties", "spring.datasource.username=test_app",
        "spring.datasource.password=isolated-test-app-password",
        "spring.flyway.user=test_migrator", "spring.flyway.password=isolated-test-migration-password",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.password=", "mail.provider=test",
        "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes", "auth.cookie.secure=false",
        "auth.signin.rate-limit-max-attempts=1000", "cloudflare.r2.enabled=false"
})
@ActiveProfiles("bootstrap-admin")
@Import(AuthenticationFlowIT.MailConfiguration.class)
class AuthenticationFlowIT {
    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        if(!"true".equals(System.getenv("MOLEBUTTER_COUPANG_LIVE"))){
            registry.add("marketplace.coupang.vendor-id",()->"");
            registry.add("marketplace.coupang.access-key",()->"");
            registry.add("marketplace.coupang.secret-key",()->"");
        }
        String url = System.getenv("MOLEBUTTER_TEST_DB_URL");
        if (url == null || !url.contains("/molebutter_test?")) {
            throw new IllegalStateException("Run with scripts/test-integration.sh (isolated test database required)");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.data.redis.port", () -> System.getenv("MOLEBUTTER_TEST_REDIS_PORT"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailConfiguration {
        @Bean CapturedMail capturedMail() { return new CapturedMail(); }
    }

    static class CapturedMail implements EmailSender {
        final Map<String, String> codes = new ConcurrentHashMap<>();
        final java.util.Set<String> failOnce = ConcurrentHashMap.newKeySet();
        @Override public void send(String to, String subject, String body) {
            if (failOnce.remove(to)) throw new IllegalStateException("Simulated provider failure");
            codes.put(to, body.substring(body.indexOf(": ") + 2, body.indexOf('\n')));
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CapturedMail mail;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired KeyValueStore store;
    @Autowired DefaultAuthTokenService tokens;
    @Autowired DefaultJwtService jwt;
    @Autowired DefaultEmailVerificationService verification;
    @Autowired AdminBootstrap bootstrap;
    @Autowired ApplicationContext context;
    @Autowired cc.ataglace.molebutter.operations.internal.DefaultOperationAuditService operations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void r2InfrastructureStartsDisabledWithoutACloudClient() {
        var storage = context.getBean(cc.ataglace.molebutter.storage.api.ObjectStorage.class);
        assertThat(context.getBeansOfType(software.amazon.awssdk.services.s3.S3Client.class)).isEmpty();
        assertThatThrownBy(() -> storage.head(cc.ataglace.molebutter.storage.api.StorageArea.PRIVATE, "test/file.png"))
                .isInstanceOf(cc.ataglace.molebutter.storage.api.StorageException.class)
                .extracting(failure -> ((cc.ataglace.molebutter.storage.api.StorageException) failure).code())
                .isEqualTo(cc.ataglace.molebutter.storage.api.StorageException.Code.NOT_CONFIGURED);
    }

    @Test
    void marketplaceRequiresCurrentAdministrator() throws Exception {
        Browser admin=admin();assertStatus(admin.get("/marketplaces"),200);
        String cancelPath="/api/marketplaces/coupang/requests/"+java.util.UUID.randomUUID()+"/cancel";
        assertStatus(admin.post(cancelPath,Map.of()),200);
        assertStatus(admin.post("/api/marketplaces/coupang/requests/invalid/cancel",Map.of()),400);
        if(!"true".equals(System.getenv("MOLEBUTTER_COUPANG_LIVE")))
            assertStatus(admin.get("/api/marketplaces/coupang/products"),503,"COUPANG_CONFIGURATION");
        assertStatus(admin.get("/api/marketplaces/coupang/products?maxPerPage=11"),400);
        assertStatus(admin.get("/api/marketplaces/coupang/products/invalid"),400);
        String email=uniqueEmail();Browser staff=approvedUser(email);
        assertStatus(staff.get("/api/marketplaces/coupang/products"),403);
        assertStatus(staff.get("/api/marketplaces/coupang/products/123"),403);
        for(String path:List.of("/marketplaces/coupang/products/new","/marketplaces/coupang/products/123/edit","/api/marketplaces/coupang/products/123/edit-data","/api/marketplaces/coupang/categories/123/rules"))assertStatus(staff.get(path),403);
        assertStatus(staff.get("/marketplaces"),403);
        assertStatus(staff.post(cancelPath,Map.of()),403);
        assertThat(staff.get("/").body()).doesNotContain("/marketplaces");
        long id=users.findByEmail(email).orElseThrow().getId();
        assertStatus(admin.post("/api/admin/users/"+id+"/role",Map.of("role","PRODUCT")),200);
        assertStatus(staff.post("/api/auth/signin",credentials(email)),200);
        assertStatus(staff.get("/api/marketplaces/coupang/products"),403);
        assertStatus(staff.get("/api/marketplaces/coupang/products/123"),403);
        for(String path:List.of("/marketplaces/coupang/products/new","/marketplaces/coupang/products/123/edit","/api/marketplaces/coupang/products/123/edit-data","/api/marketplaces/coupang/categories/123/rules"))assertStatus(staff.get(path),403);
        assertStatus(staff.get("/marketplaces"),403);
        assertStatus(staff.post(cancelPath,Map.of()),403);
        assertThat(staff.get("/").body()).doesNotContain("/marketplaces");
    }

    @Test
    void productImageWorkspaceRequiresProductAccessAndCsrf() throws Exception {
        Browser anonymous = new Browser();
        assertStatus(anonymous.get("/api/product-images/size-guide/templates"), 401, "UNAUTHORIZED");
        String uploadPath = "/api/product-images/products/upload";
        String uploadJobPath = uploadPath + "/jobs/" + UUID.randomUUID();
        Map<String, Object> uploadRequest = Map.of("requestId", UUID.randomUUID().toString(),
                "productCode", "TEST-BAG", "brandCode", "DAKS", "uploadProductCode", "CUSTOM-BAG",
                "images", List.of(Map.of("imageIndex", 0, "sourceImageUrl", "https://img.lfmall.co.kr/test.png")));
        assertStatus(anonymous.get(uploadJobPath), 401, "UNAUTHORIZED");
        Browser admin = admin();
        assertStatus(admin.get("/product-images"), 200);
        assertStatus(admin.get("/api/product-images/size-guide/templates"), 200);
        assertStatus(admin.send("POST", "/api/product-images/products/download", Map.of(), null), 403, "INVALID_CSRF_TOKEN");
        assertStatus(admin.post("/api/product-images/products/download", Map.of()), 400, "IMAGING_INVALID_INPUT");
        assertStatus(admin.send("POST", uploadPath, uploadRequest, null), 403, "INVALID_CSRF_TOKEN");
        assertStatus(admin.post(uploadPath, Map.of()), 400, "IMAGING_UPLOAD_INVALID_INPUT");
        assertStatus(admin.post(uploadPath, uploadRequest), 503, "IMAGING_UPLOAD_NOT_CONFIGURED");
        assertStatus(admin.get(uploadJobPath), 404, "IMAGING_UPLOAD_JOB_NOT_FOUND");
        String noticePath = "/api/product-images/products/TEST-BAG/notice-image/images";
        assertStatus(admin.send("POST", noticePath, Map.of("fields", Map.of("치수", "30")), null), 403, "INVALID_CSRF_TOKEN");
        assertStatus(admin.post(noticePath, Map.of("brandCode", "DAKS", "fields", Map.of("치수", "30"))), 404, "IMAGING_NOT_FOUND");
        String email = uniqueEmail();
        Browser staff = approvedUser(email);
        assertStatus(staff.get("/product-images"), 403);
        assertStatus(staff.get("/api/product-images/size-guide/templates"), 403);
        assertStatus(staff.post(uploadPath, uploadRequest), 403);
        assertStatus(staff.get(uploadJobPath), 403);
        assertStatus(staff.post(noticePath, Map.of("brandCode", "DAKS", "fields", Map.of("치수", "30"))), 403);
        assertThat(staff.get("/").body()).doesNotContain("/product-images");
        long id = users.findByEmail(email).orElseThrow().getId();
        assertStatus(admin.post("/api/admin/users/" + id + "/role", Map.of("role", "PRODUCT")), 200);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 200);
        assertStatus(staff.get("/product-images"), 200);
        assertStatus(staff.get("/api/product-images/size-guide/templates"), 200);
        assertStatus(staff.post(uploadPath, uploadRequest), 503, "IMAGING_UPLOAD_NOT_CONFIGURED");
        assertStatus(staff.get(uploadJobPath), 404, "IMAGING_UPLOAD_JOB_NOT_FOUND");
        assertThat(staff.get("/").body()).contains("/product-images");
        assertStatus(staff.get("/api/product-images/products/download/jobs/unknown/archive"), 404, "IMAGING_NOT_FOUND");
    }

    /** 명시적으로 활성화한 경우에만 실행 앱이 비밀 설정을 로딩해 읽기 전용 외부 호출을 수행한다. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOLEBUTTER_COUPANG_LIVE",matches="true")
    void coupangLivePages() throws Exception {
        Browser admin=admin();String path="/api/marketplaces/coupang/products?maxPerPage=10";
        for(int page=1;page<=2;page++){
            var response=admin.get(path);
            var root=json.readTree(response.body());
            System.out.println("COUPANG_LIVE page="+page+" http="+response.statusCode()+" code="+root.path("code").asText()+" count="+root.path("data").path("items").size()+" hasNext="+root.path("data").path("hasNext").asBoolean());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(root.path("data").path("items").isArray()).isTrue();
            if(page==1&&!root.path("data").path("items").isEmpty()){
                Thread.sleep(1100);
                String productId=root.path("data").path("items").get(0).path("sellerProductId").asText();
                var detailResponse=admin.get("/api/marketplaces/coupang/products/"+productId);
                var detail=json.readTree(detailResponse.body());
                System.out.println("COUPANG_LIVE detail http="+detailResponse.statusCode()+" code="+detail.path("code").asText()+" options="+detail.path("data").path("items").size());
                assertThat(detailResponse.statusCode()).isEqualTo(200);
                assertThat(detail.path("data").path("product").isObject()).isTrue();
                int currentCount=0;
                for(var option:detail.path("data").path("items"))if(!option.path("vendorItemId").isNull()){
                    assertThat(option.path("current").isObject()).as("current option lookup succeeds").isTrue();currentCount++;
                }
                System.out.println("COUPANG_LIVE current successfulOptions="+currentCount);
                int images=0;
                for(var option:detail.path("data").path("items"))for(var image:option.path("images"))if(image.path("url").isTextual()){
                    var imageResponse=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(image.path("url").asText())).timeout(java.time.Duration.ofSeconds(10)).method("HEAD",java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.discarding());
                    assertThat(imageResponse.statusCode()).as("detail CDN image is reachable").isEqualTo(200);images++;
                }
                System.out.println("COUPANG_LIVE images reachable="+images);
            }
            if(!root.path("data").path("hasNext").asBoolean())break;
            String token=root.path("data").path("nextToken").asText();
            path="/api/marketplaces/coupang/products?maxPerPage=10&nextToken="+java.net.URLEncoder.encode(token,java.nio.charset.StandardCharsets.UTF_8);
            Thread.sleep(1100);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOLEBUTTER_COUPANG_LIVE",matches="true")
    void coupangLiveDescriptionImages() throws Exception {
        Browser admin=admin();Thread.sleep(1100);
        var products=json.readTree(admin.get("/api/marketplaces/coupang/products?maxPerPage=10").body()).path("data").path("items");
        var src=java.util.regex.Pattern.compile("(?i)src\\s*=\\s*[\"']([^\"']+)");
        var http=java.net.http.HttpClient.newHttpClient();
        for(int n=0;n<Math.min(2,products.size());n++){
            Thread.sleep(1100);var response=admin.get("/api/marketplaces/coupang/products/"+products.get(n).path("sellerProductId").asText());
            assertThat(response.statusCode()).isEqualTo(200);var detail=json.readTree(response.body()).path("data");
            int insecure=0,cdn=0,reachable=0,images=0;var shapes=new java.util.TreeMap<String,Integer>();
            for(var option:detail.path("items"))for(var content:option.path("contents")){
                String text=content.path("content").asText();var urls=new java.util.ArrayList<String>();
                if("IMAGE".equals(content.path("detailType").asText()))urls.add(text);else{var matcher=src.matcher(text);while(matcher.find())urls.add(matcher.group(1));}
                for(String url:urls){images++;String shape=url.startsWith("vendor_inventory/")?"CDN_RELATIVE":url.startsWith("/image/")?"CDN_ROOT":url.startsWith("//")?"PROTOCOL_RELATIVE":url.startsWith("https:")?"HTTPS":url.startsWith("http:")?"HTTP":"OTHER";shapes.merge(shape,1,Integer::sum);try{var uri=java.net.URI.create(shape.equals("CDN_RELATIVE")?"https://img1a.coupangcdn.com/image/"+url:url);if("http".equals(uri.getScheme()))insecure++;if(uri.getHost()!=null&&uri.getHost().endsWith(".coupangcdn.com")){cdn++;var secure=java.net.URI.create(uri.toString().replaceFirst("^http:","https:"));var result=http.send(java.net.http.HttpRequest.newBuilder(secure).timeout(java.time.Duration.ofSeconds(10)).method("HEAD",java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.discarding());if(result.statusCode()==200)reachable++;}}catch(IllegalArgumentException ignored){}}
            }
            System.out.println("COUPANG_DESCRIPTION shape="+(n+1)+" images="+images+" httpImages="+insecure+" coupangCdn="+cdn+" httpsReachable="+reachable+" shapes="+shapes);
            assertThat(images).isPositive();assertThat(reachable).isEqualTo(images);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOLEBUTTER_COUPANG_LIVE",matches="true")
    void coupangLiveSearch() throws Exception {
        Browser admin=admin();Thread.sleep(1100);
        var baseline=json.readTree(admin.get("/api/marketplaces/coupang/products?maxPerPage=10").body()).path("data").path("items");
        assertThat(baseline.isArray()).isTrue();if(baseline.isEmpty())return;
        var first=baseline.get(0);String id=first.path("sellerProductId").asText();
        String name=first.path("sellerProductName").asText();name=name.substring(0,Math.min(10,name.length()));
        String date=first.path("createdAt").asText().substring(0,10);
        String state=switch(first.path("statusName").asText()){case "승인완료"->"APPROVED";case "임시저장"->"SAVED";case "심사중"->"IN_REVIEW";case "승인대기중"->"APPROVING";case "부분승인완료"->"PARTIAL_APPROVED";case "승인반려"->"DENIED";case "상품삭제"->"DELETED";default->first.path("statusName").asText();};
        String encName=java.net.URLEncoder.encode(name,java.nio.charset.StandardCharsets.UTF_8);
        for(String filter:java.util.List.of("sellerProductId="+id,"sellerProductName="+encName,"sellerProductId="+id+"&sellerProductName="+encName,"sellerProductId="+id+"&sellerProductName=NO_MATCH_TEST_840729","sellerProductId="+id+"&status="+state+"&createdAt="+date,"sellerProductId="+id+"&createdAt=1900-01-01")){
            Thread.sleep(1100);var response=admin.get("/api/marketplaces/coupang/products?maxPerPage=10&"+filter);var body=json.readTree(response.body());
            System.out.println("COUPANG_LIVE search http="+response.statusCode()+" code="+body.path("code").asText()+" count="+body.path("data").path("items").size());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(body.path("data").path("items").isArray()).isTrue();
            if(filter.endsWith("1900-01-01")||filter.contains("NO_MATCH_TEST_840729"))assertThat(body.path("data").path("items").size()).isZero();
            else assertThat(body.path("data").path("items").size()).isPositive();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOLEBUTTER_COUPANG_LIVE",matches="true")
    void coupangLiveEditor() throws Exception {
        Browser admin=admin();var list=admin.get("/api/marketplaces/coupang/products?maxPerPage=10");
        assertStatus(list,200);var items=json.readTree(list.body()).path("data").path("items");if(items.isEmpty())return;
        Thread.sleep(1100);var response=admin.get("/api/marketplaces/coupang/products/"+items.get(0).path("sellerProductId").asText()+"/edit-data");
        assertStatus(response,200);var editor=json.readTree(response.body()).path("data");assertThat(editor.path("options").isArray()).isTrue();
        Thread.sleep(1100);var metadata=admin.get("/api/marketplaces/coupang/categories/"+editor.path("basic").path("displayCategoryCode").asText()+"/rules");assertStatus(metadata,200);
        var rules=json.readTree(metadata.body()).path("data");assertThat(rules.path("attributes").isArray()).isTrue();
        System.out.println("COUPANG_EDITOR http="+response.statusCode()+" options="+editor.path("options").size()+" categoryHttp="+metadata.statusCode()+" attributes="+rules.path("attributes").size());
    }

    @Test
    void marketplaceEditorPagesAndMissingConfiguration() throws Exception {
        Browser admin=admin();
        assertStatus(admin.get("/marketplaces/coupang/products/new"),200);
        assertStatus(admin.get("/marketplaces/products/new"),200);
        assertStatus(admin.get("/marketplaces/coupang/products/123/edit"),200);
        assertStatus(admin.get("/api/marketplaces/coupang/products/123/edit-data"),503);
        assertStatus(admin.get("/api/marketplaces/coupang/categories/123/rules"),503);
    }

    @Test
    void defaultInMemoryAuthenticationIsNotCreated() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThat(context.containsBean("inMemoryUserDetailsManager")).isFalse();
    }

    @Test
    void accountStateConflictsNotifyAdminWithoutChangingAccountAndValidationDoesNot() throws Exception {
        String email = uniqueEmail();
        signup(email);
        long id = users.findByEmail(email).orElseThrow().getId();
        Browser admin = admin();
        long before = json.readTree(admin.get("/api/notifications/summary").body()).path("data").path("count").asLong();
        String route = "/api/admin/users/" + id;
        long version = users.findById(id).orElseThrow().getAuthVersion();
        assertStatus(admin.post(route + "/role", Map.of("role", "PRODUCT")), 409);
        assertThat(users.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(users.findById(id).orElseThrow().getAuthVersion()).isEqualTo(version);
        assertStatus(admin.post(route + "/approve", Map.of("role", "VIEWER")), 200);
        version = users.findById(id).orElseThrow().getAuthVersion();
        for (String action : List.of("approve", "unsuspend", "unlock")) {
            assertStatus(admin.post(route + "/" + action, action.equals("approve") ? Map.of("role", "VIEWER") : Map.of()), 409);
            assertThat(users.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(users.findById(id).orElseThrow().getAuthVersion()).isEqualTo(version);
        }
        assertStatus(admin.post(route + "/suspend", Map.of()), 200);
        version = users.findById(id).orElseThrow().getAuthVersion();
        assertStatus(admin.post(route + "/suspend", Map.of()), 409);
        assertStatus(admin.post(route + "/role", Map.of("role", "VIEWER")), 400);
        assertThat(users.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(users.findById(id).orElseThrow().getAuthVersion()).isEqualTo(version);
        assertThat(json.readTree(admin.get("/api/notifications/summary").body()).path("data").path("count").asLong())
                .isEqualTo(before + 5);
    }

    @Test
    void signupApprovalSigninAndRoleRestrictions() throws Exception {
        String email = uniqueEmail();
        Browser staff = signup(email);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 403, "ACCOUNT_PENDING");
        Browser admin = admin();
        long userId = users.findByEmail(email).orElseThrow().getId();
        assertStatus(admin.post("/api/admin/users/" + userId + "/approve", Map.of("role", "VIEWER")), 200);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 200);
        assertStatus(staff.get("/api/auth/me"), 200);
        assertStatus(staff.get("/api/admin/users"), 403);
        assertStatus(admin.get("/api/admin/users"), 200);
        assertStatus(staff.post("/api/auth/signout", Map.of()), 200);
        assertStatus(staff.get("/api/auth/me"), 401);
    }

    @Test
    void csrfMissingAndForgedTokensAreRejectedIncludingSignin() throws Exception {
        Browser browser = new Browser();
        assertStatus(browser.send("POST", "/api/auth/signin", credentials("admin@example.com"), null), 403,
                "INVALID_CSRF_TOKEN");
        browser.csrf();
        assertStatus(browser.send("POST", "/api/auth/signin", credentials("admin@example.com"), "forged"), 403,
                "INVALID_CSRF_TOKEN");
        assertStatus(browser.post("/api/auth/signin", Map.of("email", "admin@example.com",
                "password", "IntegrationAdmin123!")), 200);
    }

    @Test
    void suspensionAndRoleChangeImmediatelyRevokeOldAccessAndRefresh() throws Exception {
        String email = uniqueEmail();
        Browser staff = approvedUser(email);
        Browser admin = admin();
        long id = users.findByEmail(email).orElseThrow().getId();
        assertStatus(admin.post("/api/admin/users/" + id + "/role", Map.of("role", "PRODUCT")), 200);
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 200);
        assertStatus(admin.post("/api/admin/users/" + id + "/suspend", Map.of()), 200);
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        assertStatus(staff.post("/api/auth/signin", credentials(email)), 403, "ACCOUNT_SUSPENDED");
    }

    @Test
    void reusedRefreshRevokesEntireFamilyIncludingNewAccess() throws Exception {
        Browser staff = approvedUser(uniqueEmail());
        String oldRefresh = staff.cookies.get("refresh_token");
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 200);
        String newAccess = staff.cookies.get("access_token");
        String newRefresh = staff.cookies.get("refresh_token");
        staff.cookies.put("refresh_token", oldRefresh);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        staff.cookies.put("access_token", newAccess);
        staff.cookies.put("refresh_token", newRefresh);
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
    }

    @Test
    void passwordResetIsSingleUseAndRevokesPreviouslyIssuedTokens() throws Exception {
        String email = uniqueEmail();
        Browser staff = approvedUser(email);
        Browser reset = new Browser();
        Map<String, String> resetBody = Map.of("email", email, "newPassword", "NewPassword456!");
        assertStatus(reset.post("/api/auth/password-reset", resetBody), 400, "EMAIL_NOT_VERIFIED");
        assertStatus(reset.post("/api/auth/password-reset/email-code", Map.of("email", email)), 200);
        assertStatus(reset.post("/api/auth/password-reset/verify-email", Map.of("email", email,
                "code", mail.codes.get(email))), 200);
        assertStatus(reset.post("/api/auth/password-reset", resetBody), 200);
        assertStatus(reset.post("/api/auth/password-reset", Map.of("email", email,
                "newPassword", "OtherPassword789!")), 400, "EMAIL_NOT_VERIFIED");
        assertStatus(staff.get("/api/auth/me"), 401);
        assertStatus(staff.post("/api/auth/refresh", Map.of()), 401);
        assertStatus(reset.post("/api/auth/signin", credentials(email)), 401);
        assertStatus(reset.post("/api/auth/signin", Map.of("email", email,
                "password", "NewPassword456!")), 200);
    }

    @Test
    void concurrentRefreshHasAtMostOneWinnerAndRevokesReusedFamily() throws Exception {
        String email = uniqueEmail();
        approvedUser(email);
        User user = users.findByEmail(email).orElseThrow();
        var bundle = tokens.issueOnSignin(user);
        List<Integer> result = concurrent(8, () -> {
            try { tokens.rotate(bundle.refreshToken()); return 1; }
            catch (BusinessException e) { return 0; }
        });
        assertThat(result.stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(1);
        assertThat(tokens.isFamilyRevoked(jwt.familyId(jwt.parse(bundle.refreshToken())))).isTrue();
    }

    @Test
    void concurrentEmailSendVerifyAndConsumptionHaveOneWinner() throws Exception {
        String email = uniqueEmail();
        assertThat(concurrent(8, () -> attempt(() -> verification.sendCode(email, EmailVerificationPurpose.SIGNUP))))
                .filteredOn(value -> value == 1).hasSize(1);
        String code = mail.codes.get(email);
        // Two valid requests both fit the attempt budget; only one may consume the code.
        assertThat(concurrent(2, () -> attempt(() -> verification.verifyCode(email, code, EmailVerificationPurpose.SIGNUP))))
                .filteredOn(value -> value == 1).hasSize(1);
        assertThat(concurrent(8, () -> attempt(() -> verification.consumeVerified(email, EmailVerificationPurpose.SIGNUP))))
                .filteredOn(value -> value == 1).hasSize(1);
    }

    @Test
    void failedEmailDoesNotLeaveAUsableCodeAndAllowsRetry() {
        String email = uniqueEmail();
        mail.failOnce.add(email);
        assertThatThrownBy(() -> verification.sendCode(email, EmailVerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class);
        assertThat(store.get("auth:email:code:SIGNUP:" + email)).isEmpty();
        assertThatCode(() -> verification.sendCode(email, EmailVerificationPurpose.SIGNUP)).doesNotThrowAnyException();
        assertThat(mail.codes).containsKey(email);
    }

    @Test
    void tooManyWrongCodesInvalidateEvenTheCorrectCode() {
        String email = uniqueEmail();
        verification.sendCode(email, EmailVerificationPurpose.PASSWORD_RESET);
        String correct = mail.codes.get(email);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> verification.verifyCode(email, "invalid", EmailVerificationPurpose.PASSWORD_RESET))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> verification.verifyCode(email, correct, EmailVerificationPurpose.PASSWORD_RESET))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verification.consumeVerified(email, EmailVerificationPurpose.PASSWORD_RESET))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void redisCountersExpireAndOneTimeTokensHaveOnlyOneConsumer() throws Exception {
        String key = "it:" + UUID.randomUUID();
        assertThat(concurrent(16, () -> (int) store.increment(key, Duration.ofMinutes(1))))
                .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, 16).boxed().toList());
        assertThat(store.getAndDelete(key)).contains("16");
        assertThat(store.getAndDelete(key)).isEmpty();
        assertThat(store.putIfAbsent(key, "value", Duration.ofMillis(100))).isTrue();
        Thread.sleep(150);
        assertThat(store.exists(key)).isFalse();
    }

    @Test void httpAuditKeepsRequestOperationIdAndAuthenticatedActor() throws Exception {
        String email=uniqueEmail();signup(email);
        long id=users.findByEmail(email).orElseThrow().getId();
        var admin=admin();admin.operationId=UUID.randomUUID().toString();
        String path="/api/admin/users/"+id+"/approve";
        assertStatus(admin.post(path,Map.of("role","VIEWER")),200);
        var entries=jdbc.queryForList("SELECT * FROM operation_audit_log WHERE operation_id=?",admin.operationId);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst()).containsEntry("execution_source","HTTP")
            .containsEntry("user_id",users.findByEmail("admin@example.com").orElseThrow().getId())
            .containsEntry("user_role","ADMIN").containsEntry("email","admin@example.com")
            .containsEntry("event_type","USER_APPROVE").containsEntry("http_method","POST").containsEntry("request_uri",path);
    }

    @Test void backgroundAuditHasNoFabricatedAccountAndOnlyRecordsCommittedResults() {
        String target=java.util.UUID.randomUUID().toString();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status->{operations.backgroundAfterCommit("BACKGROUND_TEST","TEST",target,true,null);status.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE target_id=?",Long.class,target)).isZero();
        tx.executeWithoutResult(status->operations.backgroundAfterCommit("BACKGROUND_TEST","TEST",target,true,null));
        var entry=jdbc.queryForMap("SELECT * FROM operation_audit_log WHERE target_id=?",target);
        assertThat(entry).containsEntry("execution_source","BACKGROUND").containsEntry("user_id",null).containsEntry("user_role",null).containsEntry("email",null);
        assertThat(entry.get("operation_id").toString()).isEqualTo(java.util.UUID.fromString(entry.get("operation_id").toString()).toString());
    }

    @Test
    void migrationsValidateAndRuntimeAccountCannotCreateTables() {
        flyway.validate();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().pending()).isEmpty();
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE forbidden_ddl (id BIGINT)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void bootstrapNeverOverwritesExistingAdminCredentials() {
        User before = users.findByEmail("admin@example.com").orElseThrow();
        assertThat(before.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(before.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("IntegrationAdmin123!", before.getPasswordHash())).isTrue();
        bootstrap.run(null);
        assertThat(users.findByEmail("admin@example.com").orElseThrow().getPasswordHash())
                .isEqualTo(before.getPasswordHash());
        assertThat(users.findAllByRole(UserRole.ADMIN)).hasSize(1);
    }

    private int attempt(Runnable action) {
        try { action.run(); return 1; } catch (BusinessException e) { return 0; }
    }

    private List<Integer> concurrent(int count, IntSupplier action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) tasks.add(executor.submit(() -> { start.await(); return action.getAsInt(); }));
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (var task : tasks) results.add(task.get(20, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
    }

    private String uniqueEmail() { return "test-" + UUID.randomUUID() + "@example.com"; }
    private Map<String, String> credentials(String email) { return Map.of("email", email, "password", "Password123!"); }

    private Browser admin() throws Exception {
        Browser admin = new Browser();
        assertStatus(admin.post("/api/auth/signin", Map.of("email", "admin@example.com",
                "password", "IntegrationAdmin123!")), 200);
        return admin;
    }

    private Browser signup(String email) throws Exception {
        Browser browser = new Browser();
        assertStatus(browser.post("/api/auth/signup/email-code", Map.of("email", email)), 200);
        assertStatus(browser.post("/api/auth/signup/verify-email", Map.of("email", email,
                "code", mail.codes.get(email))), 200);
        assertStatus(browser.post("/api/auth/signup", Map.of("email", email, "password", "Password123!",
                "name", "테스트", "phoneNumber", "01012345678")), 200);
        return browser;
    }

    private Browser approvedUser(String email) throws Exception {
        Browser browser = signup(email);
        assertStatus(admin().post("/api/admin/users/" + users.findByEmail(email).orElseThrow().getId()
                + "/approve", Map.of("role", "VIEWER")), 200);
        assertStatus(browser.post("/api/auth/signin", credentials(email)), 200);
        return browser;
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).withFailMessage("HTTP %s: %s", response.statusCode(), response.body())
                .isEqualTo(expected);
    }

    private void assertStatus(HttpResponse<String> response, int expected, String code) {
        assertStatus(response, expected);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    class Browser {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final Map<String, String> cookies = new HashMap<>();
        String csrf;
        String operationId;
        void csrf() throws Exception {
            var response = get("/api/auth/csrf");
            assertStatus(response, 200);
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
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (!cookies.isEmpty()) request.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            if (csrfHeader != null) request.header("X-XSRF-TOKEN", csrfHeader);
            if (operationId != null) request.header("X-Operation-Id",operationId);
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
