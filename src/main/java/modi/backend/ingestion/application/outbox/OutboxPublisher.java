package modi.backend.ingestion.application.outbox;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.ingestion.domain.outbox.IngestionEventType;
import modi.backend.ingestion.domain.outbox.OutboxMessage;
import modi.backend.ingestion.domain.outbox.OutboxMessageRepository;

/**
 * 아웃박스 <b>발행 컴포넌트</b> — 이벤트(사실)를 큐에 싣는 면만 담당한다(설계 §3-5, 구 ExhibitionOutboxService의
 * 발행부 분리). 소비(consume·전이·백오프)는 {@link ExhibitionOutboxService}가 진다 — "발행 = 발견 + 큐에 싣기,
 * 소비 = 실행"(스펙 §1)의 절단선이자, 의존 규칙(서비스→서비스 ❌)의 귀결이다: 서비스는 이 컴포넌트만 주입한다.
 *
 * <p><b>원자성</b>: 발행 메서드는 {@code REQUIRED} 전파라 상태 변경 트랜잭션 안에서 불리면 그 트랜잭션에
 * 합류한다 — 상태 반영과 사실 기록이 같이 성공하거나 같이 실패한다. 커밋되면 {@link OutboxEnqueued} 이벤트로
 * 릴레이 소비를 앞당긴다(적재 알림 — 유실돼도 폴링이 줍는다).
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

	private final OutboxMessageRepository outboxMessageRepository;
	/** 커밋 직후 릴레이 소비를 앞당기는 적재 알림 이벤트({@link OutboxEnqueued}) 발행자. */
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 이벤트를 아웃박스에 싣는다(멱등 — 같은 (종류, 대상)의 행이 이미 있으면 no-op). 이미 종료된 메시지는
	 * 되살리지 않는다(모든 이벤트가 대상당 한 번이면 족하다 — 재검증 폐기(D4) 후 반복 이벤트는 없다).
	 */
	@Transactional
	public void enqueue(IngestionEventType eventType, String targetKey, LocalDateTime now) {
		if (targetKey == null || targetKey.isBlank()) {
			return;
		}
		if (outboxMessageRepository.findByMessageTypeAndTargetKey(eventType, targetKey).isPresent()) {
			return;
		}
		outboxMessageRepository.save(OutboxMessage.enqueue(eventType, targetKey, now));
		eventPublisher.publishEvent(new OutboxEnqueued(eventType));
	}

	/**
	 * 멱등 enqueue + <b>종료 메시지 부활</b> 변형 — 재sync 안전망 전용(ADR-12 보강). 일반 {@link #enqueue}는 종료된
	 * 메시지를 되살리지 않지만, "종료 메시지 + 미종료 진행 행" 조합이 실제로 생긴다: 게이트가 일시적으로
	 * 풀린 창에 소비된 DRAFT_READY(no-op 소비→SUCCEEDED), 실패 전이 후 가시화 전 크래시, 수동 개입 뒤
	 * 재스테이징. 이 안전망이 그 행을 되살려 진행이 영구 침묵하지 않게 한다(치유 경로). 재승격 방지의 실제
	 * 가드는 메시지 비부활이 아니라 진행 terminal 검사 + external_id UK다 — 부활은 안전하다.
	 */
	@Transactional
	public void enqueueOrReactivate(IngestionEventType eventType, String targetKey, LocalDateTime now) {
		if (targetKey == null || targetKey.isBlank()) {
			return;
		}
		outboxMessageRepository.findByMessageTypeAndTargetKey(eventType, targetKey)
				.ifPresentOrElse(existing -> {
					existing.reactivate(now); // 종료됐던 메시지면 되살리고, 미종료면 no-op(이미 선별 대상).
					outboxMessageRepository.save(existing);
				}, () -> outboxMessageRepository.save(OutboxMessage.enqueue(eventType, targetKey, now)));
		eventPublisher.publishEvent(new OutboxEnqueued(eventType));
	}
}
