package cc.ataglace.molebutter.infra.mail;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import cc.ataglace.molebutter.service.EmailSender;
import lombok.RequiredArgsConstructor;

/**
 * Resend(https://resend.com) HTTP API 기반 {@link EmailSender}.
 * 실패(4xx/5xx·네트워크 오류)는 RestClient의 RuntimeException으로 전파된다.
 */
@RequiredArgsConstructor
public class ResendEmailSender implements EmailSender {

    private final RestClient restClient;
    private final String from;

    @Override
    public void send(String to, String subject, String body) {
        restClient.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "from", from,
                        "to", List.of(to),
                        "subject", subject,
                        "text", body))
                .retrieve()
                .toBodilessEntity();
    }
}
