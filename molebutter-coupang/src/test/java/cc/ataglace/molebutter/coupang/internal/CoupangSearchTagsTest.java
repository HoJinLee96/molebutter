package cc.ataglace.molebutter.coupang.internal;

import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.coupang.api.CoupangSearchTags.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoupangSearchTagsTest {
    final DefaultCoupangSearchTags tags=new DefaultCoupangSearchTags();
    Result generate(String title,String brand,String gender,String material,String category,String...colors){return tags.generate(new Input(title,brand,new Context(gender,material,category,List.of(colors))));}
    @Test void exactPriorityExampleAndOverflowAreStable(){
        var r=generate("닥스 남성 천연 소가죽 체크 패턴 반지갑","닥스","남성","소가죽","반지갑","블랙");
        assertThat(r.tags()).containsExactly("닥스","남성","천연","소가죽","체크","패턴","반지갑","블랙","지갑","카드","남자","아버지","가죽","회사원","직장인","정장","선물","캐주얼","데일리","모던");
        assertThat(r.excluded()).containsExactly(new Excluded("클래식","OVER_COUNT"));
    }
    @Test void bagKindsGenderAndBrandAreRelevantOnly(){
        var r=generate("루이까또즈 여성 나일론 미니백","루이까또즈","","","","베이지");
        assertThat(r.tags()).contains("루이까또쯔","루이까도쯔","루이까도즈","가방","핸드백","백","여자","어머니").doesNotContain("아버지","회사원","정장","소가죽","가죽","토트백","백팩","크로스백");
        assertThat(generate("닥스 여성 토트백","닥스","","","","블랙").tags()).doesNotContain("루이까또쯔","루이까도쯔","루이까도즈");
        for(String kind:List.of("크로스백","미니백","토트백","백팩","호보백","숄더백","버킷백"))assertThat(generate("여성 "+kind,"닥스","","","","").tags()).contains(kind,"가방");
    }
    @Test void currentTitleOverridesPreviousFactsAndUnknownGenderNeverGuesses(){
        var r=generate("여성 합성가죽 숄더백","닥스","남성","소가죽","반지갑");
        assertThat(r.tags()).contains("여자","어머니","숄더백").doesNotContain("남자","아버지","회사원","정장","소가죽","가죽","지갑","카드");
        for(String title:List.of("공용 지갑","남성 여성 지갑","지갑"))assertThat(generate(title,"닥스","","","지갑").tags()).doesNotContain("남자","여자","아버지","어머니","회사원","직장인","정장");
        assertThat(generate("남성 벨트","닥스","","","","").tags()).doesNotContain("가방","지갑","회사원","선물");
    }
    @Test void cardNamesAndSourceFactsAreExpandedWithoutInferringLeather(){
        for(String name:List.of("카드지갑","명함지갑"))assertThat(generate(name,"닥스","남성","천연가죽(소)",name).tags()).contains("명함","카드","소가죽","가죽");
        assertThat(generate("장지갑","닥스","","","장지갑").tags()).doesNotContain("명함","반지갑","중지갑");
        assertThat(generate("나일론 가방","닥스","","소가죽","가방").tags()).doesNotContain("가죽","소가죽");
    }
    @Test void limitsNormalizationColorsAndSpecialOnlyPieces(){
        String twenty="가".repeat(20),longer="나".repeat(21);
        var r=generate("  DAKS   daks !!! "+twenty+" "+longer+" / 가방","DAKS","","","","블랙/화이트","블랙","상세페이지 참조");
        assertThat(r.tags()).contains("DAKS",twenty,"블랙","화이트").doesNotContain("daks","!!!",longer,"상세페이지","참조");
        assertThat(r.excluded()).contains(new Excluded(longer,"OVER_LENGTH"));
        assertThat(generate("가방", "", "", "", "", "").tags()).doesNotHaveDuplicates();
    }
    @Test void boundedInvalidInputsDoNotRunAnyExternalWork(){
        assertThatThrownBy(()->tags.generate(null)).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->generate("가".repeat(501),"","","","")).isInstanceOf(InputValidationFailure.class);
        assertThatThrownBy(()->tags.generate(new Input("제목","",new Context("","","",Collections.nCopies(33,"블랙"))))).isInstanceOf(InputValidationFailure.class);
    }
}
