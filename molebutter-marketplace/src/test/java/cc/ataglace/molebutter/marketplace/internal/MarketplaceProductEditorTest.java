package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceProductEditor.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.OptionMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketplaceProductEditorTest {
    final BusinessAccess access=mock(BusinessAccess.class);
    final DraftStore store=mock(DraftStore.class);final SubmissionStore listings=mock(SubmissionStore.class);
    final EditingStore sessions=mock(EditingStore.class);final TargetEditStore pending=mock(TargetEditStore.class);
    final DefaultMarketplaceEditing editing=mock(DefaultMarketplaceEditing.class);
    final CoupangEditor editor=mock(CoupangEditor.class);final CoupangProductDocuments cp=mock(CoupangProductDocuments.class);
    final NaverCatalog catalog=mock(NaverCatalog.class);final NaverProductDocuments nv=mock(NaverProductDocuments.class);
    final CoupangProductRegistrations cr=mock(CoupangProductRegistrations.class);final NaverProductRegistrations nr=mock(NaverProductRegistrations.class);
    final ImageAssets assets=mock(ImageAssets.class);
    final Map<Long,Document> docs=new HashMap<>();final Map<Long,String> kinds=new HashMap<>();final Map<Long,Mapping> links=new HashMap<>();
    final String a="00000000-0000-4000-8000-000000000001",b="00000000-0000-4000-8000-000000000002",x="00000000-0000-4000-8000-000000000003";
    Document source;Mapping sourceMapping;DefaultMarketplaceProductEditor service;
    final PlatformTransactionManager manager=new AbstractPlatformTransactionManager(){protected Object doGetTransaction(){return new Object();}protected void doBegin(Object t,TransactionDefinition d){}protected void doCommit(DefaultTransactionStatus s){}protected void doRollback(DefaultTransactionStatus s){}};
    @BeforeEach void setup(){
        when(cp.market()).thenReturn("COUPANG");when(cp.accountKey()).thenReturn("cp");when(nv.market()).thenReturn("NAVER");when(nv.accountKey()).thenReturn("nv");
        sourceMapping=new Mapping("cp","123",List.of(new OptionMapping(a,"11","21"),new OptionMapping(b,"12","22")));
        var remote=new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123","456","실제 상품","실제 노출명","","브랜드","","9","승인완료"),new CoupangEditor.Limits(true,true,true),List.of(),List.of(),List.of(),List.of());
        var cpOptions=List.of(new CoupangOption(a,"11","21",List.of(),List.of(),List.of(),List.of()),new CoupangOption(b,"12","22",List.of(),List.of(),List.of(),List.of()));
        source=document(null,0,List.of(option(a,"빨강"),option(b,"파랑")),new MarketConfig("9",null,new Coupang("123",List.of(),List.of(),cpOptions,List.of(),remote),null,null));
        when(editor.edit(1L,"123")).thenReturn(remote);when(cp.importObserved(remote)).thenAnswer(i->source);when(cp.legacyMapping(any(),eq("cp"))).thenAnswer(i->sourceMapping);when(cp.project(any(),any(),any())).thenAnswer(i->i.getArgument(0));
        when(cp.rebase(any(),any(),any())).thenAnswer(i->new PendingRebase(i.getArgument(2),List.of()));
        when(store.find(anyLong(),anyBoolean())).thenAnswer(i->{var d=docs.get(i.getArgument(0));if(d==null)throw new MarketplaceDraftFailure(MarketplaceDraftFailure.Kind.NOT_FOUND);return d;});
        when(store.registration(anyLong())).thenAnswer(i->new DraftStore.Registration(1L,"cp",kinds.getOrDefault(i.getArgument(0),"COMMON")));
        when(store.insert(anyLong(),anyLong(),any(),nullable(String.class),nullable(String.class),nullable(String.class))).thenAnswer(i->{long id=i.getArgument(1);var d=DraftStore.version(i.getArgument(2),Long.toString(id),0L);docs.put(id,d);return d;});
        when(store.insert(anyLong(),anyLong(),any(),nullable(String.class),nullable(String.class))).thenAnswer(i->{long id=i.getArgument(1);var d=DraftStore.version(i.getArgument(2),Long.toString(id),0L);docs.put(id,d);return d;});
        when(store.update(anyLong(),anyLong(),anyLong(),any())).thenAnswer(i->{long id=i.getArgument(1),rev=i.getArgument(2);var d=DraftStore.version(i.getArgument(3),Long.toString(id),rev+1);docs.put(id,d);return d;});
        doAnswer(i->{kinds.put(i.getArgument(0),i.getArgument(2));return null;}).when(store).markRegistration(anyLong(),nullable(String.class),anyString());
        when(listings.mappedDraft(eq("COUPANG"),eq("cp"),eq("123"))).thenAnswer(i->links.entrySet().stream().filter(e->e.getValue().sellerProductId().equals("123")).map(Map.Entry::getKey).findFirst().orElse(null));
        when(listings.mapping(anyLong(),eq("COUPANG"),eq("cp"),anyBoolean())).thenAnswer(i->links.get(i.getArgument(0)));
        when(listings.linkedMarkets(anyLong())).thenAnswer(i->links.containsKey(i.getArgument(0))?Set.of("COUPANG"):Set.of());
        doAnswer(i->{links.put(i.getArgument(0),i.getArgument(3));return null;}).when(listings).saveMapping(anyLong(),anyLong(),anyString(),any(),anyBoolean());
        doAnswer(i->{links.remove(i.getArgument(0));return null;}).when(listings).moveMapping(anyLong(),anyLong(),anyString(),anyString());
        when(editing.startLazy(anyLong(),anyString(),nullable(String.class))).thenAnswer(i->{var d=docs.get(Long.parseLong(i.getArgument(1)));return new Session("99",d.id(),d.revision(),"2099-01-01T00:00:00Z",List.of(new Target("COUPANG","UPDATE","READY",null,null,source)));});
        when(pending.assetIds(anyLong(),anyList())).thenReturn(Set.of());
        service=new DefaultMarketplaceProductEditor(access,store,listings,sessions,pending,editing,editor,cp,catalog,nv,assets,List.of(cp,nv),new tools.jackson.databind.ObjectMapper(),manager);
    }
    Option option(String id,String name){return new Option(id,name,name,"100","5",List.of(new Attribute("색상",name)),"200");}
    Document document(String id,long rev,List<Option> options,MarketConfig config){return new Document(id,rev,new Common("COMMON","공통 상품","","브랜드","","","","","","TAX","EVERYONE"),options,StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(Market.COUPANG),Map.of(Market.COUPANG,config));}
    Entry unlinked(){return service.open(1L,"MARKET_EDIT","COUPANG","123",null);}
    void common(){docs.put(10L,document("10",4,List.of(option(x,"기존 옵션")),new MarketConfig("",null,null,null,null)));}
    Link link(Entry entry,List<OptionLink> options){return new Link(entry.token(),"10",4L,null,options,List.of());}
    @Test void unlinkedOpenIsReadOnlyAndOnlyEnablesOrigin(){var entry=unlinked();assertThat(entry.activeTabs()).containsExactly("COUPANG");assertThat(entry.commonId()).isNull();assertThat(entry.reference().id()).isNull();assertThat(entry.token()).isNotBlank();verify(store,never()).insert(any(),anyLong(),any(),any(),any());verify(listings,never()).saveMapping(anyLong(),anyLong(),anyString(),any(),anyBoolean());}
    @Test void linkedOpenUsesLazyOriginSessionWithoutExtraProviderReads(){common();links.put(10L,sourceMapping);var entry=service.open(1L,"MARKET_EDIT","COUPANG","123",null);assertThat(entry.commonId()).isEqualTo("10");assertThat(entry.activeTabs()).containsExactly("","COUPANG");verify(editing).startLazy(1L,"10","COUPANG");verifyNoInteractions(editor,catalog);}
    @Test void explicitConnectionRekeysByManualIdsAddsOnlyRequestedCommonOptionAndPreservesCommon(){common();var original=docs.get(10L).common();var entry=unlinked();var result=service.connect(1L,link(entry,List.of(new OptionLink(b,x,false),new OptionLink(a,null,true))));assertThat(result.commonId()).isEqualTo("10");assertThat(docs.get(10L).common()).isEqualTo(original);assertThat(docs.get(10L).options()).hasSize(2);var mapping=links.get(10L);assertThat(mapping.options().stream().filter(o->o.sellerProductItemId().equals("12")).findFirst().orElseThrow().optionId()).isEqualTo(x);assertThat(mapping.options().stream().filter(o->o.sellerProductItemId().equals("11")).findFirst().orElseThrow().optionId()).isNotIn(a,b,x);assertThat(docs.get(10L).markets().get(Market.COUPANG).coupang().options()).extracting(CoupangOption::optionId).containsExactlyElementsOf(mapping.options().stream().map(OptionMapping::optionId).toList());verify(sessions).revokeDraft(10);verifyNoInteractions(cr,nr);}
    @Test void missingAndDuplicateManualChoicesAreRejectedBeforeSaving(){common();var entry=unlinked();assertThatThrownBy(()->service.connect(1L,link(entry,List.of(new OptionLink(a,x,false))))).isInstanceOf(InputValidationFailure.class);assertThatThrownBy(()->service.connect(1L,link(entry,List.of(new OptionLink(a,x,false),new OptionLink(b,x,false))))).isInstanceOf(InputValidationFailure.class);verify(store,never()).update(any(),anyLong(),anyLong(),any());assertThat(links).isEmpty();}
    @Test void changedExternalOptionIdsAndRevisionBlockConnection(){common();var entry=unlinked();sourceMapping=new Mapping("cp","123",List.of(new OptionMapping(a,"99","21")));assertThatThrownBy(()->service.connect(1L,link(entry,List.of(new OptionLink(a,x,false),new OptionLink(b,null,true))))).isInstanceOf(InputValidationFailure.class);verify(store,never()).update(any(),anyLong(),anyLong(),any());}
    @Test void anotherActorAndChangedAccountCannotUseObservation(){common();var entry=unlinked();assertThatThrownBy(()->service.connect(2L,link(entry,List.of()))).isInstanceOf(MarketplaceEditingFailure.class);when(cp.accountKey()).thenReturn("other");assertThatThrownBy(()->service.connect(1L,link(entry,List.of()))).isInstanceOf(MarketplaceEditingFailure.class);verify(store,never()).update(any(),anyLong(),anyLong(),any());}
    @Test void newCommonRequiresAnExplicitInternalCodeAndPreservesObservedIds(){var entry=unlinked();assertThatThrownBy(()->service.connect(1L,new Link(entry.token(),null,null,"",null,List.of()))).isInstanceOf(InputValidationFailure.class);var result=service.connect(1L,new Link(entry.token(),null,null,"INTERNAL-CODE",null,List.of()));assertThat(result.reference().common().productCode()).isEqualTo("INTERNAL-CODE");assertThat(links.get(Long.parseLong(result.commonId())).options()).isEqualTo(sourceMapping.options());assertThat(result.activeTabs()).containsExactly("","COUPANG");}
    @Test void localPendingEditsTransferWithNewUuidAndTheirOriginalValues(){common();var entry=unlinked();var change=new Change("options.price",b,"0");service.connect(1L,new Link(entry.token(),"10",4L,null,List.of(new OptionLink(a,null,true),new OptionLink(b,x,false)),List.of(change)));var intent=org.mockito.ArgumentCaptor.forClass(MarketplaceWriteGateway.EditIntent.class);verify(pending).save(eq(1L),eq(10L),eq(5L),eq("COUPANG"),any(),intent.capture());assertThat(intent.getValue().changes()).containsExactly(new Change("options.price",x,"0"));}
    @Test void nativeNewEntryDoesNotCreateAnyDraftOrCallTheMarketplace(){var entry=service.open(1L,"MARKET_NEW","NAVER",null,null);assertThat(entry.kind()).isEqualTo("NAVER_REGISTRATION");assertThat(entry.activeTabs()).containsExactly("NAVER");verifyNoInteractions(editor,catalog,store,listings,cr,nr);}
    @Test void refreshingAnUnsavedStandaloneObservationKeepsOptionUuidsAndRotatesTheToken(){var entry=unlinked();var fresh=service.refresh(1L,entry.token());assertThat(fresh.reference().options()).extracting(Option::id).containsExactly(a,b);assertThat(fresh.token()).isNotEqualTo(entry.token());assertThat(fresh.reference().id()).isNull();assertThatThrownBy(()->service.refresh(1L,entry.token())).isInstanceOf(MarketplaceEditingFailure.class);verify(store,never()).insert(any(),anyLong(),any(),any(),any());}
}
