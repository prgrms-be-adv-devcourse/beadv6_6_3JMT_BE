# pgvector를 포함한 Postgres 이미지.
#
# 공식 pgvector/pgvector 이미지(Debian) 대신 alpine에 직접 컴파일한다 — 현재 운영 이미지가
# alpine(musl)이라, Debian(glibc)으로 바꾸면 텍스트 컬럼 인덱스의 정렬 규칙이 달라져
# 기존 인덱스가 조용히 잘못된 결과를 낼 수 있다. Postgres는 전 서비스가 공유하므로
# 그 위험을 감수할 이유가 없다.
FROM postgres:18.4-alpine

ARG PGVECTOR_VERSION=v0.8.5

# with_llvm=no — 이 이미지의 postgres는 llvm21로 빌드됐는데(pg_config --configure),
# alpine 3.24 저장소에는 llvm20까지만 있어 같은 버전을 맞출 수 없다. 확장을 JIT 비트코드
# 없이 빌드하면 clang/llvm 자체가 필요 없어진다. JIT 인라이닝은 부가 최적화라
# pgvector 동작에는 영향이 없다.
#
# 서버 헤더와 pgxs는 이 이미지에 이미 들어 있어 별도 dev 패키지가 필요 없다.
RUN apk add --no-cache --virtual .pgvector-build git build-base \
 && git clone --branch "${PGVECTOR_VERSION}" --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
 && make -C /tmp/pgvector with_llvm=no \
 && make -C /tmp/pgvector with_llvm=no install \
 && rm -rf /tmp/pgvector \
 && apk del .pgvector-build
