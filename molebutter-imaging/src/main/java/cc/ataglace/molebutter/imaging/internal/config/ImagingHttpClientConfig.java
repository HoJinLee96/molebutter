package cc.ataglace.molebutter.imaging.internal.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ImagingHttpClientConfig {

    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0.0.0 Safari/537.36";

    @Bean(name = "imagingRestClient")
    public RestClient imagingRestClient(
            RestClient.Builder builder,
            @Value("${imaging.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${imaging.http.read-timeout:30s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                .build();
    }
}
