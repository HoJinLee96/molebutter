package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoupangProductSavingTest {
    final ObjectMapper json=new ObjectMapper();
    final BusinessAccess access=mock(BusinessAccess.class);
    final CoupangEditor editor=mock(CoupangEditor.class);
    final CoupangProductDocuments documents=cc.ataglace.molebutter.marketplacecoupang.internal.ProviderCoupangDocumentsFixture.create(editor,mock(CoupangBrands.class));
    final DefaultMarketplaceDrafts drafts=mock(DefaultMarketplaceDrafts.class);
    final DefaultMarketplaceEditing editing=mock(DefaultMarketplaceEditing.class);
    final MarketplaceSubmissions submissions=mock(MarketplaceSubmissions.class);
    final DefaultCoupangProductSaving service=new DefaultCoupangProductSaving(access,editor,documents,drafts,editing,submissions,json,"A-test");
    CoupangEditor.EditorDocument source(){
        return new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123","456","상품","노출명","제품","브랜드","그룹","123","승인완료"),new CoupangEditor.Limits(true,true,true),List.of(new CoupangEditor.EditOption("111","222","블랙",new CoupangCatalog.CurrentInventory("222",1000L,1L,true),"확인",true,List.of(new CoupangCatalog.Attribute("색상","블랙","EXPOSED")),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku","SKU")),List.of())),List.of(),List.of(),List.of());
    }
    @Test void observationDoesNotCreateDraftAndTokensAreBoundToActorAndProduct(){
        when(editor.edit(1L,"123",null)).thenReturn(source());
        var observed=service.observe(1L,"123",null);
        assertThat(Instant.parse(observed.expiresAt())).isAfter(Instant.now());
        assertThat(observed.document().basic().sellerProductId()).isEqualTo("123");
        var request=new CoupangProductSaving.Prepare(observed.token(),List.of(new CoupangProductSaving.Change("common.name",null,"변경")));
        assertThatThrownBy(()->service.prepare(2L,"123",request)).isInstanceOf(MarketplaceEditingFailure.class);
        assertThatThrownBy(()->service.prepare(1L,"124",request)).isInstanceOf(MarketplaceEditingFailure.class);
        verify(drafts,never()).importObserved(any(),any());verifyNoInteractions(editing,submissions);
    }
    @Test void prepareReusesTrustedObservationAndAlwaysRequestsApprovalWithoutSavingReference(){
        var source=source();var reference=documents.importObserved(source);
        reference=new MarketplaceDrafts.Document("10",3L,reference.common(),reference.options(),reference.stockMode(),reference.productQuantity(),reference.services(),reference.media(),reference.delivery(),reference.selectedMarkets(),reference.markets());
        when(editor.edit(1L,"123",null)).thenReturn(source);
        var token=service.observe(1L,"123",null);
        when(drafts.importObserved(1L,source)).thenReturn(reference);
        var session=new MarketplaceEditing.Session("20","10",3L,token.expiresAt(),List.of(new MarketplaceEditing.Target("COUPANG","UPDATE","READY",null,null,reference)));
        when(editing.startObserved(1L,"10",source,token.expiresAt())).thenReturn(session);
        var expected=new MarketplaceSubmissions.Preview("30","10",3L,token.expiresAt(),true,true,List.of());
        when(submissions.prepare(eq(1L),any())).thenReturn(expected);
        var result=service.prepare(1L,"123",new CoupangProductSaving.Prepare(token.token(),List.of(new CoupangProductSaving.Change("options.quantity","111","0"))));
        assertThat(result).isSameAs(expected);
        var captor=org.mockito.ArgumentCaptor.forClass(MarketplaceEditing.PrepareRequest.class);verify(submissions).prepare(eq(1L),captor.capture());
        assertThat(captor.getValue().requested()).isTrue();
        assertThat(captor.getValue().targets().getFirst().changes()).containsExactly(new MarketplaceEditing.Change("options.quantity",reference.options().getFirst().id(),"0"));
        verify(drafts,never()).save(any(),any(),any(),any());verify(editing,never()).saveReference(any(),any(),any());
    }
    @Test void invalidPathsAndRemoteIdentifiersFailBeforeDraftCreation(){
        when(editor.edit(1L,"123",null)).thenReturn(source());var token=service.observe(1L,"123",null);
        for(var change:List.of(new CoupangProductSaving.Change("common.brand",null,"other"),new CoupangProductSaving.Change("markets.COUPANG.coupang.source",null,Map.of()),new CoupangProductSaving.Change("options.quantity","wrong","0"))){
            assertThatThrownBy(()->service.prepare(1L,"123",new CoupangProductSaving.Prepare(token.token(),List.of(change)))).isInstanceOf(InputValidationFailure.class);
        }
        verify(drafts,never()).importObserved(any(),any());verifyNoInteractions(editing,submissions);
    }
    @Test void expiredObservationCannotPrepareOrCreateDraft(){
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-07T00:00:00Z"));
        var clock=new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return instant.get();}};
        var timed=new DefaultCoupangProductSaving(access,editor,documents,drafts,editing,submissions,json,"A-test",clock);
        when(editor.edit(1L,"123",null)).thenReturn(source());var token=timed.observe(1L,"123",null);instant.set(instant.get().plusSeconds(601));
        assertThatThrownBy(()->timed.prepare(1L,"123",new CoupangProductSaving.Prepare(token.token(),List.of(new CoupangProductSaving.Change("common.name",null,"변경")))))
            .isInstanceOfSatisfying(MarketplaceEditingFailure.class,e->assertThat(e.kind()).isEqualTo(MarketplaceEditingFailure.Kind.EXPIRED));
        verify(drafts,never()).importObserved(any(),any());verifyNoInteractions(editing,submissions);
    }
    @Test void mediaScopeIsInjectedAndRawSourceOrSuppliedOptionIdsCannotCrossBoundary(){
        var observed=documents.importObserved(source());String id=observed.options().getFirst().id();
        var changes=DefaultCoupangProductSaving.translate(List.of(new CoupangProductSaving.Change("media.images","111",List.of(Map.of("url","https://example.com/new.jpg","representative",true,"order",0,"type","REPRESENTATION")))),observed,json,documents);
        var row=json.valueToTree(changes.getFirst().value()).get(0);
        assertThat(row.path("optionId").asString()).isEqualTo(id);assertThat(row.path("id").asString()).isNotBlank();
        assertThatThrownBy(()->DefaultCoupangProductSaving.translate(List.of(new CoupangProductSaving.Change("media.images","111",List.of(Map.of("optionId",id)))),observed,json,documents)).isInstanceOf(InputValidationFailure.class);
    }
}
