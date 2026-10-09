package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import cc.ataglace.molebutter.identity.api.EmailSender;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;

/** Explicit opt-in read-only contract probes. Never writes raw responses or product fixtures. */
@EnabledIfEnvironmentVariable(named="MOLEBUTTER_ORDER_LIVE",matches="true")
@SpringBootTest(properties={"product.refresh.worker-enabled=false","mail.provider=test",
 "spring.datasource.username=test_app","spring.datasource.password=isolated-test-app-password",
 "spring.flyway.user=test_migrator","spring.flyway.password=isolated-test-migration-password",
 "spring.data.redis.host=127.0.0.1","spring.data.redis.password=",
 "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes",
 "marketplace.coupang.orders.claims-verified=true",
 "marketplace.coupang.orders.claims-verification-reference=explicit-runtime-probe-only"})
@ActiveProfiles("bootstrap-admin")
@Import(MarketplaceOrderLiveIT.MailConfiguration.class)
class MarketplaceOrderLiveIT {
 @TestConfiguration static class MailConfiguration { @Bean EmailSender liveOrderTestMail(){return (to,subject,body)->{};} }
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("MOLEBUTTER_TEST_DB_URL");
  if(url==null||!url.contains("/molebutter_test?"))throw new IllegalStateException("Run scripts/test-integration.sh");
  r.add("spring.datasource.url",()->url);r.add("spring.flyway.url",()->url);
  r.add("spring.data.redis.port",()->System.getenv("MOLEBUTTER_TEST_REDIS_PORT"));
  // Spring loads the existing credentials; the test never opens or copies the secret file.
  r.add("spring.config.import",()->"classpath:bootstrap-admin-test.properties,optional:classpath:/application-secret/marketplace.properties,optional:file:./molebutter-app/src/main/resources/application-secret/marketplace.properties");
 }
 @Autowired CoupangOrderClient client;
 @Autowired CoupangProductClient transport;
 static LocalDate claimDay(String value){try{return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();}catch(java.time.format.DateTimeParseException e){return LocalDateTime.parse(value.replace(' ','T')).toLocalDate();}}
 @Test void verifyReadContractsWithoutSavingExternalRecords(){
  LocalDate to=LocalDate.now(ZoneId.of("Asia/Seoul")),from=to.minusDays(6);
  var streams=new ArrayList<>(CoupangOrderClient.ORDER_STATUSES);
  streams.addAll(List.of("RETURN_RU","RETURN_UC","RETURN_CC","RETURN_PR","CANCEL","EXCHANGE","WITHDRAWN"));
  String firstOrder=null;int normalSuccess=0;
  CoupangOrderClient.ClaimObservation knownReturn=null,knownCancel=null,knownWithdrawn=null;
  var returnReceipts=new LinkedHashSet<String>();
  for(String stream:streams){
   try{
    var page=client.fetch(stream,from,to,"");
    if(CoupangOrderClient.ORDER_STATUSES.contains(stream))normalSuccess++;
    System.out.printf("ORDER_LIVE stream=%s http=200 code=200 count=%d hasNext=%s%n",stream,page.orders().size()+page.claims().size(),page.nextCursor()!=null);
    if(firstOrder==null&&!page.orders().isEmpty())firstOrder=page.orders().getFirst().orderId();
    for(var claim:page.claims()){
     if("RETURN".equals(claim.summary().type())&&!"WITHDRAWN".equals(claim.summary().status())) {if(knownReturn==null)knownReturn=claim;if(returnReceipts.size()<50)returnReceipts.add(claim.summary().id());}
     if("CANCEL".equals(claim.summary().type())&&knownCancel==null)knownCancel=claim;
     if("WITHDRAWN".equals(claim.summary().status())&&knownWithdrawn==null)knownWithdrawn=claim;
    }
    if(page.nextCursor()!=null){var next=client.fetch(stream,from,to,page.nextCursor());System.out.printf("ORDER_LIVE stream=%s page=2 http=200 code=200 count=%d hasNext=%s%n",stream,next.orders().size()+next.claims().size(),next.nextCursor()!=null);}
   }catch(MarketplaceFailure failure){System.out.printf("ORDER_LIVE stream=%s verified=false failure=%s%n",stream,failure.kind());}
  }
  if(firstOrder!=null){
   try{var detail=client.detail(firstOrder);System.out.printf("ORDER_LIVE detail http=200 code=200 count=%d%n",detail.items().size());}
   catch(MarketplaceFailure failure){System.out.printf("ORDER_LIVE detail verified=false failure=%s%n",failure.kind());}
  }
  if(knownReturn!=null){
   try {var single=client.returnClaim(knownReturn.summary().id()); final String expected=knownReturn.summary().id();org.assertj.core.api.Assertions.assertThat(single.claims()).allMatch(c->expected.equals(c.summary().id()));System.out.printf("ORDER_LIVE returnSingle http=200 code=200 count=%d hasNext=%s%n",single.claims().size(),single.nextCursor()!=null);}
   catch(MarketplaceFailure failure){System.out.printf("ORDER_LIVE returnSingle verified=false failure=%s%n",failure.kind());}
  }else System.out.println("ORDER_LIVE returnSingle verified=false reason=NO_RECEIPT");
  if(knownWithdrawn!=null){if(returnReceipts.size()==50)returnReceipts.remove(returnReceipts.iterator().next());returnReceipts.add(knownWithdrawn.summary().id());}
  if(!returnReceipts.isEmpty()){
   try {var withdrawn=client.withdrawalClaims(List.copyOf(returnReceipts));if(knownWithdrawn!=null){final String expected=knownWithdrawn.summary().id();org.assertj.core.api.Assertions.assertThat(withdrawn.claims()).anyMatch(c->expected.equals(c.summary().id()));}System.out.printf("ORDER_LIVE withdrawalPost http=200 code=200 count=%d hasNext=%s%n",withdrawn.claims().size(),withdrawn.nextCursor()!=null);}
   catch(MarketplaceFailure failure){System.out.printf("ORDER_LIVE withdrawalPost verified=false failure=%s%n",failure.kind());}
  }else System.out.println("ORDER_LIVE withdrawalPost verified=false reason=NO_RECEIPT");
  if(knownCancel!=null&&knownCancel.createdAt()!=null){
   LocalDate origin=claimDay(knownCancel.createdAt());
   try {var refreshed=client.fetch("CANCEL",origin,origin,null);final String expected=knownCancel.summary().id();boolean found=refreshed.claims().stream().anyMatch(c->expected.equals(c.summary().id()));
    var tokens=new HashSet<String>();int pages=1;
    while(!found&&refreshed.nextCursor()!=null&&pages++<100){if(!tokens.add(refreshed.nextCursor()))throw new AssertionError("Repeated claim cursor");refreshed=client.fetch("CANCEL",origin,origin,refreshed.nextCursor());found=refreshed.claims().stream().anyMatch(c->expected.equals(c.summary().id()));}
    org.assertj.core.api.Assertions.assertThat(found).as("Known cancellation found in origin requery").isTrue();System.out.printf("ORDER_LIVE knownCancelRequery expectedFound=%s%n",found);System.out.printf("ORDER_LIVE knownCancelRequery http=200 code=200 count=%d hasNext=%s%n",refreshed.claims().size(),refreshed.nextCursor()!=null);}
   catch(MarketplaceFailure failure){System.out.printf("ORDER_LIVE knownCancelRequery verified=false failure=%s%n",failure.kind());}
  }else System.out.println("ORDER_LIVE knownCancelRequery verified=false reason=NO_ORIGIN");
  // A deliberately small page verifies the actual token wire value without printing it.
  String path="/v2/providers/openapi/apis/api/v5/vendors/"+transport.vendorId()+"/ordersheets";
  String query="createdAtFrom="+java.net.URLEncoder.encode(from+"+09:00",java.nio.charset.StandardCharsets.UTF_8)+"&createdAtTo="+java.net.URLEncoder.encode(to+"+09:00",java.nio.charset.StandardCharsets.UTF_8)+"&status=INSTRUCT&maxPerPage=1";
  var first=client.parseOrders(transport.orderRead(path,query),null,false);
  System.out.printf("ORDER_LIVE paging page=1 http=200 code=200 count=%d hasNext=%s%n",first.orders().size(),first.nextCursor()!=null);
  if(first.nextCursor()!=null){var next=client.parseOrders(transport.orderRead(path,query+"&nextToken="+java.net.URLEncoder.encode(first.nextCursor(),java.nio.charset.StandardCharsets.UTF_8)),null,false);System.out.printf("ORDER_LIVE paging page=2 http=200 code=200 count=%d hasNext=%s%n",next.orders().size(),next.nextCursor()!=null);}
  org.assertj.core.api.Assertions.assertThat(normalSuccess).as("Normal order endpoints verified").isEqualTo(6);
 }
}
