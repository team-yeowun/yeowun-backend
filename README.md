# 시스템 아키텍처
<img width="1239" height="512" alt="여운 시스템 아키텍처" src="https://github.com/user-attachments/assets/b50bf02e-87c5-4988-8669-446148fc235a" />


<div align="center">

# 여운 (Yeowun)

**AI 기반 전시 감상 기록·회고 서비스**

전시가 끝난 후에도, 당신만의 여운을 이어가세요.

<img width="600" alt="여운 소개" src="https://github.com/user-attachments/assets/80e11f5d-8199-4c57-b431-172808157a42" />

<br />

[**서비스 바로가기**](https://yeowun.vercel.app/)

</div>

<br />

## 서비스 소개
<img width="895" height="503" alt="image" src="https://github.com/user-attachments/assets/4b562c17-2d53-4541-bb2c-bbf3323aa799" />

전시의 감동은 시간이 지나면 희미해집니다. 사진은 남아 있어도, 그 작품이 왜 좋았는지는 사라집니다.

**여운**은 전시 관람 후의 감상을 직접 기록하거나 AI의 도움으로 정리하고, 시간이 지난 뒤 다시 꺼내볼 수 있게 돕는 서비스입니다.

| | |
| --- | --- |
| **기록의 부담을 줄입니다** | AI가 던지는 질문에 답하기만 하면 감상이 정리됩니다 |
| **잊었던 감정을 다시 만납니다** | 리마인드를 통해 과거의 기록을 다시 마주합니다 |
| **취향이 쌓입니다** | 반복되는 회고 속에서 전시는 추억을 넘어 나만의 취향이 됩니다 |

<br />

## 주요 기능

### 1. 로그인 및 전시 탐색

소셜 로그인 후, 진행 중인 전시 목록에서 관심 있는 전시를 찾아봅니다.

<img alt="로그인 및 전시 목록" src="https://github.com/user-attachments/assets/77d24a00-e617-487c-9bef-a193ac42f77e" />

### 2. 여운 남기기

`관람 정보 기록` → `AI 질문에 답변` → `AI 기반 감상문 생성·저장` → `상세 확인`

빈 화면 앞에서 막막해지지 않도록, AI가 던지는 질문에 답하는 방식으로 감상을 끌어냅니다.

<img alt="여운 작성 플로우" src="https://github.com/user-attachments/assets/02bc1b6b-a124-4382-b335-5493fcd2cacf" />

### 3. 리마인드 남기기

시간이 흐른 뒤 같은 전시를 다시 떠올려 기록하고, **감정 변화 타임라인**으로 그 차이를 확인합니다.

<img alt="리마인드 및 감정 타임라인" src="https://github.com/user-attachments/assets/69eaeac1-73db-4bfe-ade3-c94556a58eab" />

### 4. 기록 모아보기 & 프로필 관리

작성한 여운과 리마인드를 한곳에서 조회하고, 프로필 조회·로그아웃·회원 탈퇴를 지원합니다.

<table>
  <tr>
    <td width="50%"><img alt="기록 목록 조회" src="https://github.com/user-attachments/assets/f74f4f22-9657-4877-8e0d-ed56d5033f8a" /></td>
    <td width="50%"><img alt="프로필" src="https://github.com/user-attachments/assets/ab02a679-608a-4ba3-b6dc-378c5c9d3c27" /></td>
  </tr>
  <tr>
    <td align="center"><b>기록 목록</b></td>
    <td align="center"><b>프로필</b></td>
  </tr>
</table>

<br />

## 프로젝트 개요

<table>
  <tr>
    <td width="110"><b>기간</b></td>
    <td>2026.06.11 ~ 2026.08.11</td>
  </tr>
  <tr>
    <td><b>팀 구성</b></td>
    <td>총 5명 · PM 1 / Designer 1 / Frontend 1 / Backend 2</td>
  </tr>
  <tr>
    <td valign="top"><b>팀</b></td>
    <td>
      <table>
        <tr>
          <td align="center" width="150">
            <a href="https://github.com/plan11plan">
              <img src="https://github.com/plan11plan.png?size=200" width="80" height="80" alt="plan11plan" /><br />
              <b>plan11plan</b>
            </a><br />Backend
          </td>
          <td align="center" width="150">
            <a href="https://github.com/SeoBYP">
              <img src="https://github.com/SeoBYP.png?size=200" width="80" height="80" alt="SeoBYP" /><br />
              <b>SeoBYP</b>
            </a><br />Backend
          </td>
          <td align="center" width="150">
            <a href="https://github.com/immyeonn">
              <img src="https://github.com/immyeonn.png?size=200" width="80" height="80" alt="immyeonn" /><br />
              <b>immyeonn</b>
            </a><br />Frontend
          </td>
        </tr>
      </table>
    </td>
  </tr>
</table>

---

## Docker 실행

```bash
git pull origin develop
docker compose --profile app up -d --build   # 띄우기
docker compose --profile app down            # 끄기
```

- API: http://localhost:18090
- Swagger: http://localhost:18090/swagger-ui/index.html
- 헬스체크: http://localhost:18090/actuator/health

---
