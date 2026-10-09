package cc.ataglace.molebutter.marketplacenaver.internal;


import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway.Response;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;

final class NaverHttpTransport {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final Duration timeout;
    private final int limit;
    NaverHttpTransport(){this(URI.create("https://api.commerce.naver.com/external/"),Duration.ofSeconds(30),10*1024*1024);}
    NaverHttpTransport(URI base,Duration timeout,int limit){this.base=base;this.timeout=timeout;this.limit=limit;}
    Response send(String method,String path,String query,String token,String contentType,byte[] payload){
        if(!path.startsWith("/v1/")&&!path.startsWith("/v2/"))throw new IllegalArgumentException("Unexpected commerce path");
        if(Thread.currentThread().isInterrupted())throw failure(CANCELLED);
        var subscriber=new LimitedBody(limit);
        var builder=HttpRequest.newBuilder(base.resolve(path.substring(1)+(query.isEmpty()?"":"?"+query)))
            .timeout(timeout).header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(contentType!=null)builder.header("Content-Type",contentType);
        var request=builder.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        var future=client.sendAsync(request,info->subscriber);
        try{
            var response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            var headers=new LinkedHashMap<String,String>();
            for(String name:List.of("Retry-After","GNCP-GW-RateLimit-Replenish-Rate","GNCP-GW-RateLimit-Burst-Capacity","GNCP-GW-RateLimit-Remaining","GNCP-GW-Quota-Remaining"))
                response.headers().firstValue(name).ifPresent(value->headers.put(name,value));
            return new Response(response.statusCode(),response.body(),response.headers().firstValue("Retry-After").orElse(null),Map.copyOf(headers));
        }catch(TimeoutException e){throw failure(TIMEOUT);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw failure(CANCELLED);}
        catch(ExecutionException e){
            if(e.getCause() instanceof MarketplaceFailure f)throw f;
            if(e.getCause() instanceof HttpTimeoutException)throw failure(TIMEOUT);
            throw failure(NETWORK);
        }finally{subscriber.cancel();future.cancel(true);}
    }
    private static MarketplaceFailure failure(MarketplaceFailure.Kind kind){return new MarketplaceFailure(kind,"NAVER");}
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return body;}
        public synchronized void onSubscribe(Flow.Subscription value){subscription=value;if(body.isDone())value.cancel();else value.request(1);}
        public synchronized void onNext(List<ByteBuffer> chunks){
            if(body.isDone())return;
            for(var chunk:chunks){if(chunk.remaining()>limit-bytes.size()){body.completeExceptionally(failure(RESPONSE));subscription.cancel();return;}
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        public void onError(Throwable e){body.completeExceptionally(e);}
        public synchronized void onComplete(){body.complete(bytes.toByteArray());}
        synchronized void cancel(){body.cancel(true);if(subscription!=null)subscription.cancel();}
    }
}
