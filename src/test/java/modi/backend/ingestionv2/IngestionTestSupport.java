package modi.backend.ingestionv2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import modi.backend.TestcontainersConfiguration;
import modi.backend.ingestionv2.collect.domain.CultureCatalogClient;
import modi.backend.ingestionv2.common.IngestionDeliveryFacade;
import modi.backend.ingestionv2.common.IngestionProperties;
import modi.backend.ingestionv2.common.StreamGroupInitializer;
import modi.backend.ingestionv2.common.deadletter.DeadLetterJpaRepository;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.ingestionv2.common.outbox.OutboxAppender;
import modi.backend.ingestionv2.common.outbox.OutboxDispatcher;
import modi.backend.ingestionv2.common.outbox.OutboxJpaRepository;
import modi.backend.ingestionv2.common.outbox.OutboxService;
import modi.backend.ingestionv2.common.event.IngestionEventType;
import modi.backend.ingestionv2.common.queue.IngestionEventRouter;
import modi.backend.ingestionv2.common.queue.IngestionStream;
import modi.backend.ingestionv2.common.queue.PendingReclaimer;
import modi.backend.ingestionv2.common.queue.StreamConsumer;
import modi.backend.ingestionv2.common.queue.StreamTrimmer;
import modi.backend.ingestionv2.enrich.domain.detail.CultureDetailClient;
import modi.backend.ingestionv2.enrich.domain.genre.GenreClassifier;
import modi.backend.ingestionv2.enrich.domain.hours.PlaceHoursClient;

/**
 * 수집 V2 통합테스트 공통 토대 - 다섯 폴더가 이 클래스를 상속한다.
 *
 * <ul>
 *   <li>단위테스트를 쓰지 않는 전략이라 검증은 전부 이 토대 위의 통합 시나리오, 관측 대상은 DB 행과 스트림 상태</li>
 *   <li>밖으로 나가는 HTTP 포트 넷만 스텁, 그 안쪽(파사드·서비스·엔티티·리포·MySQL·Redis·코어 등록)은 전부 실물</li>
 *   <li>enabled=true 로 컨슈머 그룹은 실물로 만들고 auto-delivery=false 로 리스너·스케줄러만 끈다</li>
 *   <li>컨테이너를 공유하므로 스트림 키 삭제와 그룹 재생성이 매 테스트의 책임</li>
 *   <li>선점 전략은 운영 기본(REDIS_MARKER)을 그대로 쓴다 - 통합테스트가 실제 발송 경로를 밟게 하려면
 *       기본값을 테스트용으로 바꾸지 말아야 한다. 대신 잔여 키를 매 테스트가 지운다:
 *       테이블을 비우면 자동 증가 값이 되돌아 같은 id 가 다시 나오는데, 앞 테스트가 남긴 마커가 남아 있으면
 *       새 행이 조용히 건너뛰어진다</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.ingestion.v2.enabled=true",
		"app.ingestion.v2.auto-delivery=false",
		"app.exhibition.enrich.scheduling-enabled=false"
})
public abstract class IngestionTestSupport {

	/** 드레인이 끝나지 않으면 순환 발행을 의심해야 하므로 무한 루프 대신 상한을 둔다. */
	private static final int DRAIN_GUARD = 20;
	private static final AtomicInteger UNIQUE_KEY = new AtomicInteger();

	/** 밖으로 나가는 포트만 스텁한다. 안쪽은 전부 실물. */
	@MockitoBean protected CultureCatalogClient catalogClient;
	@MockitoBean protected CultureDetailClient detailClient;
	@MockitoBean protected GenreClassifier genreClassifier;
	@MockitoBean protected PlaceHoursClient placeHoursClient;

	@Autowired protected OutboxAppender outboxAppender;
	@Autowired protected OutboxService outboxService;
	@Autowired protected OutboxDispatcher outboxDispatcher;
	@Autowired protected DeadLetterService deadLetterService;
	@Autowired protected IngestionDeliveryFacade ingestionDeliveryFacade;

	@Autowired protected IngestionEventRouter eventRouter;
	@Autowired protected StreamConsumer streamConsumer;
	@Autowired protected PendingReclaimer pendingReclaimer;
	@Autowired protected StreamTrimmer streamTrimmer;
	@Autowired protected StreamGroupInitializer streamGroupInitializer;
	@Autowired protected StringRedisTemplate redisTemplate;
	@Autowired protected IngestionProperties properties;

	@Autowired protected OutboxJpaRepository outboxRepository;
	@Autowired protected DeadLetterJpaRepository deadLetterRepository;

	@Autowired protected JdbcTemplate jdbcTemplate;

	/** 트랜잭션 경계를 테스트가 직접 그을 때 쓴다. 롤백 시나리오와 MANDATORY 메서드 호출에 필요하다. */
	@Autowired protected TransactionTemplate transactionTemplate;

	/** 테스트마다 고유한 원천키. 코어 전시는 정리 대상이 아니라 키가 겹치면 앞 테스트 결과가 남는다. */
	protected String vendorKey;

	@BeforeEach
	void resetPipeline() {
		vendorKey = "EXH-" + UNIQUE_KEY.incrementAndGet() + "-" + System.nanoTime();
		clearSliceTables();
		redisTemplate.delete(List.of(IngestionStream.values()).stream().map(IngestionStream::key).toList());
		clearLockKeys();
		streamGroupInitializer.afterPropertiesSet();
	}

	/** 앞 테스트가 남긴 행 마커·잡 락을 지운다. 지우지 않으면 재사용된 id 의 새 행이 건너뛰어진다. */
	private void clearLockKeys() {
		for (String pattern : List.of("outbox:*", "lock:*")) {
			Set<String> keys = redisTemplate.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redisTemplate.delete(keys);
			}
		}
	}

	/** 슬라이스 테이블만 비운다. 코어 전시는 건드리지 않으므로 원천 키를 매번 새로 만든다. */
	private void clearSliceTables() {
		for (String table : List.of(
				"ingestion_outbox", "ingestion_dead_letter",
				"ingestion_staging", "ingestion_inspection",
				"ingestion_enrichment", "ingestion_enrichment_detail",
				"ingestion_enrichment_genre", "ingestion_enrichment_hours",
				"ingestion_collection", "ingestion_collect_batch_mark",
				"ingestion_culture_list_snapshot", "ingestion_culture_detail_snapshot",
				"ingestion_genre_snapshot", "ingestion_google_place_snapshot")) {
			jdbcTemplate.execute("delete from " + table);
		}
	}

	/** 아웃박스에 쌓인 것을 발송하고 그룹으로 읽어 소비까지 끝낸다. 큐가 빌 때까지. */
	protected void drainAll() {
		for (int guard = 0; guard < DRAIN_GUARD; guard++) {
			int sent = outboxDispatcher.dispatchPending();
			int consumed = consumeOnce(properties.consumerName());
			if (sent == 0 && consumed == 0) {
				return;
			}
		}
		throw new IllegalStateException("드레인이 끝나지 않았습니다. 순환 발행을 의심하십시오.");
	}

	/**
	 * 이벤트 하나를 실물 핸들러에 직접 넘긴다.
	 *
	 * <p>격벽 하나의 소비를 관측할 때 쓴다. 배달 계층을 통째로 미는 {@link #drainAll()} 과 달리
	 * 뒤 격벽까지 흘러가지 않으므로, 관측 대상이 그 격벽의 상태와 아웃박스 행에서 멈춘다.
	 * 라우팅은 실물 라우터가 하므로 supports 선언도 함께 검증된다.
	 */
	protected void handle(IngestionEventType type, String key) {
		eventRouter.route(type).orElseThrow(() -> new IllegalStateException("맡는 핸들러가 없습니다. type=" + type))
				.handle(key);
	}

	/** 그룹으로 한 번 읽어 실물 소비 어댑터에 넘긴다. 리스너 컨테이너의 읽기 루프만 대신한다. */
	protected int consumeOnce(String consumerName) {
		int consumed = 0;
		for (MapRecord<String, String, String> record : readAs(consumerName)) {
			streamConsumer.onMessage(record);
			consumed++;
		}
		return consumed;
	}

	/** 읽기만 하고 처리하지 않는다. 항목을 받은 채 종료된 컨슈머를 흉내 낼 때 쓴다. */
	protected List<MapRecord<String, String, String>> readAs(String consumerName) {
		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
		List<MapRecord<String, String, String>> all = new ArrayList<>();
		for (IngestionStream stream : IngestionStream.values()) {
			List<MapRecord<String, String, String>> records = streamOperations.read(
					Consumer.from(properties.consumerGroup(), consumerName),
					StreamReadOptions.empty().count(properties.readBatchSize()),
					StreamOffset.create(stream.key(), ReadOffset.lastConsumed()));
			if (records != null) {
				all.addAll(records);
			}
		}
		return all;
	}

	protected PendingMessages pendingOf(IngestionStream stream) {
		return redisTemplate.opsForStream()
				.pending(stream.key(), properties.consumerGroup(), Range.unbounded(), 100L);
	}

	protected long lengthOf(IngestionStream stream) {
		Long size = redisTemplate.opsForStream().size(stream.key());
		return size == null ? 0L : size;
	}
}
