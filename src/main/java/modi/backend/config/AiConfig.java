package modi.backend.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 설정 프로퍼티 등록 + AI 호출 전용 스레드풀.
 * AI 엔드포인트는 응답이 수 초 걸려 서블릿(Tomcat) 워커 스레드를 오래 점유하므로,
 * 컨트롤러가 이 전용 풀에서 비동기 실행({@code CompletableFuture})해 서블릿 스레드를 빨리 반환한다.
 * 풀 포화 시 CallerRuns로 그레이스풀 다운(요청 스레드에서 실행) — 무제한 스레드 생성/거부 방지.
 */
@Configuration
@EnableConfigurationProperties({ AiProperties.class, RemindProperties.class, AiDraftProperties.class })
public class AiConfig {

	@Bean("aiExecutor")
	public Executor aiExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(8);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("ai-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}
}
