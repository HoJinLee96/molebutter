package cc.ataglace.molebutter.service;

/**
 * 메일 발송 port. 비즈니스 코드는 이 인터페이스에만 의존한다.
 * 실제 구현은 mail.provider 프로퍼티로 선택된다.
 * 구현을 추가할 때는 기존 코드를 수정하지 않고 구현체 + 전용 @Configuration을 새로 만든다.
 * 발송 실패는 RuntimeException으로 전파한다.
 */
public interface EmailSender {

    void send(String to, String subject, String body);
}
