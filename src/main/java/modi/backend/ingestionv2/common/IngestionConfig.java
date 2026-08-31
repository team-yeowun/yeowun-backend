package modi.backend.ingestionv2.common;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.ConsumerStreamReadRequest;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.common.queue.StreamConsumer;

/**
 * 수집 슬라이스의 배선 - 대기열 구독의 자리.
 *
 * <ul>
 *   <li>설정 바인딩은 조건 없이 등록 - 슬라이스를 꺼도 프로퍼티 레코드는 필요하다</li>
 *   <li>구독 등록만 enabled + auto-delivery 두 스위치에 걸림 - 테스트가 비동기 배달만 끌 수 있게 함</li>
 *   <li>초기화 컴포넌트를 인자로 받아 컨슈머 그룹 생성 이후에 구독이 등록되게 함</li>
 *   <li>autoAcknowledge(false) - 처리 확인은 소비 어댑터가 직접 한다</li>
 *   <li>cancelOnError(false) - 예외 한 번으로 구독이 끊기지 않게 함</li>
 *   <li>구독 하나가 스레드 하나 - 스레드 수를 구독 수와 같게 잡지 않으면 일부 스트림이 읽히지 않음</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

	/** 구독 전용 스레드 풀 - 구독마다 블로킹 조회 루프가 하나씩 돈다. */
	@Bean
	@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
	public ThreadPoolTaskExecutor ingestionStreamExecutor(IngestionProperties properties) {
		int subscriptions = totalSubscriptions(properties);
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(subscriptions);
		executor.setMaxPoolSize(subscriptions);
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("ingestion-stream-");
		executor.initialize();
		log.info("스트림 구독 스레드 풀을 만들었습니다. subscriptions={}", subscriptions);
		return executor;
	}

	/**
	 * 스트림 네 개에 설정된 수만큼 컨슈머를 붙인다.
	 * 초기화 컴포넌트를 인자로 받는 이유는 컨슈머 그룹이 만들어진 뒤에 구독이 시작되도록 순서를 강제하기 위함이다.
	 */
	@Bean(destroyMethod = "stop")
	@ConditionalOnProperty(prefix = "app.ingestion.v2", name = {"enabled", "auto-delivery"}, havingValue = "true")
	public StreamMessageListenerContainer<String, MapRecord<String, String, String>> ingestionStreamContainer(
			RedisConnectionFactory connectionFactory,
			StreamGroupInitializer streamGroupInitializer,
			StreamConsumer streamConsumer,
			ThreadPoolTaskExecutor ingestionStreamExecutor,
			IngestionProperties properties) {

		StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
				StreamMessageListenerContainerOptions.builder()
						.pollTimeout(Duration.ofMillis(properties.pollTimeoutMs()))
						.batchSize(properties.readBatchSize())
						.executor(ingestionStreamExecutor)
						.build();

		StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
				StreamMessageListenerContainer.create(connectionFactory, options);

		for (IngestionStream stream : IngestionStream.values()) {
			int consumers = stream.external() ? properties.externalStreamConsumers() : properties.dbStreamConsumers();
			for (int index = 0; index < consumers; index++) {
				container.register(readRequest(stream, consumerName(properties, stream, index), properties),
						streamConsumer);
			}
		}

		container.start();
		log.info("스트림 구독을 시작했습니다. group={} consumerName={}",
				properties.consumerGroup(), properties.consumerName());
		return container;
	}

	private static ConsumerStreamReadRequest<String> readRequest(IngestionStream stream, String consumerName,
			IngestionProperties properties) {
		return StreamReadRequest
				.builder(StreamOffset.create(stream.key(), ReadOffset.lastConsumed()))
				.consumer(Consumer.from(properties.consumerGroup(), consumerName))
				.autoAcknowledge(false)
				.cancelOnError(throwable -> false)
				.errorHandler(throwable -> log.warn("스트림 수신 중 오류가 발생했습니다. stream={} consumer={}",
						stream.key(), consumerName, throwable))
				.build();
	}

	private static String consumerName(IngestionProperties properties, IngestionStream stream, int index) {
		return "%s-%s-%d".formatted(properties.consumerName(), stream.name().toLowerCase(), index);
	}

	private static int totalSubscriptions(IngestionProperties properties) {
		int total = 0;
		for (IngestionStream stream : IngestionStream.values()) {
			total += stream.external() ? properties.externalStreamConsumers() : properties.dbStreamConsumers();
		}
		return total;
	}
}
