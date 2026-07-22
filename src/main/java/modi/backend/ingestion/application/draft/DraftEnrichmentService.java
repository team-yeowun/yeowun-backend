package modi.backend.ingestion.application.draft;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import modi.backend.domain.exhibition.genre.GenreClassification;
import modi.backend.domain.exhibition.genre.GenreClassificationRequest;
import modi.backend.domain.exhibition.genre.GenreInstruction;
import modi.backend.domain.exhibition.genre.GenreKeyword;
import modi.backend.domain.exhibition.genre.GenreClassifier;
import modi.backend.domain.exhibition.genre.GenreResult;
import modi.backend.ingestion.domain.data.CultureDetailPayload;
import modi.backend.ingestion.domain.draft.DraftStep;
import modi.backend.ingestion.domain.port.ExhibitionCatalogClient;

/**
 * draft 스텝 처리의 <b>단일 진입점</b> — 흐름 "읽기"는 이 한 파일이다(ADR-12 가독화).
 * 각 스텝은 [① 상태 판정(tx) → ② 외부 호출(tx 밖) → ③ 반영(tx)] 3박자로 읽힌다. 스텝 순서는
 * {@link DraftStep}이 말한다: FETCH_DETAIL → CLASSIFY_GENRE → PROMOTE → NONE.
 *
 * <p>흐름 "실행"은 durable하다 — 여기는 인메모리 사가(한 메서드 try/catch 완주)가 아니라, 스텝별 아웃박스
 * 메시지를 각 처리기(enricher)가 집어 스텝 하나씩 전진시킨다(크래시 후 테이블에서 재개). 메시지 수명주기
 * (성공/실패 전이·낙관락 skip)는 호출한 처리기의 소관이라 여기선 예외를 그대로 전파한다.
 */
@Service
@RequiredArgsConstructor
public class DraftEnrichmentService {

	private final ExhibitionDraftFacade exhibitionDraftFacade;
	private final ExhibitionCatalogClient catalogClient;
	/** 장르 분류 전략(AI 체인/mock) — {@code app.exhibition.genre.classifier}로 선택된다(@Primary). */
	private final GenreClassifier genreClassifier;

	/**
	 * FETCH_DETAIL 스텝. 값 도착·무상세 확인 모두 스텝 해소이며, 반영 트랜잭션이 다음 스텝(CLASSIFY_GENRE)을
	 * 원자 enqueue한다(스텝 체인).
	 *
	 * @return false면 처리 대상이 아니다(상세 스텝이 이미 해소됐거나 draft 없음 — 판정은 호출부가 잇는다)
	 */
	public boolean resolveDetailStep(String externalId, LocalDateTime now) {
		if (!exhibitionDraftFacade.needsDetail(externalId)) {                            // ① 판정(tx)
			return false;
		}
		CultureDetailPayload detail = catalogClient.fetchDetail(externalId);       // ② 외부 호출(tx 밖)
		exhibitionDraftFacade.applyDetail(externalId, detail, now);                // ③ 반영(tx) — 스냅샷 적재 포함
		return true;
	}

	/**
	 * CLASSIFY_GENRE 스텝 — draft당 <b>개별 AI 호출</b>(사용자 확정, ADR-12). 분류 실패는 예외로 전파돼
	 * 메시지가 RETRYABLE로 남는다(ADR-11 — 시도 소진 없는 무기한 정책). 게이트를 채우면 반영 트랜잭션이
	 * EXHIBITION_READY를 원자 기록한다.
	 *
	 * @return false면 처리 대상이 아니다(장르 스텝이 이미 해소됐거나 draft 없음)
	 */
	public boolean classifyGenreStep(String externalId, LocalDateTime now) {
		Optional<GenreClassification> input = exhibitionDraftFacade.resolveGenreInput(externalId); // ① 판정(tx)
		if (input.isEmpty()) {
			return false;
		}
		GenreResult result = genreClassifier.classify(genreRequest(input.get())); // ② 외부 호출(tx 밖)
		exhibitionDraftFacade.applyGenre(externalId, result, now);                       // ③ 반영(tx)
		return true;
	}

	/**
	 * PROMOTE 스텝 — 유일하게 외부 호출이 없는 스텝(등록은 같은 DB). [판정 → 코어 등록(멱등) → draft 종료]가
	 * {@link ExhibitionDraftFacade#completePromotion} 한 트랜잭션이다.
	 */
	public void promoteStep(String externalId, LocalDateTime now) {
		exhibitionDraftFacade.completePromotion(externalId, now);
	}

	/** 이 유스케이스가 분류기에게 무엇을 시킬지 — 표준 지시 + 마스터 전체 허용. 요청 조립은 서비스의 결정이다. */
	private GenreClassificationRequest genreRequest(GenreClassification subject) {
		return new GenreClassificationRequest(
				GenreInstruction.STANDARD, GenreKeyword.all(), subject.toPromptText());
	}

}
