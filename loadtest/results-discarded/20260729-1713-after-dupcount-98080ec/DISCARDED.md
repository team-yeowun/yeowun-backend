# 폐기 — 20260729-1713-after-dupcount-98080ec

## 사유
run.sh의 start_app이 띄운 새 JVM이 **포트 8080 바인드 실패로 즉시 죽었다**
(app.log: `Web server failed to start. Port 8080 was already in use.`).
헬스체크는 죽지 않고 남아 있던 **이전 앱 프로세스(PID 6922, 14:05 기동)** 에 응답했고,
run.sh는 "앱 준비 완료(1s)"로 오판한 뒤 그 옛 프로세스를 상대로 k6를 돌렸다.

즉 이 런의 숫자는 **17:12에 빌드한 jar가 아니라 14:05에 기동된 옛 프로세스**의 것이다.

## 직접 원인
/tmp/loadtest-app.pid에 남아 있던 PID가 실제 8080 점유 프로세스와 달라
stop_app이 엉뚱한 PID를 죽였다(고아 프로세스).

## 조치
- PID 6922 수동 종료, /tmp/loadtest-app.pid 삭제, 8080 free 확인 후 재실행
- 하네스는 손대지 않았다(고치면 이전 결과와 비교 불가)
