package modi.backend.ingestionv2.common.deadletter;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import modi.backend.ingestionv2.common.IngestionClock;
import modi.backend.ingestionv2.common.IngestionErrorCode;
import modi.backend.ingestionv2.common.event.OutboxPayload;
import modi.backend.support.error.CoreException;

/**
 * 격리 기록의 단일 창구.
 *
 * <ul>
 *   <li>컨슈머 스레드에 트랜잭션이 없으므로 이 메서드가 경계를 소유</li>
 *   <li>격리 삽입이 커밋된 뒤에야 처리 확인 - 순서가 뒤집히면 항목이 흔적 없이 사라짐</li>
 *   <li>되돌려 보내기(replay)는 여기 없음 - 아웃박스 적재를 함께 해야 하므로 관리자 파사드가 조율</li>
 *   <li>낙관적 잠금 충돌의 번역이 여기 - 트랜잭션을 소유한 자리라야 잡을 수 있고, 메시지는 ErrorCode 한 곳에만 둔다</li>
 *   <li>격리·재주입 카운터도 여기 - 두 사실이 나는 유일한 자리라 다른 곳에서 세면 경로가 하나 늘 때마다 어긋난다</li>
 *   <li>카운터는 커밋과 무관하게 메모리에서 오름 - 격리는 삽입 직후가 아니라 성공한 뒤 세어 롤백된 시도를
 *       세지 않음</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DeadLetterService {

	/** DLQ 유입 건수. 태그 reason 으로 상한 소진(exhausted)과 해석 불가(malformed)를 가른다. */
	public static final String ISOLATED_COUNTER = "ingestion.deadletter.isolated";

	/** 재주입 시도 결과. success 합계는 status=REPLAYED 행 수와 같아야 한다. */
	public static final String REDRIVE_COUNTER = "ingestion.deadletter.redrive";

	private final DeadLetterRepository deadLetterRepository;
	private final MeterRegistry meterRegistry;

	/** 재시도 상한을 넘긴 항목의 격리. */
	@Transactional
	public void isolate(OutboxPayload payload, String streamKey, String recordId, DeadLetter.Failure failure,
			int retryCount) {
		deadLetterRepository.save(DeadLetter.of(payload, streamKey, recordId, failure, retryCount));
		isolatedCounter("exhausted").increment();
	}

	/** 해석할 수 없는 레코드의 격리 - payload 원문은 있으면 남긴다. */
	@Transactional
	public void isolateMalformed(String streamKey, String recordId, String rawPayload, DeadLetter.Failure failure) {
		deadLetterRepository.save(DeadLetter.malformed(streamKey, recordId, rawPayload, failure));
		isolatedCounter("malformed").increment();
	}

	/** 관리자 조회 - 아직 처리하지 않은 항목. */
	@Transactional(readOnly = true)
	public List<DeadLetter> findPending(int limit) {
		return deadLetterRepository.findPending(limit);
	}

	/** 알람 입력 - 기준 시각 이후의 유입 건수. */
	@Transactional(readOnly = true)
	public long countFailedAfter(LocalDateTime threshold) {
		return deadLetterRepository.countFailedAfter(threshold);
	}

	/** 재주입 가능 여부 검증 - 표시와 적재를 시작하기 전에 파사드가 먼저 부른다. */
	@Transactional(readOnly = true)
	public DeadLetter findRedrivable(long deadLetterId) {
		DeadLetter deadLetter = findPendingOrThrow(deadLetterId);
		if (!deadLetter.isRedrivable()) {
			throw new CoreException(IngestionErrorCode.DEAD_LETTER_NOT_REDRIVABLE);
		}
		return deadLetter;
	}

	/**
	 * 되돌려 보낸 표시 - 같은 행이 두 번 흘러가지 않게 한다.
	 *
	 * <ul>
	 *   <li>동시에 들어온 요청 중 늦은 쪽은 버전이 어긋나 여기서 충돌로 끝남</li>
	 *   <li>상태 검사만으로는 못 막음 - 두 요청이 같은 시점에 PENDING 을 함께 읽을 수 있음</li>
	 *   <li>세 갈래를 모두 셈 - 성공 · 버전 충돌 · 이미 끝난 항목</li>
	 *   <li>존재하지 않는 id 는 세지 않음 - DLQ 행에 대한 시도가 아님</li>
	 * </ul>
	 */
	@Transactional
	public DeadLetter markReplayed(long deadLetterId) {
		DeadLetter deadLetter;
		try {
			deadLetter = findPendingOrThrow(deadLetterId);
		} catch (CoreException rejected) {
			if (rejected.errorCode() == IngestionErrorCode.DEAD_LETTER_ALREADY_RESOLVED) {
				redriveCounter("already_resolved").increment();
			}
			throw rejected;
		}
		deadLetter.markReplayed(IngestionClock.now());
		try {
			DeadLetter replayed = deadLetterRepository.save(deadLetter);
			redriveCounter("success").increment();
			return replayed;
		} catch (OptimisticLockingFailureException conflict) {
			redriveCounter("conflict").increment();
			throw new CoreException(IngestionErrorCode.DEAD_LETTER_REDRIVE_CONFLICT,
					"재주입이 경합했습니다. deadLetterId=" + deadLetterId, conflict);
		}
	}

	/** 무시 표시 - 처리하지 않기로 한 항목을 목록에서 뺀다. */
	@Transactional
	public DeadLetter markIgnored(long deadLetterId) {
		DeadLetter deadLetter = findPendingOrThrow(deadLetterId);
		deadLetter.markIgnored(IngestionClock.now());
		return deadLetterRepository.save(deadLetter);
	}

	private DeadLetter findPendingOrThrow(long deadLetterId) {
		DeadLetter deadLetter = deadLetterRepository.findById(deadLetterId)
				.orElseThrow(() -> new CoreException(IngestionErrorCode.DEAD_LETTER_NOT_FOUND));
		if (!deadLetter.isPending()) {
			throw new CoreException(IngestionErrorCode.DEAD_LETTER_ALREADY_RESOLVED);
		}
		return deadLetter;
	}

	private Counter isolatedCounter(String reason) {
		return Counter.builder(ISOLATED_COUNTER).tag("reason", reason).register(meterRegistry);
	}

	private Counter redriveCounter(String result) {
		return Counter.builder(REDRIVE_COUNTER).tag("result", result).register(meterRegistry);
	}
}
