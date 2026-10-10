package cc.ataglace.molebutter.marketplacecoupang.internal;


import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CoupangOrderClientTest {
    static final String ORDER="""
        {"code":200,"data":[{"orderId":9007199254740993,"shipmentBoxId":123456789012345680,
         "status":"INSTRUCT","orderedAt":"2026-10-01T23:59:00+09:00","paidAt":"2026-10-02T00:00:00+09:00",
         "seller":{"sellerId":"A-test"},"orderer":{"name":"PRIVATE_ORDERER"},
         "receiver":{"name":"PRIVATE_RECEIVER","safeNumber":"PRIVATE_PHONE","addr1":"PRIVATE_ADDRESS","addr2":"PRIVATE_DETAIL","postCode":"00123"},
         "overseaShippingInfoDto":{"personalCustomsClearanceCode":"PRIVATE_PCCC","otpNumber":"PRIVATE_OTP"},
         "parcelPrintMessage":"PRIVATE_MEMO","orderItems":[{"sequenceNo":"001","vendorItemId":100,"sellerProductId":200,
          "sellerProductName":"상품","sellerProductItemName":"옵션","externalVendorSkuCode":"SKU-1",
          "shippingCount":3,"cancelCount":1,"holdCountForCancel":0,
          "salesPrice":{"currencyCode":"KRW","units":1000,"nanos":500000000},"orderPrice":null}]}],"nextToken":"next +/&"}
        """;
    CoupangOrderClient client(boolean verified){
        var json=new ObjectMapper();var product=new CoupangProductClient("A-test","fake-access","fake-secret",json,new CoupangHttpTransport(),Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"),ZoneOffset.UTC));
        return new CoupangOrderClient(product,json,verified);
    }
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void storedSnapshotsKeepExactIdsAndMoneyWithoutPii(){
        var parsed=client(false).parseOrders(bytes(ORDER),null,false);var row=parsed.orders().getFirst().items().getFirst();
        assertThat(row.orderId()).isEqualTo("9007199254740993");assertThat(row.shipmentBoxId()).isEqualTo("123456789012345680");
        assertThat(row.sellerProductCode()).isEqualTo("SKU-1");assertThat(row.unitPrice().amount()).isEqualTo("1000.5");assertThat(row.orderPrice()).isNull();
        assertThat(row.quantity()).isEqualTo(3);assertThat(row.cancelQuantity()).isEqualTo(1);assertThat(row.holdQuantity()).isZero();
        assertThat(parsed.nextCursor()).isEqualTo("next +/&");assertThat(parsed.orders().getFirst().fullDetailValid()).isFalse();
        assertThat(new ObjectMapper().writeValueAsString(parsed)).doesNotContain("PRIVATE_","receiver","orderer","overseaShippingInfoDto","parcelPrintMessage");
        assertThat(row.statusUpdatedAt()).isNull();
    }
    @Test void completeDetailAcceptsDocumentedStringCodeButRejectsWrongOrderAccountOrIncompleteItems(){
        assertThat(client(false).parseOrders(bytes(ORDER.replace("\"code\":200","\"code\":\"200\"")),"9007199254740993",true).orders().getFirst().fullDetailValid()).isTrue();
        for(String body:List.of(ORDER.replace("\"orderId\":9007199254740993","\"vendorId\":\"other-account\",\"orderId\":9007199254740993"),ORDER.replace("\"shippingCount\":3","\"shippingCount\":null"),ORDER.replace("\"vendorItemId\":100","\"vendorItemId\":100.5"),ORDER.replace("\"sequenceNo\":\"001\"","\"sequenceNo\":\"oops\""),"{\"code\":200,\"data\":[]}")){
            assertThatThrownBy(()->client(false).parseOrders(bytes(body),"9007199254740993",true)).isInstanceOf(MarketplaceFailure.class);
        }
        assertThatThrownBy(()->client(false).parseOrders(bytes(ORDER),"other-order",true)).isInstanceOf(MarketplaceFailure.class);
        assertThatThrownBy(()->client(false).parseOrders(bytes(ORDER.replace("200,\"data\"","400,\"data\"")),null,false)).isInstanceOf(MarketplaceFailure.class).satisfies(e->assertThat(((MarketplaceFailure)e).kind()).isEqualTo(MarketplaceFailure.Kind.ORDER_REJECTED));
    }
    @Test void missingCursorCannotSilentlyMarkListCompleteAndDuplicateObservationFails(){
        assertThatThrownBy(()->client(false).parseOrders(bytes(ORDER.replace(",\"nextToken\":\"next +/&\"","")),null,false)).isInstanceOf(MarketplaceFailure.class);
        String item="{\"sequenceNo\":\"001\",\"vendorItemId\":100,\"shippingCount\":1}";
        String duplicate="{\"code\":200,\"data\":[{\"orderId\":1,\"shipmentBoxId\":2,\"status\":\"ACCEPT\",\"orderItems\":["+item+","+item+"]}],\"nextToken\":\"\"}";
        assertThatThrownBy(()->client(false).parseOrders(bytes(duplicate),null,false)).isInstanceOf(MarketplaceFailure.class);
    }
    @Test void unknownStatusIsPreservedAndNullQuantitiesStayUnknown(){
        var row=client(false).parseOrders(bytes(ORDER.replace("INSTRUCT","NEW_STATUS").replace("\"cancelCount\":1","\"cancelCount\":null")),null,false).orders().getFirst().items().getFirst();
        assertThat(row.status()).isEqualTo("NEW_STATUS");assertThat(row.cancelQuantity()).isNull();
    }
    @Test void defaultUnverifiedClaimsPreventFullStatusSuccessWithoutCallingNetwork(){
        var c=client(false);assertThat(c.streams()).contains("CLAIMS_UNVERIFIED").doesNotContain("RETURN_RU");
        var gate=c.fetch("CLAIMS_UNVERIFIED",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-07"),null);
        assertThat(gate.complete()).isFalse();assertThat(gate.message()).contains("전체 상태");
        assertThatThrownBy(()->c.returnClaim("123")).isInstanceOf(MarketplaceFailure.class);
    }
    @Test void claimProjectionSeparatesIdsAndExcludesCustomerReasonsAndAddress(){
        String body="""
          {"code":200,"data":[{"receiptId":55,"orderId":1,"receiptStatus":"RETURNS_UNCHECKED","modifiedAt":"2026-10-07T12:00:00",
          "requesterAddress":"PRIVATE_ADDRESS","cancelReason":"PRIVATE_FREE_TEXT","returnItems":[{"shipmentBoxId":2,"vendorItemId":3,"cancelCount":1}]}],"nextToken":""}
          """;
        assertThatThrownBy(()->client(true).parseClaims(bytes(body.replace(",\"nextToken\":\"\"","")),"RETURN")).isInstanceOf(MarketplaceFailure.class);
        assertThatThrownBy(()->client(true).parseClaims(bytes(body.replace("\"receiptId\":55","\"receiptType\":\"CANCEL\",\"receiptId\":55")),"RETURN")).isInstanceOf(MarketplaceFailure.class);
        var page=client(true).parseClaims(bytes(body),"RETURN");var claim=page.claims().getFirst();
        assertThat(claim.summary().id()).isEqualTo("55");assertThat(claim.summary().type()).isEqualTo("RETURN");assertThat(claim.summary().linked()).isFalse();
        assertThat(claim.shipmentBoxId()).isEqualTo("2");assertThat(claim.vendorItemId()).isEqualTo("3");assertThat(new ObjectMapper().writeValueAsString(page)).doesNotContain("PRIVATE_","cancelReason","requesterAddress");
        var withdrawn=client(true).parseClaims(bytes("{\"code\":200,\"data\":[{\"cancelId\":55,\"orderId\":1,\"createdAt\":\"2026-10-07T12:00:00\",\"vendorItemIds\":[3]}],\"nextPageIndex\":\"2\"}"),"WITHDRAWN");
        assertThat(withdrawn.nextCursor()).isEqualTo("2");assertThat(withdrawn.claims().getFirst().summary().status()).isEqualTo("WITHDRAWN");
    }
    @Test void malformedObservationTimestampsCannotReachStorage(){
        assertThatThrownBy(()->client(false).parseOrders(bytes(ORDER.replace("2026-10-01T23:59:00+09:00","025-01-15T14:17:13-08:00")),null,false)).isInstanceOf(MarketplaceFailure.class);
        String malformed="{\"code\":200,\"data\":[{\"cancelId\":55,\"orderId\":1,\"createdAt\":\"not-a-timestamp\",\"vendorItemIds\":[3]}],\"nextPageIndex\":\"\"}";
        assertThatThrownBy(()->client(true).parseClaims(bytes(malformed),"WITHDRAWN")).isInstanceOf(MarketplaceFailure.class);
    }
    @Test void listDetailAndWithdrawalUseTheSharedSignedReadTransport()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var requests=new ArrayList<String>();var bodies=new ArrayList<String>();
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().toString());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String result=exchange.getRequestMethod().equals("POST")?"{\"code\":\"200\",\"data\":[{\"cancelId\":55,\"orderId\":9007199254740993,\"vendorId\":\"A-test\",\"createdAt\":\"2026-10-07T12:00:00\",\"vendorItemIds\":[100]}]}":ORDER;
            byte[] bytes=bytes(result);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            var json=new ObjectMapper();var transport=new CoupangHttpTransport(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2),100000);
            var product=new CoupangProductClient("A-test","fake-access","fake-secret",json,transport,Clock.systemUTC());var c=new CoupangOrderClient(product,json,false);
            var discovered=c.fetch("INSTRUCT",LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-07"),"next +/&");
            assertThat(discovered.orders().getFirst().fullDetailValid()).isFalse();
            var live=c.detail("9007199254740993");assertThat(live.shipments().getFirst().recipientName()).isEqualTo("PRIVATE_RECEIVER");
            assertThat(new ObjectMapper().writeValueAsString(live)).doesNotContain("PRIVATE_PCCC","PRIVATE_OTP","PRIVATE_ORDERER","overseaShippingInfoDto");
            assertThat(c.withdrawalClaims(List.of("55")).claims().getFirst().summary().status()).isEqualTo("WITHDRAWN");
            assertThat(requests.getFirst()).contains("createdAtFrom=2026-10-01%2B09%3A00","nextToken=next+%2B%2F%26");
            assertThat(requests.get(1)).endsWith("/9007199254740993/ordersheets");assertThat(requests.get(2)).startsWith("POST ").endsWith("/returnWithdrawList");assertThat(bodies.get(2)).isEqualTo("{\"cancelIds\":[55]}");
        }finally{server.stop(0);}
    }
    @Test void sevenInclusiveDayWindowRejectsLongerSpan(){
        CoupangOrderClient.window(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-07"),7);
        assertThatThrownBy(()->CoupangOrderClient.window(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-08"),7)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test void rejectionReasonsAreWhitelistedAndUnknownBodiesNeverLeak(){
        var reasons=Map.of("해당 주문이 취소 또는 반품 되었습니다.",MarketplaceFailure.Kind.ORDER_UNAVAILABLE,
            "유효하지 않은 주문번호 입니다.",MarketplaceFailure.Kind.ORDER_INVALID,
            "다른 판매자의 주문을 조회할 수 없습니다.",MarketplaceFailure.Kind.ORDER_ACCOUNT_MISMATCH);
        reasons.forEach((message,kind)->assertThat(CoupangProductClient.orderRejection(bytes("{\"message\":\""+message+"\"}"))).isEqualTo(kind));
        for(String body:List.of("not JSON PRIVATE", "{\"message\":\"PRIVATE_SECRET\"}","{}")){
            var kind=CoupangProductClient.orderRejection(bytes(body));assertThat(kind).isEqualTo(MarketplaceFailure.Kind.ORDER_REJECTED);
            assertThat(new MarketplaceFailure(kind).getMessage()).doesNotContain("PRIVATE");
        }
    }
    @Test void claimProductMetadataIsPreservedWithoutInventingOrderMoney(){
        String body="""
          {"code":200,"data":[{"receiptId":55,"orderId":1,"receiptStatus":"RETURNS_COMPLETED","modifiedAt":"2026-10-07T12:00:00",
          "createdAt":"2026-10-06T12:00:00","receiver":"PRIVATE","returnItems":[{"shipmentBoxId":2,"vendorItemId":3,
          "sellerProductId":9007199254740993,"sellerProductName":"모의 상품","vendorItemName":"블랙","cancelCount":1,"purchaseCount":2}]}],"nextToken":""}
          """;
        var claim=client(true).parseClaims(bytes(body),"RETURN").claims().getFirst().summary();
        assertThat(claim.productName()).isEqualTo("모의 상품");assertThat(claim.optionName()).isEqualTo("블랙");
        assertThat(claim.sellerProductId()).isEqualTo("9007199254740993");assertThat(claim.purchaseQuantity()).isEqualTo(2);
        assertThat(claim.quantity()).isEqualTo(1);assertThat(new ObjectMapper().writeValueAsString(claim)).doesNotContain("PRIVATE","orderPrice");
    }
}
