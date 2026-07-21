package modi.backend.application.remind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import modi.backend.config.RemindProperties;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.record.AiStatus;
import modi.backend.domain.record.ExhibitionSnapshot;
import modi.backend.domain.record.Record;
import modi.backend.domain.record.RecordEmotion;
import modi.backend.domain.record.WriteMode;
import modi.backend.domain.remind.Remind;
import modi.backend.domain.remind.RemindAiStatus;
import modi.backend.domain.remind.RemindExhibitionSnapshot;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;
import modi.backend.support.error.CoreException;

@ExtendWith(MockitoExtension.class)
class RemindFacadeTest {

	@Mock
	RemindJpaRepository remindRepository;

	@Mock
	RecordJpaRepository recordRepository;

	@Mock
	ExhibitionRepository exhibitionRepository;

	@Mock
	RemindAiSummarizer summarizer;

	@Mock
	RemindSummaryBackfill remindSummaryBackfill;

	// 실제 설정 record를 주입(레코드라 목 대신 실값) — 소환 대기 기본 7d.
	@Spy
	RemindProperties remindProperties = new RemindProperties(Duration.ofDays(7));

	@InjectMocks
	RemindFacade facade;

	@Test
	@DisplayName("save — 본인 기록에 회고 저장: 감정 변화 요약은 PENDING으로 두고 백그라운드에 위임한다(M-2)")
	void save_성공_PENDING_백그라운드위임() {
		Record record = ownedRecord(1L);
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.of(record));
		given(summarizer.isEnabled()).willReturn(true);
		given(remindRepository.save(any(Remind.class))).willAnswer(inv -> inv.getArgument(0));

		RemindResult.Summary result = facade.save(new RemindCriteria.Save(1L, 10L, List.of("슬픔", "슬픔"), "다시 보니 슬프다"));

		assertThat(result.reflection()).isEqualTo("다시 보니 슬프다");
		assertThat(result.afterEmotionCodes()).containsExactly("슬픔"); // 중복 제거
		assertThat(result.beforeContent()).isEqualTo("빛이 번지는 전시실");
		assertThat(result.beforeEmotionCodes()).containsExactly("평화로운", "차분한");
		assertThat(result.aiStatus()).isEqualTo(RemindAiStatus.PENDING); // 응답을 막지 않음
		assertThat(result.aiSummary()).isNull();                        // 요약은 백그라운드에서 채움
		verify(remindRepository).save(any(Remind.class));
		verify(remindSummaryBackfill).schedule(any(), any());
	}

	@Test
	@DisplayName("save — AI 미설정이면 백그라운드 없이 SKIPPED로 확정한다")
	void save_AI미설정_SKIPPED() {
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.of(ownedRecord(1L)));
		given(summarizer.isEnabled()).willReturn(false);
		given(remindRepository.save(any(Remind.class))).willAnswer(inv -> inv.getArgument(0));

		RemindResult.Summary result = facade.save(new RemindCriteria.Save(1L, 10L, List.of("슬픔"), "소감"));

		assertThat(result.aiStatus()).isEqualTo(RemindAiStatus.SKIPPED);
		verify(remindSummaryBackfill, never()).schedule(any(), any());
	}

	@Test
	@DisplayName("save — 타인 기록엔 회고 불가(FORBIDDEN), 저장·백그라운드 위임 안 함")
	void save_타인기록_거부() {
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.of(ownedRecord(2L)));

		assertThatThrownBy(() -> facade.save(new RemindCriteria.Save(1L, 10L, List.of(), "소감")))
				.isInstanceOf(CoreException.class);

		verify(remindSummaryBackfill, never()).schedule(any(), any());
		verify(remindRepository, never()).save(any());
	}

	@Test
	@DisplayName("save — 없는 기록이면 RECORD_NOT_FOUND")
	void save_기록없음() {
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> facade.save(new RemindCriteria.Save(1L, 10L, List.of(), "소감")))
				.isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("get — 본인 리마인드 상세: 원본(before) 라이브 조회로 감정 변화 요약을 만든다")
	void get_성공() {
		Remind remind = Remind.create(1L, 10L, snapshot(), "다시 보니 슬프다", List.of("슬픔"), "감정이 옮겨갔다",
				RemindAiStatus.READY);
		given(remindRepository.findByIdAndDeletedAtIsNull(5L)).willReturn(Optional.of(remind));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.of(ownedRecord(1L)));

		RemindResult.Summary result = facade.get(1L, 5L);

		assertThat(result.beforeContent()).isEqualTo("빛이 번지는 전시실");
		assertThat(result.afterEmotionCodes()).containsExactly("슬픔");
		assertThat(result.aiSummary()).isEqualTo("감정이 옮겨갔다");
	}

	@Test
	@DisplayName("get — 원본 기록이 삭제됐으면 before는 null이어도 회고 데이터는 반환")
	void get_원본삭제() {
		Remind remind = Remind.create(1L, 10L, snapshot(), "소감", List.of("슬픔"), null, RemindAiStatus.SKIPPED);
		given(remindRepository.findByIdAndDeletedAtIsNull(5L)).willReturn(Optional.of(remind));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.empty());

		RemindResult.Summary result = facade.get(1L, 5L);

		assertThat(result.beforeContent()).isNull();
		assertThat(result.beforeEmotionCodes()).isEmpty();
		assertThat(result.reflection()).isEqualTo("소감");
	}

	@Test
	@DisplayName("get — 타인 리마인드는 FORBIDDEN")
	void get_타인_거부() {
		Remind remind = Remind.create(2L, 10L, snapshot(), "소감", List.of(), null, RemindAiStatus.SKIPPED);
		given(remindRepository.findByIdAndDeletedAtIsNull(5L)).willReturn(Optional.of(remind));

		assertThatThrownBy(() -> facade.get(1L, 5L)).isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("list — 항목에 원본 기록의 감정(beforeEmotionCodes)을 채운다(감정 변화 필터용)")
	void list_before감정_채움() {
		Remind remind = Remind.create(1L, 10L, snapshot(), "소감", List.of("슬픔"), null, RemindAiStatus.SKIPPED);
		given(remindRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(remind)));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.of(ownedRecord(1L)));

		Page<RemindResult.ListItem> page = facade.list(1L, PageRequest.of(0, 20));

		RemindResult.ListItem item = page.getContent().get(0);
		assertThat(item.beforeEmotionCodes()).containsExactly("평화로운", "차분한");
		assertThat(item.afterEmotionCodes()).containsExactly("슬픔");
	}

	@Test
	@DisplayName("list — 원본 기록이 삭제됐으면 beforeEmotionCodes는 빈 리스트")
	void list_원본삭제_before감정_빈리스트() {
		Remind remind = Remind.create(1L, 10L, snapshot(), "소감", List.of("슬픔"), null, RemindAiStatus.SKIPPED);
		given(remindRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(remind)));
		given(recordRepository.findByIdWithEmotions(10L)).willReturn(Optional.empty());

		Page<RemindResult.ListItem> page = facade.list(1L, PageRequest.of(0, 20));

		assertThat(page.getContent().get(0).beforeEmotionCodes()).isEmpty();
	}

	private Record ownedRecord(Long ownerId) {
		Record record = Record.create(ownerId, 10L,
				new ExhibitionSnapshot("모네전", "CATALOG", "http://p", "예술의전당", "SEOUL", "PAINTING", null, null),
				WriteMode.DIRECT, LocalDate.of(2026, 6, 20), "빛이 번지는 전시실", null, null, null, AiStatus.READY);
		record.replaceEmotions(List.of(RecordEmotion.create("평화로운"), RecordEmotion.create("차분한")));
		return record;
	}

	private RemindExhibitionSnapshot snapshot() {
		return new RemindExhibitionSnapshot(10L, "모네전", "http://p", "예술의전당", LocalDate.of(2026, 6, 20));
	}
}
