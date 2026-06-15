## 개발 환경

### Language
![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)

### Framework
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1.0-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![Spring Batch](https://img.shields.io/badge/Spring_Batch-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-6DB33F?style=for-the-badge&logo=spring&logoColor=white)

### Persistence
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![QueryDSL](https://img.shields.io/badge/QueryDSL-0769AD?style=for-the-badge&logo=java&logoColor=white)

### Database
![PostgreSQL](https://img.shields.io/badge/postgresql-%23316192.svg?style=for-the-badge&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/redis-%23DD0031.svg?style=for-the-badge&logo=redis&logoColor=white)

### Infra & Messaging
![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-000?style=for-the-badge&logo=apachekafka)
![AWS EC2](https://img.shields.io/badge/AWS_EC2-FF9900?style=for-the-badge&logo=amazonaws&logoColor=white)
![Amazon S3](https://img.shields.io/badge/Amazon_S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white)

### AI
![OpenAI](https://img.shields.io/badge/OpenAI-74aa9c?style=for-the-badge&logo=openai&logoColor=white)
![Claude](https://img.shields.io/badge/Claude-D97757?style=for-the-badge&logo=claude&logoColor=white)

### DevOps & CI/CD
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)

<hr>

## 1. 문서 개요

### 1.1 목적

본 문서는 PromptHub 서비스가 **무엇을, 왜, 어떤 범위 안에서** 제공하는지를 정의한다. 도메인 모델·유스케이스·용어 사전을 하나로 묶어, 이후 설계서(API 설계, ERD)와 테스트 케이스가 참조할 수 있는 **단일 기준 문서**를 목표로 한다.

### 1.2 작성 범위

- 포함: 서비스 개요, 개발 범위, 용어 사전, 액터·권한, 도메인 구성, 기능 요구사항(MVP 전체 + 확장 개요), 유저 시나리오
- 골격만 포함(추후 회의에서 확정): 비기능 요구사항, 기술 스택, AWS 인프라 구성 → 해당 섹션은 `TODO`로 표시
- 추적성: 기능 요구사항은 유스케이스 ID(`UC-*`)를 그대로 재사용해 설계·테스트와 연결한다.