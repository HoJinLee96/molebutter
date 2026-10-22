package cc.ataglace.molebutter.app.internal;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.procurement.api.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.api.SupplierDtos.Listing;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CoupangRecommendationSourcesTest {
    final ProcurementProductQueries queries=mock(ProcurementProductQueries.class);
    final Listing listing=mock(Listing.class);
    final ProcurementProductView product=mock(ProcurementProductView.class);
    final cc.ataglace.molebutter.inventory.api.InventoryService inventory=mock(cc.ataglace.molebutter.inventory.api.InventoryService.class);
    final cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources port=new BusinessConnections().recommendationSources(queries,inventory);
    CoupangRecommendationSourcesTest(){
        when(product.id()).thenReturn("41");when(product.productCode()).thenReturn(" hjwa908bk ");when(product.selectedSupplier()).thenReturn(listing);when(product.latestStatus()).thenReturn("SUCCESS");
        when(listing.priceStatus()).thenReturn("CONFIRMED");when(listing.referencePrice()).thenReturn(115170L);when(listing.priceCheckedAt()).thenReturn(LocalDateTime.parse("2026-10-16T10:00:00"));
        when(listing.current()).thenReturn(true);when(listing.storeStatus()).thenReturn("CONFIRMED");
        when(queries.byCodes(1L,Set.of("HJWA908BK"))).thenReturn(List.of(product));
        when(inventory.ownedStocks(anyLong(),anyList())).thenReturn(Map.of("41",new cc.ataglace.molebutter.inventory.api.InventoryService.OwnedStock(0,List.of())));
        when(queries.selectedStocks(anyLong(),anyList())).thenAnswer(call->Map.of("41",new ProcurementProductQueries.SelectedStock(listing.result()==null||!Set.of("CONFIRMED","OPTIONS_PARTIAL","SOLD_OUT").contains(listing.result().state())?List.of():listing.result().options(),!listing.current()||!"SUCCESS".equals(product.latestStatus()),"2026-10-15T10:00:00")));
    }
    void stock(String state,List<SourceOption> options){var observation=mock(SupplierResult.class);when(observation.accepted()).thenReturn(true);when(observation.state()).thenReturn(state);when(observation.options()).thenReturn(options);when(listing.result()).thenReturn(observation);}
    Map<String,cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.Source> read(){return port.byCodes(1L,Set.of("HJWA908BK"));}
    @Test void batchUsesExactUniqueProductsAndExcludesMissingOrUnselectedListings(){
        assertThat(read()).containsKey("HJWA908BK");verify(queries).byCodes(1L,Set.of("HJWA908BK"));verify(queries).selectedStocks(1L,List.of(product));verifyNoMoreInteractions(queries);
        when(product.duplicateCount()).thenReturn(1L);assertThat(read()).isEmpty();when(product.duplicateCount()).thenReturn(0L);
        when(queries.byCodes(anyLong(),anySet())).thenReturn(List.of(product,product));assertThat(read()).isEmpty();when(queries.byCodes(anyLong(),anySet())).thenReturn(List.of(product));
        when(listing.selectedMissing()).thenReturn(true);assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNKNOWN");when(listing.selectedMissing()).thenReturn(false);
        when(product.selectedSupplier()).thenReturn(null);assertThat(read()).isEmpty();
    }
    @Test void lastNormalStockSurvivesPendingFailedAndHistoricalStatusButInvalidEvidenceIsExcluded(){
        stock("CONFIRMED",List.of(new SourceOption("a","FREE",144L,"AVAILABLE",null,List.of())));
        assertThat(read().get("HJWA908BK").stocks()).hasSize(1);
        for(String status:List.of("PENDING","CHECKING","FAILED","NOT_CHECKED")) {
            when(product.latestStatus()).thenReturn(status);var blocked=read().get("HJWA908BK");assertThat(blocked.stocks()).singleElement().satisfies(s->assertThat(s.quantity()).isEqualTo(144));assertThat(blocked.previousPrice()).isTrue();assertThat(blocked.previousAvailability()).isTrue();
        }
        when(product.latestStatus()).thenReturn("SUCCESS");
        when(listing.current()).thenReturn(false);when(listing.priceStatus()).thenReturn("CHECKING");
        var previous=read().get("HJWA908BK");assertThat(previous.previousPrice()).isTrue();assertThat(previous.purchaseCost()).isEqualTo(115170);assertThat(previous.stocks()).singleElement().satisfies(s->assertThat(s.quantity()).isEqualTo(144));assertThat(previous.previousAvailability()).isTrue();
        when(listing.current()).thenReturn(true);when(listing.storeStatus()).thenReturn("HISTORICAL");assertThat(read().get("HJWA908BK").stocks()).hasSize(1);
        when(listing.storeStatus()).thenReturn("CONFIRMED");stock("FAILED",List.of(new SourceOption("a","FREE",144L,"AVAILABLE",null,List.of())));assertThat(read().get("HJWA908BK").stocks()).isEmpty();
        when(listing.priceStatus()).thenReturn("STALE");assertThat(read()).isEmpty();
    }
    @Test void zeroUnavailableAndUnknownOptionsKeepFullCountWithoutSummingAndPriceCanBeUnknown(){
        stock("OPTIONS_PARTIAL",List.of(new SourceOption("a","블랙",1L,"AVAILABLE",null,List.of()),new SourceOption("b","화이트",0L,"AVAILABLE",null,List.of()),new SourceOption("c","레드",3L,"UNAVAILABLE",null,List.of()),new SourceOption("d","그린",null,"STOCK_UNKNOWN",null,List.of())));
        when(listing.referencePrice()).thenReturn(null);
        var source=read().get("HJWA908BK");assertThat(source.purchaseCost()).isNull();assertThat(source.stockOptionCount()).isEqualTo(4);assertThat(source.stocks()).extracting(s->s.quantity()).containsExactly(1L);
        when(listing.conflict()).thenReturn(true);assertThat(read()).isEmpty();
    }

    @Test void availabilityDistinguishesZeroPurchaseBlockedPartialAndRetainedNormalEvidence(){
        stock("CONFIRMED",List.of(new SourceOption("a","FREE",0L,"AVAILABLE",null,List.of())));
        assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNAVAILABLE");
        when(product.latestStatus()).thenReturn("PENDING");when(listing.current()).thenReturn(false);
        var retained=read().get("HJWA908BK");assertThat(retained.previousAvailability()).isTrue();assertThat(retained.availability()).isEqualTo("UNAVAILABLE");assertThat(retained.availabilityCheckedAt()).isEqualTo("2026-10-15T10:00:00");assertThat(retained.stocks()).isEmpty();
        stock("OPTIONS_PARTIAL",List.of(new SourceOption("a","블랙",0L,"AVAILABLE",null,List.of()),new SourceOption("b","화이트",null,"STOCK_UNKNOWN",null,List.of())));
        assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNKNOWN");
        stock("CONFIRMED",List.of(new SourceOption("a","FREE",5L,"UNAVAILABLE",null,List.of())));assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNAVAILABLE");
        stock("CONFIRMED",List.of(new SourceOption("a","FREE",5L,"AVAILABLE",null,List.of())));assertThat(read().get("HJWA908BK").availability()).isEqualTo("AVAILABLE");
        when(inventory.ownedStocks(anyLong(),anyList())).thenThrow(new IllegalStateException("owned lookup failed"));assertThatThrownBy(this::read).hasMessage("owned lookup failed");
    }
    @Test void normalMissingOverridesOldPositiveStockUntilANewerNormalObservation() {
        stock("CONFIRMED",List.of(new SourceOption("a","FREE",1L,"AVAILABLE",null,List.of())));
        var options=listing.result().options();
        var absence=new ProcurementProductQueries.SearchEvidence(true,true,"2026-10-16T11:00:00","2026-10-16T11:00:00");
        when(queries.selectedStocks(anyLong(),anyList())).thenReturn(Map.of("41",new ProcurementProductQueries.SelectedStock(options,true,"2026-10-16T10:00:00",1,absence)));
        var missing=read().get("HJWA908BK");assertThat(missing.availability()).isEqualTo("SELECTED_MISSING");assertThat(missing.availabilityReason()).isEqualTo("SELECTED_LISTING_MISSING");assertThat(missing.availabilityCheckedAt()).isEqualTo(absence.checkedAt());assertThat(missing.previousAvailability()).isTrue();
        when(queries.selectedStocks(anyLong(),anyList())).thenReturn(Map.of("41",new ProcurementProductQueries.SelectedStock(options,false,"2026-10-16T11:01:00",1,absence)));
        assertThat(read().get("HJWA908BK").availability()).isEqualTo("AVAILABLE");
        var found=new ProcurementProductQueries.SearchEvidence(false,false,"2026-10-16T12:00:00",absence.checkedAt());
        when(queries.selectedStocks(anyLong(),anyList())).thenReturn(Map.of("41",new ProcurementProductQueries.SelectedStock(options,true,"2026-10-16T10:00:00",1,found)));
        assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNKNOWN");
        when(queries.selectedStocks(anyLong(),anyList())).thenReturn(Map.of("41",new ProcurementProductQueries.SelectedStock(options,true,null,1,absence)));
        assertThat(read().get("HJWA908BK").availability()).isEqualTo("SELECTED_MISSING");
    }
    @Test void unknownZeroAndMissingWithoutNormalSearchTimestampDoNotBecomeSoldOutEvidence() {
        stock("OPTIONS_PARTIAL",List.of(new SourceOption("a","FREE",0L,"STOCK_UNKNOWN",null,List.of())));
        assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNKNOWN");
        when(listing.selectedMissing()).thenReturn(true);assertThat(read().get("HJWA908BK").availability()).isEqualTo("UNKNOWN");
    }
}
