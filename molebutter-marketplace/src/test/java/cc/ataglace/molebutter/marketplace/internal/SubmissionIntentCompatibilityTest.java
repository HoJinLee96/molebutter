package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

class SubmissionIntentCompatibilityTest {
    final ObjectMapper json=new ObjectMapper();final SubmissionStore store=new SubmissionStore(org.mockito.Mockito.mock(JdbcTemplate.class),json);
    Document document(String name,String category,String other,long revision){return json.readValue("{\"id\":\"42\",\"revision\":"+revision+",\"common\":{\"name\":\""+name+"\"},\"markets\":{\"COUPANG\":{\"categoryCode\":\""+category+"\"},\"LOTTEON\":{\"categoryCode\":\""+other+"\"}}}",Document.class);}
    MarketplaceWriteGateway.EditIntent intent(Document d){return new MarketplaceWriteGateway.EditIntent(d,List.of(new MarketplaceEditing.Change("common.name",null,"전송 이름")));}
    @Test void unrelatedMarketSettingsAndDraftRevisionDoNotInvalidateSentCreate(){assertThat(store.sameRegisteredIntent(intent(document("같은 기준","123","old",1)),intent(document("같은 기준","123","new",2)),"COUPANG")).isTrue();}
    @Test void selectedProviderAndCommonChangesAreNotRetiredAsAlreadySent(){var sent=intent(document("기준","123","old",1));assertThat(store.sameRegisteredIntent(sent,intent(document("다른 기준","123","old",2)),"COUPANG")).isFalse();assertThat(store.sameRegisteredIntent(sent,intent(document("기준","456","old",2)),"COUPANG")).isFalse();}
    @Test void differentPendingChangesAreNotRetired(){var d=document("기준","123","old",1);assertThat(store.sameRegisteredIntent(intent(d),new MarketplaceWriteGateway.EditIntent(d,List.of(new MarketplaceEditing.Change("common.name",null,"새 미전송 이름"))),"COUPANG")).isFalse();}

    MarketplaceWriteGateway.Mapping mapping(String product,String option){return new MarketplaceWriteGateway.Mapping("account",product,List.of(new MarketplaceWriteGateway.OptionMapping(option,product==null?null:"item",product==null?null:"vendor")));}
    MarketplaceWriteGateway.Prepared prepared(MarketplaceWriteGateway.Mapping mapping){return new MarketplaceWriteGateway.Prepared("account",mapping,List.of(),List.of(),java.time.Instant.now(),List.of(),intent(document("기준","123","old",1)),"COUPANG",1,1L);}
    @Test void createPlaceholderIsRegistrationBasisOnlyForSameAccountGenerationAndOptionUuids(){
        var binding=new EditingStore.Binding("account",mapping("remote","uuid"),1L);var p=prepared(mapping(null,"uuid"));
        assertThat(store.registrationBasis(p,binding)).isTrue();assertThat(store.registrationBasis(prepared(null),binding)).isTrue();
        assertThat(store.registrationBasis(p,new EditingStore.Binding("account",mapping("remote","uuid"),2L))).isFalse();
        assertThat(store.registrationBasis(p,new EditingStore.Binding("other",mapping("remote","uuid"),1L))).isFalse();
        assertThat(store.registrationBasis(p,new EditingStore.Binding("account",mapping("remote","changed-uuid"),1L))).isFalse();
        assertThat(store.registrationBasis(prepared(mapping("another-remote","uuid")),binding)).isFalse();
        assertThat(store.registrationBasis(prepared(new MarketplaceWriteGateway.Mapping("account",null,List.of(new MarketplaceWriteGateway.OptionMapping("uuid","already-linked",null)))),binding)).isFalse();
    }
    @Test void successfulCreateWithPlaceholderRestoresPendingAndLastSavedIntent(){
        var binding=new EditingStore.Binding("account",mapping("remote","uuid"),1L);var p=prepared(mapping(null,"uuid"));var db=org.mockito.Mockito.mock(JdbcTemplate.class);var actual=new SubmissionStore(db,json);
        org.mockito.Mockito.when(db.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(String.class),org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(List.of(json.writeValueAsString(p)));
        org.mockito.Mockito.when(db.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(List.of(Map.of("id",1L,"prepared_json",json.writeValueAsString(p))));
        assertThat(actual.transmittedIntent(42,"COUPANG",binding).execution()).isEqualTo(1L);
        assertThat(actual.registeredIntent(42,"COUPANG",binding,p.editIntent())).isTrue();assertThat(actual.lastIntent(42,"COUPANG",binding)).isEqualTo(p.editIntent());
    }
}
