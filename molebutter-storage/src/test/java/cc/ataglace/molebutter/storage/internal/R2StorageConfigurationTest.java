package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.ObjectStorage;
import cc.ataglace.molebutter.storage.api.StorageArea;
import cc.ataglace.molebutter.storage.api.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class R2StorageConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(R2StorageConfiguration.class);

    @Test
    void absentConfigurationWiresDisabledStorageWithoutAnySdkClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class).doesNotHaveBean(S3Client.class)
                    .doesNotHaveBean(SdkHttpClient.class);
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThatThrownBy(() -> storage.get(StorageArea.PRIVATE, "a.bin"))
                    .isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.code()).isEqualTo(StorageException.Code.NOT_CONFIGURED));
            assertThatThrownBy(() -> storage.create(StorageArea.PUBLIC, "a.bin", new byte[0], "image/png", Map.of()))
                    .isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.code()).isEqualTo(StorageException.Code.NOT_CONFIGURED));
        });
    }

    @Test
    void disabledStorageIgnoresIncompleteCredentialsWithoutCreatingClient() {
        runner.withPropertyValues("cloudflare.r2.enabled=false", "cloudflare.r2.account-id=incomplete",
                "cloudflare.r2.endpoint=http://not-an-r2-endpoint.invalid").run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class).doesNotHaveBean(S3Client.class));
    }

    @Test
    void enabledIncompleteConfigurationFailsWithSanitizedReason() {
        runner.withPropertyValues("cloudflare.r2.enabled=true", "cloudflare.r2.secret-access-key=test-only-sensitive-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause().hasMessageContaining("Enabled R2 storage requires");
                    assertThat(context.getStartupFailure().toString()).doesNotContain("test-only-sensitive-secret");
                });
    }

    @Test
    void completeEnabledConfigurationCreatesClientWithoutMakingRequests() {
        runner.withPropertyValues("cloudflare.r2.enabled=true", "cloudflare.r2.account-id=" + "a".repeat(32),
                "cloudflare.r2.access-key-id=test-access-key", "cloudflare.r2.secret-access-key=test-secret-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class).hasSingleBean(SdkHttpClient.class);
                    assertThat(context.getBeansOfType(S3Client.class)).containsOnlyKeys("r2PublicS3Client", "r2PrivateS3Client");
                });
    }

    @Test
    void bucketScopedConfigurationBindsWithoutFlatCredentialsOrAccountId() {
        runner.withPropertyValues("cloudflare.r2.enabled=true",
                "cloudflare.r2.endpoint=https://" + "a".repeat(32) + ".r2.cloudflarestorage.com",
                "cloudflare.r2.region=auto",
                "cloudflare.r2.public.access-key-id=public-test-key",
                "cloudflare.r2.public.secret-access-key=public-test-secret",
                "cloudflare.r2.public.bucket=custom-public",
                "cloudflare.r2.public.base-url=https://files.example.test",
                "cloudflare.r2.private.access-key-id=private-test-key",
                "cloudflare.r2.private.secret-access-key=private-test-secret",
                "cloudflare.r2.private.bucket=custom-private")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class).hasSingleBean(SdkHttpClient.class);
                    assertThat(context.getBeansOfType(S3Client.class)).containsOnlyKeys("r2PublicS3Client", "r2PrivateS3Client");
                    R2ConfigurationProperties properties = context.getBean(R2ConfigurationProperties.class);
                    assertThat(properties.accessKeyId(StorageArea.PUBLIC)).isEqualTo("public-test-key");
                    assertThat(properties.accessKeyId(StorageArea.PRIVATE)).isEqualTo("private-test-key");
                    assertThat(properties.getPublicBucket()).isEqualTo("custom-public");
                    assertThat(properties.getPrivateBucket()).isEqualTo("custom-private");
                    assertThat(context.getBean(ObjectStorage.class).publicUrl(StorageArea.PUBLIC, "a.png").orElseThrow())
                            .hasToString("https://files.example.test/a.png");
                });
    }

    @Test
    void nestedOverridesKeepCredentialsPairedAndPreserveFlatFallbackForOtherArea() {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        properties.getPublic().setAccessKeyId("public-test-key");
        assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalStateException.class);
        properties.getPublic().setSecretAccessKey("public-test-secret");
        properties.getPublic().setBucket("nested-public");
        properties.getPublic().setBaseUrl("https://files.example.test");
        assertThatCode(properties::validatedEndpoint).doesNotThrowAnyException();
        assertThat(properties.accessKeyId(StorageArea.PUBLIC)).isEqualTo("public-test-key");
        assertThat(properties.secretAccessKey(StorageArea.PUBLIC)).isEqualTo("public-test-secret");
        assertThat(properties.accessKeyId(StorageArea.PRIVATE)).isEqualTo("test-access-key");
        assertThat(properties.secretAccessKey(StorageArea.PRIVATE)).isEqualTo("test-secret-key");
        assertThat(properties.getPublicBucket()).isEqualTo("nested-public");
        assertThat(properties.validatedPublicBaseUrl()).hasToString("https://files.example.test");
    }

    @Test
    void endpointDerivedAccountStillRejectsUntrustedHostsAndExplicitAccountMismatch() {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        properties.setAccountId(null);
        properties.setEndpoint("https://" + "b".repeat(32) + ".eu.r2.cloudflarestorage.com");
        assertThat(properties.validatedEndpoint()).hasToString("https://" + "b".repeat(32) + ".eu.r2.cloudflarestorage.com");
        properties.setAccountId("a".repeat(32));
        assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalStateException.class);
        properties.setAccountId("");
        properties.setEndpoint("https://" + "a".repeat(32) + ".r2.cloudflarestorage.com.evil.invalid");
        assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "eu.", "us.", "fedramp."})
    void accountEndpointAndKnownJurisdictionsAreAccepted(String jurisdiction) {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        properties.setEndpoint("https://" + "a".repeat(32) + "." + jurisdiction + "r2.cloudflarestorage.com");
        assertThatCode(properties::validatedEndpoint).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.r2.cloudflarestorage.com",
            "https://bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.r2.cloudflarestorage.com",
            "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.r2.cloudflarestorage.com.evil.invalid",
            "https://test@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.r2.cloudflarestorage.com",
            "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.r2.cloudflarestorage.com/a",
            "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.r2.cloudflarestorage.com?secret=test",
            "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.r2.cloudflarestorage.com:8443",
            "https://localhost"})
    void invalidEndpointsAreRejectedWithoutEchoingTheValue(String endpoint) {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        properties.setEndpoint(endpoint);
        assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(endpoint);
    }

    @Test
    void visibilityCannotUseSameBucketAndMemoryLimitCannotExceedHardCap() {
        R2ConfigurationProperties sameBuckets = R2ObjectStorageTest.properties();
        sameBuckets.setPrivateBucket("molebutter");
        assertThatThrownBy(sameBuckets::validatedEndpoint).isInstanceOf(IllegalStateException.class);
        R2ConfigurationProperties oversized = R2ObjectStorageTest.properties();
        oversized.setMaxObjectBytes(R2ConfigurationProperties.ABSOLUTE_MAX_OBJECT_BYTES + 1);
        assertThatThrownBy(oversized::validatedEndpoint).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicBaseUrlCannotCarryCredentialsQueryOrPath() {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        for (String url : new String[]{"https://user:secret@assets.molebutter.link", "http://assets.molebutter.link",
                "https://assets.molebutter.link?secret=value", "https://assets.molebutter.link/a/../b"}) {
            properties.setPublicBaseUrl(url);
            assertThatThrownBy(properties::validatedEndpoint).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(url);
        }
    }

    @Test
    void configurationToStringNeverPrintsCredentials() {
        R2ConfigurationProperties properties = R2ObjectStorageTest.properties();
        assertThat(properties.toString()).doesNotContain(properties.getAccessKeyId(), properties.getSecretAccessKey());
        assertThat(properties.toString()).contains("REDACTED");
        properties.getPublic().setAccessKeyId("public-sensitive");
        properties.getPrivate().setSecretAccessKey("private-sensitive");
        assertThat(properties.getPublic().toString()).contains("REDACTED").doesNotContain("public-sensitive");
        assertThat(properties.getPrivate().toString()).contains("REDACTED").doesNotContain("private-sensitive");
    }
}
