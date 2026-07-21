package modi.backend.application.remind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import modi.backend.domain.remind.Remind;
import modi.backend.domain.remind.RemindAiStatus;
import modi.backend.domain.remind.RemindExhibitionSnapshot;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;
import modi.backend.support.time.AppTime;

/**
 * PENDING으로 남은 요약을 복구하는 백필 로직 단위 테스트.
 * TransactionTemplate은 목 트랜잭션 매니저 위에서 콜백을 그대로 실행하므로 별도 컨텍스트가 필요 없다.
 */
@ExtendWith(MockitoExtension.class)
class RemindSummaryBackfillTest {

	@Mock
	RemindAiSummarizer summarizer;

	@Mock
	RemindJpaRepository remindRepository;

	@Mock
	RecordJpaRepository recordRepository;

	@Mock
	PlatformTransactionManager transactionManager;

	private RemindSummaryBackfill backfill() {
		return new RemindSummaryBackfill(summarizer, remindRepository, recordRepository,
				Runnable::run, transactionManager);
	}

	@Test
	@DisplayName("backfillPending — PENDING 건의 요약을 생성해 READY로 채운다")
	void 백필_요약채움() {
		Remind pending = pendingRemind(ZonedDateTime.now(AppTime.KST).minusMinutes(30));
		given(remindRepository.findPendingOlderThan(eq(RemindAiStatus.PENDING), any(), any(Pageable.class)))
				.willReturn(List.of(pending));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.empty());
		given(summarizer.isEnabled()).willReturn(true);
		given(summarizer.summarize(any()))
				.willReturn(new RemindAiSummarizer.Result(RemindAiStatus.READY, "감정이 옮겨갔다"));
		given(remindRepository.findByIdAndDeletedAtIsNull(any())).willReturn(Optional.of(pending));

		int resolved = backfill().backfillPending(20, Duration.ofMinutes(2), Duration.ofDays(1));

		assertThat(resolved).isEqualTo(1);
		assertThat(pending.getAiStatus()).isEqualTo(RemindAiStatus.READY);
		assertThat(pending.getAiSummary()).isEqualTo("감정이 옮겨갔다");
	}

	@Test
	@DisplayName("backfillPending — AI 미설정이면 조회조차 하지 않는다(외부 호출 0)")
	void 백필_AI미설정_아무것도안함() {
		given(summarizer.isEnabled()).willReturn(false);

		int resolved = backfill().backfillPending(20, Duration.ofMinutes(2), Duration.ofDays(1));

		assertThat(resolved).isZero();
		verify(remindRepository, never()).findPendingOlderThan(any(), any(), any());
		verify(summarizer, never()).summarize(any());
	}

	@Test
	@DisplayName("backfillPending — 쿨다운(SKIPPED)이면 PENDING을 유지해 다음 주기에 재시도한다")
	void 백필_쿨다운이면_PENDING유지() {
		Remind pending = pendingRemind(ZonedDateTime.now(AppTime.KST).minusMinutes(30));
		given(remindRepository.findPendingOlderThan(eq(RemindAiStatus.PENDING), any(), any(Pageable.class)))
				.willReturn(List.of(pending));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.empty());
		given(summarizer.isEnabled()).willReturn(true);
		given(summarizer.summarize(any()))
				.willReturn(new RemindAiSummarizer.Result(RemindAiStatus.SKIPPED, null));

		int resolved = backfill().backfillPending(20, Duration.ofMinutes(2), Duration.ofDays(1));

		assertThat(resolved).isZero();
		assertThat(pending.getAiStatus()).isEqualTo(RemindAiStatus.PENDING); // 그대로 둔다
		verify(remindRepository, never()).findByIdAndDeletedAtIsNull(any());
	}

	@Test
	@DisplayName("backfillPending — 포기 시간을 넘긴 PENDING은 AI 호출 없이 FAILED로 확정한다")
	void 백필_포기시간초과_FAILED확정() {
		Remind stale = pendingRemind(ZonedDateTime.now(AppTime.KST).minusDays(3));
		given(remindRepository.findPendingOlderThan(eq(RemindAiStatus.PENDING), any(), any(Pageable.class)))
				.willReturn(List.of(stale));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.empty());
		given(summarizer.isEnabled()).willReturn(true);
		given(remindRepository.findByIdAndDeletedAtIsNull(any())).willReturn(Optional.of(stale));

		int resolved = backfill().backfillPending(20, Duration.ofMinutes(2), Duration.ofDays(1));

		assertThat(resolved).isEqualTo(1);
		assertThat(stale.getAiStatus()).isEqualTo(RemindAiStatus.FAILED);
		assertThat(stale.getAiSummary()).isNull();
		verify(summarizer, never()).summarize(any()); // 유료 호출을 태우지 않는다
	}

	/** createdAt은 @PrePersist가 채우므로 단위 테스트에서는 직접 주입한다. */
	private Remind pendingRemind(ZonedDateTime createdAt) {
		Remind remind = Remind.create(1L, 10L,
				new RemindExhibitionSnapshot(7L, "모네전", "http://p", "예술의전당", LocalDate.of(2026, 6, 20)),
				"다시 보니 슬프다", List.of("슬픔"), null, RemindAiStatus.PENDING);
		ReflectionTestUtils.setField(remind, "createdAt", createdAt);
		return remind;
	}
}
