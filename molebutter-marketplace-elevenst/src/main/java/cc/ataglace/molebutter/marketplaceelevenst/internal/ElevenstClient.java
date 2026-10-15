package cc.ataglace.molebutter.marketplaceelevenst.internal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;
@Component
final class ElevenstClient {
 record Response(int status,byte[] bytes) {}
 interface Transport {Response send(String method,String path,byte[] body,boolean authenticated);}
 private final HttpClient httpClient=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final String key;private final Transport transport;private final long spacing;private long next;private final ReentrantLock lock=new ReentrantLock(true);
 @Autowired ElevenstClient(@Value("${marketplace.elevenst.api-key:}")String key){this(key,null,1000);}
 ElevenstClient(String key,Transport transport,long spacing){this.key=key==null?"":key.trim();this.transport=transport==null?this::http:transport;this.spacing=spacing;}
 String account(){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("ELEVENST\n"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 <T>T session(Supplier<T> work){if(key.isBlank())throw new MarketplaceFailure(CONFIGURATION,"ELEVENST");lock.lock();try{return work.get();}finally{lock.unlock();}}
 Element read(String method,String path,String xml,boolean auth){return root(request(method,path,xml,auth));}
 Response request(String method,String path,String xml,boolean auth){
  return request(method,path,xml,auth,()->{});
 }
 Response request(String method,String path,String xml,boolean auth,Runnable beforeSend){
  if(auth&&key.isBlank())throw new MarketplaceFailure(CONFIGURATION,"ELEVENST");
  if(!path.startsWith("/rest/")||path.contains("..")||path.contains("?")||path.contains("#"))throw new IllegalArgumentException("Invalid provider path");
  lock.lock();try{long wait=next-System.nanoTime();if(wait>0)try{java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new MarketplaceFailure(CANCELLED,"ELEVENST");}
   if(Thread.currentThread().isInterrupted())throw new MarketplaceFailure(CANCELLED,"ELEVENST");
   byte[] body=xml==null?null:ElevenstXml.encode(xml);
   beforeSend.run();
   try{return transport.send(method,path,body,auth);}finally{next=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(spacing);}
  }finally{lock.unlock();}
 }
 Element root(Response r){if(r.status()==401)throw new MarketplaceFailure(AUTHENTICATION,"ELEVENST");if(r.status()==403)throw new MarketplaceFailure(PERMISSION,"ELEVENST");if(r.status()==429)throw new MarketplaceFailure(RATE_LIMIT,"ELEVENST");if(r.status()<200||r.status()>=300)throw new MarketplaceFailure(REJECTED,"ELEVENST");var root=ElevenstXml.parse(r.bytes()).getDocumentElement();if(ElevenstXml.name(root).equals("AuthMessage"))throw new MarketplaceFailure(AUTHENTICATION,"ELEVENST");return root;}
 private Response http(String method,String path,byte[] bytes,boolean auth){try{
  var b=HttpRequest.newBuilder(URI.create("https://api.11st.co.kr"+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/xml").header("User-Agent","molebutter-elevenst");if(auth)b.header("openapikey",key);if(bytes!=null)b.header("Content-Type","text/xml;charset=EUC-KR");b.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
  var r=httpClient.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());try(var in=r.body()){var body=in.readNBytes(10*1024*1024+1);if(body.length>10*1024*1024)throw new MarketplaceFailure(RESPONSE,"ELEVENST");return new Response(r.statusCode(),body);}
 }catch(HttpTimeoutException e){throw new MarketplaceFailure(TIMEOUT,"ELEVENST");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new MarketplaceFailure(CANCELLED,"ELEVENST");}catch(java.io.IOException e){throw new MarketplaceFailure(NETWORK,"ELEVENST");}}
 static String id(String id){if(id==null||!id.matches("[1-9][0-9]{0,18}"))throw new cc.ataglace.molebutter.common.api.InputValidationFailure("11번가 상품·주소 번호를 확인해 주세요.");return id;}
}
