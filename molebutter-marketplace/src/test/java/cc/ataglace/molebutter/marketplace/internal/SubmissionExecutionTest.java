package cc.ataglace.molebutter.marketplace.internal;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceSubmissions.*;

class SubmissionExecutionTest {
    final Long actor=1L;
    final String key="00000000-0000-4000-8000-000000000001";
    BusinessAccess access;MarketplaceDrafts drafts;DraftStore draftStore;SubmissionStore store;DefaultMarketplaceEditing editing;MarketplaceWriteGateway writer;DefaultMarketplaceSubmissions service;
    final PlatformTransactionManager transactions=new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}
        protected void doRollback(DefaultTransactionStatus s){}
    };
    @BeforeEach void create(){access=mock(BusinessAccess.class);drafts=mock(MarketplaceDrafts.class);draftStore=mock(DraftStore.class);store=mock(SubmissionStore.class);editing=mock(DefaultMarketplaceEditing.class);writer=mock(MarketplaceWriteGateway.class);when(store.byKey(anyString())).thenReturn(null);when(store.byPreview(anyLong())).thenReturn(null);when(editing.require(eq(actor),eq("50"),eq(true))).thenReturn(new EditingStore.Stored(actor,DefaultMarketplaceSubmissions.account("test-vendor"),null,new MarketplaceEditing.Session("50","10",0,Instant.now().plusSeconds(300).toString(),List.of()),false));service=new DefaultMarketplaceSubmissions(access,drafts,draftStore,store,editing,writer,transactions,"test-vendor",false);executeWithRecordedActualRequest();}
    @AfterEach void close(){service.close();}
    void executeWithRecordedActualRequest(){when(writer.execute(any(),any(),any(),nullable(MarketplaceWriteGateway.Mapping.class),any())).thenAnswer(invocation->{MarketplaceWriteGateway.Step step=invocation.getArgument(2);java.util.function.Consumer<MarketplaceWriteGateway.Step> before=invocation.getArgument(4);before.accept(step);return writer.execute(invocation.getArgument(0),invocation.getArgument(1),step,invocation.getArgument(3));});}
    MarketplaceDrafts.Document document(long revision){return new MarketplaceDrafts.Document("10",revision,null,List.of(),MarketplaceDrafts.StockMode.OPTION,"",List.of(),new MarketplaceDrafts.Media(List.of(),List.of()),null,List.of(MarketplaceDrafts.Market.COUPANG),Map.of());}
    MarketplaceWriteGateway.Prepared prepared(){return new MarketplaceWriteGateway.Prepared(DefaultMarketplaceSubmissions.account("test-vendor"),null,List.of(new MarketplaceWriteGateway.Step("create",MarketplaceWriteGateway.Type.CREATE,null,"POST","/product","","{}","{}","{}")),List.of(),Instant.now(),List.of("SKU"),new MarketplaceWriteGateway.EditIntent(document(0),List.of()));}
    SubmissionStore.StoredPreview preview(Instant expires,long revision,boolean executable){return new SubmissionStore.StoredPreview(20,10,revision,actor,expires,document(revision),prepared(),new Preview("20","10",revision,expires.toString(),executable,false,List.of()),"50");}
    Execution execution(Status state,long revision){return new Execution("30","10",revision,state,Instant.now().toString(),Instant.now().toString(),List.of(new Target("COUPANG","CREATE",state,null,List.of(new Step("create",StepType.CREATE,null,"상품 등록",state,1,"REJECTED","오류",Instant.now().toString())))));}
    SubmissionStore.Job job(String action){var p=prepared();return new SubmissionStore.Job(30,10,0,actor,"owner",p,p.steps().getFirst(),action,null,null,40);}
    @Test void aggregateKeepsPartialAndUnknownDistinctFromSuccess(){
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.SUCCEEDED,Status.FAILED))).isEqualTo(Status.PARTIAL);
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.SUCCEEDED,Status.UNKNOWN,Status.QUEUED))).isEqualTo(Status.UNKNOWN);
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.SUCCEEDED,Status.ACCEPTED))).isEqualTo(Status.ACCEPTED);
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.ACCEPTED,Status.QUEUED))).isEqualTo(Status.ACCEPTED);
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.SUCCEEDED,Status.SUCCEEDED))).isEqualTo(Status.SUCCEEDED);
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.SUCCEEDED,Status.FAILED,Status.QUEUED))).isEqualTo(Status.PARTIAL);
        assertThat(SubmissionStore.aggregateStatus(List.of(Status.FAILED,Status.QUEUED))).isEqualTo(Status.FAILED);
    }
    @Test void expiredPreviewNeverQueuesExternalWork(){
        when(store.preview(20,true)).thenReturn(preview(Instant.now().minusSeconds(1),0,true));when(draftStore.find(10,true)).thenReturn(document(0));
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class).extracting("kind").isEqualTo(MarketplaceSubmissionFailure.Kind.EXPIRED);
        verify(store,never()).createExecution(any(),any(),any());verifyNoInteractions(writer);
    }
    @Test void changedSavedRevisionRequiresNewPreview(){
        when(store.preview(20,true)).thenReturn(preview(Instant.now().plusSeconds(300),0,true));when(draftStore.find(10,true)).thenReturn(document(1));
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class).extracting("kind").isEqualTo(MarketplaceSubmissionFailure.Kind.CONFLICT);verifyNoInteractions(writer);
    }
    @Test void nonExecutablePreviewIsRejected(){
        when(store.preview(20,true)).thenReturn(preview(Instant.now().plusSeconds(300),0,false));when(draftStore.find(10,true)).thenReturn(document(0));
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class).extracting("kind").isEqualTo(MarketplaceSubmissionFailure.Kind.INVALID);verify(store,never()).createExecution(any(),any(),any());
    }
    @Test void sameExecutionKeyReplaysResultEvenAfterDraftChanged(){
        when(store.byKey(key)).thenReturn(30L);when(store.byPreview(20)).thenReturn(30L);when(store.execution(30,false)).thenReturn(execution(Status.SUCCEEDED,0));
        assertThat(service.execute(actor,"20",key).id()).isEqualTo("30");verifyNoInteractions(draftStore,editing,writer);verify(store,never()).createExecution(any(),any(),any());
    }
    @Test void oldPreviewWithoutSessionCannotQueueWrite(){
        var p=preview(Instant.now().plusSeconds(300),0,true);when(store.preview(20,true)).thenReturn(new SubmissionStore.StoredPreview(p.id(),p.draftId(),p.revision(),p.actor(),p.expiresAt(),p.document(),p.prepared(),p.preview(),null));when(draftStore.find(10,true)).thenReturn(document(0));
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class).extracting("kind").isEqualTo(MarketplaceSubmissionFailure.Kind.INVALID);verify(store,never()).createExecution(any(),any(),any());verifyNoInteractions(writer);
    }
    @Test void revokedOrExpiredSessionCannotQueueWrite(){
        when(store.preview(20,true)).thenReturn(preview(Instant.now().plusSeconds(300),0,true));when(draftStore.find(10,true)).thenReturn(document(0));when(editing.require(actor,"50",true)).thenThrow(new MarketplaceSubmissionFailure(MarketplaceSubmissionFailure.Kind.CONFLICT));
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).createExecution(any(),any(),any());verifyNoInteractions(writer);
    }
    @Test void acceptedOrUnknownPriorOperationBlocksAnotherWrite(){
        when(store.preview(20,true)).thenReturn(preview(Instant.now().plusSeconds(300),0,true));when(draftStore.find(10,true)).thenReturn(document(0));when(store.active(10,DefaultMarketplaceSubmissions.account("test-vendor"),-1)).thenReturn(true);
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class).extracting("kind").isEqualTo(MarketplaceSubmissionFailure.Kind.CONFLICT);verify(store,never()).createExecution(any(),any(),any());verifyNoInteractions(writer);
    }
    @Test void keyCannotBeReusedForAnotherPreview(){
        when(store.byKey(key)).thenReturn(30L);when(store.byPreview(20)).thenReturn(31L);
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class);verifyNoInteractions(writer);
    }
    @Test void ambiguousExecutionCannotBeRetriedAsWrite(){
        when(store.execution(30,false)).thenReturn(execution(Status.UNKNOWN,0));when(store.execution(30,true)).thenReturn(execution(Status.UNKNOWN,0));when(draftStore.find(10,true)).thenReturn(document(0));
        assertThatThrownBy(()->service.retry(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).queueRetry(anyLong());
    }
    @Test void failedOldRevisionCannotBeRetried(){
        when(store.execution(30,false)).thenReturn(execution(Status.FAILED,0));when(store.execution(30,true)).thenReturn(execution(Status.FAILED,0));when(draftStore.find(10,true)).thenReturn(document(1));
        assertThatThrownBy(()->service.retry(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).queueRetry(anyLong());
        var conflict=new Execution("30","10",0,Status.FAILED,"","",List.of(new Target("COUPANG","CREATE",Status.FAILED,"9001",List.of(new Step("create",StepType.CREATE,null,"상품 등록",Status.FAILED,1,"ALREADY_CREATED","", "")))));
        when(store.execution(30,false)).thenReturn(conflict);when(store.execution(30,true)).thenReturn(conflict);when(draftStore.find(10,true)).thenReturn(document(0));
        assertThatThrownBy(()->service.retry(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).queueRetry(anyLong());
    }
    @Test void failureAfterDispatchBecomesUnknownWithoutBlindRetry(){
        var job=job("WRITE");when(store.claim(anyString())).thenReturn(job,null);when(store.owns(job)).thenReturn(true);when(writer.execute(actor,job.prepared(),job.step(),null)).thenThrow(new IllegalStateException("private key and upstream response"));
        service.runPending();service.runPending();
        verify(writer,times(1)).execute(actor,job.prepared(),job.step(),null);
        var result=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store).finish(eq(job),result.capture());
        assertThat(result.getValue().state()).isEqualTo(MarketplaceWriteGateway.State.UNKNOWN);assertThat(result.getValue().message()).isNull();
    }
    @Test void legacyQueuedWriteWithoutSelectedIntentCannotDispatch(){
        var current=prepared();var old=new MarketplaceWriteGateway.Prepared(current.accountKey(),current.mapping(),current.steps(),current.changes(),current.preparedAt(),current.expectedSkus());var job=new SubmissionStore.Job(30,10,0,actor,"owner",old,old.steps().getFirst(),"WRITE",null,null,40);when(store.claim(anyString())).thenReturn(job);when(store.owns(job)).thenReturn(true);
        service.runPending();verifyNoInteractions(writer);var result=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store).finish(eq(job),result.capture());assertThat(result.getValue().state()).isEqualTo(MarketplaceWriteGateway.State.FAILED);assertThat(result.getValue().code()).isEqualTo("LEGACY_INTENT");
    }
    @Test void revokedActorPreventsDispatch(){
        var job=job("WRITE");when(store.claim(anyString())).thenReturn(job);doThrow(new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED)).when(access).productActor(actor,true);
        service.runPending();verifyNoInteractions(writer);
        var result=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store).finish(eq(job),result.capture());assertThat(result.getValue().state()).isEqualTo(MarketplaceWriteGateway.State.FAILED);assertThat(result.getValue().code()).isEqualTo("ACCESS_DENIED");
    }
    @Test void reconciliationOnlyCallsReadback(){
        var p=prepared();var legacy=new MarketplaceWriteGateway.Prepared(p.accountKey(),p.mapping(),p.steps(),p.changes(),p.preparedAt(),p.expectedSkus());var job=new SubmissionStore.Job(30,10,0,actor,"owner",legacy,legacy.steps().getFirst(),"RECONCILE",null,null,40);when(store.claim(anyString())).thenReturn(job);when(store.owns(job)).thenReturn(true);when(writer.reconcile(eq(actor),eq(job.prepared()),eq(job.step()),any())).thenReturn(new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.UNKNOWN,null,"NOT_FOUND",null,Instant.now()));
        service.runPending();verify(writer).reconcile(eq(actor),eq(job.prepared()),eq(job.step()),any());verify(writer,never()).execute(any(),any(),any(),any());
    }
    @Test void lostLeasePreventsDispatch(){
        var job=job("WRITE");when(store.claim(anyString())).thenReturn(job);when(store.owns(job)).thenReturn(false);service.runPending();verifyNoInteractions(writer);verify(store,never()).finish(any(),any());
    }
    @Test void resultCommitFailureIsRecordedAsUnknown(){
        var job=job("WRITE");when(store.claim(anyString())).thenReturn(job);when(store.owns(job)).thenReturn(true);
        when(writer.execute(actor,job.prepared(),job.step(),null)).thenReturn(new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.CONFIRMED,null,"SUCCESS",null,Instant.now()));
        doThrow(new IllegalStateException("commit failed")).doNothing().when(store).finish(eq(job),any());service.runPending();
        var result=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store,times(2)).finish(eq(job),result.capture());assertThat(result.getAllValues().getLast().state()).isEqualTo(MarketplaceWriteGateway.State.UNKNOWN);
    }
    @Test void resultCannotSwitchExternalAccountMapping(){
        var job=job("WRITE");when(store.claim(anyString())).thenReturn(job);when(store.owns(job)).thenReturn(true);
        when(writer.execute(actor,job.prepared(),job.step(),null)).thenReturn(new MarketplaceWriteGateway.Result(MarketplaceWriteGateway.State.CONFIRMED,new MarketplaceWriteGateway.Mapping("wrong-account","9001",List.of()),"SUCCESS",null,Instant.now()));
        service.runPending();var result=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store).finish(eq(job),result.capture());assertThat(result.getValue().state()).isEqualTo(MarketplaceWriteGateway.State.UNKNOWN);assertThat(result.getValue().mapping()).isNull();
    }
    @Test void publicResultDoesNotUseExternalMessage(){
        assertThat(SubmissionStore.publicMessage("FAILED","SOME_ERROR")).doesNotContain("upstream");assertThat(SubmissionStore.publicCode("Authorization: key")).isEqualTo("RESPONSE");
    }
    @Test void requestKeysAndIdsHaveBoundedCanonicalFormat(){
        assertThatThrownBy(()->DefaultMarketplaceSubmissions.checkKey("not-a-key")).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->DefaultMarketplaceSubmissions.id("010")).isInstanceOf(InputValidationFailure.class);
        assertThat(DefaultMarketplaceSubmissions.id("10")).isEqualTo(10);
    }
    @Test void futureSnapshotVersionCannotQueueExecuteOrRetryOrReconcile(){
        var original=prepared();var future=new MarketplaceWriteGateway.Prepared(original.accountKey(),original.mapping(),original.steps(),original.changes(),original.preparedAt(),original.expectedSkus(),original.editIntent(),original.market(),7);
        var expiry=Instant.now().plusSeconds(300);when(store.preview(20,true)).thenReturn(new SubmissionStore.StoredPreview(20,10,0,actor,expiry,document(0),future,new Preview("20","10",0,expiry.toString(),true,false,List.of()),"50"));when(draftStore.find(10,true)).thenReturn(document(0));
        assertThatThrownBy(()->service.execute(actor,"20",key)).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).createExecution(any(),any(),any());
        when(store.unsupportedSnapshotVersion(30)).thenReturn(true);when(store.execution(30,false)).thenReturn(execution(Status.FAILED,0));when(store.execution(30,true)).thenReturn(execution(Status.FAILED,0));
        assertThatThrownBy(()->service.retry(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).queueRetry(anyLong());
        when(store.execution(30,false)).thenReturn(execution(Status.UNKNOWN,0));when(store.execution(30,true)).thenReturn(execution(Status.UNKNOWN,0));
        assertThatThrownBy(()->service.reconcile(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).queueReconcile(anyLong());verifyNoInteractions(writer);
    }
    @Test void alreadyQueuedFutureSnapshotFailsWithoutSendingOrReconciliation(){
        var p=prepared();var future=new MarketplaceWriteGateway.Prepared(p.accountKey(),p.mapping(),p.steps(),p.changes(),p.preparedAt(),p.expectedSkus(),p.editIntent(),p.market(),99);
        for(String action:List.of("WRITE","RECONCILE")){
            var job=new SubmissionStore.Job(30,10,0,actor,"owner",future,future.steps().getFirst(),action,null,null,40);when(store.claim(anyString())).thenReturn(job);service.runPending();
            var outcome=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.Result.class);verify(store).finish(eq(job),outcome.capture());assertThat(outcome.getValue().state()).isEqualTo(MarketplaceWriteGateway.State.FAILED);assertThat(outcome.getValue().code()).isEqualTo("UNSUPPORTED_SNAPSHOT_VERSION");clearInvocations(store);
        }
        verifyNoInteractions(writer);
    }
    @Test void revisionRetiresConfirmedFailureWithoutDispatchAndIsIdempotent(){
        var failed=execution(Status.FAILED,0);var revised=new Execution(failed.id(),failed.draftId(),failed.revision(),failed.status(),failed.createdAt(),failed.updatedAt(),failed.targets(),true);
        when(store.execution(30,false)).thenReturn(failed,revised);when(store.execution(30,true)).thenReturn(failed,revised);
        when(store.executionAccount(30)).thenReturn(DefaultMarketplaceSubmissions.account("test-vendor"));when(draftStore.find(10,true)).thenReturn(document(0));
        assertThat(service.revise(actor,"30").revised()).isTrue();assertThat(service.revise(actor,"30").revised()).isTrue();
        verify(store,times(1)).revise(30,actor);verifyNoInteractions(writer);
        when(store.execution(30,false)).thenReturn(revised);when(store.execution(30,true)).thenReturn(revised);
        assertThatThrownBy(()->service.retry(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).queueRetry(anyLong());
    }
    @Test void revisionCannotRetireRunningUncertainOrAcceptedWork(){
        when(draftStore.find(10,true)).thenReturn(document(0));when(store.executionAccount(30)).thenReturn(DefaultMarketplaceSubmissions.account("test-vendor"));
        for(var state:List.of(Status.QUEUED,Status.RUNNING,Status.UNKNOWN,Status.ACCEPTED,Status.SUCCEEDED)){
            when(store.execution(30,false)).thenReturn(execution(state,0));when(store.execution(30,true)).thenReturn(execution(state,0));
            assertThatThrownBy(()->service.revise(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);
        }
        var uncertain=new Execution("30","10",0,Status.PARTIAL,"","",List.of(new Target("COUPANG","UPDATE",Status.PARTIAL,"9001",List.of(new Step("failed",StepType.PRICE,null,"",Status.FAILED,1,"REJECTED","",""),new Step("pending",StepType.PRODUCT,null,"",Status.ACCEPTED,1,"","","")))));
        when(store.execution(30,false)).thenReturn(uncertain);when(store.execution(30,true)).thenReturn(uncertain);
        assertThatThrownBy(()->service.revise(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).revise(anyLong(),any());verifyNoInteractions(writer);
    }
    @Test void revisionRejectsChangedAccountOtherActiveExecutionAndAttemptedPendingWork(){
        var failed=execution(Status.FAILED,0);when(store.execution(30,false)).thenReturn(failed);when(store.execution(30,true)).thenReturn(failed);when(draftStore.find(10,true)).thenReturn(document(0));
        when(store.executionAccount(30)).thenReturn("different-account");assertThatThrownBy(()->service.revise(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);
        when(store.executionAccount(30)).thenReturn(DefaultMarketplaceSubmissions.account("test-vendor"));when(store.active(10,DefaultMarketplaceSubmissions.account("test-vendor"),30)).thenReturn(true);
        assertThatThrownBy(()->service.revise(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);
        when(store.active(10,DefaultMarketplaceSubmissions.account("test-vendor"),30)).thenReturn(false);when(store.pendingRevisionWork(30)).thenReturn(true);
        assertThatThrownBy(()->service.revise(actor,"30")).isInstanceOf(MarketplaceSubmissionFailure.class);verify(store,never()).revise(anyLong(),any());verifyNoInteractions(writer);
    }

}
