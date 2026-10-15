package cc.ataglace.molebutter.app.test;

import java.util.*;
import cc.ataglace.molebutter.app.internal.MarketplaceEditingController;
import cc.ataglace.molebutter.identity.api.*;
import cc.ataglace.molebutter.marketplace.api.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketplaceEditingControllerTest {
    final MarketplaceEditing editing=mock(MarketplaceEditing.class);
    MockMvc http;
    @BeforeEach void setup(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(42L,"fixture@example.com",UserRole.ADMIN),null,List.of()));http=MockMvcBuilders.standaloneSetup(new MarketplaceEditingController(editing)).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    MarketplaceEditing.Session session(){return new MarketplaceEditing.Session("20","10",4,"2099-01-01T00:00:00Z",List.of(new MarketplaceEditing.Target("NAVER","UPDATE","READY",null,null,null,List.of(),List.of(),new NaverEditor.Context(new NaverEditor.Limits(false,false,true,false,""),List.of(new NaverEditor.OptionIdentity("local","9007199254740993"))))));}
    @Test void atomicDraftSaveUsesAuthenticatedActorAndReturnsBothContracts()throws Exception{
        when(editing.saveDraft(eq(42L),eq("20"),any())).thenReturn(new MarketplaceEditing.DraftSaveResult(null,session()));
        http.perform(put("/api/marketplaces/edit-sessions/20/draft").contentType("application/json").content("""
            {"reference":{"id":"10","revision":3,"common":{"name":"공통"}},"targets":[{"market":"NAVER","changes":[{"path":"markets.NAVER.naver.editorInput.fields.originProduct.stockQuantity","optionId":null,"value":0}]}]}
            """)) .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.revision").value(4)).andExpect(jsonPath("$.data.session.targets[0].pendingChanges").isEmpty()).andExpect(jsonPath("$.data.session.targets[0].naverContext.optionIdentities[0].remoteId").value("9007199254740993"));
        var input=ArgumentCaptor.forClass(MarketplaceEditing.DraftSaveRequest.class);verify(editing).saveDraft(eq(42L),eq("20"),input.capture());assertThat(input.getValue().reference().revision()).isEqualTo(3);assertThat(input.getValue().targets().getFirst().changes().getFirst().value()).isEqualTo(0);
    }
    @Test void existingReferenceEndpointStillReturnsSessionAndConflictsStay409()throws Exception{
        when(editing.saveReference(eq(42L),eq("20"),any())).thenReturn(session());
        http.perform(put("/api/marketplaces/edit-sessions/20/reference").contentType("application/json").content("{\"id\":\"10\",\"revision\":3,\"common\":{\"name\":\"공통\"}}")) .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("20")).andExpect(jsonPath("$.data.targets[0].market").value("NAVER")).andExpect(jsonPath("$.data.session").doesNotExist());
        when(editing.saveDraft(eq(42L),eq("20"),any())).thenThrow(new MarketplaceEditingFailure(MarketplaceEditingFailure.Kind.CONFLICT));
        http.perform(put("/api/marketplaces/edit-sessions/20/draft").contentType("application/json").content("{\"reference\":{\"id\":\"10\",\"revision\":3},\"targets\":[]}")) .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MARKETPLACE_EDITING_CONFLICT"));
    }
}
