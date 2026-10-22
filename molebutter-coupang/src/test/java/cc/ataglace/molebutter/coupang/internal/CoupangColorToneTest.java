package cc.ataglace.molebutter.coupang.internal;
import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.coupang.api.CoupangColorTone;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class CoupangColorToneTest {
    @Test void fixedVocabularyAndSharedConservativeTitleCases() throws Exception {
        try(var input=getClass().getResourceAsStream("/coupang-color-tone-cases.json")){
            var data=new ObjectMapper().readTree(input);
            assertThat(data.path("choices").valueStream().map(n->n.asString()).toList()).containsExactlyElementsOf(CoupangColorTone.VALUES);
            for(var c:data.path("cases")){String[] titles=c.path("titles").valueStream().map(n->n.asString()).toArray(String[]::new);assertThat(CoupangColorTone.suggest(titles)).describedAs(c.toString()).isEqualTo(c.path("expected").isNull()?null:c.path("expected").asString());}
        }
        assertThat(CoupangColorTone.attribute("패션 의류/잡화 색상계열","NONE")).isTrue();
        assertThat(CoupangColorTone.attribute("패션 의류/잡화 색상계열","EXPOSED")).isFalse();
    }
}
