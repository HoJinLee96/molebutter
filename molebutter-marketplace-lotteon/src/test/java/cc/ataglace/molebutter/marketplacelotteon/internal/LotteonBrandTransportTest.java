package cc.ataglace.molebutter.marketplacelotteon.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import cc.ataglace.molebutter.marketplacelotteon.api.LotteonBrandFailure;

class LotteonBrandTransportTest {
    private HttpServer server;
    @BeforeEach void server()throws Exception{server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.start();}
    @AfterEach void stop(){server.stop(0);}
    private LotteonBrandTransport transport(Duration timeout,int limit){return new LotteonBrandTransport(URI.create("http://localhost:"+server.getAddress().getPort()+"/cheetah/econCheetah.ecn"),timeout,limit);}
    @Test void documentedGetEndpointNameEncodingAndHeaderContractAreUsed(){
        var problem=new AtomicReference<Throwable>();
        server.createContext("/cheetah/econCheetah.ecn",exchange->{
            try{
                assertEquals("GET",exchange.getRequestMethod());
                assertEquals("job=cheetahBrnd&skip=0&limit=100&mf_1=%EB%8B%A5%EC%8A%A4+%26+%EB%B8%8C%EB%9E%9C%EB%93%9C",exchange.getRequestURI().getRawQuery());
                assertEquals("Bearer fixture-key",exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("application/json",exchange.getRequestHeaders().getFirst("Accept"));
                assertEquals("ko",exchange.getRequestHeaders().getFirst("Accept-Language"));assertEquals("GMT+09:00",exchange.getRequestHeaders().getFirst("X-Timezone"));
                assertEquals(-1,exchange.getRequestBody().read());
            }catch(Throwable error){problem.set(error);}
            byte[] body="{\"itemList\":[]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        var result=transport(Duration.ofSeconds(2),1024).search("닥스 & 브랜드","fixture-key");
        assertEquals(200,result.status());assertNull(problem.get());
    }
    @Test void redirectIsNotFollowedAndOversizedBodyIsRejected(){
        var redirected=new AtomicReference<Boolean>(false);
        server.createContext("/redirect",exchange->{redirected.set(true);exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/cheetah/econCheetah.ecn",exchange->{exchange.getResponseHeaders().add("Location","/redirect");exchange.sendResponseHeaders(302,-1);exchange.close();});
        assertEquals(302,transport(Duration.ofSeconds(2),1024).search("닥스","fixture-key").status());assertFalse(redirected.get());
        server.removeContext("/cheetah/econCheetah.ecn");server.createContext("/cheetah/econCheetah.ecn",exchange->{exchange.sendResponseHeaders(200,128);try{exchange.getResponseBody().write(new byte[128]);}finally{exchange.close();}});
        assertEquals(LotteonBrandFailure.Kind.RESPONSE,assertThrows(LotteonBrandFailure.class,()->transport(Duration.ofSeconds(2),64).search("닥스","fixture-key")).kind());
    }
    @Test void responseBodyDeadlineCancelsStalledResponse(){
        var received=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/cheetah/econCheetah.ecn",exchange->{exchange.sendResponseHeaders(200,100);received.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
        try{assertEquals(LotteonBrandFailure.Kind.TIMEOUT,assertThrows(LotteonBrandFailure.class,()->transport(Duration.ofMillis(250),1024).search("닥스","fixture-key")).kind());assertEquals(0,received.getCount());}
        finally{release.countDown();}
    }
}
