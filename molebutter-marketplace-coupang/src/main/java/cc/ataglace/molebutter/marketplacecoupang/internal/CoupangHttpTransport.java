package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;
final class CoupangHttpTransport {
    record Response(int status, byte[] body, String retryAfter) {
        Response(int status,byte[] body){this(status,body,null);}
    }
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final Duration timeout;
    private final int limit;
    CoupangHttpTransport(){this(URI.create("https://api-gateway.coupang.com"),Duration.ofSeconds(20),5*1024*1024);}
    CoupangHttpTransport(URI base,Duration timeout,int limit){this.base=base;this.timeout=timeout;this.limit=limit;}
    Response get(String path,String query,String authorization){return get(path,query,authorization,()->false);}
    Response get(String path,String query,String authorization,java.util.function.BooleanSupplier cancelled){
        return send("GET",path,query,authorization,null,cancelled);
    }
    Response send(String method,String path,String query,String authorization,byte[] payload,java.util.function.BooleanSupplier cancelled){
        if(cancelled.getAsBoolean())throw new MarketplaceFailure(CANCELLED);
        var subscriber=new LimitedBody(limit);
        var builder=HttpRequest.newBuilder(base.resolve(path+(query.isEmpty()?"":"?"+query))).timeout(timeout).header("Authorization",authorization).header("Accept","application/json");
        // JDK 21 HTTP/2 omits Content-Length for an empty body. Coupang's gateway
        // requires it on bodyless writes; HTTP/1.1 emits Content-Length: 0 itself.
        if((method.equals("PUT")||method.equals("POST"))&&(payload==null||payload.length==0))builder.version(HttpClient.Version.HTTP_1_1);
        if(payload!=null)builder.header("Content-Type","application/json");
        var request=builder.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        var future=client.sendAsync(request,info->subscriber);
        long deadline=System.nanoTime()+timeout.toNanos();
        try {
            while(true){
                if(cancelled.getAsBoolean())throw new MarketplaceFailure(CANCELLED);
                long left=deadline-System.nanoTime();if(left<=0)throw new MarketplaceFailure(TIMEOUT);
                try{var response=future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);return new Response(response.statusCode(),response.body(),response.headers().firstValue("Retry-After").orElse(null));}
                catch(TimeoutException waiting){/* Check cancellation without extending the whole-body deadline. */}
            }
        }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new MarketplaceFailure(NETWORK);}
        catch(ExecutionException e){
            if(e.getCause() instanceof MarketplaceFailure failure)throw failure;
            if(e.getCause() instanceof HttpTimeoutException)throw new MarketplaceFailure(TIMEOUT);
            throw new MarketplaceFailure(NETWORK);
        }finally{subscriber.cancel();future.cancel(true);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return body;}
        public synchronized void onSubscribe(Flow.Subscription value){subscription=value;if(body.isDone())value.cancel();else value.request(1);}
        public synchronized void onNext(List<ByteBuffer> chunks){
            if(body.isDone())return;
            for(var chunk:chunks){if(chunk.remaining()>limit-bytes.size()){body.completeExceptionally(new MarketplaceFailure(RESPONSE));subscription.cancel();return;}
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        public void onError(Throwable e){body.completeExceptionally(e);}
        public synchronized void onComplete(){body.complete(bytes.toByteArray());}
        synchronized void cancel(){body.cancel(true);if(subscription!=null)subscription.cancel();}
    }
}
