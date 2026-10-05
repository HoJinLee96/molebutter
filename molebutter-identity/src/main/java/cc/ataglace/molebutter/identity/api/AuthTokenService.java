package cc.ataglace.molebutter.identity.api;
import io.jsonwebtoken.Claims;
public interface AuthTokenService {
    public record TokenBundle(String accessToken, String refreshToken, cc.ataglace.molebutter.identity.api.UserAccount user) {
    }
    TokenBundle issueOnSignin(cc.ataglace.molebutter.identity.api.UserAccount user);
    TokenBundle rotate(String refreshToken);
    void revokeOnLogout(String accessToken, String refreshToken);
    void blacklistAccess(String accessToken);
    boolean isAccessBlacklisted(String jti);
    boolean hasCurrentVersion(Claims claims, cc.ataglace.molebutter.identity.api.UserAccount user);
    boolean isFamilyRevoked(String familyId);
}
