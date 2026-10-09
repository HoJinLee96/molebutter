package cc.ataglace.molebutter.marketplacecoupang.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
class CoupangBrandsTest {
    private final CoupangProductClient parser=new CoupangProductClient("vendor","fake-access","fake-secret",new ObjectMapper(),new CoupangHttpTransport(),Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"),ZoneOffset.UTC));
    @Test void parsesBrandIdentityPagingAndEmptyResults(){
        var page=parser.parseBrands(bytes("{\"code\":\"SUCCESS\",\"data\":{\"page\":1,\"countPerPage\":10,\"totalCount\":11,\"items\":[{\"brandId\":\"KR-5\",\"brandName\":\"합성 브랜드\",\"isUIDRequired\":true,\"allowedUIDTypes\":[\"MPN\"],\"unknown\":\"ignored\"}]}}"));
        assertThat(page.items().getFirst().brandId()).isEqualTo("KR-5");assertThat(page.hasNext()).isTrue();assertThat(page.items().getFirst().isUIDRequired()).isTrue();
        assertThat(parser.parseBrands(bytes("{\"code\":\"SUCCESS\",\"data\":{\"page\":1,\"countPerPage\":10,\"totalCount\":0,\"items\":[]}}")).items()).isEmpty();
        assertThatThrownBy(()->parser.parseBrands(bytes("{\"code\":\"ERROR\"}"))).isInstanceOf(MarketplaceFailure.class);
        assertThatThrownBy(()->parser.parseBrands(bytes("{\"code\":\"SUCCESS\",\"data\":{}}"))).isInstanceOf(MarketplaceFailure.class);
    }
    @Test void onlySearchedIdentityForSameActorCanBeSaved(){
        var client=mock(CoupangProductClient.class);var access=mock(BusinessAccess.class);var now=Instant.parse("2026-10-07T00:00:00Z");when(client.now()).thenReturn(now);
        var page=new CoupangBrands.Page(List.of(new CoupangBrands.Brand("KR-5","합성 브랜드",false,List.of())),1,1,false);
        when(client.operation(eq(1L),isNull(),any())).thenReturn(page);var service=new DefaultCoupangBrands(access,client);
        assertThatThrownBy(()->service.requireSelection(1L,"KR-5","합성 브랜드")).isInstanceOf(InputValidationFailure.class);
        service.search(1L,"합성",1,null);service.requireSelection(1L,"KR-5","합성 브랜드");
        assertThatThrownBy(()->service.requireSelection(2L,"KR-5","합성 브랜드")).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->service.requireSelection(1L,"KR-5","다른 이름")).isInstanceOf(InputValidationFailure.class);
        when(client.now()).thenReturn(now.plusSeconds(3601));assertThatThrownBy(()->service.requireSelection(1L,"KR-5","합성 브랜드")).isInstanceOf(InputValidationFailure.class);
    }
    @Test void brandSearchUsesPostBodyAndDeniedActorsCannotCallUpstream(){
        var transport=mock(CoupangHttpTransport.class);var mapper=new ObjectMapper();
        when(transport.send(eq("POST"),eq("/v2/providers/seller_api/apis/api/v1/marketplace/brands/search"),eq(""),anyString(),any(),any())).thenAnswer(inv->{var request=mapper.readTree((byte[])inv.getArgument(4));assertThat(request.path("brandName").asString()).isEqualTo("합성 & 브랜드");assertThat(request.path("page").asInt()).isEqualTo(2);assertThat(request.path("countPerPage").asInt()).isEqualTo(10);return new CoupangHttpTransport.Response(200,bytes("{\"code\":\"SUCCESS\",\"data\":{\"page\":2,\"countPerPage\":10,\"totalCount\":0,\"items\":[]}}"));});
        var client=new CoupangProductClient("vendor","fake-access","fake-secret",mapper,transport,Clock.systemUTC());assertThat(client.brands(" 합성 & 브랜드 ",2).page()).isEqualTo(2);
        var access=mock(BusinessAccess.class);doThrow(new InputValidationFailure("권한 없음")).when(access).productActor(2L,true);var deniedClient=mock(CoupangProductClient.class);var service=new DefaultCoupangBrands(access,deniedClient);assertThatThrownBy(()->service.search(2L,"합성",1,null)).isInstanceOf(InputValidationFailure.class);verifyNoInteractions(deniedClient);
    }
    private byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
}
