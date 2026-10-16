package cc.ataglace.molebutter.coupang.internal;

import java.util.*;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway;

/** 실행 전 서버에 저장된 식별자와 준비 결과의 연결을 검사하는 순수 정책. */
final class SubmissionMappingPolicy {
    private SubmissionMappingPolicy() {}
    /** Providers may create local placeholders or enrich a previously unknown vendor ID from a fresh server read. */
    static boolean compatiblePreparedMapping(CoupangWriteGateway.Mapping bound,CoupangWriteGateway.Prepared prepared){
        var candidate=prepared.mapping();if(candidate==null)return bound==null;
        if(!Objects.equals(prepared.accountKey(),candidate.accountKey()))return false;
        var options=new HashMap<String,CoupangWriteGateway.OptionMapping>();
        if(candidate.options()==null)return false;for(var option:candidate.options())if(option==null||option.optionId()==null||options.putIfAbsent(option.optionId(),option)!=null)return false;
        if(bound==null){
            if(candidate.sellerProductId()!=null||options.values().stream().anyMatch(o->o.sellerProductItemId()!=null||o.vendorItemId()!=null))return false;
            var observed=prepared.editIntent()==null?null:prepared.editIntent().observed();if(observed==null||observed.options()==null)return false;
            return options.keySet().equals(observed.options().stream().map(CoupangWorkingCopies.Option::id).collect(java.util.stream.Collectors.toSet()));
        }
        if(!Objects.equals(bound.accountKey(),candidate.accountKey())||!Objects.equals(bound.sellerProductId(),candidate.sellerProductId())||bound.options()==null||bound.options().size()!=options.size())return false;
        var seen=new HashSet<String>();for(var original:bound.options()){
            if(original==null||!seen.add(original.optionId()))return false;var option=options.get(original.optionId());
            if(option==null||!Objects.equals(original.sellerProductItemId(),option.sellerProductItemId())||original.vendorItemId()!=null&&!Objects.equals(original.vendorItemId(),option.vendorItemId()))return false;
        }
        return true;
    }
}
