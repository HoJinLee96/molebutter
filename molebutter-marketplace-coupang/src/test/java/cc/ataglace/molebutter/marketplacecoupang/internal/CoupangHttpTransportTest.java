package cc.ataglace.molebutter.marketplacecoupang.internal;


import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoupangHttpTransportTest {
    record Received(String method,String uri,String length,String upgrade,String transfer,int bytes) {}
    @Test void bodylessPriceOriginalPriceAndQuantityPutsSendExplicitZeroLengthWithoutHttp2Upgrade()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var received=new LinkedBlockingQueue<Received>();
        server.createContext("/",exchange->{
            var headers=exchange.getRequestHeaders();var body=exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestMethod(),exchange.getRequestURI().toString(),headers.getFirst("Content-Length"),headers.getFirst("Upgrade"),headers.getFirst("Transfer-Encoding"),body.length));
            var response="{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(headers.getFirst("Content-Length")==null?411:200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try{
            var transport=new CoupangHttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),1024);
            for(String suffix:new String[]{"prices/212000?forceSalePriceUpdate=false","original-prices/398000","quantities/51"}){
                var parts=suffix.split("\\?",2);String path="/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/123/"+parts[0],query=parts.length==2?parts[1]:"";
                assertThat(transport.send("PUT",path,query,"synthetic-test",null,()->false).status()).isEqualTo(200);
                assertThat(received.take()).isEqualTo(new Received("PUT",path+(query.isEmpty()?"":"?"+query),"0",null,null,0));
            }
            byte[] json="{\"requested\":true}".getBytes(StandardCharsets.UTF_8);
            assertThat(transport.send("PUT","/product","","synthetic-test",json,()->false).status()).isEqualTo(200);
            var actual=received.take();assertThat(actual.length()).isEqualTo(Integer.toString(json.length));assertThat(actual.bytes()).isEqualTo(json.length);
        }finally{server.stop(0);}
    }
}
