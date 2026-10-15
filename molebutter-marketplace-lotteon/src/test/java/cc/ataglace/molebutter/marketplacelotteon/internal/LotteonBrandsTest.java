package cc.ataglace.molebutter.marketplacelotteon.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplacelotteon.api.*;

class LotteonBrandsTest {
    private final BusinessAccess access=mock(BusinessAccess.class);
    private final LotteonBrandTransport transport=mock(LotteonBrandTransport.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"),ZoneOffset.UTC);
    private DefaultLotteonBrands catalog(String key){return new DefaultLotteonBrands(access,key,new ObjectMapper(),transport,clock,Duration.ZERO);}
    private LotteonBrandTransport.Response response(int status,String body){return new LotteonBrandTransport.Response(status,body.getBytes(StandardCharsets.UTF_8),null);}
    private static String brand(String id,String name,String usable){return "{\"data\":{\"brnd_id\":\""+id+"\",\"brnd_nm\":\""+name+"\",\"use_yn\":\""+usable+"\"}}";}

    @Test void catalogUsesAuthenticatedActorAndReturnsOnlyActiveDistinctBrands(){
        when(transport.search("닥스","fixture-key")).thenReturn(response(200,"{\"itemList\":["+brand("P134","닥스","Y")+","+brand("P134","닥스","Y")+","+brand("P135","이전브랜드","N")+",{\"is_delete\":true,\"data\":{\"use_yn\":\"Y\"}}]}"));
        assertEquals(List.of(new LotteonBrands.Brand("P134","닥스")),catalog("fixture-key").search(42L," 닥스 "));
        verify(access).productActor(42L,true);verify(transport).search("닥스","fixture-key");
    }
    @Test void emptyResultAndCachedResultDoNotCauseExtraRequests(){
        when(transport.search(any(),any())).thenReturn(response(200,"{\"itemList\":[]}"));
        var catalog=catalog("fixture-key");assertEquals(List.of(),catalog.search(42L,"없는브랜드"));assertEquals(List.of(),catalog.search(42L,"없는브랜드"));
        verify(transport,times(1)).search(any(),any());verify(access,times(2)).productActor(42L,true);
    }
    @Test void missingConfigurationInvalidQueryAndDeniedAccessNeverCallRemote(){
        for(String key:List.of("","fixture\nkey","한글키","fixture key"))assertEquals(LotteonBrandFailure.Kind.CONFIGURATION,assertThrows(LotteonBrandFailure.class,()->catalog(key).search(42L,"닥스")).kind());
        for(String name:List.of(""," ","가".repeat(201),"닥스\n"))assertThrows(InputValidationFailure.class,()->catalog("fixture-key").search(42L,name));
        assertThrows(InputValidationFailure.class,()->catalog("fixture-key").search(42L,null));
        when(access.productActor(42L,true)).thenThrow(new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED));
        assertThrows(BusinessException.class,()->catalog("fixture-key").search(42L,"닥스"));verifyNoInteractions(transport);
    }
    @Test void malformedSuccessPayloadsAndInvalidIdentitiesDoNotBecomeChoices(){
        for(String body:List.of("{}","[]","not-json","{\"itemList\":[{}]}","{\"itemList\":[{\"data\":{}}]}",
                "{\"itemList\":["+brand("P1234567890123","닥스","Y")+"]}","{\"itemList\":["+brand("P134","","Y")+"]}",
                "{\"itemList\":["+brand("P134","닥스","Y")+","+brand("P134","다른브랜드","Y")+"]}")){
            when(transport.search(any(),any())).thenReturn(response(200,body));
            assertEquals(LotteonBrandFailure.Kind.RESPONSE,assertThrows(LotteonBrandFailure.class,()->catalog("fixture-key").search(42L,"닥스")).kind());
        }
    }
    @Test void externalErrorsExposeFixedMessagesAndRateLimitPreventsImmediateRetry(){
        var statuses=Map.of(401,LotteonBrandFailure.Kind.AUTHENTICATION,403,LotteonBrandFailure.Kind.PERMISSION,
            404,LotteonBrandFailure.Kind.REJECTED,500,LotteonBrandFailure.Kind.UPSTREAM);
        for(var entry:statuses.entrySet()){
            when(transport.search(any(),any())).thenReturn(response(entry.getKey(),"fixture-secret-and-upstream-error"));
            var failure=assertThrows(LotteonBrandFailure.class,()->catalog("fixture-key").search(42L,"닥스"));
            assertEquals(entry.getValue(),failure.kind());assertFalse(failure.getMessage().contains("fixture"));
        }
        reset(transport);when(transport.search(any(),any())).thenReturn(new LotteonBrandTransport.Response(429,new byte[0],"60"));
        var catalog=catalog("fixture-key");for(int n=0;n<2;n++)assertEquals(LotteonBrandFailure.Kind.RATE_LIMIT,assertThrows(LotteonBrandFailure.class,()->catalog.search(42L,"닥스")).kind());
        verify(transport,times(1)).search(any(),any());
    }
}
