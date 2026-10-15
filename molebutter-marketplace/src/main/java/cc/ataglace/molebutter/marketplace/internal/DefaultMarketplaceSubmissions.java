package cc.ataglace.molebutter.marketplace.internal;

import java.time.*;
import java.util.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissions.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissionFailure.Kind.*;

@Service
public class DefaultMarketplaceSubmissions implements MarketplaceSubmissions {
    private final BusinessAccess access;
    private final MarketplaceDrafts drafts;
    private final DraftStore draftStore;
    private final SubmissionStore store;
    private final DefaultMarketplaceEditing editing;
    private final Map<String,MarketplaceWriteGateway> writers;
    private final boolean legacySingleWriter;
    private final TransactionTemplate transactions;
    private final String account;
    private final SubmissionWorker worker;

    public DefaultMarketplaceSubmissions(BusinessAccess access,MarketplaceDrafts drafts,DraftStore draftStore,
            SubmissionStore store,DefaultMarketplaceEditing editing,MarketplaceWriteGateway writer,PlatformTransactionManager manager,
            String vendor,boolean enabled){
        this.access=access;this.drafts=drafts;this.draftStore=draftStore;this.store=store;this.editing=editing;writers=Map.of("COUPANG",writer);legacySingleWriter=true;
        transactions=new TransactionTemplate(manager);account=account(vendor.trim());worker=new SubmissionWorker(access,store,writers,transactions,this::accountFor,enabled);
    }
    public DefaultMarketplaceSubmissions(BusinessAccess access,MarketplaceDrafts drafts,DraftStore draftStore,
            SubmissionStore store,DefaultMarketplaceEditing editing,List<MarketplaceWriteGateway> gateways,PlatformTransactionManager manager,
            String vendor,boolean enabled){
        this.access=access;this.drafts=drafts;this.draftStore=draftStore;this.store=store;this.editing=editing;
        var configured=new HashMap<String,MarketplaceWriteGateway>();for(var gateway:gateways){String market=Objects.toString(gateway.market(),"COUPANG");if(configured.putIfAbsent(market,gateway)!=null)throw new IllegalStateException("Duplicate marketplace writer");}writers=Map.copyOf(configured);legacySingleWriter=false;
        transactions=new TransactionTemplate(manager);account=account(vendor.trim());worker=new SubmissionWorker(access,store,writers,transactions,this::accountFor,enabled);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultMarketplaceSubmissions(BusinessAccess access,MarketplaceDrafts drafts,DraftStore draftStore,
            SubmissionStore store,DefaultMarketplaceEditing editing,Map<String,MarketplaceWriteGateway> gateways,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans,PlatformTransactionManager manager,
            @Value("${marketplace.coupang.vendor-id:}") String vendor,
            @Value("${marketplace.submissions.worker-enabled:true}") boolean enabled){
        this(access,drafts,draftStore,store,editing,selectGateways(gateways,beans),manager,vendor,enabled);
    }
    /** A test or deployment can override one market with Spring's existing @Primary convention. */
    static List<MarketplaceWriteGateway> selectGateways(Map<String,MarketplaceWriteGateway> gateways,org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans){
        var grouped=new LinkedHashMap<String,List<String>>();
        for(var entry:gateways.entrySet()){
            grouped.computeIfAbsent(Objects.toString(entry.getValue().market(),"COUPANG"),k->new ArrayList<>()).add(entry.getKey());
        }
        var selected=new ArrayList<MarketplaceWriteGateway>();
        for(var names:grouped.values()){
            var primary=names.stream().filter(name->beans.containsBeanDefinition(name)&&beans.getBeanDefinition(name).isPrimary()).toList();
            if(primary.size()>1||primary.isEmpty()&&names.size()>1)throw new IllegalStateException("Duplicate marketplace writer");
            selected.add(gateways.get(primary.isEmpty()?names.getFirst():primary.getFirst()));
        }
        return List.copyOf(selected);
    }
    private String accountFor(String market){var gateway=writers.get(market);return gateway==null?null:legacySingleWriter?account:Objects.toString(gateway.accountKey(),"COUPANG".equals(market)?account:null);}
    private MarketplaceDrafts.Document changed(String market,MarketplaceDrafts.Document document,List<MarketplaceEditing.Change> changes){var writer=writers.get(market);if(writer==null)throw new InputValidationFailure("마켓 변경 매핑을 확인해 주세요.");return writer.projectChanges(document,changes);}
    @Override public Preview prepare(Long actor,MarketplaceEditing.PrepareRequest input){
        access.productActor(actor,true);
        if(input==null||input.revision()==null||input.revision()<0||input.targets()==null||input.targets().isEmpty()||input.targets().size()>6)
            throw new InputValidationFailure("편집 세션과 전송할 마켓을 확인해 주세요.");
        long draft=id(input.draftId());long previewId=BusinessIds.next();
        var session=transactions.execute(tx->{
            var observed=editing.require(actor,input.sessionId(),true);
            if(!observed.session().draftId().equals(input.draftId())||observed.session().revision()!=input.revision())throw new MarketplaceSubmissionFailure(CONFLICT);
            if(input.targets().size()==1&&input.targets().getFirst()!=null){
                var requested=input.targets().getFirst();var target=observed.session().targets().stream().filter(t->Objects.equals(t.market(),requested.market())).findFirst().orElse(null);
                if(target!=null&&"READY".equals(target.status())&&writers.containsKey(target.market())){
                    var binding=editing.requireTarget(observed,target.market(),true);
                    if(store.active(draft,binding.account(),-1))throw new MarketplaceSubmissionFailure(CONFLICT);
                }
            }
            return observed;
        });
        var reference=draftStore.find(draft,false);
        var seen=new HashSet<String>();var issues=new ArrayList<MarketplaceDrafts.Issue>();var targets=new ArrayList<PreviewTarget>();
        MarketplaceWriteGateway.Prepared prepared=null;
        for(var requested:input.targets()){
            if(requested==null||requested.market()==null||!seen.add(requested.market())||requested.changes()==null||requested.changes().size()>500)
                throw new InputValidationFailure("전송할 마켓과 변경 항목을 확인해 주세요.");
            var target=session.session().targets().stream().filter(t->t.market().equals(requested.market())).findFirst().orElseThrow(()->new InputValidationFailure("편집 세션의 마켓을 확인해 주세요."));
            var targetIssues=new ArrayList<MarketplaceDrafts.Issue>();
            MarketplaceWriteGateway.Prepared result=null;
            var conflictDifferences=new ArrayList<Change>();
            var writer=writers.get(target.market());String targetAccount=accountFor(target.market());
            if(input.targets().size()>1)targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"selectedMarkets","한 번에 한 마켓씩 저장해 주세요."));
            if(writer==null)targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"selectedMarkets","이 마켓의 전송은 아직 지원하지 않습니다."));
            else if(!"READY".equals(target.status())||target.document()==null)targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"editSession","최신 상품을 다시 조회해 주세요."));
            else if(!target.conflicts().isEmpty())targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"changes","외부에서 변경된 항목을 확인하고 임시 저장한 뒤 다시 준비해 주세요."));
            else try{
                if(writer.validateCommonDraft()&&"CREATE".equals(target.mode())){
                    var candidate=writer.projectChanges(target.document(),requested.changes());
                    var single=new MarketplaceDrafts.Document(candidate.id(),candidate.revision(),candidate.common(),candidate.options(),candidate.stockMode(),candidate.productQuantity(),candidate.services(),candidate.media(),candidate.delivery(),List.of(MarketplaceDrafts.Market.valueOf(target.market())),candidate.markets());
                    var validation=drafts.validate(actor,single);
                    targetIssues.addAll(validation.errors());
                    targetIssues.addAll(validation.unverified().stream().filter(i->!urlImageWarning(i)).toList());
                }
                if(targetIssues.isEmpty()){
                    var binding=session.binding(target.market());if(binding==null)throw new MarketplaceSubmissionFailure(CONFLICT);
                    result=writer.prepareSelected(actor,reference,binding.mapping(),"COUPANG".equals(target.market())?false:input.requested(),target.document(),requested.changes());
                    if("COUPANG".equals(target.market()))result=writer.saveWorkflow(result);
                    result=result.withListingVersion(binding.listingVersion());
                    checkPrepared(result);
                    if(!Objects.equals(targetAccount,result.accountKey())||!target.market().equals(result.market())||!binding.account().equals(result.accountKey())||result.editIntent()==null||!compatiblePreparedMapping(binding.mapping(),result))throw new MarketplaceSubmissionFailure(CONFLICT);
                    prepared=result;
                }
            }catch(MarketplaceEditConflict conflict){
                targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"changes",conflict.getMessage()));
                conflictDifferences.addAll(conflict.differences().stream().map(c->new Change(c.path(),c.before(),c.after())).toList());
            }catch(InputValidationFailure e){targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"changes",e.getMessage()));}
            catch(MarketplaceFailure e){targetIssues.add(new MarketplaceDrafts.Issue(target.market(),"editSession",e.getMessage()));}
            issues.addAll(targetIssues);
            targets.add(new PreviewTarget(target.market(),target.mode(),result==null?List.of():result.steps().stream().map(s->new PlannedStep(s.id(),StepType.valueOf(s.type().name()),s.optionId(),SubmissionStore.label(s.type().name()))).toList(),
                result==null?List.copyOf(conflictDifferences):result.changes().stream().map(c->new Change(c.path(),c.before(),c.after())).toList(),List.copyOf(targetIssues),
                target.document()==null?Map.of():target.document().options().stream().collect(java.util.stream.Collectors.toMap(MarketplaceDrafts.Option::id,o->Objects.toString(o.name(),"이름 없는 옵션"),(a,b)->a))));
        }
        boolean approvalRequested=targets.stream().anyMatch(t->t.market().equals("COUPANG"))||input.requested();
        var preview=new Preview(Long.toString(previewId),input.draftId(),input.revision(),session.session().expiresAt(),prepared!=null&&!prepared.steps().isEmpty()&&issues.isEmpty(),approvalRequested,List.copyOf(targets));
        final var immutablePrepared=prepared;
        transactions.executeWithoutResult(tx->{
            access.productActor(actor,true);var current=editing.require(actor,input.sessionId(),true);
            if(!current.equals(session))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(immutablePrepared!=null)editing.requireTarget(current,immutablePrepared.market(),true);
            store.insertPreview(previewId,actor,reference,approvalRequested,Instant.parse(preview.expiresAt()));
            store.attachSession(previewId,input.sessionId());
            var assets=new HashSet<>(MarketplaceDocuments.assetIds(reference));
            if(immutablePrepared!=null)assets.addAll(MarketplaceDocuments.assetIds(changed(immutablePrepared.market(),immutablePrepared.editIntent().observed(),immutablePrepared.editIntent().changes())));
            store.pinAssets(actor,previewId,input.draftId(),assets);store.completePreview(previewId,immutablePrepared,preview);
        });return preview;
    }
    private static boolean urlImageWarning(MarketplaceDrafts.Issue i){return "COUPANG".equals(i.market())&&i.path().equals("media.images")&&i.message().contains("URL 이미지");}
    private void checkPrepared(MarketplaceWriteGateway.Prepared p){
        if(p==null||p.schemaVersion()!=1||p.accountKey()==null||!p.accountKey().matches("[a-f0-9]{64}")||p.steps()==null||p.steps().size()>500||p.changes()==null)throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
        var ids=new HashSet<String>();for(var s:p.steps())if(s==null||s.id()==null||!s.id().matches("[A-Za-z0-9_-]{1,80}")||!ids.add(s.id()))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
        if(store.preparedSize(p)>15*1024*1024)throw new InputValidationFailure("전송 정보가 너무 큽니다. 이미지·설명과 옵션을 확인해 주세요.");
    }
    @Override public Execution execute(Long actor,String previewValue,String key){
        access.productActor(actor,true);long previewId=id(previewValue);checkKey(key);
        try{return transactions.execute(tx->{
            access.productActor(actor,true);var byKey=store.byKey(key);
            if(byKey!=null){var existing=store.byPreview(previewId);if(!Objects.equals(existing,byKey))throw new MarketplaceSubmissionFailure(CONFLICT);return store.execution(byKey,false);}
            var preview=store.preview(previewId,true);var prior=store.byPreview(previewId);if(prior!=null)return store.execution(prior,false);
            var d=draftStore.find(preview.draftId(),true);if(d.revision()!=preview.revision())throw new MarketplaceSubmissionFailure(CONFLICT);
            if(!Instant.now().isBefore(preview.expiresAt()))throw new MarketplaceSubmissionFailure(EXPIRED);
            if(preview.preview()==null||!preview.preview().executable()||preview.prepared()==null||preview.prepared().schemaVersion()!=1)throw new MarketplaceSubmissionFailure(INVALID);
            if(!Objects.equals(accountFor(preview.prepared().market()),preview.prepared().accountKey()))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(preview.sessionId()==null||preview.prepared().editIntent()==null)throw new MarketplaceSubmissionFailure(INVALID);
            var session=editing.require(actor,preview.sessionId(),true);
            if(!session.session().draftId().equals(Long.toString(preview.draftId()))||session.session().revision()!=preview.revision())throw new MarketplaceSubmissionFailure(CONFLICT);
            var binding=editing.requireTarget(session,preview.prepared().market(),true);
            if(!binding.account().equals(preview.prepared().accountKey())||!compatiblePreparedMapping(binding.mapping(),preview.prepared()))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(store.active(preview.draftId(),preview.prepared().accountKey(),-1))throw new MarketplaceSubmissionFailure(CONFLICT);
            long execution=store.createExecution(actor,preview,key);return store.execution(execution,false);
        });}catch(DuplicateKeyException duplicate){
            Long existing=store.byPreview(previewId);Long keyed=store.byKey(key);if(existing!=null&&(keyed==null||existing.equals(keyed)))return store.execution(existing,false);
            throw new MarketplaceSubmissionFailure(CONFLICT);
        }
    }
    static boolean compatiblePreparedMapping(MarketplaceWriteGateway.Mapping bound,MarketplaceWriteGateway.Prepared prepared){
        return SubmissionMappingPolicy.compatiblePreparedMapping(bound,prepared);
    }
    @Override public Execution get(Long actor,String value){access.productActor(actor,true);return store.execution(id(value),false);}
    @Override public PageResponse<Execution> list(Long actor,String draftId,int page,int size){
        access.productActor(actor,true);if(page<0||page>1_000_000||!Set.of(10,20,50,100).contains(size))throw new InputValidationFailure("페이지 크기와 위치를 확인해 주세요.");
        long draft=id(draftId);draftStore.find(draft,false);return store.list(draft,page,size);
    }
    @Override public Execution retry(Long actor,String value){
        access.productActor(actor,true);long executionId=id(value);
        return transactions.execute(tx->{
            access.productActor(actor,true);var original=store.execution(executionId,false);var d=draftStore.find(id(original.draftId()),true);var e=store.execution(executionId,true);if(store.unsupportedSnapshotVersion(executionId))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(store.archived(id(original.draftId())))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.revised())throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.status()!=Status.FAILED&&e.status()!=Status.PARTIAL)throw new MarketplaceSubmissionFailure(CONFLICT);
            if(d.revision()!=e.revision())throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.targets().stream().flatMap(t->t.steps().stream()).anyMatch(step->Set.of(Status.RUNNING,Status.UNKNOWN,Status.ACCEPTED).contains(step.status())))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.targets().size()!=1||accountFor(e.targets().getFirst().market())==null)throw new MarketplaceSubmissionFailure(CONFLICT);
            if(store.active(id(e.draftId()),accountFor(e.targets().getFirst().market()),executionId))throw new MarketplaceSubmissionFailure(CONFLICT);
            // Baseline/account conflicts need a fresh preview, not a replay of an old payload.
            if(e.targets().stream().flatMap(t->t.steps().stream()).anyMatch(s->s.status()==Status.FAILED&&s.code()!=null&&Set.of("BASELINE_CHANGED","ACCOUNT_CHANGED","ACCESS_DENIED","MAPPING_CHANGED","ALREADY_CREATED","ALREADY_REGISTERED","LEGACY_INTENT","UNSUPPORTED_SNAPSHOT_VERSION").contains(s.code())))throw new MarketplaceSubmissionFailure(CONFLICT);
            store.queueRetry(executionId);return store.execution(executionId,false);
        });
    }
    @Override public Execution reconcile(Long actor,String value){
        access.productActor(actor,true);long executionId=id(value);
        return transactions.execute(tx->{
            access.productActor(actor,true);var original=store.execution(executionId,false);draftStore.find(id(original.draftId()),true);var e=store.execution(executionId,true);if(store.unsupportedSnapshotVersion(executionId))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.status()!=Status.UNKNOWN&&e.status()!=Status.ACCEPTED&&e.status()!=Status.PARTIAL)throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.targets().stream().flatMap(t->t.steps().stream()).noneMatch(s->s.status()==Status.UNKNOWN||s.status()==Status.ACCEPTED))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.targets().size()!=1||accountFor(e.targets().getFirst().market())==null)throw new MarketplaceSubmissionFailure(CONFLICT);
            if(store.active(id(e.draftId()),accountFor(e.targets().getFirst().market()),executionId))throw new MarketplaceSubmissionFailure(CONFLICT);
            store.queueReconcile(executionId);return store.execution(executionId,false);
        });
    }
    @Override public Execution revise(Long actor,String value){
        access.productActor(actor,true);long executionId=id(value);
        return transactions.execute(tx->{
            access.productActor(actor,true);var original=store.execution(executionId,false);
            draftStore.find(id(original.draftId()),true);var e=store.execution(executionId,true);
            if(e.targets().size()!=1||accountFor(e.targets().getFirst().market())==null||!Objects.equals(accountFor(e.targets().getFirst().market()),store.executionAccount(executionId)))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(e.revised())return e;
            if(e.status()!=Status.FAILED&&e.status()!=Status.PARTIAL)throw new MarketplaceSubmissionFailure(CONFLICT);
            var steps=e.targets().stream().flatMap(t->t.steps().stream()).toList();
            if(steps.stream().noneMatch(s->s.status()==Status.FAILED)||steps.stream().anyMatch(s->Set.of(Status.RUNNING,Status.UNKNOWN,Status.ACCEPTED).contains(s.status()))||store.pendingRevisionWork(executionId))throw new MarketplaceSubmissionFailure(CONFLICT);
            if(store.active(id(e.draftId()),accountFor(e.targets().getFirst().market()),executionId))throw new MarketplaceSubmissionFailure(CONFLICT);
            store.revise(executionId,actor);return store.execution(executionId,false);
        });
    }
    @Scheduled(fixedDelay=1000,initialDelay=15000)
    public void poll(){
        worker.poll();
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void heartbeat(){worker.heartbeat();}
    @Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void cleanupExpiredPreviews(){for(long preview:store.expiredUnexecutedPreviews())transactions.executeWithoutResult(tx->store.releaseExpiredPreviewPins(preview));}
    /** 호환 진입점: 모의 게이트웨이 검증과 수동 실행에서 사용한다. */
    public void runPending(){worker.runPending();}
    @PreDestroy public void close(){worker.close();}
    static long id(String value){try{long id=Long.parseLong(value);if(id<=0||!value.equals(Long.toString(id)))throw new NumberFormatException();return id;}catch(RuntimeException e){throw new InputValidationFailure("실행 식별자를 확인해 주세요.");}}
    static void checkKey(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new InputValidationFailure("실행 요청 키를 확인해 주세요.");}
    static String account(String value){return MarketplaceHashes.sha256(value);}
}
