package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.bcrypt.BCrypt;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;

@Component
final class DefaultNaverGateway implements NaverGateway {
    private final String clientId,clientSecret,type,accountId;
    private final ObjectMapper json;
    private final NaverHttpTransport transport;
    private final NaverImageSource imageSource;
    private final Clock clock;
    private final Duration spacing;
    private final ReentrantLock lock=new ReentrantLock(true);
    private String token;
    private Instant tokenExpires=Instant.EPOCH;
    private long nextStart;
    private final LinkedHashMap<String,Cached> metadataCache=new LinkedHashMap<>();
    private record Cached(Instant expires,JsonNode value) {}
    @Autowired
    DefaultNaverGateway(@Value("${marketplace.naver.client-id:}") String clientId,
            @Value("${marketplace.naver.client-secret:}") String clientSecret,
            @Value("${marketplace.naver.type:SELF}") String type,
            @Value("${marketplace.naver.account-id:}") String accountId,
            ObjectMapper json,NaverImageSource imageSource){
        this(clientId,clientSecret,type,accountId,json,new NaverHttpTransport(),imageSource,Clock.systemUTC(),Duration.ofSeconds(1));
    }
    DefaultNaverGateway(String clientId,String clientSecret,String type,String accountId,ObjectMapper json,
            NaverHttpTransport transport,NaverImageSource imageSource,Clock clock,Duration spacing){
        this.clientId=clean(clientId);this.clientSecret=clean(clientSecret);this.type=clean(type);this.accountId=clean(accountId);
        this.json=json;this.transport=transport;this.imageSource=imageSource;this.clock=clock;this.spacing=spacing;
    }
    public String accountKey(){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
            ("NAVER\n"+clientId+"\n"+type+"\n"+accountId).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public <T>T session(Supplier<T> work){
        configured();boolean acquired=false;
        try{acquired=lock.tryLock(30,TimeUnit.SECONDS);if(!acquired)throw failure(BUSY);return work.get();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw failure(CANCELLED);}
        finally{if(acquired)lock.unlock();}
    }
    public JsonNode product(String id){checkId(id);var value=parse(request("GET","/v2/products/origin-products/"+id,"",null,null));
        if(!value.path("originProduct").isObject())throw failure(RESPONSE);
        for(var identity:List.of(value.path("originProductNo"),value.path("originProduct").path("originProductNo")))
            if(!identity.isMissingNode()&&!identity.isNull()&&!id.equals(scalar(identity)))throw failure(RESPONSE);
        // The documented GET response need not repeat its path identity. Keep that trusted
        // identity in the server projection, outside the origin/channel objects sent by PUT.
        var projection=value.deepCopy().asObject();projection.put("originProductNo",Long.parseLong(id));return projection;
    }
    public JsonNode channel(String id){checkId(id);return parse(request("GET","/v2/products/channel-products/"+id,"",null,null));}
    public JsonNode search(JsonNode input){return parse(request("POST","/v1/products/search","","application/json",json.writeValueAsBytes(input)));}
    public NaverGateway.Response write(String method,String path,JsonNode input){
        if(!Set.of("POST","PUT").contains(method)||!(path.matches("/v2/products(?:/(?:origin-products|channel-products)/[0-9]{1,19})?")||method.equals("PUT")&&path.matches("/v1/products/origin-products/[1-9][0-9]{0,18}/option-stock")))
            throw new InputValidationFailure("스마트스토어 상품 전송 경로를 확인해 주세요.");
        byte[] bytes=json.writeValueAsBytes(input);if(bytes.length>5*1024*1024)throw new InputValidationFailure("전송 내용을 5MiB 이하로 줄여 주세요.");
        return request(method,path,"","application/json",bytes);
    }
    public JsonNode metadata(String kind,Map<String,String> input){
        var query=input==null?Map.<String,String>of():input;String path;var allowed=new LinkedHashMap<String,String>();
        switch(kind){
            case "categories" -> {path="/v1/categories";copy(query,allowed,"last",false);if(allowed.containsKey("last")&&!Set.of("true","false").contains(allowed.get("last")))invalid();}
            case "category" -> {String id=required(query,"categoryId");if(!id.matches("[0-9]{1,30}"))invalid();path="/v1/categories/"+id;}
            case "brands" -> {path="/v1/product-brands";copy(query,allowed,"name",true);}
            case "models" -> {path="/v1/product-models";copy(query,allowed,"name",true);copyPage(query,allowed,"page",1_000_000);copyPage(query,allowed,"size",100);}
            case "notices" -> {path="/v1/products-for-provided-notice";copy(query,allowed,"categoryId",true);}
            case "notice" -> {String value=required(query,"productInfoProvidedNoticeType");if(!value.matches("[A-Z_]{1,60}"))invalid();path="/v1/products-for-provided-notice/"+value;}
            case "addresses" -> {path="/v1/seller/addressbooks-for-page";copyPage(query,allowed,"page",1_000_000);}
            case "attributes" -> {path="/v1/product-attributes/attributes";copy(query,allowed,"categoryId",true);}
            case "attribute-values" -> {path="/v1/product-attributes/attribute-values";copy(query,allowed,"categoryId",true);}
            case "attribute-units" -> path="/v1/product-attributes/attribute-value-units";
            case "origins" -> {path="/v1/product-origin-areas";}
            case "origin-children" -> {path="/v1/product-origin-areas/sub-origin-areas";copy(query,allowed,"code",false);}
            case "delivery-companies" -> {path="/v2/product-delivery-info/return-delivery-companies";copy(query,allowed,"name",false);}
            default -> throw new InputValidationFailure("지원하는 스마트스토어 규격 조회를 선택해 주세요.");
        }
        String encoded=allowed.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        String key=path+"?"+encoded;var cached=metadataCache.get(key);
        if(cached!=null&&cached.expires().isAfter(clock.instant()))return cached.value().deepCopy();
        var value=parse(request("GET",path,encoded,null,null));
        if(!value.isArray()&&!value.isObject())throw failure(RESPONSE);
        metadataCache.entrySet().removeIf(e->!e.getValue().expires().isAfter(clock.instant()));
        if(metadataCache.size()>=128)metadataCache.remove(metadataCache.keySet().iterator().next());
        metadataCache.put(key,new Cached(clock.instant().plusSeconds("addresses".equals(kind)?30:300),value.deepCopy()));return value;
    }
    public String uploadImage(Long actor,NaverEditor.Image image){
        if(imageSource==null)throw failure(CONFIGURATION);
        var content=imageSource.read(actor,image);String boundary="molebutter-"+UUID.randomUUID();
        var body=new ByteArrayOutputStream();
        body.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"imageFiles\"; filename=\"image."+content.extension()+"\"\r\nContent-Type: "+content.mime()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content.bytes());body.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var root=parse(request("POST","/v1/product-images/upload","","multipart/form-data; boundary="+boundary,body.toByteArray()));
        if(!root.path("images").isArray()||root.path("images").size()!=1)throw failure(RESPONSE);
        String url=scalar(root.path("images").get(0).path("url"));
        if(url==null||!naverImageUrl(url))throw failure(RESPONSE);return url;
    }
    static boolean naverImageUrl(String value){
        try{var u=java.net.URI.create(value);String host=u.getHost();return "https".equalsIgnoreCase(u.getScheme())&&host!=null
            &&(host.equals("pstatic.net")||host.endsWith(".pstatic.net"))&&u.getRawUserInfo()==null&&u.getRawFragment()==null&&(u.getPort()==-1||u.getPort()==443);}
        catch(RuntimeException e){return false;}
    }
    private NaverGateway.Response request(String method,String path,String query,String contentType,byte[] body){
        if(!lock.isHeldByCurrentThread())return session(()->request(method,path,query,contentType,body));
        boolean renewed=false,retriedRate=false;
        while(true){
            String bearer=token();pace();var response=transport.send(method,path,query,bearer,contentType,body);
            String code=errorCode(response);
            if(response.status()==401&&"GW.AUTHN".equals(code)&&!renewed){token=null;tokenExpires=Instant.EPOCH;renewed=true;continue;}
            if(response.status()==429){
                Duration wait=delay(response.retryAfter());nextStart=Math.max(nextStart,System.nanoTime()+wait.toNanos());
                if(!"GW.QUOTA_LIMIT".equals(code)&&!retriedRate&&wait.compareTo(Duration.ofSeconds(30))<=0){retriedRate=true;continue;}
            }
            return response;
        }
    }
    private String token(){
        if(token!=null&&tokenExpires.isAfter(clock.instant().plusSeconds(60)))return token;
        long timestamp=clock.millis();String signature;
        try{signature=Base64.getEncoder().encodeToString(BCrypt.hashpw(clientId+"_"+timestamp,clientSecret).getBytes(StandardCharsets.UTF_8));}
        catch(RuntimeException invalidSecret){throw failure(CONFIGURATION);}
        String form="client_id="+encode(clientId)+"&timestamp="+timestamp+"&grant_type=client_credentials&client_secret_sign="+encode(signature)+"&type="+encode(type)
            +("SELLER".equals(type)?"&account_id="+encode(accountId):"");
        pace();var response=transport.send("POST","/v1/oauth2/token","",null,"application/x-www-form-urlencoded",form.getBytes(StandardCharsets.UTF_8));
        if(response.status()!=200)throw failure(response.status()==403?PERMISSION:response.status()==429?RATE_LIMIT:AUTHENTICATION);
        var root=parse(response);String next=scalar(root.path("access_token"));var expires=root.path("expires_in");
        if(next==null||next.length()>8192||next.codePoints().anyMatch(Character::isISOControl)||!expires.isIntegralNumber()||expires.asLong()<1||expires.asLong()>86400)throw failure(RESPONSE);
        token=next;tokenExpires=clock.instant().plusSeconds(expires.asLong());return token;
    }
    public JsonNode parse(NaverGateway.Response response){
        int status=response.status();if(status<200||status>=300)throw failure(status==401?AUTHENTICATION:status==403?PERMISSION:status==429?RATE_LIMIT:status>=500?UPSTREAM:REJECTED);
        try{var root=json.readTree(response.body());if(root==null||(!root.isObject()&&!root.isArray()))throw failure(RESPONSE);return root;}
        catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw failure(RESPONSE);}
    }
    private String errorCode(NaverGateway.Response response){
        if(response.status()<400)return "";
        try{return json.readTree(response.body()).path("code").asString("");}catch(RuntimeException e){return "";}
    }
    private void configured(){if(clientId.isBlank()||clientSecret.isBlank()||!Set.of("SELF","SELLER").contains(type)||"SELLER".equals(type)&&accountId.isBlank())throw failure(CONFIGURATION);}
    private void pace(){
        long remaining=nextStart-System.nanoTime();if(remaining>0)try{TimeUnit.NANOSECONDS.sleep(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();throw failure(CANCELLED);}
        if(Thread.currentThread().isInterrupted())throw failure(CANCELLED);nextStart=System.nanoTime()+spacing.toNanos();
    }
    private Duration delay(String retryAfter){
        try{long seconds=Long.parseLong(retryAfter);return Duration.ofSeconds(Math.min(3600,Math.max(1,seconds)));}
        catch(Exception e){try{return Duration.ofSeconds(Math.min(3600,Math.max(1,Duration.between(clock.instant(),ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds())));}catch(Exception ignored){return Duration.ofSeconds(2);}}
    }
    static void checkId(String id){if(id==null||!id.matches("[1-9][0-9]{0,18}"))throw new InputValidationFailure("스마트스토어 상품 번호를 확인해 주세요.");try{Long.parseLong(id);}catch(NumberFormatException e){invalid();}}
    private static void copy(Map<String,String> source,Map<String,String> target,String key,boolean mandatory){String value=source.get(key);if(value==null||value.isBlank()){if(mandatory)invalid();return;}if(value.length()>200||value.codePoints().anyMatch(Character::isISOControl))invalid();target.put(key,value.trim());}
    private static void copyPage(Map<String,String> source,Map<String,String> target,String key,int max){String value=source.get(key);if(value==null)return;try{int number=Integer.parseInt(value);if(number<1||number>max)invalid();target.put(key,Integer.toString(number));}catch(NumberFormatException e){invalid();}}
    private static String required(Map<String,String> source,String key){String value=source.get(key);if(value==null||value.isBlank()||value.length()>200||value.codePoints().anyMatch(Character::isISOControl))invalid();return value.trim();}
    private static void invalid(){throw new InputValidationFailure("스마트스토어 조회 조건을 확인해 주세요.");}
    private static String clean(String value){return value==null?"":value.trim();}
    static String scalar(JsonNode value){return value.isString()||value.isIntegralNumber()?value.asString():null;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static MarketplaceFailure failure(MarketplaceFailure.Kind kind){return new MarketplaceFailure(kind,"NAVER");}
}
