package modi.backend.ingestionv2.lab.retry;

import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import modi.backend.ingestionv2.common.IngestionDeliveryCriteria;
import modi.backend.ingestionv2.common.IngestionDeliveryFacade;
import modi.backend.ingestionv2.common.deadletter.DeadLetter;
import modi.backend.ingestionv2.common.deadletter.DeadLetterService;
import modi.backend.ingestionv2.common.outbox.OutboxService;
import modi.backend.support.error.CoreException;

/**
 * 변형 하나로 재처리 요청 한 건을 실행하는 드라이버.
 *
 * <ul>
 *   <li>V1~V3 는 <b>같은 1-tx 흐름</b>(잠금 획득 → 상태 검사 → markReplayed → outbox append)을 공유하고
 *       다른 것은 {@link #acquire} 한 줄뿐이다 - 락 방식 외의 변수를 없애기 위한 구조</li>
 *   <li>P0 는 프로덕션 파사드를 그대로 부른다 - 3-tx 경계를 재현하려면 파사드를 흉내 내면 안 된다</li>
 *   <li>업무 규칙을 lab 이 복제하지 않는다 - 상태 검사와 표시는 프로덕션 서비스가 한다</li>
 *   <li>tx 지속시간은 1-tx 변형만 잰다 - 3-tx 는 파사드 내부라 분해 측정하지 않는다({@code TX_NOT_MEASURED})</li>
 *   <li>거절 사유는 예외를 그대로 분류만 한다 - 하네스가 의미를 붙이지 않는다</li>
 * </ul>
 */
final class RedriveRunner {

	/** 3-tx 변형의 tx 지속시간 - 분해 측정하지 않았다는 표시. */
	static final long TX_NOT_MEASURED = -1L;

	private final IngestionDeliveryFacade ingestionDeliveryFacade;
	private final DeadLetterService deadLetterService;
	private final OutboxService outboxService;
	private final TransactionTemplate transactionTemplate;
	private final EntityManagerFactory entityManagerFactory;

	RedriveRunner(IngestionDeliveryFacade ingestionDeliveryFacade, DeadLetterService deadLetterService,
			OutboxService outboxService, TransactionTemplate transactionTemplate,
			EntityManagerFactory entityManagerFactory) {
		this.ingestionDeliveryFacade = ingestionDeliveryFacade;
		this.deadLetterService = deadLetterService;
		this.outboxService = outboxService;
		this.transactionTemplate = transactionTemplate;
		this.entityManagerFactory = entityManagerFactory;
	}

	RedriveOutcome redrive(RedriveVariant variant, long deadLetterId) {
		long start = System.nanoTime();
		try {
			if (variant.usesProductionFacade()) {
				ingestionDeliveryFacade.redrive(IngestionDeliveryCriteria.Redrive.of(deadLetterId));
				return RedriveOutcome.succeeded(System.nanoTime() - start, TX_NOT_MEASURED);
			}
			transactionTemplate.executeWithoutResult(status -> redriveInOneTransaction(variant, deadLetterId));
			long elapsed = System.nanoTime() - start;
			return RedriveOutcome.succeeded(elapsed, elapsed);
		} catch (RuntimeException rejection) {
			long elapsed = System.nanoTime() - start;
			return RedriveOutcome.rejected(classify(rejection), elapsed,
					variant.usesProductionFacade() ? TX_NOT_MEASURED : elapsed);
		}
	}

	/**
	 * 세 변형이 공유하는 1-tx 흐름.
	 *
	 * <p>셋 다 프로덕션 서비스를 그대로 호출한다 - 갈라지는 지점은 {@link #acquire} 하나뿐이다.
	 */
	private void redriveInOneTransaction(RedriveVariant variant, long deadLetterId) {
		EntityManager entityManager = EntityManagerFactoryUtils
				.getTransactionalEntityManager(entityManagerFactory);
		acquire(variant, entityManager, deadLetterId);
		DeadLetter target = deadLetterService.findRedrivable(deadLetterId);
		deadLetterService.markReplayed(deadLetterId);
		outboxService.append(target.getEventType(), target.getAggregateId());
	}

	/** 변형 사이의 유일한 차이 - 잠금 획득 한 줄. */
	private void acquire(RedriveVariant variant, EntityManager entityManager, long deadLetterId) {
		switch (variant) {
			case V2 -> entityManager.find(DeadLetter.class, deadLetterId, LockModeType.PESSIMISTIC_WRITE);
			case V3 -> acquireSkippingLocked(entityManager, deadLetterId);
			default -> {
				// V1(낙관)은 획득 단계가 없다 - 충돌은 커밋 시점에 드러난다.
			}
		}
	}

	/** 빈 결과가 곧 "다른 요청이 처리 중" - 대기하지 않고 즉시 거절한다. */
	private void acquireSkippingLocked(EntityManager entityManager, long deadLetterId) {
		List<?> claimed = entityManager.createNativeQuery("""
						select id from ingestion_dead_letter
						 where id = :id and status = 'PENDING'
						 for update skip locked
						""")
				.setParameter("id", deadLetterId)
				.getResultList();
		if (claimed.isEmpty()) {
			throw new CoreException(RetryLabErrorCode.DEAD_LETTER_REDRIVE_IN_PROGRESS,
					"SKIP LOCKED 가 행을 건너뛰었습니다. deadLetterId=" + deadLetterId);
		}
	}

	private static String classify(RuntimeException rejection) {
		if (rejection instanceof CoreException coreException) {
			return coreException.errorCode().code();
		}
		if (rejection instanceof OptimisticLockingFailureException) {
			return "OPTIMISTIC_LOCK_UNTRANSLATED";
		}
		return rejection.getClass().getSimpleName();
	}
}
