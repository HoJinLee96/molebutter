package cc.ataglace.molebutter.app.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import cc.ataglace.molebutter.app.internal.NaverProductController;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.marketplace.api.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class NaverProductControllerTest {
    private final NaverCatalog catalog=mock(NaverCatalog.class);
    private final NaverProductSaving saving=mock(NaverProductSaving.class);
    private final NaverProductRegistrations registrations=mock(NaverProductRegistrations.class);
    private MockMvc http;
    @BeforeEach void setup(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(42L,"fixture@example.com",UserRole.ADMIN),null,List.of()));http=MockMvcBuilders.standaloneSetup(new NaverProductController(catalog,saving,registrations,new ObjectMapper())).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void listUsesServerActorAndSourceQueryContract()throws Exception{
        when(catalog.products(eq(42L),any())).thenReturn(new NaverCatalog.ProductPage(List.of(new NaverCatalog.Product("9001","9002",null,"합성 상품","SALE","ON",10000L,0L)),1,1,false));
        http.perform(get("/api/marketplaces/naver/products").param("keyword","9001").param("sellerManagementCode","SELLER").param("size","50").param("actor","999")).andExpect(status().isOk()).andExpect(jsonPath("$.data.products[0].originProductNo").value("9001")).andExpect(jsonPath("$.data.products[0].stockQuantity").value(0));
        var input=ArgumentCaptor.forClass(NaverCatalog.Search.class);verify(catalog).products(eq(42L),input.capture());assertThat(input.getValue()).isEqualTo(new NaverCatalog.Search(1,50,"9001","SELLER"));
    }
    @Test void preparationUsesObservationTokenAndNativeZeroValues()throws Exception{
        when(saving.prepare(eq(42L),eq("9001"),any())).thenReturn(new MarketplaceSubmissions.Preview("p","d",1,"2099-01-01",true,false,List.of()));
        http.perform(post("/api/marketplaces/naver/products/9001/prepare").contentType("application/json").content("""
            {"token":"server-observation","input":{"fields":{"originProduct.salePrice":0},"optionMode":"COMBINATION","optionNames":["색상"],"options":[{"id":"801fed42-3ad7-4db4-bd9c-362e6312c009","values":["블랙"],"price":0,"stockQuantity":0,"usable":true}],"images":[],"description":"<p>합성</p>"}}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.requested").value(false));
        var input=ArgumentCaptor.forClass(NaverProductSaving.Prepare.class);verify(saving).prepare(eq(42L),eq("9001"),input.capture());assertThat(input.getValue().token()).isEqualTo("server-observation");assertThat(input.getValue().input().options().getFirst().stockQuantity()).isZero();
    }
    @Test void newDraftsRejectRemoteIdentitiesAndSourceEnvelopesBeforeService()throws Exception{
        for(String forbidden:List.of("\"originProductNo\":9001","\"source\":{}","\"input\":{}"))http.perform(post("/api/marketplaces/naver/product-registrations/drafts").contentType("application/json").content("{"+forbidden+",\"fields\":{}}")) .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        http.perform(post("/api/marketplaces/naver/product-registrations/drafts").contentType("application/json").content("{\"fields\":{},\"options\":[{\"id\":\"uuid\",\"remoteId\":9}]}")) .andExpect(status().isBadRequest());verifyNoInteractions(registrations);
    }
    @Test void configurationAndExternalFailuresAreMarketSpecificAndDoNotLeakPayloads()throws Exception{
        when(catalog.products(eq(42L),any())).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.CONFIGURATION,"NAVER"));
        http.perform(get("/api/marketplaces/naver/products")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NAVER_CONFIGURATION")).andExpect(jsonPath("$.message").value("스마트스토어 커머스 API 연결 설정이 필요합니다."));
        when(saving.observe(42L,"9001")).thenThrow(new MarketplaceFailure(MarketplaceFailure.Kind.RATE_LIMIT,"NAVER"));http.perform(get("/api/marketplaces/naver/products/9001/observation")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("NAVER_RATE_LIMIT"));
    }
    @Test void draftUpdateKeepsRevisionAndNeverAddsApprovalRequestFlag()throws Exception{
        when(registrations.save(eq(42L),eq("101"),any())).thenReturn(new NaverProductRegistrations.Draft("101",2,new NaverEditor.Input(Map.of(),"NONE",List.of(),List.of(),List.of(),""),null,false));
        http.perform(put("/api/marketplaces/naver/product-registrations/drafts/101").contentType("application/json").content("{\"revision\":1,\"input\":{\"fields\":{},\"optionMode\":\"NONE\",\"optionNames\":[],\"options\":[],\"images\":[],\"description\":\"\"}}")) .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        var input=ArgumentCaptor.forClass(NaverProductRegistrations.Save.class);verify(registrations).save(eq(42L),eq("101"),input.capture());assertThat(input.getValue().revision()).isEqualTo(1L);
    }
}
