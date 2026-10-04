package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.service.common.BusinessTime;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.infra.product.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component @RequiredArgsConstructor @Slf4j
public class SupplierRefreshWorker {
    private final SupplierRefreshService runs;
    private final SupplierLookupService lookup;
    private final NaverPriceSearch search;
    private final BusinessTime time;
    private final SupplierStockLookupService stockLookups;
    private final String owner=UUID.randomUUID().toString();
    @Value("${product.refresh.worker-enabled:true}") private boolean enabled;
    @Scheduled(fixedDelayString="${product.refresh.poll-ms:5000}",initialDelay=15000)
    public void tick() {
        if(!enabled)return;
        try{var manual=stockLookups.claim(owner);if(manual!=null){processStock(manual);return;}runs.schedule();var work=runs.claim(owner);if(work!=null)process(work);}
        catch(Exception e){log.warn("상품 최신화 작업 처리 실패: {}",e.getClass().getSimpleName());}
    }
    public void processStock(SupplierStockLookupService.Work work){
        try{stockLookups.finish(owner,work,lookup.inspectFresh(work.product(),work.offer()));}
        catch(NaverPriceSearch.SearchBlocked e){stockLookups.blocked(owner,work,e.getMessage());}
        catch(Exception e){var failure=SupplierLookupFailure.classify(e);log.warn("[MANUAL_STOCK_FAILED] jobId={} productId={} mall={} supplierProductId={} stage={} code={} exception={}",work.id(),work.product().productId(),work.offer().mall(),work.offer().mallProductId(),failure.stage(),failure.code(),e.getClass().getSimpleName());
            var old=work.previous();var evidence=new StockEvidence(work.product().runId(),old.stockEvidence()==null?null:old.stockEvidence().representativeKey(),old.stockEvidence()==null?null:old.stockEvidence().channelId(),null,false);
            stockLookups.finish(owner,work,new SupplierResult(work.offer(),old.match(),"FAILED",List.of(),failure.getMessage(),old.sourceTitle(),old.sourceModelCode(),old.sourceBrand(),old.branch(),evidence));
        }finally{lookup.invalidate(work.offer().mall(),work.offer().mallProductId());}
    }
    public void process(SupplierRefreshService.Work work) {
        try {
            if(!runs.heartbeat(owner,work))return;
            SearchResult found=runs.cached(work);
            if(found==null){
                Long attempt=runs.searchStarted(owner,work);if(attempt==null)return;
                SearchResult searched;
                try {searched=search.search(work.query(),Set.of(),3);}
                catch(RuntimeException failure){runs.searchFailed(owner,work,attempt,failure);return;}
                if(!runs.searchSucceeded(owner,work,attempt))return;
                var raw=ProductCandidateSearch.classify(searched);
                var offers=new ArrayList<Offer>();offers.addAll(raw.offers());offers.addAll(raw.needsReview());
                var keys=new LinkedHashSet<String>();
                for(var o:searched.offers()){
                    var mall=ProductSourceMetadata.mall(o.url());
                    if(mall!=null){var id=ProductSourceMetadata.productId(mall,o.url(),o.mallProductId());keys.add(mall+":"+id+":"+Objects.toString(o.naverProductId(),""));}
                    if(o.naverProductId()!=null&&!o.naverProductId().isBlank())keys.add("naver:"+o.naverProductId());
                }
                found=new SearchResult(List.copyOf(offers),raw.complete(),raw.message(),raw.completionReason(),List.copyOf(keys));runs.cache(work,found);}
            runs.finish(owner,work,lookup.lookup(work,found,()->runs.heartbeat(owner,work),owner));
        }catch(NaverPriceSearch.SearchBlocked e){runs.blocked(owner,work,e instanceof SupplierAccessRestricted?"SUPPLIER_ACCESS_RESTRICTED":e.reason().name(),e.getMessage());}
        catch(Exception e){runs.finish(owner,work,new RefreshResult("PARTIAL",null,null,null,List.of(),time.now(),"매입처 조회 처리를 완료하지 못했습니다. 다시 확인해 주세요."));}
    }
}
