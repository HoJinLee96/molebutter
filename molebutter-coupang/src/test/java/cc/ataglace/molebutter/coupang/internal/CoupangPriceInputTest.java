package cc.ataglace.molebutter.coupang.internal;
import cc.ataglace.molebutter.coupang.api.*;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CoupangPriceInputTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void formattingIsNormalizedBeforeDocumentAndExplicitIntentComparison(){
        var before=CoupangInputs.empty();var id=before.options().getFirst().id();
        var changes=List.of(new CoupangEditing.Change("options.registration.salePrice",id," 139,000 "),new CoupangEditing.Change("options.registration.originalPrice",id," 219,000 "));
        var after=CoupangInputs.apply(json,before,changes);
        assertThat(after.options().getFirst().registration()).containsEntry("salePrice","139000").containsEntry("originalPrice","219000");
        assertThatCode(()->CoupangInputs.verify(json,before,after,CoupangPrices.changes(changes),false)).doesNotThrowAnyException();
        assertThat(changes.getFirst().value()).isEqualTo(" 139,000 ");
        for(String invalid:List.of("1원","-1","1.5","1 000"))assertThat(CoupangPrices.normalize(invalid)).isEqualTo(invalid);
    }
    @Test void optionPriceJsonAcceptsCommaStringsAndRejectsFractionalOrStockCommaInputs(){
        var request=json.readValue("{\"observedVersion\":1,\"kind\":\"PRICE\",\"value\":\" 139,000 \"}",CoupangOptionEdits.Prepare.class);assertThat(request.value()).isEqualTo(139000);
        assertThat(json.readValue("{\"observedVersion\":1,\"kind\":\"PRICE\",\"value\":139000}",CoupangOptionEdits.Prepare.class).value()).isEqualTo(139000);
        for(String value:List.of("139000.5","\"1원\"","-10","\"1e3\""))assertThatThrownBy(()->json.readValue("{\"observedVersion\":1,\"kind\":\"PRICE\",\"value\":"+value+"}",CoupangOptionEdits.Prepare.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->json.readValue("{\"observedVersion\":1,\"kind\":\"STOCK\",\"value\":\"1,000\"}",CoupangOptionEdits.Prepare.class)).isInstanceOf(RuntimeException.class);
    }
}
