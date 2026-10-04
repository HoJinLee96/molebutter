package cc.ataglace.molebutter.infra.product;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.ProcurementMall;

@SuppressWarnings({"unchecked","rawtypes"})
class SupplierTransportTest {
    HttpClient client=mock(HttpClient.class);
    MallOptionGateway gateway=new MallOptionGateway(new ObjectMapper(),client);
    void response(int status,String body)throws Exception {
        HttpResponse<byte[]> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));
    }
    private void inspect(){gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://shopping.naver.com/window-products/department/42");}
    @Test void finalTransportFailuresAreClassifiedWithoutExposingExceptionText()throws Exception{
        for(var error:java.util.List.of(new HttpTimeoutException("secret timeout"),new IOException("secret connection"))){
            reset(client);when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(error));
            assertThatThrownBy(this::inspect).isInstanceOfSatisfying(SupplierLookupFailure.class,e->{
                assertThat(e.code()).isEqualTo(error instanceof HttpTimeoutException?SupplierLookupFailure.Code.TIMEOUT:SupplierLookupFailure.Code.NETWORK);
                assertThat(e.stage()).isEqualTo("FETCH");assertThat(e.getMessage()).doesNotContain("secret");
            });
            verify(client,times(2)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        }
        reset(client);response(500,"");
        assertThatThrownBy(this::inspect).isInstanceOfSatisfying(SupplierLookupFailure.class,e->{assertThat(e.code()).isEqualTo(SupplierLookupFailure.Code.HTTP);assertThat(e.httpStatus()).isEqualTo(500);assertThat(e.getMessage()).isEqualTo("재고 조회 실패 · HTTP 500");});
        verify(client,times(2)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void malformedAndMismatchingResponsesHaveSeparateReasons()throws Exception{
        response(200,"{secret-broken-json");
        assertThatThrownBy(this::inspect).isInstanceOfSatisfying(SupplierLookupFailure.class,e->{assertThat(e.code()).isEqualTo(SupplierLookupFailure.Code.RESPONSE_FORMAT);assertThat(e.stage()).isEqualTo("PARSE");assertThat(e.getMessage()).isEqualTo("상품 응답 형식 오류");});
        reset(client);response(200,"{\"_id\":\"other\"}");
        assertThatThrownBy(this::inspect).isInstanceOfSatisfying(SupplierLookupFailure.class,e->{assertThat(e.code()).isEqualTo(SupplierLookupFailure.Code.PRODUCT_MISMATCH);assertThat(e.stage()).isEqualTo("IDENTITY");});
        reset(client);response(200,"{\"_id\":\"42\",\"contents\":{\"id\":42,\"optionUsable\":true,\"stockQuantity\":50,\"optionCombinations\":[]}}");
        var d=gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://shopping.naver.com/window-products/department/42");
        assertThat(d.options()).isEmpty();assertThat(d.optionsComplete()).isFalse();
    }
    @Test void sendsOnlyPublicHeadersAndParsesStoreWithoutInventory()throws Exception {
        response(200,"{\"_id\":\"42\",\"channel\":{\"storeCategory\":{\"wholeNames\":[\"현대백화점\",\"천호점\"],\"wholeIds\":[\"1\",\"2\"]}}}");
        var result=gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://shopping.naver.com/window-products/department/42");
        assertThat(result.storeEvidence().name()).isEqualTo("천호점");assertThat(result.options()).isEmpty();
        var request=ArgumentCaptor.forClass(HttpRequest.class);verify(client).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("User-Agent")).isPresent();assertThat(request.getValue().headers().firstValue("Accept-Language")).hasValue("ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");assertThat(request.getValue().headers().firstValue("Cookie")).isEmpty();assertThat(request.getValue().headers().firstValue("Authorization")).isEmpty();
    }
    @Test void lotteHttpSuccessStillRequiresBusinessSuccessAndMatchingProduct()throws Exception {
        response(200,"{\"returnCode\":\"500\",\"data\":{\"basicInfo\":{\"sitmNo\":\"LO1_2\"}}}");
        assertThatThrownBy(()->gateway.inspect(ProcurementMall.LOTTE_ON,"LO1_2","https://www.lotteon.com/p/product/LO1?sitmNo=LO1_2")).hasMessageContaining("상품 응답");
        reset(client);response(200,"{\"returnCode\":\"200\",\"data\":{\"basicInfo\":{\"sitmNo\":\"OTHER\"}}}");
        assertThatThrownBy(()->gateway.inspect(ProcurementMall.LOTTE_ON,"LO1_2","https://www.lotteon.com/p/product/LO1?sitmNo=LO1_2")).hasMessageContaining("상품 응답");
    }
    @Test void ordinaryStoreIsRejectedBeforeTransport(){
        assertThatThrownBy(()->gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://smartstore.naver.com/lotte/products/42")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
    @Test void noContentDoesNotInventAnOnlineStore()throws Exception {
        response(204,"");assertThat(gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://shopping.naver.com/window-products/department/42").storeEvidence()).isNull();
    }
    @Test void redirectAndRestrictionAreNotFollowedOrRetried()throws Exception {
        response(302,"");assertThatThrownBy(()->gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://shopping.naver.com/window-products/department/42")).hasMessageContaining("302");verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        reset(client);response(429,"");assertThatThrownBy(()->gateway.inspect(ProcurementMall.NAVER_SMART_STORE,"42","https://shopping.naver.com/window-products/department/42")).isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessageContaining("[쇼핑몰 재고 조회 제한]","네이버 쇼핑윈도","HTTP 429");verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void blockReportsSupplierAndHttpStatusWithoutRetry()throws Exception {
        response(403,"<h1>403 Forbidden</h1>");
        assertThatThrownBy(()->gateway.inspect(ProcurementMall.LOTTE_IMALL,"3258294034","https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no=3258294034"))
            .isInstanceOf(NaverPriceSearch.SearchBlocked.class).hasMessage("[쇼핑몰 재고 조회 제한] 롯데홈쇼핑 · HTTP 403. 잠시 후 재개해 주세요.");
        verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }

    @Test void stalledResponseBodyTimesOutAndRetriesWithRealHttp()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var threads=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var release=new java.util.concurrent.CountDownLatch(1);var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.setExecutor(threads);
        server.createContext("/",exchange->{
            requests.incrementAndGet();exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()) {
                out.write('a');out.flush();
                try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
        });server.start();
        try(var realHttp=HttpClient.newHttpClient()) {
            var realGateway=new MallOptionGateway(new ObjectMapper(),realHttp,java.time.Duration.ofMillis(500));
            long start=System.nanoTime();
            assertThatThrownBy(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(realGateway,"fetch",ProcurementMall.HAZZYS,"test","http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                .isInstanceOfSatisfying(SupplierLookupFailure.class,e->assertThat(e.code()).isEqualTo(SupplierLookupFailure.Code.TIMEOUT));
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(5000);
            assertThat(requests.get()).isEqualTo(2);
        }finally{release.countDown();server.stop(0);threads.shutdownNow();}
    }

    @Test void bodyLimitAndAbortCancelSubscriptionWithoutTreatingDataAsComplete() {
        for(boolean overflow:java.util.List.of(true,false)) {
            var subscriber=new MallOptionGateway.LimitedBodySubscriber();
            var subscription=mock(java.util.concurrent.Flow.Subscription.class);subscriber.onSubscribe(subscription);
            if(overflow)subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(5_000_001)));
            else subscriber.abort(new HttpTimeoutException("test timeout"));
            assertThatThrownBy(()->subscriber.getBody().toCompletableFuture().join()).hasCauseInstanceOf(overflow?SupplierLookupFailure.class:HttpTimeoutException.class);
            verify(subscription).cancel();
        }
    }

    @Test void realHttpRejectsRestrictedHeadersAndOversizedBody()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var threads=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var release=new java.util.concurrent.CountDownLatch(1);var restrictedRequests=new java.util.concurrent.atomic.AtomicInteger();
        server.setExecutor(threads);
        server.createContext("/restricted",exchange->{
            restrictedRequests.incrementAndGet();exchange.sendResponseHeaders(429,0);
            try(var out=exchange.getResponseBody()) {
                try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
        });
        server.createContext("/large",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()){out.write(new byte[5_000_001]);}catch(IOException ignored){/* client cancels oversized response */}
        });server.start();
        try(var realHttp=HttpClient.newHttpClient()) {
            var realGateway=new MallOptionGateway(new ObjectMapper(),realHttp,java.time.Duration.ofSeconds(2));
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            assertThatThrownBy(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(realGateway,"fetch",ProcurementMall.HAZZYS,"test",url+"/restricted"))
                .isInstanceOfSatisfying(NaverPriceSearch.SearchBlocked.class,e->assertThat(e.httpStatus()).isEqualTo(429));
            assertThat(restrictedRequests.get()).isEqualTo(1);
            assertThatThrownBy(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(realGateway,"fetch",ProcurementMall.HAZZYS,"test",url+"/large"))
                .isInstanceOfSatisfying(SupplierLookupFailure.class,e->{assertThat(e.code()).isEqualTo(SupplierLookupFailure.Code.RESPONSE_FORMAT);assertThat(e.stage()).isEqualTo("READ");});
        }finally{release.countDown();server.stop(0);threads.shutdownNow();}
    }

}
