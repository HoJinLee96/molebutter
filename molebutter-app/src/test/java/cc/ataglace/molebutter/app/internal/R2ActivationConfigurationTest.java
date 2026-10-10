package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.storage.api.ObjectStorage;
import cc.ataglace.molebutter.storage.api.StorageArea;
import cc.ataglace.molebutter.storage.api.StorageException;
import cc.ataglace.molebutter.storage.internal.R2StorageConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the application's real non-secret ConfigData, with imports and host environment isolated. */
class R2ActivationConfigurationTest {
    @TempDir Path configurationDirectory;

    @BeforeEach
    void isolateConfigDataImports() throws IOException {
        for (String file : new String[]{"application.properties", "application-cloudflare.properties"}) {
            Properties properties = new Properties();
            // Read only the two ordinary application resources; never resolve their imported secret locations.
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            properties.remove("spring.config.import");
            try (var writer = Files.newBufferedWriter(configurationDirectory.resolve(file), StandardCharsets.UTF_8)) {
                properties.store(writer, "Actual application settings with external/secret imports removed for isolation");
            }
        }
    }

    @Test
    void defaultProfileKeepsStorageDisabledWithoutCredentials() {
        configuration("", Map.of(), false).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class)
                    .doesNotHaveBean(SdkHttpClient.class).doesNotHaveBean(S3Client.class);
            assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("molebutter");
            assertThat(context.getEnvironment().getProperty("cloudflare.r2.enabled", Boolean.class)).isFalse();
            assertDisabled(context.getBean(ObjectStorage.class));
        });
    }

    @Test
    void cloudflareProfileEnablesStorageWithConfiguredCredentials() {
        configuration("cloudflare", Map.of(), true).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class).hasSingleBean(SdkHttpClient.class);
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("cloudflare");
            assertThat(context.getEnvironment().getProperty("cloudflare.r2.enabled", Boolean.class)).isTrue();
            assertThat(context.getBeansOfType(S3Client.class)).containsOnlyKeys("r2PublicS3Client", "r2PrivateS3Client");
            assertPublicUrl(context.getBean(ObjectStorage.class));
        });
    }

    @Test
    void environmentCanDisableCloudflareProfileWithoutCredentials() {
        configuration("cloudflare", Map.of("CLOUDFLARE_R2_ENABLED", "false"), false).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class)
                    .doesNotHaveBean(SdkHttpClient.class).doesNotHaveBean(S3Client.class);
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("cloudflare");
            assertThat(context.getEnvironment().getProperty("cloudflare.r2.enabled", Boolean.class)).isFalse();
            assertDisabled(context.getBean(ObjectStorage.class));
        });
    }

    @Test
    void environmentCanEnableDefaultProfileWithoutChangingHttpSettings() {
        configuration("", Map.of("CLOUDFLARE_R2_ENABLED", "true"), true).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class).hasSingleBean(SdkHttpClient.class);
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context.getEnvironment().getProperty("cloudflare.r2.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("server.address")).isNull();
            assertThat(context.getEnvironment().getProperty("app.security.require-https")).isNull();
            assertThat(context.getEnvironment().getProperty("auth.cookie.secure", Boolean.class)).isFalse();
            assertThat(context.getBeansOfType(S3Client.class)).containsOnlyKeys("r2PublicS3Client", "r2PrivateS3Client");
            assertPublicUrl(context.getBean(ObjectStorage.class));
        });
    }

    private ApplicationContextRunner configuration(String profile, Map<String, Object> variables, boolean credentials) {
        return new ApplicationContextRunner().withUserConfiguration(R2StorageConfiguration.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            // CI/IDE environment and system properties must not supply profiles or real storage credentials.
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            Map<String, Object> isolation = new LinkedHashMap<>();
            isolation.put("spring.config.location", configurationDirectory.toUri().toString());
            // ConfigData resolves imports per document, so both the copied resources and this override omit imports.
            isolation.put("spring.config.import", "");
            isolation.put("spring.profiles.active", profile);
            if (credentials) {
                isolation.put("cloudflare.r2.account-id", "a".repeat(32));
                isolation.put("cloudflare.r2.endpoint", "https://" + "a".repeat(32) + ".r2.cloudflarestorage.com");
                isolation.put("cloudflare.r2.access-key-id", "isolated-test-access-key");
                isolation.put("cloudflare.r2.secret-access-key", "isolated-test-secret-key");
            }
            sources.addFirst(new MapPropertySource("isolated-r2-configuration", isolation));
            sources.addFirst(new SystemEnvironmentPropertySource("isolated-r2-environment", variables));
            new ConfigDataApplicationContextInitializer().initialize(context);
        });
    }

    private void assertDisabled(ObjectStorage storage) {
        assertThatThrownBy(() -> storage.publicUrl(StorageArea.PUBLIC, "products/TEST1/01.png"))
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(StorageException.Code.NOT_CONFIGURED));
    }

    private void assertPublicUrl(ObjectStorage storage) {
        // SDK construction and URL calculation make no S3/object requests.
        assertThat(storage.publicUrl(StorageArea.PUBLIC, "products/TEST1/01.png").orElseThrow())
                .hasToString("https://assets.molebutter.link/products/TEST1/01.png");
        assertThat(storage.publicUrl(StorageArea.PRIVATE, "products/TEST1/01.png")).isEmpty();
    }
}
