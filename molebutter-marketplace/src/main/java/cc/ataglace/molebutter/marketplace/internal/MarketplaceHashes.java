package cc.ataglace.molebutter.marketplace.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 내부 계정·커서 식별자용 해시. 저장된 기존 SHA-256 값과 동일한 형식을 유지한다. */
public final class MarketplaceHashes {
    private MarketplaceHashes() {}
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
