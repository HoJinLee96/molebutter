package cc.ataglace.molebutter.marketplacelotteon.internal;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import cc.ataglace.molebutter.marketplacelotteon.api.LotteonBrandFailure;
import static cc.ataglace.molebutter.marketplacelotteon.api.LotteonBrandFailure.Kind.*;

/** Fixed read endpoint; redirects never receive the bearer credential. */
final class LotteonBrandTransport {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final Duration timeout;
    private final int limit;
    record Response(int status,byte[] body,String retryAfter) {}
    LotteonBrandTransport(){this(URI.create("https://onpick-api.lotteon.com/cheetah/econCheetah.ecn"),Duration.ofSeconds(20),1024*1024);}
    LotteonBrandTransport(URI endpoint,Duration timeout,int limit){this.endpoint=endpoint;this.timeout=timeout;this.limit=limit;}
    Response search(String name,String key){
        if(Thread.currentThread().isInterrupted())throw new LotteonBrandFailure(CANCELLED);
        String query="job=cheetahBrnd&skip=0&limit=100&mf_1="+URLEncoder.encode(name,StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(URI.create(endpoint.toString()+"?"+query)).timeout(timeout)
            .header("Accept","application/json").header("Accept-Language","ko").header("X-Timezone","GMT+09:00")
            .header("Authorization","Bearer "+key).GET().build();
        var subscriber=new LimitedBody(limit);var future=client.sendAsync(request,info->subscriber);
        try{
            var response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(),response.body(),response.headers().firstValue("Retry-After").orElse(null));
        }catch(TimeoutException e){throw new LotteonBrandFailure(TIMEOUT);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new LotteonBrandFailure(CANCELLED);}
        catch(ExecutionException e){
            if(e.getCause() instanceof LotteonBrandFailure failure)throw failure;
            if(e.getCause() instanceof HttpTimeoutException)throw new LotteonBrandFailure(TIMEOUT);
            throw new LotteonBrandFailure(NETWORK);
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
            for(var chunk:chunks){
                if(chunk.remaining()>limit-bytes.size()){
                    body.completeExceptionally(new LotteonBrandFailure(RESPONSE));subscription.cancel();return;
                }
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable e){body.completeExceptionally(e);}
        public synchronized void onComplete(){body.complete(bytes.toByteArray());}
        synchronized void cancel(){body.cancel(true);if(subscription!=null)subscription.cancel();}
    }
}
