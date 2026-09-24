package cc.ataglace.molebutter.service.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import cc.ataglace.molebutter.infra.mail.ResendEmailSender;

class ResendEmailSenderTests {
    @Test
    void sendsCorrectRequestAndPropagatesProviderFailure() {
        var builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer test-key");
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = new ResendEmailSender(builder.build(), "Molebutter <sender@example.com>");
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {"from":"Molebutter <sender@example.com>","to":["recipient@example.com"],
                         "subject":"Verification","text":"code: 123456"}
                        """))
                .andRespond(withSuccess("{\"id\":\"test-message\"}", MediaType.APPLICATION_JSON));
        sender.send("recipient@example.com", "Verification", "code: 123456");
        server.verify();
        server.reset();
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> sender.send("recipient@example.com", "Verification", "code: 123456"))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        server.verify();
    }
}
