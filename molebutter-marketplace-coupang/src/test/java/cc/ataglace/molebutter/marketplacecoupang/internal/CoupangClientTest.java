package cc.ataglace.molebutter.marketplacecoupang.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import static org.assertj.core.api.Assertions.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class CoupangClientTest {
    @Test void categoryRulesPreserveIndependentExposureAndRequiredness(){
        String body="""
            {"code":"SUCCESS","data":{"isAllowSingleItem":true,"attributes":[
              {"attributeTypeName":"Manufacturer Part Number","required":"MANDATORY","exposed":"NONE","groupNumber":"NONE","dataType":"STRING","usableUnits":[]}],
              "noticeCategories":[{"noticeCategoryName":"패션잡화","noticeCategoryDetailNames":[{"noticeCategoryDetailName":"소재","required":"MANDATORY"}]}],
              "certifications":[{"certificationType":"TEST","name":"인증","required":"OPTIONAL","dataType":"CODE"}],
              "requiredDocumentNames":[{"templateName":"서류","required":"MANDATORY_OVERSEAS_PURCHASED"}],"allowedOfferConditions":["NEW"],"secretUnknown":"do-not-return"}}
            """;
        var rules=client(new CoupangHttpTransport()).parseCategory(body.getBytes(StandardCharsets.UTF_8),"123");
        assertThat(rules.attributes().getFirst().exposed()).isEqualTo("NONE");assertThat(rules.attributes().getFirst().required()).isEqualTo("MANDATORY");
        assertThat(rules.notices().getFirst().fields().getFirst().required()).isEqualTo("MANDATORY");
        assertThat(new ObjectMapper().writeValueAsString(rules)).doesNotContain("secretUnknown","do-not-return");
        failure(()->client(new CoupangHttpTransport()).parseCategory("{\"code\":\"SUCCESS\",\"data\":{}}".getBytes(StandardCharsets.UTF_8),"123"),RESPONSE);
        failure(()->client(new CoupangHttpTransport()).parseCategory("{\"code\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8),"123"),REJECTED);
        assertThatThrownBy(()->client(new CoupangHttpTransport()).category("../123")).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test void searchValidationEncodingAndBlankOmission(){
        var empty=cc.ataglace.molebutter.marketplace.api.CoupangCatalog.ProductSearch.empty();
        assertThat(CoupangProductClient.searchQuery(empty)).isEmpty();
        var search=new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.ProductSearch("123"," 블랙 + & ","APPROVED","2026-10-05");
        assertThat(CoupangProductClient.searchQuery(search)).isEqualTo("&sellerProductId=123&sellerProductName=%EB%B8%94%EB%9E%99+%2B+%26&status=APPROVED&createdAt=2026-10-05");
        assertThatThrownBy(()->CoupangProductClient.searchQuery(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.ProductSearch(null,null,"unknown",null))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        for(String date:java.util.List.of("2026-02-29","2026-1-01","invalid"))assertThatThrownBy(()->CoupangProductClient.searchQuery(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.ProductSearch(null,null,null,date))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
        assertThatThrownBy(()->CoupangProductClient.searchQuery(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.ProductSearch(null,"x".repeat(21),null,null))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test void detailSectionsAreWhitelistedAndPreservedDuringCurrentEnrichment(){
        String option="\"itemName\":\"블랙 / FREE\"";
        String payload=DETAIL.replace(option,option+",\"salePrice\":0,\"maximumBuyCount\":7,\"attributes\":[{\"attributeTypeName\":\"색상\",\"attributeValueName\":\"블랙\",\"exposed\":\"EXPOSED\"}],\"images\":[{\"imageOrder\":2,\"imageType\":\"DETAIL\",\"vendorPath\":\"javascript:alert(1)\"},{\"imageOrder\":1,\"imageType\":\"REPRESENTATION\",\"cdnPath\":\"vendor_inventory/test.jpg\"}],\"contents\":[{\"contentsType\":\"HTML\",\"contentDetails\":[{\"detailType\":\"TEXT\",\"content\":\"<script>bad()</script>\"}]}],\"notices\":[],\"certifications\":[],\"secretUnknown\":\"do-not-return\"");
        var d=client(new CoupangHttpTransport()).parseDetail(payload.getBytes(StandardCharsets.UTF_8),"123");var item=d.items().getFirst();
        assertThat(item.images().getFirst().url()).isEqualTo("https://img1a.coupangcdn.com/image/vendor_inventory/test.jpg");assertThat(item.images().get(1).url()).isNull();
        assertThat(item.attributes()).hasSize(1);assertThat(item.contents()).hasSize(1);assertThat(item.notices()).isEmpty();
        assertThat(item.withCurrent(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.CurrentInventory("1",0L,0L,false),null).images()).isEqualTo(item.images());
        assertThat(new ObjectMapper().writeValueAsString(d)).doesNotContain("secretUnknown","do-not-return");
        assertThat(CoupangProductClient.imageUrl(null,"http://invalid.test/x")).isNull();assertThat(CoupangProductClient.imageUrl(null,"https://user:pass@invalid.test/x")).isNull();
    }
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"),ZoneOffset.UTC);
    static final String EMPTY="{\"code\":\"SUCCESS\",\"data\":[],\"nextToken\":\"\"}";
    CoupangProductClient client(CoupangHttpTransport transport){return new CoupangProductClient("A000-test","fake-access","fake-secret",new ObjectMapper(),transport,CLOCK);}
    void failure(Runnable action,MarketplaceFailure.Kind kind){assertThatThrownBy(action::run).isInstanceOf(MarketplaceFailure.class).satisfies(e->assertThat(((MarketplaceFailure)e).kind()).isEqualTo(kind)).hasMessageNotContaining("fake-secret").hasMessageNotContaining("fake-access").hasMessageNotContaining("raw-secret");}
    static final String DETAIL="""
        {"code":"SUCCESS","data":{"vendorId":"A000-test","sellerProductId":123,
        "sellerProductName":"관리용 등록명","displayProductName":"고객 노출상품명","brand":"브랜드",
        "productId":99999999999999999999,"displayCategoryCode":77413,"categoryId":2102,
        "saleStartedAt":"2026-10-05T10:00:00","items":[{"sellerProductItemId":9876543210123456789,"vendorItemId":null,"itemName":"블랙 / FREE"}],"future":true}}
        """;
    @Test void detailPreservesBothNamesAndRejectsWrongAccountOrProduct(){
        var c=client(new CoupangHttpTransport());var d=c.parseDetail(DETAIL.getBytes(StandardCharsets.UTF_8),"123");
        assertThat(d.product().sellerProductName()).isEqualTo("관리용 등록명");
        assertThat(d.displayProductName()).isEqualTo("고객 노출상품명");
        assertThat(d.product().productId()).isEqualTo("99999999999999999999");
        assertThat(d.items().getFirst().vendorItemId()).isNull();
        assertThat(d.items().getFirst().sellerProductItemId()).isEqualTo("9876543210123456789");
        failure(()->c.parseDetail(DETAIL.getBytes(),"124"),RESPONSE);
        failure(()->c.parseDetail(DETAIL.replace("A000-test","OTHER").getBytes(),"123"),RESPONSE);
        failure(()->c.parseDetail(DETAIL.replace("\"items\":[", "\"other\":[").getBytes(),"123"),RESPONSE);
        failure(()->c.parseDetail("{\"code\":\"ERROR\",\"message\":\"raw-secret\"}".getBytes(),"123"),REJECTED);
        assertThatThrownBy(()->c.product("../123")).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test void editorIsWhitelistedAndReadOnly()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var methods=new java.util.ArrayList<String>();
        String payload=DETAIL.replace("\"itemName\":\"블랙 / FREE\"","\"itemName\":\"블랙 / FREE\",\"salePrice\":0,\"autoPricingInfo\":{\"active\":true,\"minSalePrice\":0},\"searchTags\":[\"검색어\"]");
        server.createContext("/",ex->{methods.add(ex.getRequestMethod());byte[] b=payload.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});server.start();
        try{
            var editor=client(new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2),4096)).editor("123");
            assertThat(editor.basic().sellerProductId()).isEqualTo("123");assertThat(editor.basic().productId()).isEqualTo("99999999999999999999");
            assertThat(editor.limits().purchaseAttributesReadOnly()).isTrue();assertThat(editor.options().getFirst().current()).isNull();
            assertThat(editor.options().getFirst().registration()).anyMatch(f->"autoPricingInfo.active".equals(f.name())&&"true".equals(f.value()));
            assertThat(editor.options().getFirst().registration()).filteredOn(f->"searchTags".equals(f.name())).hasSize(1);
            var imported=DraftCoupangImport.convert(editor);
            assertThat(imported.markets().get(cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Market.COUPANG).coupang().options().getFirst().registration())
                .filteredOn(f->"searchTags".equals(f.name())).extracting(cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field::value).containsExactly("검색어");
            assertThat(methods).containsExactly("GET");assertThat(new ObjectMapper().writeValueAsString(editor)).doesNotContain("fake-secret","fake-access","vendorId");
        }finally{server.stop(0);}
    }
    @Test void optionSearchTagsSurviveImportAndLatestReadWithoutDuplicateSettings(){
        var mapper=new ObjectMapper();var c=client(new CoupangHttpTransport());
        for(String tags:java.util.List.of("[]","[\"검색어\"]","[\"검색어1\",\"검색어2\"]")){
            var source=mapper.readTree(DETAIL).path("data").deepCopy().asObject();
            source.set("searchTags",mapper.readTree("[\"옵션에 속하지 않은 값\"]"));
            var first=source.path("items").get(0).asObject();first.set("searchTags",mapper.readTree(tags));
            first.set("autoPricingInfo",mapper.readTree("{\"active\":false,\"minSalePrice\":0}"));
            var second=first.deepCopy();second.put("sellerProductItemId",2);second.put("itemName","화이트 / FREE");
            second.remove("autoPricingInfo");second.set("searchTags",mapper.readTree("[\"화이트\"]"));source.path("items").asArray().add(second);
            var editor=c.editorDocument(source,java.util.Map.of(),java.util.Map.of());
            assertThat(editor.delivery()).noneMatch(f->"searchTags".equals(f.name()));
            assertThat(editor.settings()).noneMatch(f->"searchTags".equals(f.name()));
            var imported=DraftCoupangImport.convert(editor);var market=cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Market.COUPANG;
            var links=imported.markets().get(market).coupang().options().stream()
                .map(o->new MarketplaceWriteGateway.OptionMapping(o.optionId(),o.sellerProductItemId(),o.vendorItemId())).toList();
            var latest=CoupangEditingProjection.latest(imported,new MarketplaceWriteGateway.Mapping("fake-account","123",links),editor);
            assertThat(latest.options()).extracting(cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Option::id)
                .containsExactlyElementsOf(imported.options().stream().map(cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Option::id).toList());
            for(var document:java.util.List.of(imported,latest)){
                var options=document.markets().get(market).coupang().options();
                for(var option:options)assertThat(option.registration()).extracting(cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field::name).doesNotHaveDuplicates();
                var expected=new java.util.ArrayList<String>();for(var value:mapper.readTree(tags))expected.add(value.asString());
                assertThat(options.getFirst().registration()).filteredOn(f->"searchTags".equals(f.name()))
                    .extracting(cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field::value)
                    .containsExactlyElementsOf(expected.isEmpty()?java.util.List.of():java.util.List.of(String.join(", ",expected)));
                assertThat(options.get(1).registration()).filteredOn(f->"searchTags".equals(f.name()))
                    .extracting(cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field::value).containsExactly("화이트");
                assertThat(options.getFirst().registration()).anyMatch(f->"autoPricingInfo.minSalePrice".equals(f.name())&&"0".equals(f.value()));
            }
        }
    }
    @Test void detailSignsPathWithNoQuery()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var path=new AtomicReference<String>();var query=new AtomicReference<String>();var auth=new AtomicReference<String>();
        server.createContext("/",ex->{path.set(ex.getRequestURI().getPath());query.set(ex.getRequestURI().getRawQuery());auth.set(ex.getRequestHeaders().getFirst("Authorization"));byte[] b=(ex.getRequestURI().getPath().equals(CoupangProductClient.PATH)?EMPTY:DETAIL).getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});server.start();
        try {
            var c=client(new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2),4096));
            assertThat(c.product("123").items()).hasSize(1);assertThat(path.get()).isEqualTo(CoupangProductClient.PATH+"/123");assertThat(query.get()).isNull();
            assertThat(auth.get()).isEqualTo(CoupangSignature.authorization("fake-access","fake-secret",CLOCK.instant(),"GET",path.get(),""));
            long start=System.nanoTime();assertThat(c.products(10,"").items()).isEmpty();assertThat(Duration.ofNanos(System.nanoTime()-start)).isGreaterThan(Duration.ofMillis(850));
        }finally{server.stop(0);}
    }
    static final String INVENTORY="{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":3000000000,\"salePrice\":0,\"amountInStock\":0,\"onSale\":true}}";
    @Test void inventorySeparatesZeroStockFromSaleStatusAndRejectsMissingValues(){
        var c=client(new CoupangHttpTransport());var current=c.parseInventory(INVENTORY.getBytes());
        assertThat(current.salePrice()).isZero();assertThat(current.amountInStock()).isZero();assertThat(current.onSale()).isTrue();assertThat(current.sellerItemId()).isEqualTo("3000000000");
        for(String invalid:new String[]{INVENTORY.replace("\"salePrice\":0","\"salePrice\":null"),INVENTORY.replace("\"amountInStock\":0","\"amountInStock\":-1"),INVENTORY.replace("true","\"true\""),"{}"})failure(()->c.parseInventory(invalid.getBytes()),RESPONSE);
    }
    @Test void detailFetchesEachDistinctApprovedOptionOnceWithSpacingAndPartialFailure()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new java.util.ArrayList<String>();var starts=new java.util.ArrayList<Long>();
        String detail=DETAIL.replace("\"vendorItemId\":null","\"vendorItemId\":100");
        detail=detail.replace("}],", "},{\"sellerProductItemId\":2,\"vendorItemId\":100,\"itemName\":\"동일 옵션\"},{\"sellerProductItemId\":3,\"vendorItemId\":101,\"itemName\":\"삭제 옵션\"},{\"sellerProductItemId\":4,\"vendorItemId\":null,\"itemName\":\"임시 옵션\"}],");
        final String responseDetail=detail;
        server.createContext("/",ex->{calls.add(ex.getRequestURI().getPath());starts.add(System.nanoTime());String response=calls.size()==1?responseDetail:ex.getRequestURI().getPath().contains("/101/")?"{\"code\":\"ERROR\"}":INVENTORY;byte[] b=response.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});server.start();
        try{
            var c=client(new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2),8192));var result=c.product("123");
            assertThat(calls).hasSize(3);assertThat(calls.get(1)).endsWith("/100/inventories");assertThat(result.items()).hasSize(4);
            assertThat(result.items().get(0).current()).isNotNull();assertThat(result.items().get(1).current()).isEqualTo(result.items().get(0).current());
            assertThat(result.items().get(2).current()).isNull();assertThat(result.items().get(2).currentError()).isNotBlank();assertThat(result.items().get(3).current()).isNull();
            assertThat(starts.get(1)-starts.get(0)).isGreaterThan(900_000_000L);assertThat(starts.get(2)-starts.get(1)).isGreaterThan(900_000_000L);
        }finally{server.stop(0);}
    }
    @Test void retriesOnlyRateLimitedInventoryWithoutReloadingDetail()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var detailCalls=new java.util.concurrent.atomic.AtomicInteger();var inventoryCalls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",ex->{
            boolean inventory=ex.getRequestURI().getPath().endsWith("/inventories");
            int status=200;String body;
            if(inventory){int attempt=inventoryCalls.incrementAndGet();status=attempt==1?429:200;body=INVENTORY;ex.getResponseHeaders().add("Retry-After","0");}
            else{detailCalls.incrementAndGet();body=DETAIL.replace("\"vendorItemId\":null","\"vendorItemId\":100");}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(status,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });server.start();
        try{
            var c=client(new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2),8192));
            var result=c.product("123");assertThat(result.items().getFirst().current()).isNotNull();
            assertThat(detailCalls.get()).isEqualTo(1);assertThat(inventoryCalls.get()).isEqualTo(2);
        }finally{server.stop(0);}
    }
    @Test void fixedHmacVector(){assertThat(CoupangSignature.authorization("fake-access","fake-secret",CLOCK.instant(),"GET","/test","x=1")).isEqualTo("CEA algorithm=HmacSHA256, access-key=fake-access, signed-date=261005T080000Z, signature=4d8be5a4550bef38dc2b105f2073090df0f1560f06141ff8ea9c1bb554e248b9");}
    @Test void parsesListsAndPreservesIdentifiersWithoutInventingSaleStatus(){
        var c=client(new CoupangHttpTransport());
        var page=c.parse(("{\"code\":\"SUCCESS\",\"nextToken\":\"0007+/=\",\"future\":true,\"data\":[{\"vendorId\":\"A000-test\",\"sellerProductId\":99999999999999999999,\"sellerProductName\":\"한글 상품\",\"statusName\":\"승인완료\",\"productId\":\"00012\",\"extra\":{}}]}").getBytes(StandardCharsets.UTF_8));
        assertThat(page.items().getFirst().sellerProductId()).isEqualTo("99999999999999999999");
        assertThat(page.items().getFirst().productId()).isEqualTo("00012");
        assertThat(page.items().getFirst().statusName()).isEqualTo("승인완료");
        assertThat(page.nextToken()).isEqualTo("0007+/=");assertThat(page.hasNext()).isTrue();
        assertThat(c.parse(EMPTY.getBytes()).items()).isEmpty();assertThat(c.parse(EMPTY.getBytes()).hasNext()).isFalse();
        for(String body:new String[]{"not-json","{\"code\":\"SUCCESS\"}","{\"code\":\"SUCCESS\",\"data\":null,\"nextToken\":\"\"}"})failure(()->c.parse(body.getBytes()),RESPONSE);
        failure(()->c.parse("{\"code\":\"ERROR\",\"message\":\"raw-secret\"}".getBytes()),REJECTED);
        failure(()->new CoupangProductClient("","","",new ObjectMapper()).products(10,""),CONFIGURATION);
    }
    @Test void signsExactEncodedWireQueryAndOmitsFirstToken()throws Exception {
        var query=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{query.set(ex.getRequestURI().getRawQuery());auth.set(ex.getRequestHeaders().getFirst("Authorization"));byte[] b=EMPTY.getBytes();ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});server.start();
        try {
            var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2),1024);
            client(transport).products(10,"");assertThat(query.get()).isEqualTo("vendorId=A000-test&maxPerPage=10");
            client(transport).products(50,"한 글+/=&");assertThat(query.get()).isEqualTo("vendorId=A000-test&maxPerPage=50&nextToken=%ED%95%9C+%EA%B8%80%2B%2F%3D%26");
            client(transport).products(10,"",new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.ProductSearch(null,"한글 &",null,null));
            assertThat(query.get()).contains("sellerProductName=%ED%95%9C%EA%B8%80+%26");
            assertThat(auth.get()).isEqualTo(CoupangSignature.authorization("fake-access","fake-secret",CLOCK.instant(),"GET",CoupangProductClient.PATH,query.get()));
        } finally {server.stop(0);}
    }
    @Test void cancelsStalledAndOversizedBodiesAndDoesNotFollowRedirects()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        server.createContext("/stall",ex->{try{ex.sendResponseHeaders(200,0);ex.getResponseBody().write('x');ex.getResponseBody().flush();Thread.sleep(1500);}catch(Exception ignored){}finally{ex.close();}});
        server.createContext("/large",ex->{try{ex.sendResponseHeaders(200,1000);ex.getResponseBody().write(new byte[1000]);}finally{ex.close();}});
        server.createContext("/redirect",ex->{ex.getResponseHeaders().set("Location","/large");ex.sendResponseHeaders(302,-1);ex.close();});server.start();
        try {
            var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(350),64);
            long start=System.nanoTime();failure(()->transport.get("/stall","a=1","fake"),TIMEOUT);assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            failure(()->transport.get("/large","a=1","fake"),RESPONSE);
            assertThat(transport.get("/redirect","a=1","fake").status()).isEqualTo(302);
        }finally{server.stop(0);pool.shutdownNow();}
    }
    @Test void parsesRetryAfterAndBoundsLongCooldowns(){
        var now=Instant.parse("2026-10-05T08:00:00Z");
        assertThat(CoupangProductClient.retryDelay(null,now)).isEqualTo(Duration.ofSeconds(5));
        assertThat(CoupangProductClient.retryDelay("invalid",now)).isEqualTo(Duration.ofSeconds(5));
        assertThat(CoupangProductClient.retryDelay("-1",now)).isEqualTo(Duration.ofSeconds(5));
        assertThat(CoupangProductClient.retryDelay("0",now)).isZero();
        assertThat(CoupangProductClient.retryDelay("2",now)).isEqualTo(Duration.ofSeconds(2));
        assertThat(CoupangProductClient.retryDelay("Mon, 5 Oct 2026 08:00:07 GMT",now)).isEqualTo(Duration.ofSeconds(7));
        assertThat(CoupangProductClient.retryDelay("Mon, 5 Oct 2026 07:59:00 GMT",now)).isZero();
        assertThat(CoupangProductClient.retryDelay("999999999999999999999",now)).isEqualTo(Duration.ofSeconds(5));
    }
    @Test void retriesOnlyFailedGetOnceAndSignsAgain()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var wires=new java.util.ArrayList<String>();var auth=new java.util.ArrayList<String>();var starts=new java.util.ArrayList<Long>();
        server.createContext("/",ex->{int n=calls.incrementAndGet();wires.add(ex.getRequestMethod()+" "+ex.getRequestURI());auth.add(ex.getRequestHeaders().getFirst("Authorization"));starts.add(System.nanoTime());ex.getResponseHeaders().set("Retry-After","0");byte[] b=EMPTY.getBytes();ex.sendResponseHeaders(n==1?429:200,b.length);ex.getResponseBody().write(b);ex.close();});server.start();
        var ticks=new AtomicInteger();Clock moving=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return CLOCK.instant().plusSeconds(ticks.getAndIncrement());}};
        try{
            var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),4096);
            var c=new CoupangProductClient("A000-test","fake-access","fake-secret",new ObjectMapper(),transport,moving);
            assertThat(c.products(10,"0007+/=").items()).isEmpty();assertThat(calls.get()).isEqualTo(2);assertThat(wires.get(0)).isEqualTo(wires.get(1)).startsWith("GET ");assertThat(auth.get(0)).isNotEqualTo(auth.get(1));assertThat(starts.get(1)-starts.get(0)).isGreaterThan(900_000_000L);
        }finally{server.stop(0);}
    }
    @Test void repeated429FailsAfterOneRetryAndLongRetryAfterDoesNotRetryEarly()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var delay=new AtomicReference<>("0");
        server.createContext("/",ex->{calls.incrementAndGet();ex.getResponseHeaders().set("Retry-After",delay.get());ex.sendResponseHeaders(429,-1);ex.close();});server.start();
        try{
            var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),4096);
            failure(()->client(transport).products(10,""),RATE_LIMIT);assertThat(calls.get()).isEqualTo(2);
            delay.set("3600");var c=client(transport);long start=System.nanoTime();failure(()->c.products(10,""),RATE_LIMIT);failure(()->c.products(10,""),RATE_LIMIT);assertThat(calls.get()).isEqualTo(3);assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        }finally{server.stop(0);}
    }
    @Test void cancellationStopsPendingRetryAndIsScopedToActor()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var entered=new CountDownLatch(1);
        server.createContext("/",ex->{calls.incrementAndGet();ex.sendResponseHeaders(429,-1);ex.close();entered.countDown();});server.start();var pool=Executors.newSingleThreadExecutor();
        String id="10000000-1000-4000-8000-100000000001";
        try{
            var c=client(new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),4096));
            var result=pool.submit(()->c.operation(1L,id,()->c.products(10,"")));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            c.cancel(2L,id);Thread.sleep(200);assertThat(result.isDone()).isFalse();
            c.cancel(1L,id);assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(MarketplaceFailure.class).satisfies(e->assertThat(((MarketplaceFailure)e.getCause()).kind()).isEqualTo(CANCELLED));
            assertThat(calls.get()).isEqualTo(1);
            c.cancel(1L,id);failure(()->c.operation(1L,id,()->c.products(10,"")),CANCELLED);assertThat(calls.get()).isEqualTo(1);
            assertThat(c.operation(1L,"10000000-1000-4000-8000-100000000002",()->"ready")).isEqualTo("ready");
        }finally{server.stop(0);pool.shutdownNow();}
    }
    @Test void cancellationCancelsStalledResponseBody()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);
        server.createContext("/",ex->{try{ex.sendResponseHeaders(200,0);ex.getResponseBody().write('x');ex.getResponseBody().flush();entered.countDown();gate.await();}catch(Exception ignored){}finally{ex.close();}});server.start();var pool=Executors.newSingleThreadExecutor();
        String id="10000000-1000-4000-8000-100000000003";
        try{var c=client(new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),4096));var future=pool.submit(()->c.operation(1L,id,()->c.products(10,"")));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();c.cancel(1L,id);assertThatThrownBy(()->future.get(1,TimeUnit.SECONDS)).hasCauseInstanceOf(MarketplaceFailure.class).satisfies(e->assertThat(((MarketplaceFailure)e.getCause()).kind()).isEqualTo(CANCELLED));}finally{gate.countDown();server.stop(0);pool.shutdownNow();}
    }
    @Test void categorizesHttpFailuresAndEnforcesOneRequestAndStartInterval()throws Exception {
        var status=new AtomicInteger(401);var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{calls.incrementAndGet();entered.countDown();try{gate.await();byte[] b=EMPTY.getBytes();ex.sendResponseHeaders(status.get(),b.length);ex.getResponseBody().write(b);}catch(Exception ignored){}finally{ex.close();}});server.start();
        var pool=Executors.newSingleThreadExecutor();
        try {
            var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),1024);var c=client(transport);
            var request=pool.submit(()->{failure(()->c.products(10,""),AUTHENTICATION);});assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();failure(()->c.products(10,""),BUSY);gate.countDown();request.get();failure(()->c.products(10,""),AUTHENTICATION);assertThat(calls.get()).isEqualTo(2);
            for(int code:new int[]{403,429,500}){status.set(code);failure(()->client(transport).products(10,""),code==403?PERMISSION:code==429?RATE_LIMIT:UPSTREAM);}
        }finally{gate.countDown();server.stop(0);pool.shutdownNow();}
    }
}
