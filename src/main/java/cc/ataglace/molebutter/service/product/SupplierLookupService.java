package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.service.common.BusinessTime;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.infra.product.*;

@Service @lombok.extern.slf4j.Slf4j
public class SupplierLookupService {
    private final SupplierProductGateway sources;
    private final BusinessTime time;
    private final RecommendationLookupService diagnostics;
    public SupplierLookupService(SupplierProductGateway sources,BusinessTime time){this(sources,time,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public SupplierLookupService(SupplierProductGateway sources,BusinessTime time,RecommendationLookupService diagnostics){this.sources=sources;this.time=time;this.diagnostics=diagnostics;}
    private final Map<ProcurementMall,RecommendationDiagnostic> restrictedMalls=new EnumMap<>(ProcurementMall.class);
    private final Map<String,RecommendationDiagnostic> currentDiagnostics=new LinkedHashMap<>();
    private String worker;
    private long cachedRun=Long.MIN_VALUE;
    // 같은 검색어의 상품들은 응답을 공유하되, 대량 실행에서도 최근 400개만 보관한다.
    private record CachedDetail(SourceDetails detail,java.time.LocalDateTime at) {}
    private final Map<String,CachedDetail> detailsCache=new LinkedHashMap<>(64,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,CachedDetail> entry){return size()>400;}
    };
    public synchronized RefreshResult lookup(SupplierRefreshService.Work work,SearchResult search,BooleanSupplier heartbeat) {
        return lookup(work,search,heartbeat,null);
    }
    public synchronized RefreshResult lookup(SupplierRefreshService.Work work,SearchResult search,BooleanSupplier heartbeat,String owner) {
        if(cachedRun!=work.runId()){detailsCache.clear();restrictedMalls.clear();cachedRun=work.runId();}
        worker=owner;currentDiagnostics.clear();
        if(diagnostics!=null){restrictedMalls.clear();restrictedMalls.putAll(diagnostics.restrictions(work.runId()));diagnostics.list(work.runId(),work.productId()).forEach(d->currentDiagnostics.put(d.listingKey(),d));}
        return lookupWithRecommendations(work,search,heartbeat);
    }
    public synchronized void invalidate(ProcurementMall mall,String productId){detailsCache.remove(mall+":"+productId);}
    private RefreshResult lookupWithRecommendations(SupplierRefreshService.Work work,SearchResult search,BooleanSupplier heartbeat) {
        var candidates=ProductCandidateSearch.classify(search);
        var offers=new LinkedHashMap<String,Offer>();
        candidates.offers().forEach(o->offers.putIfAbsent(SupplierStorePolicy.listingKey(o),o));
        var results=new LinkedHashMap<String,SupplierResult>();
        var proofs=new HashMap<String,SupplierResult>();
        var sortedNaver=new ArrayDeque<>(offers.values().stream().filter(o->o.mall()==ProcurementMall.NAVER_SMART_STORE).sorted(Comparator.comparing(Offer::price,Comparator.nullsLast(Long::compareTo)).thenComparing(SupplierStorePolicy::listingKey)).toList());
        var ordered=offers.values().stream().map(o->o.mall()==ProcurementMall.NAVER_SMART_STORE?sortedNaver.removeFirst():o).toList();
        var basis=work.selectionBasis()==null?new cc.ataglace.molebutter.dto.product.SupplierDtos.SelectionBasis(null,null,null,null):work.selectionBasis();
        // The exact selected listing is checked first, even after it has left common preferences.
        Long reference=null;
        for(var o:offers.values())if(basis.matches(o)){
            var result=inspect(work,o,heartbeat);remember(work,proofs,result);results.put(SupplierStorePolicy.listingKey(o),result);
            if(NaverChannelPolicy.comparable(result.offer())&&result.accepted()&&o.price()!=null&&o.price()>0)reference=o.price();
            break;
        }
        for(var o:ordered) {
            String key=SupplierStorePolicy.listingKey(o);
            if(results.containsKey(key)||!eligible(work,o))continue;
            // Known unwanted branches need no preferred-supplier request. Unknown branches may require inspection.
            if(!SupplierStorePolicy.include(work.preferences(),work.manualStores(),o,SupplierBranch.resolve(o,null)))continue;
            var result=groupedInspect(work,o,heartbeat,proofs);
            if(SupplierStorePolicy.include(work.preferences(),work.manualStores(),o,result.branch())||basis.supplierId()!=null&&SupplierRecommendationPolicy.cheaper(o.price(),reference))results.put(key,result);
        }
        int additional=0;boolean limited=false;
        if(reference!=null){
            final Long price=reference;
            var cheaper=offers.values().stream().filter(o->!basis.matches(o)&&SupplierRecommendationPolicy.cheaper(o.price(),price)).sorted(Comparator.comparing(Offer::price).thenComparing(SupplierStorePolicy::listingKey)).toList();
            for(var o:cheaper){
                String key=SupplierStorePolicy.listingKey(o);
                if(results.containsKey(key))continue;
                if(restrictedMalls.containsKey(o.mall())){
                    if(!heartbeat.getAsBoolean())throw new IllegalStateException("작업 소유권이 변경되었습니다.");
                    var restriction=restrictedMalls.get(o.mall());recordDiagnostic(work,o,"SKIPPED",restriction.causeCode(),restriction.httpStatus(),false);continue;
                }
                String cacheKey=o.mall()+":"+o.mallProductId();
                var skipped=skipped(work,o,proofs);
                if(skipped!=null){if(!heartbeat.getAsBoolean())throw new IllegalStateException("작업 소유권이 변경되었습니다.");results.put(key,skipped);continue;}
                if(!detailsCache.containsKey(cacheKey)){
                    if(additional>=SupplierRecommendationPolicy.MAX_ADDITIONAL_DETAILS){limited=true;continue;}
                    additional++;
                }
                try {
                    var result=inspect(work,o,heartbeat,true);remember(work,proofs,result);results.put(key,result);
                }catch(SupplierAccessRestricted restriction){
                    if(restriction.mall()!=o.mall())throw restriction;
                    recordDiagnostic(work,o,"FAILED",restriction.code(),restriction.httpStatus(),true);
                }catch(SupplierLookupFailure failure){
                    recordDiagnostic(work,o,"FAILED",failure.code().name(),failure.httpStatus(),false);
                }
            }
        }
        for(var o:candidates.needsReview())if(eligible(work,o)&&SupplierStorePolicy.include(work.preferences(),work.manualStores(),o,SupplierBranch.resolve(o,null))){
            var review=new SupplierResult(o,ProductCodePolicy.review("상품 주소 확인 필요"),"REVIEW",List.of(),null,null,null,null,SupplierBranch.resolve(o,null));
            results.putIfAbsent(SupplierStorePolicy.listingKey(o),review);
        }
        var saved=new ArrayList<SupplierResult>();
        for(var r:results.values()){
            boolean verified=SupplierRecommendationPolicy.verified(work.preferences(),work.manualStores(),r);
            boolean preferred=SupplierRecommendationPolicy.preferred(work.preferences(),work.manualStores(),r);
            boolean recommendation=basis.supplierId()!=null&&SupplierRecommendationPolicy.cheaper(r.offer().price(),reference)&&verified&&!SupplierRecommendationPolicy.soldOut(r)&&SupplierRecommendationPolicy.permittedSeller(r.offer().mall(),SupplierRecommendationPolicy.store(work.preferences(),work.manualStores(),r),r);
            if(!NaverChannelPolicy.comparable(r.offer())){if("FAILED".equals(r.state())){saved.add(r);}continue;}
            if(basis.matches(r.offer())||preferred||!verified||recommendation){
                saved.add(r);
            }
        }
        return SearchCompletion.summarize(saved,SearchCompletion.reason(search),time.now(),work.preferences(),work.manualStores(),limited,List.copyOf(currentDiagnostics.values()),basis);
    }
    private void remember(SupplierRefreshService.Work work,Map<String,SupplierResult> proofs,SupplierResult result){
        if(result.offer().price()!=null&&result.offer().price()>0&&SupplierGroupStockPolicy.verified(result)&&SupplierGroupStockPolicy.available(result)&&SupplierRecommendationPolicy.verified(work.preferences(),work.manualStores(),result)&&SupplierRecommendationPolicy.permittedSeller(result.offer().mall(),SupplierRecommendationPolicy.store(work.preferences(),work.manualStores(),result),result)){
            String key=SupplierGroupStockPolicy.key(result.offer());var old=proofs.get(key);
            if(old==null||result.offer().price()!=null&&(old.offer().price()==null||result.offer().price()<old.offer().price()))proofs.put(key,result);
        }
    }
    private SupplierResult skipped(SupplierRefreshService.Work work,Offer offer,Map<String,SupplierResult> proofs){
        String key=SupplierGroupStockPolicy.key(offer);var proof=key==null?null:proofs.get(key);
        // Cached responses cost no extra request and retain actual per-listing stock.
        if(proof==null||detailsCache.containsKey(offer.mall()+":"+offer.mallProductId())||offer.price()==null||proof.offer().price()==null||offer.price()<proof.offer().price())return null;
        var candidate=SupplierGroupStockPolicy.skip(offer,proof,work.runId());
        return SupplierRecommendationPolicy.verified(work.preferences(),work.manualStores(),candidate)?candidate:null;
    }
    private SupplierResult groupedInspect(SupplierRefreshService.Work work,Offer offer,BooleanSupplier heartbeat,Map<String,SupplierResult> proofs){
        var skipped=skipped(work,offer,proofs);if(skipped!=null){if(!heartbeat.getAsBoolean())throw new IllegalStateException("작업 소유권이 변경되었습니다.");return skipped;}
        var result=inspect(work,offer,heartbeat);remember(work,proofs,result);return result;
    }
    public SupplierResult inspectFresh(SupplierRefreshService.Work work,Offer offer){
        // Manual inspections deliberately bypass the run cache.
        var detail=sources.inspect(offer.mall(),offer.mallProductId(),offer.url());
        return observed(work,offer,detail,time.now());
    }
    private SupplierResult observed(SupplierRefreshService.Work work,Offer offer,SourceDetails detail,java.time.LocalDateTime observedAt){
        if(offer.mall()==ProcurementMall.NAVER_SMART_STORE){offer=offer.withChannel(NaverChannelPolicy.inspected(offer,detail));if(!NaverChannelPolicy.comparable(offer))return new SupplierResult(offer,searchResult(null),"CHANNEL_UNCONFIRMED",List.of(),"판매채널 확인 필요");}
        var branch=SupplierBranch.resolve(offer,detail);String state=detail.options().isEmpty()?"OPTIONS_UNKNOWN":!detail.optionsComplete()?"OPTIONS_PARTIAL":"CONFIRMED";
        var result=new SupplierResult(offer,searchResult(detail.modelCode()),state,detail.options(),state.equals("OPTIONS_PARTIAL")?"일부 옵션만 확인했습니다.":null,detail.title(),detail.modelCode(),detail.brand(),branch);
        boolean verified=SupplierRecommendationPolicy.verified(work.preferences(),work.manualStores(),result)&&branch!=null&&branch.store()!=null&&!"CONFLICT".equals(branch.state());
        return new SupplierResult(offer,result.match(),state,result.options(),result.message(),result.sourceTitle(),result.sourceModelCode(),result.sourceBrand(),branch,new StockEvidence(work.runId(),null,branch==null||branch.store()==null?null:branch.store().references().get("channelId"),observedAt,verified));
    }
    private void recordDiagnostic(SupplierRefreshService.Work work,Offer offer,String kind,String code,Integer httpStatus,boolean restricted){
        var diagnostic=new RecommendationDiagnostic(Long.toString(work.runId()),Long.toString(work.productId()),SupplierStorePolicy.listingKey(offer),offer.mall(),offer.mallProductId(),offer.price(),offer.url(),kind,code,httpStatus,time.now());
        if(diagnostics!=null)diagnostics.record(worker,work,diagnostic,restricted);
        currentDiagnostics.putIfAbsent(diagnostic.listingKey(),diagnostic);
        if(restricted){restrictedMalls.put(offer.mall(),diagnostic);currentDiagnostics.put(diagnostic.listingKey(),diagnostic);}
        log.warn("[RECOMMENDATION_LOOKUP] runId={} productId={} mall={} supplierProductId={} kind={} code={} httpStatus={}",work.runId(),work.productId(),offer.mall(),offer.mallProductId(),kind,code,httpStatus);
    }
    private SupplierResult inspect(SupplierRefreshService.Work work,Offer offer,BooleanSupplier heartbeat){return inspect(work,offer,heartbeat,false);}
    private SupplierResult inspect(SupplierRefreshService.Work work,Offer offer,BooleanSupplier heartbeat,boolean recommendation){
        if(!heartbeat.getAsBoolean())throw new IllegalStateException("작업 소유권이 변경되었습니다.");
        var restriction=restrictedMalls.get(offer.mall());
        if(restriction!=null)throw new SupplierAccessRestricted(offer.mall(),restriction.causeCode(),restriction.httpStatus());
        try{
            String key=offer.mall()+":"+offer.mallProductId();var detail=detailsCache.get(key);
            if(detail==null){detail=new CachedDetail(sources.inspect(offer.mall(),offer.mallProductId(),offer.url()),time.now());detailsCache.put(key,detail);}
            return observed(work,offer,detail.detail(),detail.at());
        }catch(NaverPriceSearch.SearchBlocked e){throw e;}catch(Exception e){
            var failure=SupplierLookupFailure.classify(e);
            var cause=failure.getCause()==null?e:failure.getCause();
            log.warn("[PRODUCT_SUPPLIER_FAILED] runId={} productId={} mall={} supplierProductId={} stage={} code={} httpStatus={} exception={}",
                work.runId(),work.productId(),offer.mall(),offer.mallProductId(),failure.stage(),failure.code(),failure.httpStatus(),cause.getClass().getSimpleName());
            if(recommendation)throw failure;
            return new SupplierResult(offer,searchResult(null),"FAILED",List.of(),failure.getMessage(),null,null,null,SupplierBranch.resolve(offer,null));
        }
    }
    private boolean eligible(SupplierRefreshService.Work work,Offer offer) {
        if(work.preferences()==null)return true;
        if(!work.preferences().mallAllowed(offer.mall()))return false;
        String manual=work.manualStores().get(SupplierStorePolicy.listingKey(offer));
        return manual==null||work.preferences().allowed(offer.mall(),manual);
    }
    private CodeMatch searchResult(String model){return new CodeMatch("SEARCH_RESULT",model,null,null,null,null);}
    public static RefreshResult summarize(List<SupplierResult> results,boolean searchComplete,java.time.LocalDateTime now,
            cc.ataglace.molebutter.dto.product.SupplierDtos.Preferences preferences,Map<String,String> manualStores) {
        // Keep excluded observations for history, but only actual failures and verified suppliers affect status.
        var relevant=results.stream().filter(s->"FAILED".equals(s.state())||SupplierRecommendationPolicy.verified(preferences,manualStores,s)).toList();
        var summary=summarize(relevant,searchComplete,now);
        return new RefreshResult(summary.status(),summary.searchPrice(),summary.searchMall(),summary.searchDeliveryFee(),
            List.copyOf(results),summary.checkedAt(),summary.message());
    }
    public static RefreshResult summarize(List<SupplierResult> results,boolean searchComplete,java.time.LocalDateTime now) {
        var matched=results.stream().filter(s->s.accepted()&&NaverChannelPolicy.comparable(s.offer())).toList();
        var lowest=matched.stream().map(SupplierResult::offer).filter(o->o.price()!=null&&o.price()>0).min(Comparator.comparing(Offer::price).thenComparing(o->Objects.toString(o.naverProductId(),""))).orElse(null);
        var assessment=SupplierLookupStatusPolicy.assess(results,searchComplete);
        return new RefreshResult(assessment.status(),lowest==null?null:lowest.price(),lowest==null?null:lowest.mallName(),lowest==null?null:lowest.deliveryFee(),List.copyOf(results),now,
            "PARTIAL".equals(assessment.status())?"일부 매입처 또는 가격·옵션·재고 정보의 확인이 필요합니다.":null);

    }
}
