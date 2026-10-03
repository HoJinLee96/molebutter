package cc.ataglace.molebutter.service.product;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Preview before applying; every product is rechecked under the normal transaction lock. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="product.changes.initialize-enabled",havingValue="true",matchIfMissing=true)
public class ProductChangeInitializer implements ApplicationRunner {
    private final ProductChangeService changes;
    @Override public void run(ApplicationArguments args){
        var candidates=changes.previewInitial();
        for(var c:candidates)log.info("[CHANGE_BASELINE_PREVIEW] productId={} revision={} verifiedListings={}",c.productId(),c.revision(),c.listings());
        int applied=0;
        for(var c:candidates)if(changes.seed(c))applied++;
        log.info("[CHANGE_BASELINE_APPLIED] candidates={} initialized={} skipped={}",candidates.size(),applied,candidates.size()-applied);
    }
}
