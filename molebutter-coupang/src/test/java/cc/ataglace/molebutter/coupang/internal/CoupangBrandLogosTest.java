package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangRegistrationDefaults.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CoupangBrandLogosTest {
    final BrandLogo daks=new BrandLogo("닥스",List.of("DAKS"),"https://assets.test/common/daks.png");
    final BrandLogo jill=new BrandLogo("질스튜어트",List.of("JILL STUART"),"https://assets.test/common/jill.png");
    Settings settings(List<BrandLogo> logos){return new Settings("a".repeat(64),3,Map.of(),Map.of(),Map.of(),List.of(),List.of(),logos);}
    @Test void titleNamesAndAliasesAreNormalizedWholeWordsAndPhrases(){
        for(String title:List.of("닥스 남성 지갑","[DAKS] 지갑","dAkS 지갑","ＤＡＫＳ 지갑"))assertThat(CoupangBrandLogos.match(title,settings(List.of(daks,jill))).logo()).isEqualTo(daks);
        assertThat(CoupangBrandLogos.match("JILL   STUART 가방",settings(List.of(daks,jill))).logo()).isEqualTo(jill);
        for(String title:List.of("닥스훈트","SuperDAKS","DAKS123","헤지스 가방",""))assertThat(CoupangBrandLogos.match(title,settings(List.of(daks,jill))).status()).isEqualTo("NOT_FOUND");
        assertThat(CoupangBrandLogos.match("닥스 JILL STUART",settings(List.of(daks,jill))).status()).isEqualTo("AMBIGUOUS");
        assertThat(CoupangBrandLogos.match("닥스 DAKS",settings(List.of(daks)))).extracting(CoupangBrandLogos.Result::status).isEqualTo("MATCHED");
        assertThatThrownBy(()->CoupangBrandLogos.match(null,settings(List.of(daks)))).isInstanceOf(RuntimeException.class);
    }
    @Test void selectionUsesOnlyTheAuthorizedAccountsSavedSettings(){
        var access=mock(cc.ataglace.molebutter.identity.api.BusinessAccess.class);var defaults=mock(CoupangRegistrationDefaults.class);when(defaults.get(1L)).thenReturn(settings(List.of(daks)));when(defaults.get(2L)).thenReturn(new Settings("b".repeat(64),1,Map.of(),Map.of(),Map.of()));
        var service=new DefaultCoupangBrandLogos(defaults,access);assertThat(service.select(1L,new CoupangBrandLogos.Input("닥스" )).logo()).isEqualTo(daks);assertThat(service.select(2L,new CoupangBrandLogos.Input("닥스")).status()).isEqualTo("NOT_FOUND");verify(access).productActor(1L,true);verify(access).productActor(2L,true);verify(defaults,never()).save(any(),any());
    }
}
