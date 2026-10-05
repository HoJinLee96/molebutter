package cc.ataglace.molebutter;
import cc.ataglace.molebutter.MolebutterApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import cc.ataglace.molebutter.identity.api.AuthProperties;
import cc.ataglace.molebutter.identity.internal.ResendProperties;
import cc.ataglace.molebutter.identity.internal.DefaultJwtService;
import static org.assertj.core.api.Assertions.assertThat;

class MolebutterApplicationTests {

    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = MolebutterApplication.class)
    static class PropertiesOnly { }

	@Test
	void configurationBindsWithoutLocalSecretsOrExternalServices() {
        assertThat(MolebutterApplication.class.isAnnotationPresent(ConfigurationPropertiesScan.class)).isTrue();
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class, DefaultJwtService.class)
                .withInitializer(context -> TestPropertySourceUtils.addPropertiesFilesToEnvironment(
                        context, "classpath:application.properties"))
                .withPropertyValues("auth.jwt.secret=test-only-secret-with-at-least-32-bytes")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AuthProperties.class)
                            .hasSingleBean(ResendProperties.class).hasSingleBean(DefaultJwtService.class);
                    assertThat(context.getBean(AuthProperties.class).jwt().issuer()).isEqualTo("molebutter");
                });
	}

}
