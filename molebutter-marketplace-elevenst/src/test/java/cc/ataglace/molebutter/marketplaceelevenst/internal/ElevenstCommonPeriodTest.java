package cc.ataglace.molebutter.marketplaceelevenst.internal;
import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.marketplace.api.PartnerEditor;
import static org.assertj.core.api.Assertions.*;
class ElevenstCommonPeriodTest {
    PartnerEditor.Input input(String start,String end){return new PartnerEditor.Input(Map.of("aplBgnDy",start,"aplEndDy",end,"selPrdClfCd","0:100"),List.of(),List.of(),"","",List.of(),List.of());}
    @Test void threeYearBoundaryIsAcceptedWithoutExtendingTheDate(){ElevenstRules.validatePeriod(input("2026/10/14","2029/10/14"));assertThatThrownBy(()->ElevenstRules.validatePeriod(input("2026/10/14","2029/10/15"))).hasMessageContaining("최대 3년");}
    @Test void unsupportedTimePrecisionIsNotSilentlyDiscarded(){assertThatThrownBy(()->ElevenstRules.validatePeriod(input("2026-10-14T10:00","2027/10/14"))).hasMessageContaining("YYYY/MM/DD");}
    @Test void reversedAndMissingExplicitPeriodsCannotPass(){assertThatThrownBy(()->ElevenstRules.validatePeriod(input("2027/10/14","2026/10/14"))).hasMessageContaining("시작일 이후");assertThatThrownBy(()->ElevenstRules.validatePeriod(input("2026/10/14",""))).hasMessageContaining("시작일·종료일");}
}
