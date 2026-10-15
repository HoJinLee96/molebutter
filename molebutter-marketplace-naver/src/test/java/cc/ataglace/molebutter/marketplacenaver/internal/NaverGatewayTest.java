package cc.ataglace.molebutter.marketplacenaver.internal;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import tools.jackson.databind.*;
import org.springframework.security.crypto.bcrypt.BCrypt;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;

class NaverGatewayTest {
    private HttpServer server;
    private DefaultNaverGateway gateway;
    private final ObjectMapper json=new ObjectMapper();
    private final List<String> methods=new ArrayList<>(),paths=new ArrayList<>(),queries=new ArrayList<>(),bodies=new ArrayList<>(),authorizations=new ArrayList<>();
    private java.util.function.BiFunction<String,Integer,Reply> respond;
    private record Reply(int status,String body) {}
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/external/",exchange->{
            int number;String path=exchange.getRequestURI().getPath();
            synchronized(paths){methods.add(exchange.getRequestMethod());paths.add(path);queries.add(exchange.getRequestURI().getRawQuery());bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));number=paths.size();}
            Reply reply=path.endsWith("oauth2/token")?new Reply(200,"{\"access_token\":\"synthetic-token\",\"expires_in\":10800,\"token_type\":\"Bearer\"}"):respond.apply(path,number);
            byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(reply.status(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        gateway=new DefaultNaverGateway("synthetic-app","$2a$04$abcdefghijklmnopqrstuu","SELF","",json,
            new NaverHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/external/"),Duration.ofSeconds(2),1024*1024),null,
            Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneOffset.UTC),Duration.ZERO);
        respond=(path,n)->new Reply(200,"{\"originProduct\":{\"originProductNo\":17},\"smartstoreChannelProduct\":{\"channelProductNo\":28}}");
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    @Test void documentedMissingIsConfirmedTwiceAndChannelAndOriginStaySeparate(){
        respond=(path,n)->new Reply(404,"{\"code\":\"NOT_FOUND\"}");
        assertThatThrownBy(()->gateway.product("123")).isInstanceOf(MarketplaceListingMissing.class);
        assertThat(paths.stream().filter(p->p.endsWith("origin-products/123"))).hasSize(2);
        assertThatThrownBy(()->gateway.channel("456")).isInstanceOf(MarketplaceListingMissing.class);
        assertThat(paths.stream().filter(p->p.endsWith("channel-products/456"))).hasSize(2);
    }
    @Test void generic404AndPermissionCannotEstablishDeletion(){
        respond=(path,n)->new Reply(404,"{\"code\":\"OTHER\"}");assertThatThrownBy(()->gateway.product("123")).isInstanceOf(MarketplaceFailure.class);
        respond=(path,n)->new Reply(403,"{\"code\":\"FORBIDDEN\"}");assertThatThrownBy(()->gateway.product("123")).isInstanceOf(MarketplaceFailure.class);
        respond=(path,n)->n%2==0?new Reply(404,"{\"code\":\"NOT_FOUND\"}"):new Reply(403,"{\"code\":\"FORBIDDEN\"}");assertThatThrownBy(()->gateway.product("123")).isInstanceOf(MarketplaceFailure.class);
    }
    @Test void signsFormCachesTokenAndKeepsTokenOutOfBusinessPayload(){
        gateway.session(()->{gateway.product("17");gateway.product("17");return null;});
        assertEquals(List.of("POST","GET","GET"),methods);assertNull(authorizations.get(0));assertEquals("Bearer synthetic-token",authorizations.get(1));
        var form=new HashMap<String,String>();for(String p:bodies.getFirst().split("&")){String[] parts=p.split("=",2);form.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}
        String hash=BCrypt.hashpw("synthetic-app_"+Instant.parse("2026-10-08T00:00:00Z").toEpochMilli(),"$2a$04$abcdefghijklmnopqrstuu");
        assertEquals(Base64.getEncoder().encodeToString(hash.getBytes(StandardCharsets.UTF_8)),form.get("client_secret_sign"));
        assertEquals("client_credentials",form.get("grant_type"));assertEquals("SELF",form.get("type"));assertFalse(form.containsKey("account_id"));assertFalse(form.containsKey("client_secret"));
        assertEquals("",bodies.get(1));assertEquals("",bodies.get(2));
    }
    @Test void renewsOnlyExplicitAuthnOnce(){
        var attempts=new AtomicInteger();respond=(path,n)->attempts.getAndIncrement()==0?new Reply(401,"{\"code\":\"GW.AUTHN\"}"):new Reply(200,"{\"originProduct\":{\"originProductNo\":17}}");
        gateway.product("17");assertEquals(4,paths.size());assertTrue(paths.get(2).endsWith("oauth2/token"));
    }
    @Test void quotaDoesNotRetryWriteAndErrorResponseDoesNotExposeExternalMessage(){
        respond=(path,n)->new Reply(429,"{\"code\":\"GW.QUOTA_LIMIT\",\"message\":\"synthetic-token SECRET SQL\"}");
        var response=gateway.write("POST","/v2/products",json.createObjectNode().put("name","test"));
        assertEquals(429,response.status());assertEquals(2,paths.size());var failure=assertThrows(MarketplaceFailure.class,()->gateway.parse(response));
        assertEquals(MarketplaceFailure.Kind.RATE_LIMIT,failure.kind());assertFalse(failure.getMessage().contains("synthetic-token"));assertTrue(failure.getMessage().contains("스마트스토어"));
    }
    @Test void permissionErrorsExposeOnlyKnownCodesAndFixedGuidance(){
        for(String code:List.of("GW.IP_NOT_ALLOWED","GW.AUTHN","ROLE_NOT_FOUND","PROVISION_NOT_FOUND","INVALID_INTERLOCK_STATUS","RESOURCE_NOT_AVAILABLE")){
            var response=new cc.ataglace.molebutter.marketplacenaver.api.NaverGateway.Response(403,
                ("{\"code\":\""+code+"\",\"message\":\"private-token\"}").getBytes(StandardCharsets.UTF_8),null,Map.of());
            var error=assertThrows(MarketplaceFailure.class,()->gateway.parse(response));
            assertEquals(MarketplaceFailure.Kind.PERMISSION,error.kind());assertTrue(error.getMessage().contains(code));assertFalse(error.getMessage().contains("private-token"));
        }
        var response=new cc.ataglace.molebutter.marketplacenaver.api.NaverGateway.Response(403,
            "{\"code\":\"private-code\",\"message\":\"private-token\"}".getBytes(StandardCharsets.UTF_8),null,Map.of());
        var error=assertThrows(MarketplaceFailure.class,()->gateway.parse(response));
        assertFalse(error.getMessage().contains("private-code"));assertFalse(error.getMessage().contains("private-token"));
    }
    @Test void serverErrorWriteIsNotRetried(){
        respond=(path,n)->new Reply(500,"{\"message\":\"uncertain\"}");
        assertEquals(500,gateway.write("PUT","/v2/products/origin-products/17",json.createObjectNode()).status());assertEquals(2,paths.size());
    }
    @Test void optionStockTransportUsesOnlyTheDocumentedPutAndDoesNotRetry(){
        var body=json.readTree("{\"optionInfo\":{\"useStockManagement\":true,\"optionCombinations\":[{\"id\":7,\"stockQuantity\":0,\"price\":0,\"usable\":true}]}}");
        respond=(path,n)->new Reply(500,"{\"message\":\"uncertain\"}");
        assertEquals(500,gateway.write("PUT","/v1/products/origin-products/17/option-stock",body).status());
        assertEquals(List.of("POST","PUT"),methods);assertEquals("/external/v1/products/origin-products/17/option-stock",paths.get(1));assertEquals(body,json.readTree(bodies.get(1)));
        for(String method:List.of("GET","POST","DELETE"))assertThrows(InputValidationFailure.class,()->gateway.write(method,"/v1/products/origin-products/17/option-stock",body));
        assertThrows(InputValidationFailure.class,()->gateway.write("PUT","/v1/products/origin-products/17/option-stock?other=1",body));assertEquals(2,paths.size());
    }
    @Test void metadataCacheIsCopiedAndRejectsArbitraryPath(){
        respond=(path,n)->new Reply(200,"[{\"id\":\"50000001\",\"name\":\"test\",\"last\":true}]");
        var a=gateway.session(()->gateway.metadata("categories",Map.of("last","true")));a.get(0).asObject().put("name","changed");
        var b=gateway.session(()->gateway.metadata("categories",Map.of("last","true")));assertEquals("test",b.get(0).path("name").asString());assertEquals(2,paths.size());
        assertThrows(InputValidationFailure.class,()->gateway.metadata("../oauth2/token",Map.of()));assertThrows(InputValidationFailure.class,()->gateway.product("17?other=1"));
    }
    @Test void brandsUseDocumentedGetNameAndExistingAuthenticatedCache(){
        respond=(path,n)->new Reply(200,"[{\"id\":9007199254740993,\"name\":\"닥스 & DAKS\"}]");
        var catalog=new DefaultNaverCatalog(mock(cc.ataglace.molebutter.identity.api.BusinessAccess.class),gateway,json);
        var expected=List.of(new NaverCatalog.Brand("9007199254740993","닥스 & DAKS"));
        assertEquals(expected,catalog.metadata(7L,"brands",Map.of("name","닥스 & DAKS")));
        assertEquals(expected,catalog.metadata(7L,"brands",Map.of("name","닥스 & DAKS")));
        assertEquals(List.of("POST","GET"),methods);assertEquals("/external/v1/product-brands",paths.get(1));
        assertEquals("name=닥스 & DAKS",URLDecoder.decode(queries.get(1),StandardCharsets.UTF_8));
        assertEquals("Bearer synthetic-token",authorizations.get(1));assertEquals("",bodies.get(1));
    }
    @Test void onlyBrandNotFoundIsAnEmptyCandidateList(){
        respond=(path,n)->new Reply(404,"{\"code\":\"NOT_FOUND\",\"message\":\"synthetic private detail\"}");
        assertTrue(gateway.session(()->gateway.metadata("brands",Map.of("name","없음"))).isEmpty());
        assertTrue(gateway.session(()->gateway.metadata("brands",Map.of("name","없음"))).isEmpty());
        assertEquals(2,paths.size());
        assertEquals(MarketplaceFailure.Kind.REJECTED,assertThrows(MarketplaceFailure.class,
            ()->gateway.session(()->gateway.metadata("categories",Map.of("last","true")))).kind());
        respond=(path,n)->new Reply(403,"{\"code\":\"FORBIDDEN\"}");
        assertEquals(MarketplaceFailure.Kind.PERMISSION,assertThrows(MarketplaceFailure.class,
            ()->gateway.session(()->gateway.metadata("brands",Map.of("name","다른브랜드")))).kind());
    }
    @Test void documentedDetailWithoutProductIdsKeepsRequestedIdentityAndSourceFields(){
        String body=documentedDetail();respond=(path,n)->new Reply(200,body);
        var source=gateway.product("17");var expected=json.readTree(body);
        assertTrue(source.path("originProductNo").isIntegralNumber());assertEquals(17L,source.path("originProductNo").asLong());
        assertEquals(expected.path("originProduct"),source.path("originProduct"));
        assertEquals(expected.path("smartstoreChannelProduct"),source.path("smartstoreChannelProduct"));
        assertFalse(source.path("originProduct").has("originProductNo"));
        assertFalse(source.has("smartstoreChannelProductNo"));assertFalse(source.path("smartstoreChannelProduct").has("channelProductNo"));
    }
    @Test void documentedDetailProjectsStableProductScopedOptionAndImageIdentities(){
        respond=(path,n)->new Reply(200,documentedDetail());
        var catalog=new DefaultNaverCatalog(mock(cc.ataglace.molebutter.identity.api.BusinessAccess.class),gateway,json);
        var first=catalog.editor(7L,"17");var reloaded=catalog.editor(7L,"17");var other=catalog.editor(7L,"18");
        assertEquals("17",first.originProductNo());assertEquals("18",other.originProductNo());assertNull(first.channelProductNo());
        assertEquals(first.optionIdentities(),reloaded.optionIdentities());assertEquals(first.input().options(),reloaded.input().options());
        assertEquals(first.input().images(),reloaded.input().images());
        assertEquals("7",first.optionIdentities().getFirst().remoteId());
        assertEquals(NaverEditPatch.uuid("naver:17:option:7"),first.input().options().getFirst().id());
        assertEquals(NaverEditPatch.uuid("naver:17:image:https://shop-phinf.pstatic.net/representative.jpg"),first.input().images().getFirst().id());
        assertNotEquals(first.input().options().getFirst().id(),other.input().options().getFirst().id());
        for(int i=0;i<first.input().images().size();i++)assertNotEquals(first.input().images().get(i).id(),other.input().images().get(i).id());
        assertEquals("SALE",first.input().fields().get("originProduct.statusType"));
        assertEquals("ON",first.input().fields().get("smartstoreChannelProduct.channelProductDisplayStatusType"));
        assertEquals("<p>상품 상세</p>",first.input().description());
    }
    @Test void suspendedProductDetailKeepsSaleAndDisplayStatusWithInt64RouteIdentity(){
        var detail=json.readTree(documentedDetail()).asObject();detail.path("originProduct").asObject().put("statusType","SUSPENSION");
        respond=(path,n)->new Reply(200,json.writeValueAsString(detail));
        var catalog=new DefaultNaverCatalog(mock(cc.ataglace.molebutter.identity.api.BusinessAccess.class),gateway,json);
        var editor=catalog.editor(7L,"13000000001");
        assertEquals("13000000001",editor.originProductNo());assertNull(editor.channelProductNo());
        assertEquals("SUSPENSION",editor.input().fields().get("originProduct.statusType"));
        assertEquals("ON",editor.input().fields().get("smartstoreChannelProduct.channelProductDisplayStatusType"));
        assertEquals(NaverEditPatch.uuid("naver:13000000001:option:7"),editor.input().options().getFirst().id());
        assertTrue(paths.contains("/external/v2/products/origin-products/13000000001"));
    }
    @Test void mismatchedReadIdentityIsRejected(){
        for(String body:List.of("{\"originProduct\":{\"originProductNo\":99}}",
                "{\"originProductNo\":99,\"originProduct\":{}}",
                "{\"originProductNo\":99,\"originProduct\":{\"originProductNo\":17}}",
                "{\"originProductNo\":17,\"originProduct\":{\"originProductNo\":99}}")){
            respond=(path,n)->new Reply(200,body);
            assertEquals(MarketplaceFailure.Kind.RESPONSE,assertThrows(MarketplaceFailure.class,()->gateway.product("17")).kind());
        }
    }
    @Test void imageUploadUsesR2BytesAndSingleFileMapping() throws Exception {
        var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        var assets=mock(ImageAssets.class);when(assets.read(7L,"asset-id")).thenReturn(new ImageAssets.AssetContent(null,bytes.toByteArray()));
        var r2=mock(cc.ataglace.molebutter.media.api.R2Images.class);when(r2.read(7L,"https://assets.molebutter.link/products/CODE/official/01.png")).thenReturn(new cc.ataglace.molebutter.media.api.R2Images.Content(null,bytes.toByteArray()));
        var source=new NaverImageSource(r2);
        gateway=new DefaultNaverGateway("synthetic-app","$2a$04$abcdefghijklmnopqrstuu","SELF","",json,new NaverHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/external/"),Duration.ofSeconds(2),1024*1024),source,Clock.systemUTC(),Duration.ZERO);
        respond=(path,n)->new Reply(200,"{\"images\":[{\"url\":\"https://shop-phinf.pstatic.net/test.png\"}]}");
        assertEquals("https://shop-phinf.pstatic.net/test.png",gateway.uploadImage(7L,new NaverEditor.Image("image-id",null,"https://assets.molebutter.link/products/CODE/official/01.png",true,0)));
        assertTrue(bodies.get(1).contains("name=\"imageFiles\""));assertTrue(bodies.get(1).contains("Content-Type: image/png"));verify(r2).read(7L,"https://assets.molebutter.link/products/CODE/official/01.png");verify(assets,never()).read(anyLong(),anyString());
    }
    @Test void generatedCropUploadsItsActualServerBytesAndUsesTheReturnedNaverUrl()throws Exception{
        var image=new BufferedImage(750,750,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        var assets=mock(ImageAssets.class);when(assets.readEdited(7L,"server-crop")).thenReturn(new ImageAssets.AssetContent(null,bytes.toByteArray()));var r2=mock(cc.ataglace.molebutter.media.api.R2Images.class);
        gateway=new DefaultNaverGateway("synthetic-app","$2a$04$abcdefghijklmnopqrstuu","SELF","",json,new NaverHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/external/"),Duration.ofSeconds(2),1024*1024),new NaverImageSource(r2,assets),Clock.systemUTC(),Duration.ZERO);
        respond=(path,n)->new Reply(200,"{\"images\":[{\"url\":\"https://shop-phinf.pstatic.net/crop.png\"}]}");
        assertEquals("https://shop-phinf.pstatic.net/crop.png",gateway.uploadImage(7L,new NaverEditor.Image("image-id","server-crop","/api/marketplaces/assets/server-crop",true,0)));
        assertTrue(bodies.get(1).contains("name=\"imageFiles\""));assertTrue(bodies.get(1).contains("Content-Type: image/png"));verify(assets).readEdited(7L,"server-crop");verifyNoInteractions(r2);
    }
    @Test void rejectsLegacyLocalSourcesBeforeReadingOrUploading(){
        var r2=mock(cc.ataglace.molebutter.media.api.R2Images.class);var source=new NaverImageSource(r2);
        assertThrows(InputValidationFailure.class,()->source.read(7L,new NaverEditor.Image("id","asset","/api/marketplaces/assets/asset",true,0)));verifyNoInteractions(r2);
    }
    @Test void missingConfigDoesNotDispatchAndAccountNamespaceIgnoresSecret(){
        var none=new DefaultNaverGateway("","","SELF","",json,null,null,Clock.systemUTC(),Duration.ZERO);
        assertEquals(MarketplaceFailure.Kind.CONFIGURATION,assertThrows(MarketplaceFailure.class,()->none.product("17")).kind());assertTrue(paths.isEmpty());
        var other=new DefaultNaverGateway("synthetic-app","different-secret","SELF","",json,null,null,Clock.systemUTC(),Duration.ZERO);
        assertEquals(gateway.accountKey(),other.accountKey());assertEquals(64,gateway.accountKey().length());
    }
    @Test void catalogProjectsOnlySmartstoreAndPreservesZeroValues(){
        var catalog=new DefaultNaverCatalog(mock(cc.ataglace.molebutter.identity.api.BusinessAccess.class),gateway,json);
        var response=json.readTree("{\"page\":1,\"totalPages\":1,\"last\":true,\"contents\":[{\"originProductNo\":17,\"channelProducts\":[{\"channelServiceType\":\"STOREFARM\",\"channelProductNo\":28,\"name\":\"test\",\"salePrice\":0,\"stockQuantity\":0}]},{\"originProductNo\":18,\"channelProducts\":[{\"channelServiceType\":\"WINDOW\",\"channelProductNo\":29}]}]}");
        var page=catalog.page(response,1);assertEquals(1,page.products().size());assertEquals(0L,page.products().getFirst().salePrice());assertEquals(0L,page.products().getFirst().stockQuantity());assertFalse(page.hasNext());
    }
    @Test void catalogueAcceptsTheScreenDefaultPageSizeAndSourceLimit(){
        var fake=mock(NaverGateway.class);
        org.mockito.Mockito.when(fake.session(org.mockito.ArgumentMatchers.any())).thenAnswer(a->((java.util.function.Supplier<?>)a.getArgument(0)).get());
        org.mockito.Mockito.when(fake.search(org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"page\":1,\"totalPages\":0,\"last\":true,\"contents\":[]}"));
        var catalog=new DefaultNaverCatalog(mock(cc.ataglace.molebutter.identity.api.BusinessAccess.class),fake,json);
        catalog.products(1L,new cc.ataglace.molebutter.marketplace.api.NaverCatalog.Search(1,20,null,null));
        catalog.products(1L,new cc.ataglace.molebutter.marketplace.api.NaverCatalog.Search(1,500,null,null));
        assertThrows(cc.ataglace.molebutter.common.api.InputValidationFailure.class,()->catalog.products(1L,new cc.ataglace.molebutter.marketplace.api.NaverCatalog.Search(1,501,null,null)));
        org.mockito.Mockito.verify(fake).search(org.mockito.ArgumentMatchers.argThat(body->body.path("size").asInt()==20));
    }
    private static String documentedDetail(){
        // The official GET response schemas do not include originProductNo or channelProductNo.
        return """
          {"originProduct":{"statusType":"SALE","saleType":"NEW","leafCategoryId":"50000001","name":"테스트 상품",
            "salePrice":10000,"stockQuantity":2,"detailContent":"<p>상품 상세</p>",
            "images":{"representativeImage":{"url":"https://shop-phinf.pstatic.net/representative.jpg"},
              "optionalImages":[{"url":"https://shop-phinf.pstatic.net/optional.jpg"}]},
            "detailAttribute":{"optionInfo":{"optionCombinationGroupNames":{"optionGroupName1":"색상"},
              "optionCombinations":[{"id":7,"optionName1":"검정","price":0,"stockQuantity":2,"usable":true}]}}},
           "smartstoreChannelProduct":{"channelProductName":"채널 상품명","naverShoppingRegistration":false,"channelProductDisplayStatusType":"ON"}}
          """;
    }
}
