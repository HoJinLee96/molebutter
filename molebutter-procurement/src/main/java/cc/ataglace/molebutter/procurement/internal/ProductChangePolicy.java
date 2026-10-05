package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ChangeDtos.*;


import java.time.LocalDateTime;
import java.util.*;


/** Pure comparisons. Missing observations never mean zero, a sale, or a restock. */
public final class ProductChangePolicy {
    private ProductChangePolicy() {}
    public static String optionKey(OptionValue o){return o.scope()+":"+o.id();}
    public static boolean numeric(OptionValue o){return o.stock()!=null&&o.stock()>=0;}
    public static boolean knownState(String s){return Set.of("AVAILABLE","SOLD_OUT","UNAVAILABLE").contains(Objects.toString(s,""));}
    public static boolean sameIdentity(Observation a,Observation b){return a!=null&&b!=null&&Objects.equals(a.identity(),b.identity());}
    public static List<Delta> compare(Observation base,Observation now){
        if(base==null||now==null||!now.current()||!now.verified()||!sameIdentity(base,now))return List.of();
        var changes=new ArrayList<Delta>();
        number(changes,"PRICE",null,base.price(),now.price(),base.priceAt(),now.priceAt());
        number(changes,"DELIVERY",null,base.fee(),now.fee(),base.feeAt(),now.feeAt());
        Map<String,OptionValue> old=options(base);
        var current=options(now);
        for(var o:current.values()){
            var b=old.get(optionKey(o));
            if(b==null){
                if(!old.isEmpty()&&(numeric(o)||knownState(o.state())))changes.add(new Delta("OPTION_NEW",o.id(),o.label(),o.scope(),null,o.stock(),null,null,o.state(),null,o.checkedAt(),"옵션 구성 변경 · 비교 기준 없음"));
                continue;
            }
            number(changes,"STOCK",o,b.stock(),o.stock(),b.checkedAt(),o.checkedAt());
            if(knownState(b.state())&&knownState(o.state())&&!b.state().equals(o.state())){
                String kind="SOLD_OUT".equals(o.state())?"SOLD_OUT":"SOLD_OUT".equals(b.state())&&"AVAILABLE".equals(o.state())&&numeric(o)&&o.stock()>0?"RESTOCK":"AVAILABILITY";
                changes.add(new Delta(kind,o.id(),o.label(),o.scope(),b.stock(),o.stock(),null,b.state(),o.state(),b.stateAt(),o.stateAt(),null));
            }
        }
        if(!now.partial()&&!current.isEmpty())for(var b:old.values())if(!current.containsKey(optionKey(b)))changes.add(new Delta("OPTION_MISSING",b.id(),b.label(),b.scope(),b.stock(),null,null,b.state(),null,b.checkedAt(),now.observedAt(),"옵션 구성 변경 · 이번 응답에서 미확인"));
        return List.copyOf(changes);
    }
    private static void number(List<Delta> out,String kind,OptionValue option,Long before,Long after,LocalDateTime at,LocalDateTime now){
        if(before!=null&&after!=null&&before>=0&&after>=0&&!before.equals(after))out.add(new Delta(kind,option==null?null:option.id(),option==null?null:option.label(),option==null?null:option.scope(),before,after,after-before,null,null,at,now,null));
    }
    public static Map<String,OptionValue> options(Observation observation){
        var result=new LinkedHashMap<String,OptionValue>();var duplicate=new HashSet<String>();
        for(var o:observation.options())if(o.id()!=null&&!o.id().isBlank()){String key=optionKey(o);if(result.putIfAbsent(key,o)!=null)duplicate.add(key);}
        duplicate.forEach(result::remove);return result;
    }
    /** Keep known fields when acknowledging an unknown value. Complete option sets replace old structures. */
    public static Observation mergeValid(Observation old,Observation now){
        if(now==null||!now.verified())return old;
        if(!sameIdentity(old,now))old=null;
        var opts=new LinkedHashMap<String,OptionValue>();
        if(old!=null&&(now.partial()||now.options().isEmpty()))opts.putAll(options(old));
        var previous=old==null?Map.<String,OptionValue>of():options(old);
        for(var o:options(now).values()){
            var b=previous.get(optionKey(o));
            if(numeric(o)||knownState(o.state())||b!=null)opts.put(optionKey(o),new OptionValue(o.id(),o.label(),o.scope(),numeric(o)?o.stock():b==null?null:b.stock(),knownState(o.state())?o.state():b==null?o.state():b.state(),numeric(o)?o.checkedAt():b==null?null:b.checkedAt(),knownState(o.state())?o.stateAt():b==null?null:b.stateAt()));
        }
        return new Observation(now.id(),now.key(),now.mall(),now.storeId(),now.identity(),now.name(),now.url(),now.price()!=null?now.price():old==null?null:old.price(),now.fee()!=null?now.fee():old==null?null:old.fee(),now.price()!=null?now.priceAt():old==null?null:old.priceAt(),List.copyOf(opts.values()),now.partial(),true,now.current(),now.recommendable(),now.observedAt(),now.fee()!=null?now.feeAt():old==null?null:old.feeAt()).withLookupState(now.lookupState());
    }
    /** First valid fields establish a baseline; never auto-advance an existing value. */
    public static Observation fillBaseline(Observation old,Observation now){
        if(old==null)return mergeValid(null,now);
        if(!sameIdentity(old,now))return mergeValid(null,now);
        var opts=new LinkedHashMap<>(options(old));
        for(var o:options(now).values()){
            var b=opts.get(optionKey(o));
            if(b!=null)opts.put(optionKey(o),new OptionValue(b.id(),b.label(),b.scope(),b.stock()==null?o.stock():b.stock(),knownState(b.state())?b.state():o.state(),b.stock()==null?o.checkedAt():b.checkedAt(),knownState(b.state())?b.stateAt():o.stateAt()));
            else if(old.options().isEmpty()&&(numeric(o)||knownState(o.state())))opts.put(optionKey(o),o);
        }
        return new Observation(old.id(),old.key(),old.mall(),old.storeId(),old.identity(),old.name(),old.url(),old.price()==null?now.price():old.price(),old.fee()==null?now.fee():old.fee(),old.price()==null?now.priceAt():old.priceAt(),List.copyOf(opts.values()),old.partial(),old.verified(),old.current(),old.recommendable(),old.observedAt(),old.fee()==null?now.feeAt():old.feeAt()).withLookupState(old.lookupState());
    }
}
