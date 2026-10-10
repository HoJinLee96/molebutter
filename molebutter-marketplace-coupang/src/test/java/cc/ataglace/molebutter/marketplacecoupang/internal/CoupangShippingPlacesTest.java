package cc.ataglace.molebutter.marketplacecoupang.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
class CoupangShippingPlacesTest {
    private final String outbound="{\"content\":[{\"outboundShippingPlaceCode\":25414067,\"shippingPlaceName\":\"합성 출고지\",\"usable\":false,\"placeAddresses\":[{\"addressType\":\"ROADNAME\",\"returnZipCode\":\"00112\",\"returnAddress\":\"합성 주소\",\"returnAddressDetail\":\"상세\",\"companyContactNumber\":\"010-0000-0000\"}]}],\"pagination\":{\"currentPage\":1,\"totalPages\":2,\"totalElements\":51}}";
    private CoupangProductClient client(CoupangHttpTransport transport){return new CoupangProductClient("vendor","fake-access","fake-secret",new ObjectMapper(),transport,Clock.systemUTC());}
    private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void outboundRootAndReturnEnvelopePreserveAddressesAndPaging(){
        var parser=client(new CoupangHttpTransport());var out=parser.parseShippingPlaces(bytes(outbound),false);
        assertThat(out.hasNext()).isTrue();assertThat(out.items().getFirst().usable()).isFalse();assertThat(out.items().getFirst().addresses().getFirst().zipCode()).isEqualTo("00112");
        String data=outbound.replace("\"outboundShippingPlaceCode\":25414067","\"vendorId\":\"vendor\",\"returnCenterCode\":\"1002704120\"");
        var ret=parser.parseShippingPlaces(bytes("{\"code\":200,\"data\":"+data+"}"),true);
        assertThat(ret.items().getFirst().code()).isEqualTo("1002704120");
        assertThatThrownBy(()->parser.parseShippingPlaces(bytes("{\"code\":200,\"data\":"+data.replace("\"vendorId\":\"vendor\"","\"vendorId\":\"other\"")+"}"),true)).isInstanceOf(MarketplaceFailure.class);
        assertThatThrownBy(()->parser.parseShippingPlaces(bytes("{\"code\":400,\"data\":"+data+"}"),true)).isInstanceOf(MarketplaceFailure.class);
        assertThatThrownBy(()->parser.parseShippingPlaces(bytes("{}"),false)).isInstanceOf(MarketplaceFailure.class);
    }
    @Test void emptyPageIsValid(){
        assertThat(client(new CoupangHttpTransport()).parseShippingPlaces(bytes("{\"content\":[],\"pagination\":{\"currentPage\":1,\"totalPages\":0,\"totalElements\":0}}"),false).items()).isEmpty();
    }
    @Test void readsUseDocumentedPathsAndServerAccount(){
        var transport=mock(CoupangHttpTransport.class);
        String ret=outbound.replace("\"outboundShippingPlaceCode\":25414067","\"vendorId\":\"vendor\",\"returnCenterCode\":\"1002704120\"");
        when(transport.send(eq("GET"),eq("/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound"),eq("pageNum=1&pageSize=50"),anyString(),isNull(),any())).thenReturn(new CoupangHttpTransport.Response(200,bytes(outbound)));
        when(transport.send(eq("GET"),eq("/v2/providers/openapi/apis/api/v5/vendors/vendor/returnShippingCenters"),eq("pageNum=1&pageSize=50"),anyString(),isNull(),any())).thenReturn(new CoupangHttpTransport.Response(200,bytes("{\"code\":200,\"data\":"+ret+"}")));
        when(transport.send(eq("GET"),eq("/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound"),eq("placeCodes=25414067"),anyString(),isNull(),any())).thenReturn(new CoupangHttpTransport.Response(200,bytes(outbound)));
        var client=client(transport);assertThat(client.shippingPlaces(false,1).items()).hasSize(1);assertThat(client.shippingPlaces(true,1).items()).hasSize(1);
        assertThatThrownBy(()->client.shippingPlaces(false,0)).isInstanceOf(InputValidationFailure.class);
        assertThat(client.outboundPlace("25414067").items().getFirst().code()).isEqualTo("25414067");
        verify(transport,times(3)).send(eq("GET"),anyString(),anyString(),anyString(),isNull(),any());
    }
    @Test void deniedActorNeverReadsUpstream(){
        var access=mock(BusinessAccess.class);var client=mock(CoupangProductClient.class);doThrow(new InputValidationFailure("권한 없음")).when(access).productActor(2L,true);
        assertThatThrownBy(()->new DefaultCoupangShippingPlaces(access,client).list(2L,false,1,null)).isInstanceOf(InputValidationFailure.class);verifyNoInteractions(client);
    }
}
