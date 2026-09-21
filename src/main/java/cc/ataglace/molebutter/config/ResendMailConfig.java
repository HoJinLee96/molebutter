package cc.ataglace.molebutter.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import cc.ataglace.molebutter.config.properties.ResendProperties;
import cc.ataglace.molebutter.infra.mail.ResendEmailSender;
import cc.ataglace.molebutter.service.EmailSender;

/**
 * mail.provider=resend일 때 활성화되는 Resend 전용 배선(프로퍼티 생략 시 기본값).
 *
 * <p>다른 발송 구현을 추가할 때 이 클래스는 수정하지 않는다. 새 구현체(infra/mail)와 전용 @Configuration을 만들어
 * {@code @ConditionalOnProperty(name = "mail.provider", havingValue = "<이름>")}을 붙이고,
 * application.properties의 mail.provider 값만 바꾸면 된다.
 */
@Configuration
@ConditionalOnProperty(name = "mail.provider", havingValue = "resend", matchIfMissing = true)
public class ResendMailConfig {

    private static final String RESEND_BASE_URL = "https://api.resend.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * API 키는 application-secret/mail.properties의 resend.api-key에 입력한다.
     * 검증을 여기서 하는 이유: resend가 선택된 경우에만 키를 요구해야 다른 provider로 갈아껴도 기동이 막히지 않는다.
     * 타임아웃을 짧게 잡아 Resend 장애가 가입 요청 스레드를 오래 붙잡지 않게 한다.
     */
    @Bean
    EmailSender resendEmailSender(ResendProperties resendProperties, RestClient.Builder restClientBuilder) {
        if (resendProperties.apiKey() == null || resendProperties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "resend.api-key가 비어 있습니다. application-secret/mail.properties에 입력하세요(https://resend.com/api-keys).");
        }
        if (resendProperties.from() == null || resendProperties.from().isBlank()) {
            throw new IllegalStateException("resend.from이 비어 있습니다. application.properties에서 발신자 주소를 설정하세요.");
        }

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient restClient = restClientBuilder
                .baseUrl(RESEND_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resendProperties.apiKey())
                .requestFactory(requestFactory)
                .build();
        return new ResendEmailSender(restClient, resendProperties.from());
    }
}
