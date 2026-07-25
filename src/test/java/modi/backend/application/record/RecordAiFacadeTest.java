package modi.backend.application.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import modi.backend.application.exhibition.ExhibitionFacade;
import modi.backend.application.exhibition.ExhibitionResult;
import modi.backend.domain.ai.AiChatClient;
import modi.backend.domain.ai.AiDraft;
import modi.backend.domain.ai.AiDraftStore;
import modi.backend.infra.record.RecordJpaRepository;
import modi.backend.support.error.CoreException;

@ExtendWith(MockitoExtension.class)
class RecordAiFacadeTest {

	@Mock
	AiChatClient aiChatClient;

	@Mock
	ExhibitionFacade exhibitionFacade;

	@Mock
	AiRateLimiter aiRateLimiter;

	@Mock
	AiDraftStore aiDraftStore;

	@Mock
	RecordJpaRepository recordRepository;

	@InjectMocks
	RecordAiFacade facade;

	@Test
	@DisplayName("questions — 구조화 출력(3개 필드)을 질문 목록으로 변환한다")
	void questions_구조화출력() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		given(aiChatClient.completeStructured(anyString(), anyString(), eq(AiQuestionsOutput.class)))
				.willReturn(new AiQuestionsOutput("가장 오래 남은 장면은?", "어떤 감정이었나요?", "한 문장으로 남긴다면?"));

		RecordAiResult.Questions result = facade.questions(new RecordAiCriteria.Questions(1L, 10L));

		assertThat(result.questions()).containsExactly(
				"가장 오래 남은 장면은?", "어떤 감정이었나요?", "한 문장으로 남긴다면?");
	}

	@Test
	@DisplayName("questions — 빈 필드는 걸러내고, 모두 비면 실패한다")
	void questions_모두빈값_실패() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		given(aiChatClient.completeStructured(anyString(), anyString(), eq(AiQuestionsOutput.class)))
				.willReturn(new AiQuestionsOutput(" ", "", null));

		assertThatThrownBy(() -> facade.questions(new RecordAiCriteria.Questions(1L, 10L)))
				.isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("questions — 과거 관람객 감정 집계를 프롬프트 근거로 넣는다(그라운딩)")
	void questions_그라운딩_감정포함() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		given(recordRepository.findTopEmotionCodesByExhibitionId(eq(10L), any()))
				.willReturn(List.of("먹먹함", "벅참"));
		ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
		given(aiChatClient.completeStructured(anyString(), userPrompt.capture(), eq(AiQuestionsOutput.class)))
				.willReturn(new AiQuestionsOutput("q1", "q2", "q3"));

		facade.questions(new RecordAiCriteria.Questions(1L, 10L));

		assertThat(userPrompt.getValue()).contains("먹먹함", "벅참", "이 전시를 기록한 사람들");
	}

	@Test
	@DisplayName("questions — 생성한 질문을 draft로 캐싱한다(뒤로가기 복원용)")
	void questions_draft캐싱() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		given(aiChatClient.completeStructured(anyString(), anyString(), eq(AiQuestionsOutput.class)))
				.willReturn(new AiQuestionsOutput("q1", "q2", "q3"));

		facade.questions(new RecordAiCriteria.Questions(1L, 10L));

		verify(aiDraftStore).save(eq(1L), eq(10L), any(AiDraft.class));
	}

	@Test
	@DisplayName("compose — Q&A로 감상문 본문을 생성하고 300자로 자른다")
	void compose_본문생성_및_클램프() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		String longEssay = "가".repeat(400);
		given(aiChatClient.complete(anyString(), anyString())).willReturn(longEssay);

		RecordAiResult.Compose result = facade.compose(new RecordAiCriteria.Compose(
				1L, 10L, List.of(new RecordAiCriteria.QnaPair("q", "a"))));

		assertThat(result.content()).hasSize(300);
	}

	@Test
	@DisplayName("compose — 질문+답변+초안을 draft로 캐싱한다")
	void compose_draft캐싱() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		given(aiChatClient.complete(anyString(), anyString())).willReturn("감상문");

		facade.compose(new RecordAiCriteria.Compose(1L, 10L, List.of(new RecordAiCriteria.QnaPair("q", "a"))));

		verify(aiDraftStore).save(eq(1L), eq(10L), any(AiDraft.class));
	}

	@Test
	@DisplayName("compose — 답변이 비면 AI_GENERATION_FAILED")
	void compose_빈답변_실패() {
		assertThatThrownBy(() -> facade.compose(new RecordAiCriteria.Compose(1L, 10L, List.of())))
				.isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("composeStream — 델타를 순차로 흘리고, 이어붙인 전체 본문을 draft로 저장한다")
	void composeStream_델타_및_draft캐싱() {
		given(exhibitionFacade.getForSnapshot(any(), any())).willReturn(detail());
		willAnswer(invocation -> {
			Consumer<String> onDelta = invocation.getArgument(2);
			onDelta.accept("전시장을 ");
			onDelta.accept("천천히 걸었다.");
			return null;
		}).given(aiChatClient).completeStream(anyString(), anyString(), any());

		List<String> received = new ArrayList<>();
		facade.composeStream(new RecordAiCriteria.Compose(
				1L, 10L, List.of(new RecordAiCriteria.QnaPair("q", "a"))), received::add);

		assertThat(received).containsExactly("전시장을 ", "천천히 걸었다.");
		ArgumentCaptor<AiDraft> draft = ArgumentCaptor.forClass(AiDraft.class);
		verify(aiDraftStore).save(eq(1L), eq(10L), draft.capture());
		assertThat(draft.getValue().content()).isEqualTo("전시장을 천천히 걸었다.");
	}

	@Test
	@DisplayName("composeStream — 답변이 비면 스트림 시작 없이 AI_GENERATION_FAILED")
	void composeStream_빈답변_실패() {
		assertThatThrownBy(() -> facade.composeStream(
				new RecordAiCriteria.Compose(1L, 10L, List.of()), delta -> {
				}))
				.isInstanceOf(CoreException.class);
	}

	@Test
	@DisplayName("getDraft — 캐시에 있으면 exists=true로 질문·답변·초안을 복원한다")
	void getDraft_있음() {
		given(aiDraftStore.find(1L, 10L)).willReturn(Optional.of(
				new AiDraft(List.of("q1"), List.of(new AiDraft.Qna("q1", "a1")), "초안")));

		RecordAiResult.Draft draft = facade.getDraft(1L, 10L);

		assertThat(draft.exists()).isTrue();
		assertThat(draft.questions()).containsExactly("q1");
		assertThat(draft.answers()).containsExactly(new RecordAiCriteria.QnaPair("q1", "a1"));
		assertThat(draft.content()).isEqualTo("초안");
	}

	@Test
	@DisplayName("getDraft — 캐시에 없으면 exists=false")
	void getDraft_없음() {
		given(aiDraftStore.find(1L, 10L)).willReturn(Optional.empty());

		RecordAiResult.Draft draft = facade.getDraft(1L, 10L);

		assertThat(draft.exists()).isFalse();
		assertThat(draft.questions()).isEmpty();
	}

	@Test
	@DisplayName("saveDraft — 진행 상태를 그대로 캐시에 저장 위임한다")
	void saveDraft_위임() {
		facade.saveDraft(new RecordAiCriteria.DraftSave(1L, 10L, List.of("q1"),
				List.of(new RecordAiCriteria.QnaPair("q1", "a1")), "초안"));

		verify(aiDraftStore).save(eq(1L), eq(10L), any(AiDraft.class));
	}

	@Test
	@DisplayName("deleteDraft — 캐시 삭제를 위임한다")
	void deleteDraft_위임() {
		facade.deleteDraft(1L, 10L);

		verify(aiDraftStore).delete(1L, 10L);
	}

	private ExhibitionResult.Detail detail() {
		return new ExhibitionResult.Detail(10L, "CATALOG", "모네: 빛을 그리다", "http://p",
				LocalDate.now(), null, "예술의전당", "SEOUL", "PAINTING", null,
				"인상주의 대표전", null, null, List.of("클로드 모네"), List.of("인상주의"),
				null, null, null, null, null, null, null, 0L, null, null,
				null, false, false, false);
	}
}
