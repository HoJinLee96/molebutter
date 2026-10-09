package cc.ataglace.molebutter.marketplace.internal;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import jakarta.annotation.PreDestroy;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrderCollections.*;

@Service
@org.springframework.context.annotation.DependsOn("marketplaceOrderScheduler")
public final class DefaultMarketplaceOrderCollections implements MarketplaceOrderCollections {
    static final Set<String> MARKETS=MarketplaceChannels.ids();
    private final BusinessAccess access; private final MarketplaceOrderStore store; private final MarketplaceOrderGateway client;
    private final TransactionTemplate tx; private final Clock clock; private final String owner=UUID.randomUUID().toString();
    private final AtomicBoolean active=new AtomicBoolean();
    private volatile Thread workerThread;private volatile Long runningId;private volatile boolean shutdown;
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultMarketplaceOrderCollections(BusinessAccess access,MarketplaceOrderStore store,MarketplaceOrderGateway client,PlatformTransactionManager manager){this(access,store,client,manager,Clock.systemUTC());}
    DefaultMarketplaceOrderCollections(BusinessAccess access,MarketplaceOrderStore store,MarketplaceOrderGateway client,PlatformTransactionManager manager,Clock clock){this.access=access;this.store=store;this.client=client;this.tx=new TransactionTemplate(manager);this.clock=clock;}
    @Override public Job start(Long actor,Request input){
        access.productActor(actor,true);var request=normalize(input,clock);String account=client.accountKey();
        Long previous=store.requestJob(actor,request.requestId());if(previous!=null)return sameRequest(actor,previous,request);
        if(request.markets().contains("COUPANG")&&!client.configured())throw new MarketplaceFailure(MarketplaceFailure.Kind.CONFIGURATION);
        long id;
        try{id=tx.execute(t->{
            access.productActor(actor,true);long job=store.insertJob(actor,account,request);
            if(request.markets().contains("COUPANG")){
                LocalDate from=LocalDate.parse(request.dateFrom()),to=LocalDate.parse(request.dateTo());
                for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(7)){LocalDate end=date.plusDays(6).isAfter(to)?to:date.plusDays(6);for(String stream:client.streams())store.addCheckpoint(job,stream,date,end);}
                for(String order:store.openOrderIds(account))store.addCheckpoint(job,"OPEN:"+order,from,to);
                for(var claim:store.openClaims(account)) {
                    String stream;LocalDate origin=from;
                    if("RETURN".equals(claim.type()))stream="CLAIMRETURN:"+claim.id();
                    else if(claim.createdAt()==null||claim.createdAt().length()<10)stream="CLAIM_ORIGIN_UNVERIFIED:"+claim.id();
                    else {var instant=MarketplaceOrderStore.kstTimestamp(claim.createdAt());origin=instant==null?parse(claim.createdAt().substring(0,10),from):instant.toLocalDateTime().toLocalDate();stream=("EXCHANGE".equals(claim.type())?"CLAIMEXCHANGE:":"CLAIMCANCEL:")+claim.orderId()+":"+claim.id();}
                    store.addCheckpoint(job,stream,origin,origin);
                    if("RETURN".equals(claim.type()))store.addCheckpoint(job,"CLAIMWITHDRAWN:"+claim.id(),from,from);
                }
            }
            if(!request.markets().contains("COUPANG"))store.status(job,"PARTIAL");
            return job;
        });}catch(DuplicateKeyException duplicate){Long existing=store.requestJob(actor,request.requestId());if(existing==null)throw duplicate;return sameRequest(actor,existing,request);}
        return get(actor,Long.toString(id));
    }
    private Job sameRequest(Long actor,long id,Request request){var previous=require(actor,id,false);if(!previous.request().equals(request))throw new InputValidationFailure("같은 수집 요청 번호에 다른 조건을 사용할 수 없습니다.");return view(previous);}
    @Override public List<Job> list(Long actor){access.productActor(actor,true);return store.jobs(actor,client.accountKey()).stream().map(this::view).toList();}
    @Override public Job get(Long actor,String id){return view(require(actor,id(id),false));}
    @Override public Job cancel(Long actor,String id){access.productActor(actor,true);return tx.execute(t->{store.lockAccount(client.accountKey());var job=require(actor,id(id),true);if(Set.of("QUEUED","RUNNING").contains(job.status())){store.status(job.id(),"CANCELLED");if(Objects.equals(runningId,job.id())&&workerThread!=null)workerThread.interrupt();}return view(store.find(job.id(),false));});}
    @Override public Job retry(Long actor,String id){access.productActor(actor,true);return tx.execute(t->{store.lockAccount(client.accountKey());var job=require(actor,id(id),true);if(!Set.of("PARTIAL","FAILED","CANCELLED","INTERRUPTED").contains(job.status()))throw new InputValidationFailure("재개 가능한 수집 이력을 선택해 주세요.");if(!job.request().markets().contains("COUPANG"))return view(job);if(!client.configured())throw new MarketplaceFailure(MarketplaceFailure.Kind.CONFIGURATION);if(client.claimsVerified()) {
            var checkpoints=store.checkpoints(job.id());
            for(var gate:checkpoints)if("CLAIMS_UNVERIFIED".equals(gate.stream())) {
                for(String stream:client.streams())if((stream.startsWith("RETURN_")||"CANCEL".equals(stream))&&checkpoints.stream().noneMatch(c->c.stream().equals(stream)&&c.from().equals(gate.from())&&c.to().equals(gate.to())))store.addCheckpoint(job.id(),stream,gate.from(),gate.to());
                store.checkpoint(gate.id(),null,"DONE",null);
            }
        }
        store.retry(job.id());return view(store.find(job.id(),false));});}
    private MarketplaceOrderStore.StoredJob require(Long actor,long id,boolean lock){access.productActor(actor,true);var job=store.find(id,lock);if(!Objects.equals(actor,job.actor())||!Objects.equals(client.accountKey(),job.account()))throw new InputValidationFailure("수집 이력을 찾을 수 없습니다.");return job;}
    private Job view(MarketplaceOrderStore.StoredJob job){
        var progress=store.progress(job.id());var failures=store.failures(job);
        var markets=job.request().markets().stream().map(m->{
            if(!"COUPANG".equals(m))return new MarketResult(m,"PREPARING",0,"마켓 주문 API 연결 준비 중입니다.",Progress.empty());
            return new MarketResult(m,job.status(),progress.orders(),failures.isEmpty()?null:failures.getFirst().message(),progress);
        }).toList();return new Job(Long.toString(job.id()),job.status(),job.request().dateFrom(),job.request().dateTo(),job.startedAt(),job.finishedAt(),markets,store.stage(job.id()),progress,store.checkpointProgress(job.id()),failures);
    }
    /** A DB lease protects concurrent instances. Each tick performs one page outside a transaction. */
    @Scheduled(scheduler="marketplaceOrderScheduler",fixedDelay=1000)
    void collect(){
        if(shutdown||!client.configured()||!active.compareAndSet(false,true))return;
        Long jobId=null;
        try {
            jobId=tx.execute(t->store.claimJob(owner,client.accountKey()));if(jobId==null)return;
            runningId=jobId;workerThread=Thread.currentThread();
            var job=store.find(jobId,false);access.productActor(job.actor(),true);
            if(!Objects.equals(job.account(),client.accountKey()))throw new MarketplaceFailure(MarketplaceFailure.Kind.CONFIGURATION);
            var pendingDetail=store.details(jobId).stream().filter(d->"PENDING".equals(d.status())).findFirst();if(pendingDetail.isPresent()){retryDetail(job,pendingDetail.get());return;}
            var pending=store.checkpoints(jobId).stream().filter(c->"PENDING".equals(c.status())).findFirst();
            if(pending.isEmpty()){finish(jobId);return;}
            var checkpoint=pending.get();
            if(!client.configured())throw new MarketplaceFailure(MarketplaceFailure.Kind.CONFIGURATION);
            MarketplaceOrderGateway.Page page;
            try {
                access.productActor(job.actor(),true);store.stage(job.id(),owner,remoteStage(checkpoint.stream()));
                if(checkpoint.stream().startsWith("OPEN:"))page=new MarketplaceOrderGateway.Page(store.observed(job.id(),job.account(),checkpoint.stream().substring(5))?List.of():List.of(client.snapshot(checkpoint.stream().substring(5))),List.of(),null,true,null);
                else if(checkpoint.stream().startsWith("CLAIMRETURN:"))page=store.withdrawnReceipt(job.account(),checkpoint.stream().substring(12))?new MarketplaceOrderGateway.Page(List.of(),List.of(),null,true,null):client.returnClaim(checkpoint.stream().substring(12));
                else if(checkpoint.stream().startsWith("CLAIMWITHDRAWN:"))page=client.withdrawalClaims(List.of(checkpoint.stream().substring(15)));
                else if(checkpoint.stream().startsWith("CLAIMEXCHANGE:"))page=client.exchangeForOrder(checkpoint.from(),checkpoint.to(),checkpoint.cursor(),checkpoint.stream().substring(14).split(":")[0]);
                else if(checkpoint.stream().startsWith("CLAIMCANCEL:"))page=client.fetch("CANCEL",checkpoint.from(),checkpoint.to(),checkpoint.cursor());
                else if(checkpoint.stream().startsWith("CLAIM_ORIGIN_UNVERIFIED:"))page=new MarketplaceOrderGateway.Page(List.of(),List.of(),null,false,"클레임 최초 일자가 없어 이전 클레임 상태를 재조회하지 못했습니다.");
                else page=client.fetch(checkpoint.stream(),checkpoint.from(),checkpoint.to(),checkpoint.cursor());
            } catch(RuntimeException failure){failPage(jobId,checkpoint,failure);return;}
            var detailed=new ArrayList<MarketplaceOrderGateway.Snapshot>();var seen=new HashSet<String>();
            var detailFailures=new ArrayList<DetailFailure>();
            for(var discovered:page.orders()) {
                if(!seen.add(discovered.orderId())||store.observed(job.id(),job.account(),discovered.orderId()))continue;
                access.productActor(job.actor(),true);if(!store.renew(job.id(),owner)||Thread.currentThread().isInterrupted())return;store.stage(job.id(),owner,"ORDER_DETAIL");
                if(discovered.fullDetailValid())detailed.add(discovered);
                else try{detailed.add(client.snapshot(discovered.orderId()));}catch(MarketplaceFailure failure){detailFailures.add(new DetailFailure(discovered.orderId(),failureCode(failure),failure.getMessage()));if(failure.kind()==MarketplaceFailure.Kind.ORDER_ACCOUNT_MISMATCH)break;}
            }
            for(var claim:page.claims()) {
                if(detailFailures.stream().anyMatch(f->"ORDER_ACCOUNT_MISMATCH".equals(f.code())))break;
                String order=claim.orderId();if(order==null||order.isBlank()||!seen.add(order)||store.observed(job.id(),job.account(),order)||store.unavailable(job.account(),order))continue;
                access.productActor(job.actor(),true);if(!store.renew(job.id(),owner)||Thread.currentThread().isInterrupted())return;store.stage(job.id(),owner,"ORDER_DETAIL");
                try{detailed.add(client.snapshot(order));}catch(MarketplaceFailure failure){detailFailures.add(new DetailFailure(order,failureCode(failure),failure.getMessage()));if(failure.kind()==MarketplaceFailure.Kind.ORDER_ACCOUNT_MISMATCH)break;}
            }
            page=new MarketplaceOrderGateway.Page(detailed,page.claims(),page.nextCursor(),page.complete(),page.message());
            commitPage(job,checkpoint,page,detailFailures);
        }catch(RuntimeException failure){if(jobId!=null){long id=jobId;org.slf4j.LoggerFactory.getLogger(DefaultMarketplaceOrderCollections.class).warn("[MARKETPLACE_ORDER_COLLECTION] job={} code=FAILED exception={}",id,failure.getClass().getSimpleName());tx.executeWithoutResult(t->{store.lockAccount(client.accountKey());if(store.isOwned(id,owner)){store.jobError(id,"ACCESS_DENIED".equals(failureCode(failure))?"ACCESS":"STORE",failureCode(failure),"ACCESS_DENIED".equals(failureCode(failure))?"현재 관리자 권한을 확인해 주세요.":"주문 수집 기록을 처리하지 못했습니다.");store.status(id,"FAILED");}});}}
        finally{workerThread=null;runningId=null;Thread.interrupted();active.set(false);}
    }
    record DetailFailure(String orderId,String code,String message) {}
    void commitPage(MarketplaceOrderStore.StoredJob job,MarketplaceOrderStore.Checkpoint checkpoint,MarketplaceOrderGateway.Page page){commitPage(job,checkpoint,page,List.of());}
    void commitPage(MarketplaceOrderStore.StoredJob job,MarketplaceOrderStore.Checkpoint checkpoint,MarketplaceOrderGateway.Page page,List<DetailFailure> detailFailures){
        tx.executeWithoutResult(t->{
            store.lockAccount(job.account());var current=store.find(job.id(),true);if(!store.isOwned(job.id(),owner))return;
            access.productActor(current.actor(),true);
            if(!Objects.equals(current.account(),client.accountKey()))throw new MarketplaceFailure(MarketplaceFailure.Kind.CONFIGURATION);
            boolean valid=page.complete();String expected=null;
            if(checkpoint.stream().startsWith("CLAIMRETURN:"))expected=checkpoint.stream().substring(12);
            else if(checkpoint.stream().startsWith("CLAIMEXCHANGE:"))expected=checkpoint.stream().substring(14).split(":")[1];
            else if(checkpoint.stream().startsWith("CLAIMCANCEL:"))expected=checkpoint.stream().substring(12).split(":")[1];
            if(expected!=null){for(var claim:page.claims())if(expected.equals(claim.summary().id()))store.markExpectedSeen(checkpoint.id());if(checkpoint.stream().startsWith("CLAIMRETURN:")&&store.withdrawnReceipt(current.account(),expected))store.markExpectedSeen(checkpoint.id());}
            for(var snapshot:page.orders()){if(!snapshot.fullDetailValid()){store.failedDetail(current.id(),snapshot.orderId(),checkpoint,"RESPONSE","완전한 주문 상세를 확인하지 못했습니다.");continue;}store.saveSnapshot(current.account(),snapshot,current.id());store.detailDone(current.id(),snapshot.orderId());}
            for(var claim:page.claims())store.saveClaim(current.account(),claim,current.id());
            for(var failure:detailFailures)store.failedDetail(current.id(),failure.orderId(),checkpoint,failure.code(),failure.message());
            String next=page.nextCursor();if(next!=null&&next.isBlank())next=null;
            if(expected!=null&&next==null&&!store.expectedSeen(checkpoint.id()))valid=false;
            if(next!=null&&(Objects.equals(next,checkpoint.cursor())||store.cursorSeen(checkpoint.id(),next))||store.cursorCount(checkpoint.id())>=10000)throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
            if(valid){store.rememberCursor(checkpoint.id(),checkpoint.cursor());store.pageCompleted(checkpoint.id());}
            store.checkpoint(checkpoint.id(),valid?next:checkpoint.cursor(),valid?(next==null?"DONE":"PENDING"):"PARTIAL",valid?null:"일부 조회 계약 또는 주문 상세를 확인하지 못했습니다.");
            if(!valid)store.checkpointError(checkpoint.id(),remoteStage(checkpoint.stream()),expected!=null?"CLAIM_NOT_FOUND":"UNVERIFIED");
            if(detailFailures.stream().anyMatch(f->"ORDER_ACCOUNT_MISMATCH".equals(f.code()))){store.jobError(current.id(),"ORDER_DETAIL","ORDER_ACCOUNT_MISMATCH","판매자 불일치로 수집을 중단했습니다. 연결 계정을 확인해 주세요.");store.status(current.id(),"FAILED");return;}
            finishLocked(current.id());
        });
    }
    private void failPage(long job,MarketplaceOrderStore.Checkpoint checkpoint,RuntimeException failure){tx.executeWithoutResult(t->{store.lockAccount(client.accountKey());store.find(job,true);if(!store.isOwned(job,owner))return;String message=failure instanceof MarketplaceFailure known?known.getMessage():"주문 조회를 완료하지 못했습니다.";store.checkpoint(checkpoint.id(),checkpoint.cursor(),failure instanceof MarketplaceFailure known&&known.kind()==MarketplaceFailure.Kind.CONFIGURATION?"PARTIAL":"FAILED",message);store.checkpointError(checkpoint.id(),remoteStage(checkpoint.stream()),failureCode(failure));if(failure instanceof MarketplaceFailure known&&known.kind()==MarketplaceFailure.Kind.ORDER_ACCOUNT_MISMATCH){store.jobError(job,"ORDER_PAGE",failureCode(failure),known.getMessage());store.status(job,"FAILED");}else finishLocked(job);});}
    private void finish(long job){tx.executeWithoutResult(t->{store.lockAccount(client.accountKey());store.find(job,true);if(store.isOwned(job,owner))finishLocked(job);});}
    private void finishLocked(long job){
        var current=store.find(job,false);var checkpoints=store.checkpoints(job);
        for(var checkpoint:checkpoints)if(checkpoint.stream().startsWith("CLAIMRETURN:")&&Set.of("PARTIAL","FAILED").contains(checkpoint.status())&&store.withdrawnReceipt(current.account(),checkpoint.stream().substring(12))){store.markExpectedSeen(checkpoint.id());store.checkpoint(checkpoint.id(),null,"DONE",null);}
        checkpoints=store.checkpoints(job);
        if(checkpoints.stream().anyMatch(c->"PENDING".equals(c.status()))||store.details(job).stream().anyMatch(d->"PENDING".equals(d.status()))){store.status(job,"QUEUED");return;}
        boolean issue=checkpoints.stream().anyMatch(c->Set.of("FAILED","PARTIAL").contains(c.status()))||store.details(job).stream().anyMatch(d->Set.of("FAILED","BLOCKED").contains(d.status()));boolean unsupported=current.request().markets().stream().anyMatch(m->!"COUPANG".equals(m));store.status(job,issue||unsupported?"PARTIAL":"SUCCEEDED");
    }
    private void retryDetail(MarketplaceOrderStore.StoredJob job,MarketplaceOrderStore.FailedDetail detail){
        access.productActor(job.actor(),true);if(!store.renew(job.id(),owner))return;store.stage(job.id(),owner,"ORDER_DETAIL");
        MarketplaceOrderGateway.Snapshot snapshot;
        try{snapshot=client.snapshot(detail.orderId());}catch(MarketplaceFailure failure){tx.executeWithoutResult(t->{store.lockAccount(job.account());store.find(job.id(),true);if(!store.isOwned(job.id(),owner))return;store.detailFailed(detail.id(),failureCode(failure),failure.getMessage());if(failure.kind()==MarketplaceFailure.Kind.ORDER_ACCOUNT_MISMATCH){store.jobError(job.id(),"ORDER_DETAIL",failureCode(failure),failure.getMessage());store.status(job.id(),"FAILED");}else finishLocked(job.id());});return;}
        tx.executeWithoutResult(t->{store.lockAccount(job.account());store.find(job.id(),true);if(!store.isOwned(job.id(),owner))return;access.productActor(job.actor(),true);store.saveSnapshot(job.account(),snapshot,job.id());store.detailDone(job.id(),detail.orderId());finishLocked(job.id());});
    }
    String remoteStage(String source){if(client.orderStream(source))return "ORDER_PAGE";if(source.startsWith("OPEN:"))return "ORDER_DETAIL";return "CLAIMS";}
    static String failureCode(RuntimeException failure){if(failure instanceof MarketplaceFailure known)return known.kind().name();if(failure instanceof cc.ataglace.molebutter.common.api.BusinessException)return "ACCESS_DENIED";return "INTERNAL";}
    @PreDestroy void stop(){shutdown=true;Long id=runningId;if(id!=null)tx.executeWithoutResult(t->{store.lockAccount(client.accountKey());store.find(id,true);if(store.isOwned(id,owner))store.status(id,"INTERRUPTED");});Thread thread=workerThread;if(thread!=null)thread.interrupt();}
    static Request normalize(Request input,Clock clock){
        if(input==null||input.markets()==null||input.markets().isEmpty()||input.markets().size()>5||!MARKETS.containsAll(input.markets())||new HashSet<>(input.markets()).size()!=input.markets().size())throw new InputValidationFailure("수집할 마켓을 선택해 주세요.");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));LocalDate to=parse(input.dateTo(),today),from=parse(input.dateFrom(),to.minusDays(6));
        if(to.isBefore(from)||to.isAfter(today)||ChronoUnit.DAYS.between(from,to)>=31)throw new InputValidationFailure("수집 기간은 오늘까지 최대 31일입니다.");
        String requestId=input.requestId()==null?UUID.randomUUID().toString():input.requestId();try{if(!UUID.fromString(requestId).toString().equals(requestId))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new InputValidationFailure("수집 요청 번호를 확인해 주세요.");}
        return new Request(List.copyOf(input.markets()),from.toString(),to.toString(),requestId);
    }
    static LocalDate parse(String value,LocalDate fallback){if(value==null||value.isBlank())return fallback;try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(value);}catch(RuntimeException invalid){throw new InputValidationFailure("조회 날짜를 확인해 주세요.");}}
    private static long id(String value){try{long id=Long.parseLong(value);if(id<=0)throw new IllegalArgumentException();return id;}catch(RuntimeException invalid){throw new InputValidationFailure("수집 이력 번호를 확인해 주세요.");}}
}
