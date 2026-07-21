# Kubernetes CD 복구 교착 방지 설계

## 배경

기존 애플리케이션 Deployment가 실패한 상태에서 이를 교정하는 매니페스트가 `develop`에 병합돼도, Kubernetes CD의 `최초 애플리케이션 리소스 준비` 단계가 모든 기존 Deployment의 rollout 완료를 먼저 기다린다. 이 사전 대기가 실패하면 실제 매니페스트 적용 단계에 도달하지 못해 장애를 고치는 변경 자체가 배포되지 않는 교착이 발생한다.

2026-07-21에는 `admin-service` 이미지가 Redis 환경변수 없이 기동 실패한 상태에서, 해당 환경변수를 추가한 PR #470의 매니페스트 적용이 dry-run 충돌로 실패했다. 충돌을 교정한 PR #474는 매니페스트 변경으로 분류되지 않아 기존 rollout 사전 대기에서 다시 중단됐다.

## 결정

`최초 애플리케이션 리소스 준비` 단계는 Deployment, Service와 정산 CronJob의 **존재만 보장**한다. 이미 존재하는 애플리케이션 Deployment의 정상 여부는 이 단계에서 검사하지 않는다.

실제 상태 변경과 검증은 기존 `애플리케이션 리소스와 이미지 배포, 실패 시 rollback` 단계가 계속 담당한다. 이 단계가 교정 매니페스트 또는 새 이미지를 먼저 적용한 후 `kubectl rollout status`를 실행하고, 실패하면 이번 실행이 변경한 Pod template만 rollback한다.

상태 저장 인프라와 Config/Discovery의 선행조건은 유지한다. PostgreSQL, Redis, Kafka는 Secret 사전 확인 단계에서 정상 rollout을 요구하고, 최초 생성된 Config와 Discovery는 의존 서비스 생성 전에 준비 완료를 기다린다.

## 대안

- 실패한 서비스만 rollout 검사에서 예외 처리: 현재 장애는 해소하지만 다음 서비스 장애에서 같은 교착이 재발하므로 제외한다.
- 운영자가 매번 수동 patch 후 CD 재실행: 긴급 복구 수단으로는 사용할 수 있지만 선언적 매니페스트의 자동 복구 경로를 보장하지 못하므로 기본 설계로 채택하지 않는다.

## 검증

- 정적 검증 스크립트가 최초 준비 단계에 애플리케이션 전체 rollout 대기가 다시 추가되면 실패해야 한다.
- 최초 준비 단계에는 리소스 존재 보장과 Config/Discovery 선행 대기만 남아야 한다.
- 본 배포 단계의 적용 후 rollout 검증과 rollback 계약은 유지돼야 한다.
- Kubernetes 아키텍처 문서에 교정 매니페스트가 기존 장애 상태보다 먼저 적용된다는 복구 순서를 명시한다.

## 운영 복구

현재 클러스터의 `admin-service`에는 이미 존재하는 `runtime-secret`의 `REDIS_HOST`, `REDIS_PORT` 참조를 반영해 정상화한다. 이후 PR의 CD를 재실행해 전체 자동 배포 경로를 검증한다.
