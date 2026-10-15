package cc.ataglace.molebutter.marketplacelotteon.internal;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplacelotteon.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import static cc.ataglace.molebutter.marketplacelotteon.api.LotteonBrandFailure.Kind.*;

@Service
final class DefaultLotteonBrands implements LotteonBrands {
    private final BusinessAccess access;
    private final String key;
    private final ObjectMapper json;
    private final LotteonBrandTransport transport;
    private final Clock clock;
    private final Duration spacing;
    private final ReentrantLock lock=new ReentrantLock(true);
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();
    
    private Instant blockedUntil=Instant.EPOCH;
    private record Cached(Instant expires,List<Brand> brands) {}
    @Autowired
    DefaultLotteonBrands(BusinessAccess access,@Value("${marketplace.lotteon.api-key:}") String key,ObjectMapper json){
        this(access,key,json,new LotteonBrandTransport(),Clock.systemUTC(),Duration.ofSeconds(1));
    }
    DefaultLotteonBrands(BusinessAccess access,String key,ObjectMapper json,LotteonBrandTransport transport,Clock clock,Duration spacing){
        this.access=access;this.key=key==null?"":key.trim();this.json=json;this.transport=transport;this.clock=clock;this.spacing=spacing;
    }
    public List<Brand> search(Long actor,String input){
        access.productActor(actor,true);
        if(input==null||input.isBlank()||input.length()>200||input.codePoints().anyMatch(Character::isISOControl))
            throw new InputValidationFailure("브랜드명을 200자 이내로 입력해 주세요.");
        if(key.isBlank()||key.length()>8192||key.codePoints().anyMatch(c->c<=32||c>=127))throw new LotteonBrandFailure(CONFIGURATION);
        String name=input.trim();if(!lock.tryLock())throw new LotteonBrandFailure(BUSY);
        try{
            var existing=cache.get(name);if(existing!=null&&existing.expires().isAfter(clock.instant()))return existing.brands();
            if(blockedUntil.isAfter(clock.instant()))throw new LotteonBrandFailure(RATE_LIMIT);
            LotteonBrandTransport.Response response;try{response=LotteonCalls.call(key,spacing,()->transport.search(name,key));}catch(cc.ataglace.molebutter.marketplace.api.MarketplaceFailure failure){throw new LotteonBrandFailure(failure.kind()==cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.RATE_LIMIT?RATE_LIMIT:CANCELLED);}int status=response.status();
            if(status==429){blockedUntil=clock.instant().plus(delay(response.retryAfter()));if(!spacing.isZero())LotteonCalls.block(key,delay(response.retryAfter()));throw new LotteonBrandFailure(RATE_LIMIT);}
            if(status!=200)throw new LotteonBrandFailure(status==401?AUTHENTICATION:status==403?PERMISSION:status>=500?UPSTREAM:REJECTED);
            List<Brand> brands=parse(response.body());
            cache.entrySet().removeIf(e->!e.getValue().expires().isAfter(clock.instant()));
            if(cache.size()>=128)cache.remove(cache.keySet().iterator().next());
            cache.put(name,new Cached(clock.instant().plusSeconds(300),brands));return brands;
        }finally{lock.unlock();}
    }
    private List<Brand> parse(byte[] body){
        try{
            var root=json.readTree(body);if(root==null||!root.isObject()||!root.path("itemList").isArray()||root.path("itemList").size()>100)throw new LotteonBrandFailure(RESPONSE);
            var brands=new LinkedHashMap<String,Brand>();
            for(var item:root.path("itemList")){
                var data=item.path("data");if(!data.isObject())throw new LotteonBrandFailure(RESPONSE);
                if(!data.path("use_yn").isString()||data.path("use_yn").asString().length()!=1)throw new LotteonBrandFailure(RESPONSE);
                // A search result must be active before it can become a selectable registration code.
                if(item.path("is_delete").asBoolean(false)||!"Y".equals(data.path("use_yn").asString("")))continue;
                String id=text(data.path("brnd_id"),12),name=text(data.path("brnd_nm"),255);
                var brand=new Brand(id,name);var previous=brands.putIfAbsent(id,brand);
                if(previous!=null&&!previous.equals(brand))throw new LotteonBrandFailure(RESPONSE);
            }
            return List.copyOf(brands.values());
        }catch(LotteonBrandFailure e){throw e;}catch(RuntimeException e){throw new LotteonBrandFailure(RESPONSE);}
    }
    private static String text(JsonNode value,int max){
        if(!value.isString())throw new LotteonBrandFailure(RESPONSE);String text=value.asString();
        if(text.isBlank()||text.length()>max||text.codePoints().anyMatch(Character::isISOControl))throw new LotteonBrandFailure(RESPONSE);return text;
    }
    private Duration delay(String retryAfter){
        try{return Duration.ofSeconds(Math.max(1,Math.min(3600,Long.parseLong(retryAfter))));}
        catch(Exception e){try{return Duration.ofSeconds(Math.max(1,Math.min(3600,Duration.between(clock.instant(),ZonedDateTime.parse(retryAfter,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds())));}
        catch(Exception ignored){return Duration.ofSeconds(2);}}
    }
}
