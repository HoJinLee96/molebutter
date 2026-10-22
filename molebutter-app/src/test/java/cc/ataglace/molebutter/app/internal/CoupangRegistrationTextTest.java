package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.imaging.api.ProductLookupDto;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CoupangRegistrationTextTest {
    ProductLookupDto product(String raw,Map<String,String> fields,List<String> categories){return new ProductLookupDto("SKU123","DAKS","닥스","닥스 남성 화면 추정값",null,null,List.of(),categories,fields,null,null,Map.of(),false,null,null,null,raw);}
    @Test void explicitSourceFactsProduceOrderedTitleWithoutMarketingSkuOrColor(){
        var title=CoupangRegistrationText.title(product("[스테디셀러] 여성 블랙 체크 체크패턴 2단 참장식 반지갑 SKU123",Map.of("제품 소재(제품 주소재)","천연가죽(소)"),List.of("패션잡화","지갑")));
        assertThat(title.display()).isEqualTo("닥스 여성 천연 소가죽 체크 패턴 2단 참장식 반지갑");
        assertThat(title.general()).isEqualTo("여성 천연 소가죽 체크 패턴 2단 참장식 반지갑");assertThat(title.missing()).isEmpty();
    }
    @Test void missingSourceNeverReusesInferredDisplayGenderAndSpecificCategoryWins(){
        var title=CoupangRegistrationText.title(product(null,Map.of(),List.of("지갑","여성 반지갑")));
        assertThat(title.display()).isEqualTo("닥스 여성 반지갑");assertThat(title.missing()).containsExactly("소재","특징");
        assertThat(CoupangRegistrationText.title(product(null,Map.of(),List.of())).display()).isEqualTo("닥스");
    }
    @Test void materialIsNotUpgradedAndConflictingGenderIsOmitted(){
        assertThat(CoupangRegistrationText.title(product("남성 여성 가죽 숄더백",Map.of(),List.of())).display()).isEqualTo("닥스 가죽 숄더백");
        assertThat(CoupangRegistrationText.title(product("WOMEN NYLON 크로스백",Map.of(),List.of())).display()).isEqualTo("닥스 여성 나일론 크로스백");
        assertThat(CoupangRegistrationText.title(product("가죽 지갑",Map.of(),List.of())).display()).doesNotContain("천연","소가죽");
        assertThat(CoupangRegistrationText.title(product("12단 지갑 SKU123",Map.of(),List.of())).display()).isEqualTo("닥스 지갑");
    }
    @Test void manufacturerNormalizesLabelSpacingBracketsAndReportsConflict(){
        assertThat(CoupangRegistrationText.manufacturers(product("지갑",Map.of(" 제조자 （수입자） "," LF ","제 조 사","LF","제조국","한국"),List.of()))).containsExactly("LF");
        assertThat(CoupangRegistrationText.manufacturers(product("지갑",Map.of("제조사","LF","제조자(수입자)","OTHER"),List.of()))).hasSize(2);
        assertThat(CoupangRegistrationText.manufacturers(product("지갑",Map.of("제조국","한국"),List.of()))).isEmpty();
        assertThat(CoupangRegistrationText.manufacturers(product("지갑",Map.of("제조사(공식수입/병행수입)","LF","제조국","한국"),List.of()))).containsExactly("LF");
    }
    @Test void longSourceFeaturesAreNotTruncatedToBypassTheExistingTitleValidator(){
        var title=CoupangRegistrationText.title(new ProductLookupDto("SKU123","DAKS","브".repeat(110),"",null,null,List.of(),List.of(),Map.of(),null,null,Map.of(),false,null,null,null,"여성 소가죽 반지갑"));
        assertThat(title.display().length()).isGreaterThan(100);
    }
}
