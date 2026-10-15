package cc.ataglace.molebutter.marketplacenaver.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

class NaverCatalogTest {
    private final BusinessAccess access=mock(BusinessAccess.class);
    private final NaverGateway gateway=mock(NaverGateway.class);
    private final ObjectMapper json=new ObjectMapper();
    private final DefaultNaverCatalog catalog=new DefaultNaverCatalog(access,gateway,json);

    @BeforeEach void session(){when(gateway.session(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());}

    @Test void bundleGroupIdsStayExactStringsForBrowserSelection(){
        when(gateway.metadata(eq("bundle-groups"),any())).thenReturn(json.readTree("{\"contents\":[{\"id\":9007199254740993,\"name\":\"group\",\"usable\":true}]}"));
        var result=(tools.jackson.databind.JsonNode)catalog.metadata(42L,"bundle-groups",Map.of());assertEquals("9007199254740993",result.path("contents").path(0).path("id").asString());assertTrue(result.path("contents").path(0).path("id").isString());
    }

    @Test void statusFilterIsSentToProviderWithOtherSearchConditions(){
        when(gateway.search(any())).thenReturn(json.readTree("{\"contents\":[],\"page\":2,\"totalPages\":3,\"last\":false}"));
        catalog.products(42L,new NaverCatalog.Search(2,20,null,"SELLER","SUSPENSION"));
        var request=org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.node.ObjectNode.class);
        verify(gateway).search(request.capture());
        assertEquals("SUSPENSION",request.getValue().path("productStatusTypes").path(0).asString());
        assertEquals("SELLER",request.getValue().path("sellerManagementCode").asString());
        assertEquals(2,request.getValue().path("page").asInt());
    }
    @Test void everyDocumentedStatusCanBeSearchedAndAllOmitsFilter(){
        when(gateway.search(any())).thenReturn(json.readTree("{\"contents\":[],\"page\":1,\"totalPages\":0}"));
        for(String status:List.of("WAIT","SALE","OUTOFSTOCK","UNADMISSION","REJECTION","SUSPENSION","CLOSE","PROHIBITION","DELETE")){
            catalog.products(42L,new NaverCatalog.Search(1,20,"9001",null,status));
            verify(gateway).search(argThat(body->body.path("productStatusTypes").size()==1&&status.equals(body.path("productStatusTypes").path(0).asString())&&body.path("originProductNos").path(0).asLong()==9001));
        }
        catalog.products(42L,new NaverCatalog.Search(1,20,null,null));
        verify(gateway).search(argThat(body->!body.has("productStatusTypes")));
    }
    @Test void invalidStatusIsRejectedBeforeExternalRequest(){
        assertThrows(InputValidationFailure.class,()->catalog.products(42L,new NaverCatalog.Search(1,20,null,null,"ON")));
        verify(gateway,never()).session(any());verify(gateway,never()).search(any());
    }

    @Test void brandSearchUsesCurrentActorAndOnlyDocumentedNameQuery(){
        when(gateway.metadata("brands",Map.of("name","닥스"))).thenReturn(json.readTree("[{\"id\":9007199254740993,\"name\":\"닥스\"}]"));
        assertEquals(List.of(new NaverCatalog.Brand("9007199254740993","닥스")),
            catalog.metadata(42L,"brands",Map.of("name"," 닥스 ","actor","999","brandId","KR-41013")));
        verify(access).productActor(42L,true);verify(gateway).session(any());
        verify(gateway).metadata("brands",Map.of("name","닥스"));
    }

    @Test void incompleteOrInvalidSearchDoesNotReachExternalGateway(){
        for(String name:List.of(""," ","가".repeat(201),"닥스\n","브랜드\0"))
            assertThrows(InputValidationFailure.class,()->catalog.metadata(42L,"brands",Map.of("name",name)));
        assertThrows(InputValidationFailure.class,()->catalog.metadata(42L,"brands",null));
        assertThrows(InputValidationFailure.class,()->catalog.metadata(42L,"brands",Map.of()));
        verify(gateway,never()).metadata(any(),any());verify(gateway,never()).session(any());
    }

    @Test void emptySearchResultIsAValidList(){
        when(gateway.metadata(any(),any())).thenReturn(json.createArrayNode());
        assertEquals(List.of(),catalog.metadata(42L,"brands",Map.of("name","없는브랜드")));
    }

    @Test void malformedBrandIdentitiesNeverBecomeSelectableCandidates(){
        for(String body:List.of("{}","[{\"name\":\"브랜드\"}]","[{\"id\":1}]",
                "[{\"id\":1.5,\"name\":\"브랜드\"}]","[{\"id\":-1,\"name\":\"브랜드\"}]",
                "[{\"id\":9223372036854775808,\"name\":\"브랜드\"}]",
                "[{\"id\":\"KR-41013\",\"name\":\"브랜드\"}]","[{\"id\":1,\"name\":\" \"}]")){
            when(gateway.metadata(any(),any())).thenReturn(json.readTree(body));
            var failure=assertThrows(MarketplaceFailure.class,()->catalog.metadata(42L,"brands",Map.of("name","닥스")));
            assertEquals(MarketplaceFailure.Kind.RESPONSE,failure.kind());
        }
    }

    @Test void accessDeniedStopsBrandLookupBeforeSession(){
        when(access.productActor(42L,true)).thenThrow(new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED));
        assertThrows(BusinessException.class,()->catalog.metadata(42L,"brands",Map.of("name","닥스")));
        verify(gateway,never()).session(any());verify(gateway,never()).metadata(any(),any());
    }
}
