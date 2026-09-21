package cc.ataglace.molebutter.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import cc.ataglace.molebutter.config.ResendMailConfig;

/**
 * Resend 메일 발송 설정. api-key는 application-secret/mail.properties에 입력하고(비밀),
 * from은 application.properties에 둔다.
 *
 * <p>값 검증은 바인딩 시점이 아니라 {@link ResendMailConfig}에서 한다 — mail.provider가 resend일 때만
 * 키를 요구해야, 다른 발송 구현으로 갈아껴도 빈 값 때문에 기동이 막히지 않는다.
 */
@ConfigurationProperties(prefix = "resend")
public record ResendProperties(
        String apiKey,
        String from
) {}
