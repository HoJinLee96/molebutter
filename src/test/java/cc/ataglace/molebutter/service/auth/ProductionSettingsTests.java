package cc.ataglace.molebutter.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.support.TestPropertySourceUtils;

import cc.ataglace.molebutter.MolebutterApplication;

class ProductionSettingsTests {
    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = MolebutterApplication.class)
    static class PropertiesOnly { }

    @Test
    void productionRequiresHttpsAndSetsSecureHttpOnlyCookies() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class, AuthCookieService.class)
                .withInitializer(context -> TestPropertySourceUtils.addPropertiesFilesToEnvironment(context,
                        "classpath:application.properties", "classpath:application-prod.properties"))
                .withPropertyValues("auth.jwt.secret=test-secret-not-used-for-live-connections")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("app.security.require-https", Boolean.class)).isTrue();
                    String proxies = context.getEnvironment().getProperty("server.tomcat.remoteip.internal-proxies");
                    assertThat("127.0.0.1".matches(proxies)).isTrue();
                    assertThat("192.168.1.20".matches(proxies)).isFalse();
                    var response = new MockHttpServletResponse();
                    context.getBean(AuthCookieService.class).writeTokens(response, "access", "refresh");
                    assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                            .allSatisfy(cookie -> assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax"));
                    assertThat(response.getHeaders("Set-Cookie").get(1)).contains("Path=/api/auth");
                });
    }
}
