package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverWorkflowServicesTest {
    final BusinessAccess access=mock(BusinessAccess.class);
    final DraftStore drafts=mock(DraftStore.class);
    final SubmissionStore store=mock(SubmissionStore.class);
    final ImageAssets assets=mock(ImageAssets.class);
    final DefaultMarketplaceEditing editing=mock(DefaultMarketplaceEditing.class);
    final MarketplaceSubmissions submissions=mock(MarketplaceSubmissions.class);
    final NaverProductDocuments gateway=spy(cc.ataglace.molebutter.marketplacenaver.internal.ProviderNaverDocumentsFixture.create(DefaultMarketplaceSubmissions.account("NAVER:test:SELF")));
    final NaverCatalog catalog=mock(NaverCatalog.class);
    final String account=DefaultMarketplaceSubmissions.account("NAVER:test:SELF");
    final String remoteUuid=UUID.randomUUID().toString(),savedUuid=UUID.randomUUID().toString();
    final PlatformTransactionManager transactions=new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}protected void doRollback(DefaultTransactionStatus s){}
    };
    @BeforeEach void setup(){when(gateway.accountKey()).thenReturn(account);when(store.mappedDraft(anyString(),anyString(),anyString())).thenReturn(null);}
    NaverEditor.Input input(){return new NaverEditor.Input(Map.of("originProduct.name","시험 상품","originProduct.salePrice",1000L,"originProduct.stockQuantity",0L),"NONE",List.of(),List.of(),List.of(),"");}
    NaverEditor.Input named(String name){var f=new HashMap<>(input().fields());f.put("originProduct.name",name);return new NaverEditor.Input(f,"NONE",List.of(),List.of(),List.of(),"");}
    NaverEditor.EditorDocument source(){return new NaverEditor.EditorDocument(input(),new NaverEditor.Limits(false,false,false,false,""),List.of(),"123","456");}
    DefaultNaverProductRegistrations registrations(){return new DefaultNaverProductRegistrations(access,drafts,store,assets,editing,submissions,gateway,new ObjectMapper(),transactions);}
    DefaultNaverProductSaving saving(){return new DefaultNaverProductSaving(access,catalog,gateway,drafts,store,editing,submissions,transactions);}
    MarketplaceDrafts.Document document(long revision){return gateway.document("10",revision,input());}
    @Test void incompleteDraftIsInternalOnlyAndKeepsDedicatedEditorAndAccount(){
        when(drafts.insert(eq(1L),anyLong(),any(),isNull(),isNull())).thenAnswer(i->DraftStore.version(i.getArgument(2),"10",0L));
        var draft=registrations().create(1L,input());
        assertThat(draft.id()).isEqualTo("10");assertThat(draft.input().fields()).containsEntry("originProduct.stockQuantity",0L);assertThat(draft.blocked()).isFalse();
        verify(drafts).markRegistration(10,account,"NAVER_REGISTRATION");verify(assets).replaceReferences(1L,"10",Set.of());
        verifyNoInteractions(editing,submissions,catalog);
    }
    @Test void registrationPrepareUsesSingleNativeCreateAndNoCoupangApprovalFlag(){
        var d=document(3);when(drafts.find(10,true)).thenReturn(d);when(drafts.registration(10)).thenReturn(new DraftStore.Registration(1L,account,"NAVER_REGISTRATION"));
        var session=new MarketplaceEditing.Session("20","10",3,Instant.now().plusSeconds(600).toString(),List.of(new MarketplaceEditing.Target("NAVER","CREATE","READY",null,null,d)));
        when(editing.start(1L,"10")).thenReturn(session);var preview=new MarketplaceSubmissions.Preview("30","10",3,session.expiresAt(),true,false,List.of());when(submissions.prepare(eq(1L),any())).thenReturn(preview);
        assertThat(registrations().prepare(1L,"10",new NaverProductRegistrations.Prepare(3L))).isSameAs(preview);
        var request=ArgumentCaptor.forClass(MarketplaceEditing.PrepareRequest.class);verify(submissions).prepare(eq(1L),request.capture());assertThat(request.getValue().requested()).isFalse();assertThat(request.getValue().targets()).containsExactly(new MarketplaceEditing.TargetChanges("NAVER",List.of()));
    }
    @Test void registrationActorAccountRevisionAndConfirmedMappingProtectDraft(){
        var d=document(3);when(drafts.find(10,false)).thenReturn(d);when(drafts.find(10,true)).thenReturn(d);
        when(drafts.registration(10)).thenReturn(new DraftStore.Registration(1L,account,"NAVER_REGISTRATION"));var service=registrations();
        assertThatThrownBy(()->service.get(2L,"10")).isInstanceOfSatisfying(MarketplaceDraftFailure.class,e->assertThat(e.kind()).isEqualTo(MarketplaceDraftFailure.Kind.NOT_FOUND));
        assertThatThrownBy(()->service.save(1L,"10",new NaverProductRegistrations.Save(2L,named("수정")))).isInstanceOf(MarketplaceDraftFailure.class);
        when(store.mapping(10,"NAVER",account,false)).thenReturn(new MarketplaceWriteGateway.Mapping(account,"123",List.of(),"456"));
        assertThatThrownBy(()->service.save(1L,"10",new NaverProductRegistrations.Save(3L,named("수정")))).isInstanceOf(MarketplaceDraftFailure.class);assertThat(service.get(1L,"10").blocked()).isTrue();verify(drafts,never()).update(any(),anyLong(),anyLong(),any());
        when(gateway.accountKey()).thenReturn(DefaultMarketplaceSubmissions.account("other"));assertThatThrownBy(()->service.get(1L,"10")).isInstanceOf(MarketplaceDraftFailure.class);
    }
    @Test void observationBindsActorProductAndExpiryAndDoesNotImportOnRead(){
        var time=new AtomicReference<>(Instant.parse("2026-10-08T00:00:00Z"));var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
        var service=new DefaultNaverProductSaving(access,catalog,gateway,drafts,store,editing,submissions,transactions,clock);when(catalog.editor(1L,"123")).thenReturn(source());
        var observation=service.observe(1L,"123");assertThat(observation.draftId()).isNull();
        assertThatThrownBy(()->service.prepare(2L,"123",new NaverProductSaving.Prepare(observation.token(),named("수정")))).isInstanceOf(MarketplaceEditingFailure.class);
        assertThatThrownBy(()->service.prepare(1L,"124",new NaverProductSaving.Prepare(observation.token(),named("수정")))).isInstanceOf(MarketplaceEditingFailure.class);
        time.set(time.get().plusSeconds(601));assertThatThrownBy(()->service.prepare(1L,"123",new NaverProductSaving.Prepare(observation.token(),named("수정")))).isInstanceOfSatisfying(MarketplaceEditingFailure.class,e->assertThat(e.kind()).isEqualTo(MarketplaceEditingFailure.Kind.EXPIRED));
        verify(drafts,never()).insert(any(),anyLong(),any(),any(),any(),any());verifyNoInteractions(editing,submissions);
    }
    @Test void ambiguousRegistrationWithoutProductIdBlocksEditingAndAnotherCreate(){
        var d=document(3);when(drafts.find(10,false)).thenReturn(d);when(drafts.find(10,true)).thenReturn(d);
        when(drafts.registration(10)).thenReturn(new DraftStore.Registration(1L,account,"NAVER_REGISTRATION"));
        when(store.active(10,account,0)).thenReturn(true);var service=registrations();
        var draft=service.get(1L,"10");assertThat(draft.externalProductId()).isNull();assertThat(draft.blocked()).isTrue();
        assertThatThrownBy(()->service.save(1L,"10",new NaverProductRegistrations.Save(3L,named("변경")))).isInstanceOf(MarketplaceDraftFailure.class);
        assertThatThrownBy(()->service.prepare(1L,"10",new NaverProductRegistrations.Prepare(3L))).isInstanceOf(MarketplaceDraftFailure.class);
        verifyNoInteractions(editing,submissions);verify(drafts,never()).update(any(),anyLong(),anyLong(),any());
    }
    @Test void browserRemoteIdsAndRawSourceFieldsNeverReachImport(){
        when(catalog.editor(1L,"123")).thenReturn(source());var service=saving();var token=service.observe(1L,"123");
        for(var forbidden:List.of("originProductNo","originProduct.source","originProduct.detailAttribute.optionInfo.optionCombinations")){
            var f=new HashMap<>(input().fields());f.put(forbidden,"999");var input=new NaverEditor.Input(f,"NONE",List.of(),List.of(),List.of(),"");
            assertThatThrownBy(()->service.prepare(1L,"123",new NaverProductSaving.Prepare(token.token(),input))).isInstanceOf(InputValidationFailure.class);
        }
        verify(drafts,never()).insert(any(),anyLong(),any(),any(),any(),any());verifyNoInteractions(editing,submissions);
    }
    @Test void groupAndReadonlyCategoryChangesAreBlockedBeforeDraftCreation(){
        var group=new NaverEditor.EditorDocument(input(),new NaverEditor.Limits(true,true,true,true,"그룹상품"),List.of(),"123","456");when(catalog.editor(1L,"123")).thenReturn(group);var service=saving();var groupToken=service.observe(1L,"123");
        assertThatThrownBy(()->service.prepare(1L,"123",new NaverProductSaving.Prepare(groupToken.token(),named("수정")))).isInstanceOf(InputValidationFailure.class);
        var category=new NaverEditor.EditorDocument(input(),new NaverEditor.Limits(false,false,true,false,""),List.of(),"123","456");when(catalog.editor(1L,"123")).thenReturn(category);var token=service.observe(1L,"123");
        var f=new HashMap<>(input().fields());f.put("originProduct.leafCategoryId","50000000");var changed=new NaverEditor.Input(f,"NONE",List.of(),List.of(),List.of(),"");var finalToken=token;
        assertThatThrownBy(()->service.prepare(1L,"123",new NaverProductSaving.Prepare(finalToken.token(),changed))).isInstanceOf(InputValidationFailure.class);verifyNoInteractions(editing,submissions);
    }
    @Test void savingUsesRemoteIdentityToRekeyOptionsAndPreservesExistingReference(){
        var original=new NaverEditor.Input(input().fields(),"COMBINATION",List.of("색상"),List.of(new NaverEditor.Option(remoteUuid,List.of("브라운"),0L,5L,"SKU",true)),List.of(),"");
        var source=new NaverEditor.EditorDocument(original,new NaverEditor.Limits(false,true,false,false,""),List.of(new NaverEditor.OptionIdentity(remoteUuid,"99")),"123","456");when(catalog.editor(1L,"123")).thenReturn(source);var service=saving();
        when(store.mappedDraft("NAVER",account,"123")).thenReturn(10L);var reference=gateway.document("10",2L,original);when(drafts.find(10,false)).thenReturn(reference);
        var mapping=new MarketplaceWriteGateway.Mapping(account,"123",List.of(new MarketplaceWriteGateway.OptionMapping(savedUuid,"99",null)),"456");when(store.mapping(10,"NAVER",account,false)).thenReturn(mapping);var observed=gateway.project(reference,source,mapping);
        var token=service.observe(1L,"123");var session=new MarketplaceEditing.Session("20","10",2,token.expiresAt(),List.of(new MarketplaceEditing.Target("NAVER","UPDATE","READY",null,null,observed)));when(editing.startObservedNaver(1L,"10",source,token.expiresAt())).thenReturn(session);
        var changed=new NaverEditor.Input(original.fields(),original.optionMode(),original.optionNames(),List.of(new NaverEditor.Option(remoteUuid,List.of("브라운"),0L,0L,"SKU",true)),List.of(),"");
        service.prepare(1L,"123",new NaverProductSaving.Prepare(token.token(),changed));var request=ArgumentCaptor.forClass(MarketplaceEditing.PrepareRequest.class);verify(submissions).prepare(eq(1L),request.capture());
        assertThat(request.getValue().requested()).isFalse();var rows=new ObjectMapper().valueToTree(request.getValue().targets().getFirst().changes().getFirst().value());assertThat(rows.get(0).path("id").asString()).isEqualTo(savedUuid);assertThat(rows.get(0).path("stockQuantity").asLong()).isZero();
        verify(drafts,never()).update(any(),anyLong(),anyLong(),any());verify(editing,never()).saveReference(any(),any(),any());
    }
    @Test void unchangedImagesReopenWithSavedUuidsWithoutProducingWriteIntent(){
        String sourceImage=UUID.randomUUID().toString(),savedImage=UUID.randomUUID().toString();
        var original=new NaverEditor.Input(input().fields(),"NONE",List.of(),List.of(),List.of(new NaverEditor.Image(sourceImage,null,"https://shop.example/original.jpg",true,0)),"");
        var source=new NaverEditor.EditorDocument(original,new NaverEditor.Limits(false,false,false,false,""),List.of(),"123","456");when(catalog.editor(1L,"123")).thenReturn(source);
        var reference=gateway.document("10",2L,new NaverEditor.Input(original.fields(),original.optionMode(),original.optionNames(),original.options(),List.of(new NaverEditor.Image(savedImage,null,"https://shop.example/original.jpg",true,0)),""));
        when(store.mappedDraft("NAVER",account,"123")).thenReturn(10L);when(drafts.find(10,false)).thenReturn(reference);var mapping=new MarketplaceWriteGateway.Mapping(account,"123",List.of(),"456");when(store.mapping(10,"NAVER",account,false)).thenReturn(mapping);
        var service=saving();var token=service.observe(1L,"123");var observed=gateway.project(reference,source,mapping);assertThat(gateway.input(observed).images().getFirst().id()).isEqualTo(savedImage);
        when(editing.startObservedNaver(1L,"10",source,token.expiresAt())).thenReturn(new MarketplaceEditing.Session("20","10",2,token.expiresAt(),List.of(new MarketplaceEditing.Target("NAVER","UPDATE","READY",null,null,observed))));
        service.prepare(1L,"123",new NaverProductSaving.Prepare(token.token(),original));var request=ArgumentCaptor.forClass(MarketplaceEditing.PrepareRequest.class);verify(submissions).prepare(eq(1L),request.capture());assertThat(request.getValue().targets().getFirst().changes()).isEmpty();
    }
}
