package cc.ataglace.molebutter.identity.api;
import java.time.Duration;
import java.time.Instant;
import io.jsonwebtoken.Claims;
public interface JwtService {
    String newId();
    String createAccessToken(cc.ataglace.molebutter.identity.api.UserAccount user, String familyId);
    String createRefreshToken(cc.ataglace.molebutter.identity.api.UserAccount user, String familyId, String jti, Instant absoluteExpiry);
    Claims parse(String token);
    boolean isType(Claims claims, String expectedType);
    String email(Claims claims);
    String role(Claims claims);
    String familyId(Claims claims);
    Long userId(Claims claims);
    Duration remainingTtl(Claims claims);
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
}
