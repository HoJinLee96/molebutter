package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CoupangOptionEditsTest {
    @Test void openingUsesCurrentInventoryAndPriorPriceWithoutSavingAndStockPreparationMergesOtherIntents(){
        var copies=mock(DefaultCoupangWorkingCopies.class);var sending=mock(DefaultCoupangSubmissions.class);var registry=mock(CoupangRegisteredProducts.class);var sources=mock(CoupangQuickEditSources.class);var pricing=mock(CoupangPricing.class);var json=new ObjectMapper();
        var settings=new CoupangPricing.Settings(3,4000,new java.math.BigDecimal("10.5"),new java.math.BigDecimal("7"),null,null);when(pricing.get(1L)).thenReturn(settings);
        var source=new CoupangQuickEditSources.Source("42"," hjwa908bk ",115170L,"2026-10-16T00:00:00",true,List.of(),"fp");when(sources.get(1L,"42")).thenReturn(source);
        var observed=new CoupangEditor.EditorDocument(new CoupangEditor.Basic("123",null,"상품","상품",null,null,null,null,"승인완료"),new CoupangEditor.Limits(true,true,true),List.of(new CoupangEditor.EditOption("111","222","옵션",new CoupangCatalog.CurrentInventory("222",143000L,0L,true),null,true,List.of(),List.of(),List.of(),List.of(),List.of(new CoupangCatalog.Field("externalVendorSku","HJWA908BK")),List.of())),List.of(),List.of(),List.of());
        var mapping=new CoupangWriteGateway.Mapping("a".repeat(64),"123",List.of(new CoupangWriteGateway.OptionMapping("00000000-0000-4000-8000-000000000001","111","222")));
        var document=CoupangInputs.from(observed,mapping);var other=new CoupangEditing.Change("basic.sellerProductName",null,"유지할 수정");
        var session=new CoupangEditing.Session("session","copy",1,"2099-01-01T00:00:00Z","UPDATE",document,observed,List.of(other));
        var entry=new CoupangWorkingCopies.Entry("UPDATE","copy",1L,"123",document,"session",session.expiresAt(),observed,false,List.of(other),mapping.accountKey(),null,null,0L);
        when(copies.open(1L,"123",null)).thenReturn(entry);when(copies.require(1L,"session",false)).thenReturn(new EditingStore.Stored(1L,new EditingStore.Binding(mapping.accountKey(),mapping,1L),session,false));
        var state=new CoupangRegisteredProducts.Detail(new CoupangRegisteredProducts.Row(new CoupangCatalog.Product("123","상품",null,"APPROVED",null,null),5,null,null,null,false,false,true),null,"copy",1L,List.of(),false);when(registry.get(1L,"123")).thenReturn(state);
        var service=new DefaultCoupangOptionEdits(copies,sending,registry,sources,pricing,json,new QuickEditGuard(pricing,sources));
        var opened=service.open(1L,"123","222","STOCK","42");assertThat(opened.currentValue()).isZero();assertThat(opened.currentInventory().salePrice()).isEqualTo(143000);assertThat(opened.currentInventory().amountInStock()).isZero();assertThat(opened.source().stocks()).isEmpty();verify(copies,never()).save(anyLong(),any(),any());
        when(copies.open(1L,"123",null)).thenReturn(new CoupangWorkingCopies.Entry("UPDATE","copy",1L,"123",document,"session",session.expiresAt(),observed,true,List.of(other),mapping.accountKey(),null,null,0L));
        when(registry.get(1L,"123")).thenReturn(new CoupangRegisteredProducts.Detail(state.state(),null,"copy",1L,List.of(),true));
        assertThat(service.open(1L,"123","222","STOCK","42").registry().blocked()).isTrue();
        verify(copies,never()).save(anyLong(),any(),any());
        when(registry.get(1L,"123")).thenReturn(state);
        var change=new CoupangEditing.Change("options.registration.maximumBuyCount","00000000-0000-4000-8000-000000000001","70");
        when(copies.save(eq(1L),eq("copy"),any())).thenReturn(new CoupangWorkingCopies.Entry("UPDATE","copy",2L,"123",document,"session",session.expiresAt(),observed,false,List.of(other,change),mapping.accountKey(),null,null,0L));
        service.prepare(1L,"123","222",new CoupangOptionEdits.Prepare("STOCK",70L,"session",1L,5,"42","fp",3L,null));
        var saved=org.mockito.ArgumentCaptor.forClass(CoupangWorkingCopies.Save.class);verify(copies).save(eq(1L),eq("copy"),saved.capture());assertThat(saved.getValue().changes()).containsExactly(other,change);
        verify(sending).prepareSelected(eq(1L),eq(new CoupangEditing.PrepareRequest("copy",2L,"session",List.of(change))),eq(new CoupangOptionEdits.Basis("42","fp",null)));
        when(sources.get(1L,"42")).thenReturn(new CoupangQuickEditSources.Source("42","HJWA908",null,null,true,List.of(),"fp"));assertThatThrownBy(()->service.open(1L,"123","222","STOCK","42")).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
}
