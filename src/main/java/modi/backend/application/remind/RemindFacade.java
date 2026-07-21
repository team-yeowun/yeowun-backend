package modi.backend.application.remind;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.catalog.Exhibition;
import modi.backend.domain.exhibition.catalog.ExhibitionRepository;
import modi.backend.domain.record.Record;
import modi.backend.domain.record.RecordEmotion;
import modi.backend.domain.record.RecordErrorCode;
import modi.backend.domain.record.RecordMedia;
import modi.backend.domain.record.RecordMediaType;
import modi.backend.domain.remind.Remind;
import modi.backend.domain.remind.RemindAiStatus;
import modi.backend.domain.remind.RemindEmotion;
import modi.backend.domain.remind.RemindErrorCode;
import modi.backend.domain.remind.RemindExhibitionSnapshot;
import modi.backend.config.RemindProperties;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.infra.remind.RemindJpaRepository;
import modi.backend.support.error.CoreException;
import modi.backend.support.time.AppTime;

/**
 * 리마인드(회고) 유스케이스 조율.
 * 여러 도메인(Remind·Record·Exhibition)을 조합하고, 저장 시 감정 변화 AI 요약을 best-effort로 함께 만든다.
 * 상태 변경은 엔티티 메서드(Remind.create) 안에서만, Facade는 load·조율·save만 담당한다.
 */
@Service
@RequiredArgsConstructor
public class RemindFacade {

	/** 목록의 소감 미리보기 길이. */
	private static final int REFLECTION_PREVIEW_LENGTH = 60;

	private final RemindJpaRepository remindRepository;
	private final RecordJpaRepository recordRepository;
	private final ExhibitionRepository exhibitionRepository;
	private final RemindAiSummarizer summarizer;
	private final RemindSummaryBackfill remindSummaryBackfill;
	/** 소환 대상 최소 경과 시간(정식 7d, 베타는 env로 단축 — 시작값 {@link RemindProperties}, 런타임 오버라이드 {@link RemindRuntimeConfig}). */
	private final RemindRuntimeConfig remindRuntimeConfig;

	/** 오늘의 소환 대상(경과 시간 충족, 아직 회고 안 한 내 기록) 1건. 없으면 null. */
	@Transactional(readOnly = true)
	public RemindResult.Candidate candidate(Long userId) {
		ZonedDateTime createdBefore = ZonedDateTime.now(AppTime.KST).minus(remindRuntimeConfig.eligibleAfter());
		List<Record> found = remindRepository.findRemindCandidates(userId, createdBefore, PageRequest.of(0, 1));
		if (found.isEmpty()) {
			return null;
		}
		Record record = found.get(0);
		int daysAgo = daysAgo(record.getCreatedAt());
		List<String> artistNames = exhibitionRepository.findArtistNames(record.getExhibitionId());
		String artist = artistNames.isEmpty() ? null : String.join(", ", artistNames);
		List<String> emotions = record.getEmotions().stream().map(RecordEmotion::getEmotionCode).toList();
		// "전시 속, 그 장면"(2단계)용 — 기록에 첨부한 첫 사진. 없으면 null(FE가 포스터로 폴백).
		String sceneImageUrl = record.getMedia().stream()
				.filter(m -> m.getType() == RecordMediaType.PHOTO)
				.sorted(Comparator.comparingInt(RecordMedia::getSortOrder))
				.map(RecordMedia::getUrl)
				.findFirst()
				.orElse(null);
		return new RemindResult.Candidate(record.getId(), daysAgo, elapsedLabel(daysAgo),
				record.getExhibitionId(), record.getExhibitionTitle(), artist, record.getExhibitionPosterUrl(),
				sceneImageUrl, record.getExhibitionPlace(), record.getExhibitionRegion(), record.getViewedAt(),
				record.getContent(), emotions);
	}

	/** 리마인드 저장 — 새 감정·소감. 감정 변화 AI 요약은 best-effort(실패/미설정이어도 저장 성공). */
	public RemindResult.Summary save(RemindCriteria.Save criteria) {
		Record record = recordRepository.findByIdWithEmotions(criteria.recordId())
				.orElseThrow(() -> new CoreException(RecordErrorCode.RECORD_NOT_FOUND));
		if (!Objects.equals(record.getUserId(), criteria.userId())) {
			throw new CoreException(RemindErrorCode.FORBIDDEN_REMIND);
		}
		List<String> afterEmotions = normalizeEmotions(criteria.emotionCodes());
		List<String> beforeEmotions = record.getEmotions().stream().map(RecordEmotion::getEmotionCode).toList();

		RemindExhibitionSnapshot snapshot = new RemindExhibitionSnapshot(record.getExhibitionId(),
				record.getExhibitionTitle(), record.getExhibitionPosterUrl(), record.getExhibitionPlace(),
				record.getViewedAt());
		// 감정 변화 AI 요약은 응답을 막지 않도록 저장 커밋 후 백그라운드에서 채운다(M-2). AI 미설정이면 요약 없이 바로 SKIPPED로 확정.
		RemindAiStatus initialStatus = summarizer.isEnabled() ? RemindAiStatus.PENDING : RemindAiStatus.SKIPPED;
		Remind saved = remindRepository.save(Remind.create(criteria.userId(), record.getId(), snapshot,
				criteria.reflection(), afterEmotions, null, initialStatus));
		if (initialStatus == RemindAiStatus.PENDING) {
			remindSummaryBackfill.schedule(saved.getId(), new RemindAiSummarizer.Context(
					criteria.userId(), record.getId(), record.getExhibitionTitle(), record.getContent(),
					beforeEmotions, criteria.reflection(), afterEmotions));
		}
		return toSummary(saved, afterEmotions, record.getContent(), beforeEmotions);
	}

	/** 리마인드 상세(감정 변화 요약). before(원본)는 라이브 조회 — 원본 삭제 시 null. */
	@Transactional(readOnly = true)
	public RemindResult.Summary get(Long userId, Long remindId) {
		Remind remind = remindRepository.findByIdAndDeletedAtIsNull(remindId)
				.orElseThrow(() -> new CoreException(RemindErrorCode.REMIND_NOT_FOUND));
		if (!Objects.equals(remind.getUserId(), userId)) {
			throw new CoreException(RemindErrorCode.FORBIDDEN_REMIND);
		}
		List<String> afterEmotions = remind.getEmotions().stream().map(RemindEmotion::getEmotionCode).toList();
		Record record = recordRepository.findByIdWithEmotions(remind.getRecordId()).orElse(null);
		String beforeContent = record == null ? null : record.getContent();
		List<String> beforeEmotions = record == null ? List.of()
				: record.getEmotions().stream().map(RecordEmotion::getEmotionCode).toList();
		return toSummary(remind, afterEmotions, beforeContent, beforeEmotions);
	}

	/** 아카이브 '리마인드' 목록(최신순). */
	@Transactional(readOnly = true)
	public Page<RemindResult.ListItem> list(Long userId, Pageable pageable) {
		return remindRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
				.map(this::toListItem);
	}

	private RemindResult.Summary toSummary(Remind remind, List<String> afterEmotions, String beforeContent,
			List<String> beforeEmotions) {
		return new RemindResult.Summary(remind.getId(), remind.getRecordId(), remind.getCreatedAt(),
				remind.getExhibitionId(), remind.getExhibitionTitle(), remind.getExhibitionPosterUrl(),
				remind.getExhibitionPlace(), remind.getRecordViewedAt(),
				beforeContent, beforeEmotions, remind.getReflection(), afterEmotions,
				remind.getAiStatus(), remind.getAiSummary());
	}

	private RemindResult.ListItem toListItem(Remind remind) {
		List<String> afterEmotions = remind.getEmotions().stream().map(RemindEmotion::getEmotionCode).toList();
		// 원본 기록 감정(FE 감정 변화-유지/반전 필터용) — 항목별 단건 조회라 N+1이지만
		// 페이지 20건·베타 규모라 허용. 원본이 삭제됐으면 빈 리스트.
		List<String> beforeEmotions = recordRepository.findByIdWithEmotions(remind.getRecordId())
				.map(record -> record.getEmotions().stream().map(RecordEmotion::getEmotionCode).toList())
				.orElse(List.of());
		return new RemindResult.ListItem(remind.getId(), remind.getRecordId(), remind.getCreatedAt(),
				remind.getExhibitionTitle(), remind.getExhibitionPosterUrl(), remind.getExhibitionPlace(),
				remind.getRecordViewedAt(), preview(remind.getReflection()), beforeEmotions, afterEmotions,
				remind.getAiStatus(), remind.getAiSummary() != null);
	}

	private List<String> normalizeEmotions(List<String> codes) {
		if (codes == null) {
			return List.of();
		}
		return codes.stream()
				.filter(code -> code != null && !code.isBlank())
				.map(String::trim)
				.distinct()
				.toList();
	}

	private String preview(String reflection) {
		if (reflection == null) {
			return null;
		}
		String trimmed = reflection.trim();
		return trimmed.length() > REFLECTION_PREVIEW_LENGTH
				? trimmed.substring(0, REFLECTION_PREVIEW_LENGTH) + "…" : trimmed;
	}

	private int daysAgo(ZonedDateTime createdAt) {
		LocalDate created = createdAt.withZoneSameInstant(AppTime.KST).toLocalDate();
		return (int) ChronoUnit.DAYS.between(created, LocalDate.now(AppTime.KST));
	}

	private String elapsedLabel(int daysAgo) {
		if (daysAgo == 0) {
			return "오늘"; // 베타(경과 단축) 등 당일 소환 시 "0일 전" 대신 자연스러운 표기
		}
		if (daysAgo < 7) {
			return daysAgo + "일 전";
		}
		if (daysAgo < 28) {
			return (daysAgo / 7) + "주일 전";
		}
		if (daysAgo < 365) {
			return (daysAgo / 30) + "개월 전";
		}
		return (daysAgo / 365) + "년 전";
	}
}
