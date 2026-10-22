package cc.ataglace.molebutter.coupang.internal;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.coupang.api.CoupangRegisteredProducts.Collect;
import static org.assertj.core.api.Assertions.*;
class RecentProductCollectionTest {
    final Instant now=Instant.parse("2026-10-20T00:30:00Z");
    @Test void validatesInclusiveKoreanDateAndMaximumBoundaries(){
        assertThat(RecentProductCollection.validate(new Collect(null,"RECENT","2026-07-23","2026-10-20",500),now).maximum()).isEqualTo(500);
        assertThat(RecentProductCollection.validate(new Collect(null,"RECENT","2026-10-20","2026-10-20",1),now).cutoff()).isEqualTo(LocalDateTime.parse("2026-10-20T09:30:00"));
        for(var c:List.of(new Collect(List.of("1"),"RECENT","2026-10-20","2026-10-20",1),new Collect(null,"RECENT","2026-07-22","2026-10-20",1),new Collect(null,"RECENT","2026-10-20","2026-10-21",1),new Collect(null,"RECENT","2026-02-30","2026-10-20",1),new Collect(null,"RECENT","2026-10-20","2026-10-19",1),new Collect(null,"RECENT","2026-10-20","2026-10-20",0),new Collect(null,"RECENT","2026-10-20","2026-10-20",501),new Collect(null,"RECENT",null,null,null)))
            assertThatThrownBy(()->RecentProductCollection.validate(c,now)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
}
