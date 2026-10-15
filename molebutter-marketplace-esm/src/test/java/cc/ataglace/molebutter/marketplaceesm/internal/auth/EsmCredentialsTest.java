package cc.ataglace.molebutter.marketplaceesm.internal.auth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;
import tools.jackson.databind.ObjectMapper;
class EsmCredentialsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void signsJointSitesWithDocumentedAudienceAndNoSecretInPayload() throws Exception {
        var credentials=new EsmCredentials("test-secret","master","seller.example","g-seller","a-seller");
        String token=credentials.token(json,List.of("GMARKET","AUCTION","GMARKET"));var parts=token.split("\\.");
        var header=json.readTree(Base64.getUrlDecoder().decode(parts[0]));var payload=json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asString()).isEqualTo("HS256");assertThat(header.path("kid").asString()).isEqualTo("master");
        assertThat(payload.path("aud").asString()).isEqualTo("sa.esmplus.com");assertThat(payload.path("ssi").asString()).isEqualTo("A:a-seller,G:g-seller");
        assertThat(payload.toString()).doesNotContain("test-secret");var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(mac.doFinal((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void missingExplicitSecretNeverFallsBackToLegacyApiKey(){assertThatThrownBy(()->new EsmCredentials("","master","issuer","g","").token(json,List.of("GMARKET"))).hasMessageContaining("연결 설정");}
    @Test void accountScopeIsSharedAcrossSitesButChangesWithSellerIdentity(){var c=new EsmCredentials("s","m","i","g","a");assertThat(c.account()).hasSize(64).isEqualTo(new EsmCredentials("rotated","m","i","g","a").account()).isNotEqualTo(new EsmCredentials("s","m","i","other","a").account());}
    @Test void rejectsClaimSeparatorInjection(){assertThatThrownBy(()->new EsmCredentials("s","m","i","g,A:other","a").token(json,List.of("GMARKET"))).hasMessageContaining("연결 설정");}
}
