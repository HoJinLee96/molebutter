package cc.ataglace.molebutter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan // ConfigurationProperties 활성화
@EnableCaching // 캐싱 활성화
@EnableAsync // 비동기 처리 활성화
@EnableScheduling // 스케줄링 활성화
@SpringBootApplication
public class MolebutterApplication {

	public static void main(String[] args) {
		SpringApplication.run(MolebutterApplication.class, args);
	}

}
