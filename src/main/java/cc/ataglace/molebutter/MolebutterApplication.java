package cc.ataglace.molebutter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan // ConfigurationProperties 활성화
@EnableCaching // 캐싱 활성화
@EnableAsync // 비동기 처리 활성화
@EnableScheduling // 스케줄링 활성화
// DB 회원 로그인과 JWT 인증을 사용하므로 기본 메모리 사용자 자동 생성은 제외한다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MolebutterApplication {

	public static void main(String[] args) {
		SpringApplication.run(MolebutterApplication.class, args);
	}

}
