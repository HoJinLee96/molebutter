package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.identity.internal.*;
import cc.ataglace.molebutter.identity.api.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "product.refresh.worker-enabled=false","spring.config.import=classpath:bootstrap-admin-test.properties",
 "spring.datasource.username=test_app","spring.datasource.password=isolated-test-app-password",
 "spring.flyway.user=test_migrator","spring.flyway.password=isolated-test-migration-password",
 "spring.data.redis.host=127.0.0.1","spring.data.redis.password=","mail.provider=test",
 "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes","auth.cookie.secure=false"
})
@ActiveProfiles("bootstrap-admin")
@Import(AuthenticationFlowIT.MailConfiguration.class)
class MarketplaceDraftFlowIT {
 static final Path assetsDirectory=createDirectory();
 static Path createDirectory(){try{return Files.createTempDirectory("molebutter-assets-it-");}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);r.add("marketplace.assets.directory",()->assetsDirectory.toString());r.add("marketplace.assets.public-base-url",()->"https://images.market.example");}
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
 cc.ataglace.molebutter.marketplace.api.CoupangEditor coupang;
 @Autowired MarketplaceDrafts drafts;@Autowired ImageAssets assets;@Autowired ImageAssets store;
 @Autowired JdbcTemplate db;@Autowired UserRepository users;@Autowired DefaultAuthTokenService tokens;@Autowired ObjectMapper json;@LocalServerPort int port;
 long admin,otherAdmin,staff,viewer;
 @BeforeEach void prepare(){clear();admin=users.findByEmail("admin@example.com").orElseThrow().getId();otherAdmin=account(UserRole.ADMIN);staff=account(UserRole.PRODUCT);viewer=account(UserRole.VIEWER);}
 @AfterEach void cleanup(){
  clear();
  for(long id:List.of(otherAdmin,staff,viewer))if(id>0){db.update("DELETE FROM user_notification WHERE user_id=?",id);users.deleteById(id);}
 }
 void clear(){for(String table:List.of("marketplace_execution_attempt","marketplace_execution_step","marketplace_execution_target","marketplace_execution","marketplace_execution_asset","marketplace_submission_preview","marketplace_edit_session","marketplace_listing_mapping","marketplace_submission_account","marketplace_asset_publication","marketplace_draft_asset","marketplace_draft","marketplace_asset"))db.update("DELETE FROM "+table);}
 @AfterAll static void cleanFiles()throws Exception {try(var stream=Files.list(assetsDirectory)){for(var p:stream.toList())Files.deleteIfExists(p);}Files.deleteIfExists(assetsDirectory);}
 long account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@market.test").name("마켓 테스트").passwordHash("unused").role(role).status(UserStatus.ACTIVE).build()).getId();}
 Document blank(){return json.readValue("""
  {"common":{"productCode":"DRAFT-1","name":""},"options":[{"id":"10000000-1000-4000-8000-000000000001","name":"블랙","price":"","quantity":"","attributes":[]}],"stockMode":"OPTION","productQuantity":"","services":[],"media":{"images":[],"contents":[]},"delivery":{},"selectedMarkets":["NAVER"],"markets":{"NAVER":{"categoryCode":""}}}
  """,Document.class);}
 Document changeName(Document d,String name){var c=d.common();return new Document(d.id(),d.revision(),new Common(c.productCode(),name,c.productName(),c.brand(),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly()),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());}
 Document withImage(Document d,ImageAssets.Asset asset){return new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(new Image(UUID.randomUUID().toString(),asset.id(),asset.url(),true,0,null)),d.media().contents()),d.delivery(),d.selectedMarkets(),d.markets());}
 byte[] png()throws Exception {var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),"PNG",bytes);return bytes.toByteArray();}
 @Test void incompleteDraftPersistsAcrossReadsAndVersionConflictPreservesStoredData()throws Exception {
  var browser=new Browser(admin);var created=browser.send("POST","/api/marketplaces/drafts",blank(),true);assertThat(created.statusCode()).isEqualTo(200);
  var d=json.treeToValue(json.readTree(created.body()).path("data"),Document.class);assertThat(d.id()).isNotBlank();assertThat(d.common().name()).isEmpty();
  assertThat(browser.get("/marketplaces/products/"+d.id()+"/edit").statusCode()).isEqualTo(200);
  var saved=browser.send("PUT","/api/marketplaces/drafts/"+d.id(),changeName(d,"변경 이름"),true);assertThat(saved.statusCode()).isEqualTo(200);
  var stale=browser.send("PUT","/api/marketplaces/drafts/"+d.id(),changeName(d,"오래된 변경"),true);assertThat(stale.statusCode()).isEqualTo(409);
  assertThat(drafts.get(otherAdmin,d.id()).common().name()).isEqualTo("변경 이름");
  assertThat(drafts.list(admin,"변경",0,20).items()).hasSize(1);assertThat(drafts.list(admin,"%",0,20).items()).isEmpty();
  assertThat(browser.send("POST","/api/marketplaces/drafts",blank(),false).statusCode()).isEqualTo(403);
  assertThat(browser.get("/marketplaces/coupang/products/new").statusCode()).isEqualTo(200);
 }
 @Test void onlyActiveAdministratorsCanReadWriteOrUpload()throws Exception {
  for(long actor:List.of(staff,viewer)){var b=new Browser(actor);assertThat(b.get("/marketplaces/products/new").statusCode()).isEqualTo(403);assertThat(b.get("/api/marketplaces/drafts").statusCode()).isEqualTo(403);assertThat(b.send("POST","/api/marketplaces/drafts",blank(),true).statusCode()).isEqualTo(403);assertThat(b.upload(png()).statusCode()).isEqualTo(403);}
  var b=new Browser(admin);db.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",admin);
  try{assertThat(b.get("/api/marketplaces/drafts").statusCode()).isEqualTo(401);}finally{db.update("UPDATE `user` SET user_status='ACTIVE' WHERE id=?",admin);}
 }
 @Test void uploadsArePrivateAndReferencedImagesSurviveCleanup()throws Exception {
  var browser=new Browser(admin);var upload=browser.upload(png());assertThat(upload.statusCode()).isEqualTo(200);
  var image=json.treeToValue(json.readTree(upload.body()).path("data"),ImageAssets.Asset.class);
  assertThat(image.width()).isEqualTo(500);assertThat(image.url()).startsWith("/api/marketplaces/assets/");
  var bytes=browser.get(image.url());assertThat(bytes.statusCode()).isEqualTo(200);assertThat(bytes.headers().firstValue("x-content-type-options")).contains("nosniff");
  assertThat(new Browser(staff).get(image.url()).statusCode()).isEqualTo(403);
  assertThatThrownBy(()->drafts.create(otherAdmin,withImage(blank(),image))).isInstanceOf(IllegalArgumentException.class);
  var d=drafts.create(admin,withImage(blank(),image));assertThat(drafts.get(otherAdmin,d.id()).media().images()).hasSize(1);
  db.update("UPDATE marketplace_asset SET created_at=NOW()-INTERVAL 3 DAY,pending_delete_at=NOW()-INTERVAL 2 DAY WHERE id=?",image.id());store.reapUnreferenced();assertThat(assets.metadata(admin,image.id()).id()).isEqualTo(image.id());
  var cleared=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),new Media(List.of(),List.of()),d.delivery(),d.selectedMarkets(),d.markets());drafts.save(otherAdmin,d.id(),d.revision(),cleared);
  store.reapUnreferenced();assertThat(assets.metadata(admin,image.id()).id()).isEqualTo(image.id());
  db.update("UPDATE marketplace_asset SET pending_delete_at=NOW()-INTERVAL 2 DAY WHERE id=?",image.id());store.reapUnreferenced();assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=?",Long.class,image.id())).isZero();
 }
 @Test void explicitImportDeduplicatesAndProtectsCategoryOptionIdentityAndSource() {
  var original=new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123","456","가져온 상품","노출명","제품명","브랜드","","123","승인완료"),new CoupangEditor.Limits(true,true,true),
    List.of(new CoupangEditor.EditOption("71","72","블랙",new CoupangCatalog.CurrentInventory("72",100L,9L,true),null,true,
     List.of(new CoupangCatalog.Attribute("색상","블랙","EXPOSED")),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("salePrice","100"),new CoupangCatalog.Field("maximumBuyCount","9")),List.of())),List.of(),List.of(),List.of());
  org.mockito.Mockito.doReturn(original).when(coupang).edit(org.mockito.ArgumentMatchers.eq(admin),org.mockito.ArgumentMatchers.eq("123"),org.mockito.ArgumentMatchers.nullable(String.class));
  var d=drafts.importCoupang(admin,"123",null);var again=drafts.importCoupang(admin,"123",null);
  assertThat(again.id()).isEqualTo(d.id());org.mockito.Mockito.verify(coupang,org.mockito.Mockito.times(1)).edit(admin,"123",null);
  assertThat(d.options().getFirst().quantity()).isEqualTo("9");assertThat(d.markets().get(Market.COUPANG).coupang().source()).isEqualTo(original);
  var config=d.markets().get(Market.COUPANG);var modified=new EnumMap<Market,MarketConfig>(Market.class);modified.putAll(d.markets());modified.put(Market.COUPANG,new MarketConfig("999",config.overrides(),config.coupang(),config.naver(),config.esm()));
  var changed=new Document(d.id(),d.revision(),d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),modified);
  assertThatThrownBy(()->drafts.save(admin,d.id(),d.revision(),changed)).isInstanceOf(IllegalArgumentException.class);
  assertThat(drafts.get(admin,d.id()).markets().get(Market.COUPANG).categoryCode()).isEqualTo("123");
 }
 @Test void submissionCanPublishOwnUnsavedUploadWithoutChangingCommonReference()throws Exception{
  var image=assets.upload(admin,png());
  assertThatThrownBy(()->store.submissionUrl(otherAdmin,image.id())).isInstanceOf(IllegalArgumentException.class);
  String url=store.submissionUrl(admin,image.id());assertThat(url).startsWith("https://images.market.example/marketplace-images/");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE asset_id=?",Long.class,image.id())).isZero();
  assertThatThrownBy(()->store.publicUrl(admin,image.id())).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void onlyExplicitlyPublishedRasterImagesAreReadableWithoutAuthentication()throws Exception {
  var image=assets.upload(admin,png());
  assertThatThrownBy(()->store.publicUrl(admin,image.id())).isInstanceOf(IllegalArgumentException.class);
  drafts.create(admin,withImage(blank(),image));String url=store.publicUrl(admin,image.id());
  assertThat(url).startsWith("https://images.market.example/marketplace-images/").doesNotContain(image.id());
  assertThat(store.publicUrl(otherAdmin,image.id())).isEqualTo(url);
  String path=URI.create(url).getPath();var client=HttpClient.newHttpClient();
  var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
  assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(png());
  assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
  var privateRead=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+image.url())).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(privateRead.statusCode()).isEqualTo(401);
  for(String token:List.of(image.id(),"0".repeat(64),"invalid")){
   var missing=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/marketplace-images/"+token)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(missing.statusCode()).isEqualTo(404);assertThat(missing.body()).isEmpty();
  }
 }
 @Test void missingBusinessInputsAreValidationErrorsRatherThanFakeExternalSuccess(){
  var result=drafts.validate(admin,blank());assertThat(result.valid()).isFalse();assertThat(result.errors()).isNotEmpty();assertThat(result.unverified()).anyMatch(i->i.market().equals("NAVER"));
  assertThat(drafts.list(admin,"",0,20).totalElements()).isZero();
 }
 class Browser {
  final HttpClient client=HttpClient.newHttpClient();final String token;String csrf,cookie;
  Browser(long actor){token=tokens.issueOnSignin(users.findById(actor).orElseThrow()).accessToken();}
  void security()throws Exception {if(csrf!=null)return;var r=get("/api/auth/csrf");csrf=json.readTree(r.body()).path("data").path("token").asText();cookie=r.headers().allValues("set-cookie").stream().map(s->s.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));}
  HttpRequest.Builder request(String path,boolean secure)throws Exception {if(secure)security();var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("X-Operation-Id",UUID.randomUUID().toString());if(secure)b.header("X-XSRF-TOKEN",csrf).header("Cookie",cookie);return b;}
  HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,false);}
  HttpResponse<String> send(String method,String path,Object body,boolean secure)throws Exception {var b=request(path,secure).header("Content-Type","application/json");return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
  HttpResponse<String> upload(byte[] bytes)throws Exception {String boundary="marketplace-test-boundary";var body=new ByteArrayOutputStream();body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes());body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes());return client.send(request("/api/marketplaces/assets",true).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());}
 }
}
