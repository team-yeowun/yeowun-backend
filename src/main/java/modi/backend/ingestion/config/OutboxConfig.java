package modi.backend.ingestion.config;

import java.util.concurrent.ThreadPoolExecutor;

import modi.backend.ingestion.properties.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 전시 아웃박스 관련 설정 바인딩·릴레이 실행기 등록.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

	/**
	 * 릴레이의 <b>이벤트 드레인 코얼레싱 실행기</b> — 스레드 1·대기 1·초과 폐기(Discard).
	 *
	 * <p>enqueue 커밋마다 {@code OutboxEnqueued}가 날아와도 드레인은 "진행 중 1 + 대기 1"로 뭉쳐진다.
	 * 초과분을 버려도 되는 이유: 이벤트는 글루일 뿐이고 진실은 아웃박스 테이블에 있다 — 대기 중인 드레인 한 번이
	 * 그 시점까지 쌓인 도래 메시지를 전부 집는다(버려진 이벤트 = 지연 0의 손실 0).
	 */
	@Bean
	public ThreadPoolTaskExecutor outboxRelayExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("outbox-relay-");
		executor.setDaemon(true);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
		executor.initialize();
		return executor;
	}
}
