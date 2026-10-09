package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
final class CoupangSignature {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static String authorization(String access, String secret, Instant now, String method, String path, String query) {
        String date=DATE.format(now);
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=HexFormat.of().formatHex(mac.doFinal((date+method+path+query).getBytes(StandardCharsets.UTF_8)));
            return "CEA algorithm=HmacSHA256, access-key="+access+", signed-date="+date+", signature="+signature;
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException("HMAC initialization failed");}
    }
}
