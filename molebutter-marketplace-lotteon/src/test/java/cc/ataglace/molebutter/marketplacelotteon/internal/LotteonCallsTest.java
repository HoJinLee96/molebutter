package cc.ataglace.molebutter.marketplacelotteon.internal;
import org.junit.jupiter.api.Test;import java.time.Duration;import java.util.UUID;import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;import static org.assertj.core.api.Assertions.*;import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
class LotteonCallsTest {
 @Test void sharedAccountPacesIndependentRequests(){String key=UUID.randomUUID().toString();long first=LotteonCalls.call(key,Duration.ofMillis(25),System::nanoTime);long next=LotteonCalls.call(key,Duration.ZERO,System::nanoTime);assertThat(next-first).isGreaterThanOrEqualTo(20_000_000L);}
 @Test void rateLimitFailsFastInsteadOfHoldingARequestForOneMinute(){String key=UUID.randomUUID().toString();LotteonCalls.block(key,Duration.ofSeconds(60));assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertThatThrownBy(()->LotteonCalls.call(key,Duration.ZERO,()->"unexpected")).isInstanceOfSatisfying(MarketplaceFailure.class,e->assertThat(e.kind()).isEqualTo(MarketplaceFailure.Kind.RATE_LIMIT)));}
}
