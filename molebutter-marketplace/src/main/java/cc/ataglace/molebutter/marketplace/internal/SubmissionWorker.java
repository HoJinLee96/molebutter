package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;

/** 접수된 실행의 임대·순차 호출·결과 기록만 담당한다. 초안과 준비 화면은 수정하지 않는다. */
final class SubmissionWorker implements AutoCloseable {
    private final BusinessAccess access;
    private final SubmissionStore store;
    private final Map<String,MarketplaceWriteGateway> writers;
    private final TransactionTemplate transactions;
    private final Function<String,String> accountFor;
    private final boolean enabled;
    private final String owner=UUID.randomUUID().toString();
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"marketplace-submission");t.setDaemon(true);return t;});
    private volatile SubmissionStore.Job current;
    SubmissionWorker(BusinessAccess access,SubmissionStore store,Map<String,MarketplaceWriteGateway> writers,
                     TransactionTemplate transactions,Function<String,String> accountFor,boolean enabled) {
        this.access=access;this.store=store;this.writers=writers;this.transactions=transactions;
        this.accountFor=accountFor;this.enabled=enabled;
    }
    void poll(){if(enabled&&running.compareAndSet(false,true))executor.execute(()->{try{runPending();}finally{running.set(false);}});}
    void heartbeat(){var job=current;if(job!=null)store.heartbeat(job);}
    /** One persisted step; integration tests can call this with a fake gateway and disabled polling. */
    public void runPending(){
        transactions.executeWithoutResult(tx->store.recoverExpired());
        var job=transactions.execute(tx->store.claim(owner));if(job==null)return;current=job;
        MarketplaceWriteGateway.Result result;
        try{
            access.productActor(job.actor(),true);
            var writer=writers.get(job.prepared().market());
            if(job.prepared().schemaVersion()!=1)result=new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.FAILED,job.mapping(),"UNSUPPORTED_SNAPSHOT_VERSION",null,Instant.now());
            else if(writer==null||!Objects.equals(accountFor.apply(job.prepared().market()),job.prepared().accountKey()))result=new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.FAILED,job.mapping(),"ACCOUNT_CHANGED",null,Instant.now());
            else if(!store.owns(job))return;
            else if(job.action().equals("RECONCILE")){
                var previous=job.previous()==null?new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.UNKNOWN,job.mapping(),"INTERRUPTED",null,Instant.now()):job.previous();
                result=writer.reconcile(job.actor(),job.prepared(),job.step(),previous);
            }else if(job.prepared().editIntent()==null)result=new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.FAILED,job.mapping(),"LEGACY_INTENT",null,Instant.now());
            else result=writer.execute(job.actor(),job.prepared(),job.step(),job.mapping(),actual->transactions.executeWithoutResult(tx->{access.productActor(job.actor(),true);store.recordRequest(job,actual);}));
            if(result==null)result=unknown(job,"RESPONSE");
            if(result.mapping()!=null&&(!job.prepared().accountKey().equals(result.mapping().accountKey())
                    ||job.mapping()!=null&&job.mapping().sellerProductId()!=null&&!Objects.equals(job.mapping().sellerProductId(),result.mapping().sellerProductId())
                    ||job.mapping()!=null&&job.mapping().channelProductId()!=null&&!Objects.equals(job.mapping().channelProductId(),result.mapping().channelProductId())))result=unknown(job,"MAPPING_CHANGED");
        }catch(BusinessException forbidden){result=new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.FAILED,job.mapping(),"ACCESS_DENIED",null,Instant.now());}
        catch(RuntimeException unexpected){result=unknown(job,"INTERRUPTED");}
        finally{current=null;}
        final var outcome=result;
        try{transactions.executeWithoutResult(tx->store.finish(job,outcome));}
        catch(RuntimeException persistenceFailure){
            // The remote call has already happened. A failed result/mapping commit must never make it retryable.
            transactions.executeWithoutResult(tx->store.finish(job,unknown(job,"PERSISTENCE")));
        }
    }
    private static MarketplaceWriteGateway.Result unknown(SubmissionStore.Job job,String code){return new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.UNKNOWN,job.mapping(),code,null,Instant.now());}
    @Override public void close(){executor.shutdownNow();}
}
