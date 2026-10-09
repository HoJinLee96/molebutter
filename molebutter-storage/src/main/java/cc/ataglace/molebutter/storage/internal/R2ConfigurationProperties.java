package cc.ataglace.molebutter.storage.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import cc.ataglace.molebutter.storage.api.StorageArea;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/** Credentials are intentionally excluded from toString and validation messages. */
@ConfigurationProperties(prefix = "cloudflare.r2")
public class R2ConfigurationProperties {
    static final long ABSOLUTE_MAX_OBJECT_BYTES = 64L * 1024 * 1024;
    private static final Pattern R2_HOST = Pattern.compile("([a-f0-9]{32})\\.(?:(?:eu|us|fedramp)\\.)?r2\\.cloudflarestorage\\.com");
    private boolean enabled;
    private String accountId;
    private String endpoint;
    private String accessKeyId;
    private String secretAccessKey;
    private String region = "auto";
    private final BucketConfiguration publicConfiguration = new BucketConfiguration();
    private final BucketConfiguration privateConfiguration = new BucketConfiguration();
    private String publicBucket = "molebutter";
    private String privateBucket = "molebutter-private";
    private String publicBaseUrl = "https://assets.molebutter.link";
    private long maxObjectBytes = 32L * 1024 * 1024;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(30);

    URI validatedEndpoint() {
        String effectiveAccount = accountId;
        URI configuredEndpoint = null;
        if (endpoint != null && !endpoint.isBlank()) {
            configuredEndpoint = safeUri(endpoint);
            require(cleanHttpsUri(configuredEndpoint)
                    && (configuredEndpoint.getRawPath().isEmpty() || "/".equals(configuredEndpoint.getRawPath())));
            var host = R2_HOST.matcher(configuredEndpoint.getHost());
            require(host.matches());
            if (effectiveAccount == null || effectiveAccount.isBlank()) effectiveAccount = host.group(1);
        }
        require(effectiveAccount != null && effectiveAccount.matches("[a-f0-9]{32}"));
        require(validCredential(accessKeyId(StorageArea.PUBLIC)) && validCredential(secretAccessKey(StorageArea.PUBLIC)));
        require(validCredential(accessKeyId(StorageArea.PRIVATE)) && validCredential(secretAccessKey(StorageArea.PRIVATE)));
        require(region == null || region.isBlank() || "auto".equals(region));
        require(validBucket(getPublicBucket()) && validBucket(getPrivateBucket()) && !getPublicBucket().equals(getPrivateBucket()));
        require(maxObjectBytes > 0 && maxObjectBytes <= ABSOLUTE_MAX_OBJECT_BYTES);
        require(validDuration(connectTimeout, Duration.ofMinutes(1)));
        require(validDuration(requestTimeout, Duration.ofMinutes(5)) && requestTimeout.compareTo(connectTimeout) >= 0);
        validatedPublicBaseUrl();
        URI result = configuredEndpoint == null
                ? safeUri("https://" + effectiveAccount + ".r2.cloudflarestorage.com") : configuredEndpoint;
        Set<String> allowedHosts = Set.of(effectiveAccount + ".r2.cloudflarestorage.com",
                effectiveAccount + ".eu.r2.cloudflarestorage.com", effectiveAccount + ".us.r2.cloudflarestorage.com",
                effectiveAccount + ".fedramp.r2.cloudflarestorage.com");
        require(cleanHttpsUri(result) && allowedHosts.contains(result.getHost())
                && (result.getRawPath().isEmpty() || "/".equals(result.getRawPath())));
        return result;
    }

    URI validatedPublicBaseUrl() {
        URI uri = safeUri(getPublicBaseUrl());
        require(cleanHttpsUri(uri) && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())));
        return URI.create("https://" + uri.getRawAuthority());
    }

    /** A nested credential pair is selected together; partial pairs never mix with flat credentials. */
    String accessKeyId(StorageArea area) {
        BucketConfiguration nested = configuration(area);
        return nested.hasCredentials() ? nested.getAccessKeyId() : accessKeyId;
    }

    String secretAccessKey(StorageArea area) {
        BucketConfiguration nested = configuration(area);
        return nested.hasCredentials() ? nested.getSecretAccessKey() : secretAccessKey;
    }

    private BucketConfiguration configuration(StorageArea area) {
        require(area != null);
        return area == StorageArea.PUBLIC ? publicConfiguration : privateConfiguration;
    }

    private static String preferred(String nested, String fallback) {
        return nested == null || nested.isBlank() ? fallback : nested;
    }

    private static boolean cleanHttpsUri(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static URI safeUri(String value) {
        try {
            require(value != null && !value.isBlank());
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw invalidConfiguration();
        }
    }

    private static boolean validCredential(String value) {
        return value != null && !value.isBlank() && value.length() <= 2048
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private static boolean validBucket(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    }

    private static boolean validDuration(Duration value, Duration maximum) {
        return value != null && !value.isNegative() && !value.isZero() && value.compareTo(maximum) <= 0;
    }

    private static void require(boolean condition) {
        if (!condition) throw invalidConfiguration();
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Enabled R2 storage requires valid endpoint, credentials, distinct buckets and bounded limits.");
    }

    @Override
    public String toString() { return "R2ConfigurationProperties[credentials=REDACTED]"; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public BucketConfiguration getPublic() { return publicConfiguration; }
    public BucketConfiguration getPrivate() { return privateConfiguration; }
    public String getPublicBucket() { return preferred(publicConfiguration.getBucket(), publicBucket); }
    public void setPublicBucket(String publicBucket) { this.publicBucket = publicBucket; }
    public String getPrivateBucket() { return preferred(privateConfiguration.getBucket(), privateBucket); }
    public void setPrivateBucket(String privateBucket) { this.privateBucket = privateBucket; }
    public String getPublicBaseUrl() { return preferred(publicConfiguration.getBaseUrl(), publicBaseUrl); }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public long getMaxObjectBytes() { return maxObjectBytes; }
    public void setMaxObjectBytes(long maxObjectBytes) { this.maxObjectBytes = maxObjectBytes; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }

    public static final class BucketConfiguration {
        private String accessKeyId;
        private String secretAccessKey;
        private String bucket;
        private String baseUrl;

        private boolean hasCredentials() { return accessKeyId != null || secretAccessKey != null; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        @Override public String toString() { return "BucketConfiguration[credentials=REDACTED]"; }
    }
}
