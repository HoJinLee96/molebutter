package cc.ataglace.molebutter.app.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads actual application ConfigData using only temporary, explicitly fake credentials. */
class CoupangCredentialConfigurationTest {
    private static final Map<String,String> CREDENTIAL_BINDINGS=Map.of("coupang.vendor-id","coupang_access_id","coupang.vendor-user-id","coupang_vendor_user_id","coupang.access-key","coupang_access_key","coupang.secret-key","coupang_secret_key");

    @TempDir Path configurationDirectory;

    private Properties applicationProperties;

    @BeforeEach
    void isolateCredentialImports() throws IOException {
        applicationProperties = new Properties();
        // Inspect only the ordinary application resource; never resolve its real secret locations.
        try (var reader = new InputStreamReader(new ClassPathResource("application.properties").getInputStream(),
                StandardCharsets.UTF_8)) {
            applicationProperties.load(reader);
        }
        Properties isolatedProperties = new Properties();
        isolatedProperties.putAll(applicationProperties);
        // Replace all real imports with one optional fixture outside every application-secret directory.
        isolatedProperties.setProperty("spring.config.import",
                "optional:" + configurationDirectory.resolve("coupang.properties").toUri());
        store(configurationDirectory.resolve("application.properties"), isolatedProperties);
    }

    @Test
    void importsConsolidatedCoupangFileInsteadOfFormerSplitFiles() {
        var imports = Arrays.stream(applicationProperties.getProperty("spring.config.import").split(","))
                .map(String::trim).toList();

        assertThat(imports).contains(
                "optional:classpath:/application-secret/marketplace.properties",
                "optional:file:./application-secret/marketplace.properties");
        assertThat(imports).noneMatch(location -> location.endsWith("/coupang.properties")
                || location.endsWith("/naver-commerce.properties"));
    }

    @Test
    void missingOptionalCoupangFileLeavesCoupangCredentialsEmpty() {
        configuration(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.application.name")).isEqualTo("molebutter");
            CREDENTIAL_BINDINGS.keySet().forEach(property ->
                    assertThat(environment.getProperty(property)).as(property).isEmpty());
            assertThat(environment.getProperty("coupang.naver.type")).isNull();
        });
    }

    @Test
    void consolidatedFlatKeysResolveToEachCoupangCredential() throws IOException {
        writeFixtureCredentials();

        configuration(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            Environment environment = context.getEnvironment();
            CREDENTIAL_BINDINGS.forEach((property, flatKey) ->
                    assertThat(environment.getProperty(property)).as(property).isEqualTo(fixtureValue(flatKey)));
            assertThat(environment.getProperty("coupang.naver.type")).isNull();
        });
    }



    @Test
    void canonicalCoupangOverridesTakePrecedenceOverConsolidatedFile() throws IOException {
        writeFixtureCredentials();

        configuration(Map.of(),
                "coupang.vendor-id=fixture-override-coupang-vendor",
                "coupang.access-key=fixture-override-coupang-access",
                "coupang.secret-key=fixture-override-coupang-secret").run(context -> {
            assertThat(context).hasNotFailed();
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("coupang.vendor-id"))
                    .isEqualTo("fixture-override-coupang-vendor");
            assertThat(environment.getProperty("coupang.access-key"))
                    .isEqualTo("fixture-override-coupang-access");
            assertThat(environment.getProperty("coupang.secret-key"))
                    .isEqualTo("fixture-override-coupang-secret");
            assertThat(environment.getProperty("coupang.naver.client-id")).isNull();
        });
    }

    private void writeFixtureCredentials() throws IOException {
        Properties credentials = new Properties();
        CREDENTIAL_BINDINGS.values().forEach(key -> credentials.setProperty(key, fixtureValue(key)));
        // Distinct legacy aliases catch accidental use of generic, cross-market key names.
        credentials.setProperty("access_id", "fixture-legacy-vendor");
        credentials.setProperty("access_key", "fixture-legacy-access");
        credentials.setProperty("secret_key", "fixture-legacy-secret");
        store(configurationDirectory.resolve("coupang.properties"), credentials);
    }

    private ApplicationContextRunner configuration(Map<String, Object> variables, String... overrides) {
        return new ApplicationContextRunner().withPropertyValues(overrides).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            // Host profiles, system properties, and real environment credentials cannot enter this test.
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            sources.addFirst(new MapPropertySource("isolated-coupang-configuration", Map.of("spring.config.location",configurationDirectory.toUri().toString(),"spring.profiles.active","")));
            sources.addFirst(new SystemEnvironmentPropertySource("isolated-coupang-environment", variables));
            new ConfigDataApplicationContextInitializer().initialize(context);
        });
    }

    private static String fixtureValue(String key) {
        return "fixture-" + key;
    }

    private static void store(Path path, Properties properties) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Isolated configuration test fixture; contains no real credentials");
        }
    }
}
