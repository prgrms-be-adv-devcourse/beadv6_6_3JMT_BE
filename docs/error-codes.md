# 에러 코드 명세

## 공통 (VALIDATION)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `INVALID_INPUT_VALUE` | V001 | 입력값 검증 실패 | 400 |
| `INTERNAL_SERVER_ERROR` | SYS001 | 서버 내부 오류가 발생했습니다. | 500 |
| `PRODUCT_SERVICE_UNAVAILABLE` | SYS002 | 상품 서비스를 사용할 수 없습니다. | 503 |

---

## 인증 / 회원 (AUTH) — user-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `AUTH_NOT_FOUND` | A001 | 사용자가 없습니다. | 404 |
| `AUTH_INVALID_PASSWORD` | A002 | 비밀번호가 일치하지 않습니다. | 401 |
| `AUTH_TOKEN_EXPIRED` | A003 | 토큰이 만료되었습니다. | 401 |
| `AUTH_FORBIDDEN` | A004 | 권한이 없습니다. | 403 |
| `AUTH_SELLER_ALREADY_APPLIED` | A005 | 이미 신청된 판매자입니다. | 409 |
| `AUTH_INVALID_REFRESH_TOKEN` | A006 | 리프레시 토큰이 유효하지 않습니다. | 401 |
| `AUTH_EMAIL_DUPLICATED` | A007 | 이미 사용 중인 이메일입니다. | 409 |
| `AUTH_SELLER_APPLICATION_NOT_FOUND` | A008 | 판매자 등록 신청 내역이 없습니다. | 404 |
| `UNSUPPORTED_OAUTH_PROVIDER` | A009 | 지원하지 않는 OAuth 공급자입니다. | 400 |
| `AUTH_WITHDRAW_ORDER_IN_PROGRESS` | A010 | 진행 중인 주문이 있어 탈퇴할 수 없습니다. | 400 |
| `AUTH_OAUTH_VERIFICATION_FAILED` | A011 | OAuth 인증에 실패했습니다. | 401 |
| `AUTH_REFRESH_TOKEN_REUSE_DETECTED` | A012 | 리프레시 토큰 재사용이 감지되어 모든 세션이 무효화되었습니다. | 401 |
| `AUTH_SESSION_INVALIDATED` | A013 | 세션이 무효화되었습니다. 다시 로그인해주세요. | 401 |
| `AUTH_REJOIN_TOKEN_INVALID` | A014 | 재가입 확인 정보가 유효하지 않거나 만료되었습니다. | 401 |

> 이 섹션은 `user-service`의 `UserErrorCode`(`global/exception`) 기준이다. order-service도 `A003`/`A004`
> 코드를 쓰지만 enum명·메시지가 다르다(`INVALID_AUTHENTICATION`/`FORBIDDEN`, 메시지도 다름) — order-service
> 값은 "주문/장바구니 - order-service 현재 구현" 섹션 참고.

---

## 상품 (PRODUCT) — product-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `PRODUCT_NOT_FOUND` | P001 | 상품이 존재하지 않습니다. | 404 |
| `PRODUCT_NOT_ON_SALE` | P002 | 판매 중인 상품이 아닙니다. | 400 |
| `PRODUCT_FORBIDDEN` | P003 | 본인의 상품만 수정/삭제할 수 있습니다. | 403 |
| `INVALID_PRODUCT_TYPE` | P004 | 올바르지 않은 상품 유형입니다. | 400 |
| `PRODUCT_INVALID_STATUS` | P006 | 현재 상태에서 처리할 수 없는 상품입니다. | 409 |
| `PRODUCT_TYPE_FIELD_MISMATCH` | P007 | 상품 유형에 맞지 않는 필드 구성입니다. | 400 |
| `INVALID_UPLOAD_FILE_TYPE` | P008 | 업로드할 수 없는 파일 형식입니다. | 400 |
| `S3_PRESIGN_FAILED` | S001 | 파일 업로드 URL 생성에 실패했습니다. | 500 |
| `S3_COPY_FAILED` | S002 | 파일 저장에 실패했습니다. | 500 |

---

## 찜 (WISHLIST) — user-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `WISHLIST_DUPLICATED` | W001 | 이미 찜한 상품입니다. | 409 |
| `WISHLIST_NOT_FOUND` | W002 | 찜 항목이 존재하지 않습니다. | 404 |
| `WISHLIST_FORBIDDEN` | W003 | 본인의 찜 항목이 아닙니다. | 403 |

---

## 셀러 정산 (SELLER SETTLEMENT) — user-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `SELLER_SETTLEMENT_NOT_FOUND` | SS001 | 정산 내역을 찾을 수 없습니다. | 404 |
| `SELLER_SETTLEMENT_ACCESS_DENIED` | SS002 | 본인 정산이 아닙니다. | 403 |
| `SELLER_SETTLEMENT_INVALID_STATE` | SS003 | 요청한 상태로 전이할 수 없습니다. | 409 |
| `SETTLEMENT_EVENT_DESERIALIZE_FAILED` | SS004 | 정산 이벤트 메시지 역직렬화에 실패했습니다. | 500 |
| `SETTLEMENT_EVENT_CONTRACT_VIOLATION` | SS005 | 정산 이벤트 계약 검증에 실패했습니다. | 500 |

---

## 장바구니 (CART)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `CART_ITEM_DUPLICATED` | C001 | 이미 장바구니에 담긴 상품입니다. | 409 |
| `CART_ITEM_FORBIDDEN` | C003 | 본인의 장바구니 항목이 아닙니다. | 403 |

---

## 주문/장바구니 - order-service 현재 구현

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `METHOD_NOT_ALLOWED` | V002 | 지원하지 않는 HTTP 메서드입니다. | 405 |
| `ORDER_IDEMPOTENCY_STORE_UNAVAILABLE` | SYS003 | 주문 중복 방지 저장소를 사용할 수 없습니다. | 503 |
| `PRODUCT_NOT_FOUND` | P001 | 상품을 찾을 수 없습니다. | 404 |
| `PRODUCT_REQUEST_INVALID` | P002 | 상품 요청이 올바르지 않습니다. | 400 |
| `PRODUCT_OPERATION_CONFLICT` | P003 | 상품 요청이 현재 상태와 충돌합니다. | 409 |
| `PRODUCT_SERVICE_UNAUTHENTICATED` | P004 | 상품 서비스 인증에 실패했습니다. | 401 |
| `PRODUCT_SERVICE_ACCESS_DENIED` | P005 | 상품 서비스 접근 권한이 없습니다. | 403 |
| `ORDER_NOT_FOUND` | O001 | 주문을 찾을 수 없습니다. | 404 |
| `ORDER_CANCEL_NOT_ALLOWED` | O002 | 취소할 수 없는 주문 상태입니다. | 400 |
| `PRODUCT_NOT_ON_SALE` | O003 | 판매 중이 아닌 상품입니다. | 400 |
| `CART_EMPTY` | O004 | 장바구니가 비어 있습니다. | 400 |
| `CART_NOT_FOUND` | O005 | 장바구니를 찾을 수 없습니다. | 404 |
| `CART_PRODUCT_NOT_FOUND` | O006 | 장바구니 상품을 찾을 수 없습니다. | 404 |
| `CART_PRODUCT_ACCESS_DENIED` | O007 | 해당 장바구니 상품에 접근할 수 없습니다. | 403 |
| `ORDER_ACCESS_DENIED` | O008 | 해당 주문에 접근할 수 없습니다. | 403 |
| `INVALID_ORDER_STATUS_TRANSITION` | O009 | 허용되지 않는 주문 상태 변경입니다. | 400 |
| `ORDER_ALREADY_PROCESSED` | O010 | 이미 처리된 주문입니다. | 409 |
| `ORDER_PRICE_CHANGED` | O011 | 상품 가격이 변경되었습니다. | 409 |
| `ORDER_PRODUCT_NOT_FOUND` | O012 | 주문 상품을 찾을 수 없습니다. | 404 |
| `ORDER_PAYMENT_STATUS_INVALID` | O013 | 결제 완료 처리할 수 없는 주문 상태입니다. | 400 |
| `ORDER_PAYMENT_AMOUNT_MISMATCH` | O014 | 주문 금액과 결제 승인 금액이 일치하지 않습니다. | 400 |
| `SELF_PURCHASE_NOT_ALLOWED` | O015 | 본인이 판매하는 상품은 구매할 수 없습니다. | 403 |
| `ORDER_REFUND_AMOUNT_MISMATCH` | O016 | 주문 상품 금액과 환불 금액이 일치하지 않습니다. | 400 |
| `ORDER_REFUND_NOT_ALLOWED` | O017 | 환불할 수 없는 주문 또는 주문 상품 상태입니다. | 409 |
| `ORDER_PRODUCT_ALREADY_OWNED` | O018 | 이미 구매했거나 결제 대기 중인 상품입니다. | 409 |
| `ORDER_REFUND_REQUEST_NOT_FOUND` | O019 | 처리 중인 환불 요청을 찾을 수 없습니다. | 404 |
| `ORDER_CONTENT_ACCESS_DENIED` | E001 | 구매 콘텐츠를 열람할 수 없습니다. | 403 |
| `ORDER_REVIEW_ACCESS_DENIED` | E002 | 구매한 상품에만 리뷰를 작성할 수 있습니다. | 403 |
| `EVENT_PAYLOAD_MAPPING_ERROR` | E003 | 이벤트 페이로드 매핑에 실패했습니다. | 500 |

> `O003`은 현재 order-service에서 `PRODUCT_NOT_ON_SALE`로 사용한다. 이전 문서의 `ORDER_FORBIDDEN` 의미와 다르므로 API 문서에서는 현재 구현을 우선한다.
> `ORDER_EXPIRED`는 `ErrorCode`가 아니라 Kafka `OrderEventType.ORDER_EXPIRED`(이벤트 타입)다. 에러 코드가 아니므로 이 표에는 포함하지 않는다.
---

## 결제 (PAYMENT)

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `INVALID_INPUT` | V001 | 입력값이 올바르지 않습니다. | 400 |
| `DUPLICATE_PAYMENT` | PAY002 | 이미 결제된 주문입니다. | 409 |
| `PG_INVALID_REQUEST` | PAY003 | 잘못된 API 요청으로 인한 PG사 오류입니다. | 502 |
| `REFUND_NOT_ALLOWED` | PAY004 | 환불 가능한 상태가 아닙니다. | 400 |
| `PAYMENT_NOT_FOUND` | PAY005 | 결제 건을 찾을 수 없습니다. | 404 |
| `UNAUTHORIZED_REFUND` | PAY006 | 본인 결제 건만 환불할 수 있습니다. | 403 |
| `ORDER_NOT_FOUND` | PAY008 | 주문 정보를 찾을 수 없습니다. | 404 |
| `ORDER_INFO_UNAVAILABLE` | PAY009 | 주문 정보를 확보할 수 없습니다. | 503 |
| `NOT_ORDER_OWNER` | PAY010 | 본인 주문만 결제할 수 있습니다. | 403 |
| `PG_UNAVAILABLE` | PAY011 | PG사 서비스에 일시적으로 연결할 수 없습니다. | 503 |
| `AMOUNT_MISMATCH` | PAY012 | 결제 금액이 주문 금액과 일치하지 않습니다. | 400 |
| `PG_BUSY` | PAY013 | 결제 승인 요청이 많아 일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해주세요. | 503 |
| `PG_RATE_LIMITED` | PAY014 | 결제 승인 요청이 많아 일시적으로 제한되었습니다. 잠시 후 다시 시도해주세요. | 503 |
| `PG_SERVER_ERROR` | PAY_PG_5XX | PG사 서버 오류가 발생했습니다. | 502 |
| `PAYMENT_FAILED` | PAY_FAILED | PG사 결제가 실패했습니다. | 422 |

> PAY003 (502): 잘못된 요청으로 인한 PG 오류 / PAY_PG_5XX (502): PG사 서버 오류 / PAY_FAILED (422): PG사 결제 실패(정상 처리된 거부)
> `INVALID_INPUT`(V001)은 공통 입력 검증 코드를 결제에서도 사용한다.

---

## 정산 (SETTLEMENT) — settlement-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `SETTLEMENT_BATCH_NOT_FOUND` | S-001 | 정산 배치를 찾을 수 없습니다. | 404 |
| `SETTLEMENT_JOB_EXECUTION_FAILED` | S-002 | 정산 배치 잡 실행에 실패했습니다. | 500 |
| `INVALID_INPUT_VALUE` | S-003 | 요청 값이 올바르지 않습니다. | 400 |
| `INTERNAL_SERVER_ERROR` | S-004 | 예상하지 못한 서버 오류가 발생했습니다. | 500 |
| `UNAUTHENTICATED` | S-005 | 인증 정보가 없습니다. | 401 |
| `FORBIDDEN` | S-006 | 접근 권한이 없습니다. | 403 |
| `SETTLEMENT_BATCH_INVALID_STATE` | S-007 | 정산 배치가 처리 중 상태가 아닙니다. | 409 |
| `SETTLEMENT_JOB_NOT_FOUND` | S-008 | 정산 배치 잡 실행 이력을 찾을 수 없습니다. | 404 |
| `SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED` | S-009 | 이미 정산에 포함된 소스 라인입니다. | 409 |
| `SETTLEMENT_EVENT_PUBLISH_FAILED` | S-015 | 정산 이벤트 발행에 실패했습니다. | 500 |
| `SETTLEMENT_SOURCE_QUERY_FAILED` | S-017 | 정산 대상 라인 조회에 실패했습니다. | 500 |
| `OUTBOX_EVENT_SERIALIZE_FAILED` | S-018 | 정산 아웃박스 이벤트 직렬화에 실패했습니다. | 500 |
| `OUTBOX_EVENT_NOT_FOUND` | S-019 | 정산 아웃박스 이벤트를 찾을 수 없습니다. | 404 |
| `SETTLEMENT_BATCH_JOB_INSTANCE_NOT_LINKED` | S-020 | 정산 배치와 잡 실행 이력이 연결되지 않았습니다. | 409 |
| `SETTLEMENT_JOB_NOT_RESTARTABLE` | S-021 | 정산 배치 잡을 재시작할 수 없는 상태입니다. | 409 |
| `SETTLEMENT_JOB_BATCH_MISMATCH` | S-022 | 정산 배치와 잡 실행 이력이 일치하지 않습니다. | 409 |

> 코드는 `S-001`처럼 하이픈을 포함한다(다른 서비스의 `S001` 형식과 다름). `S-010`~`S-014`, `S-016`은 현재 미사용 결번이다.

---

## 관리자 (ADMIN) — admin-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `INVALID_INPUT_VALUE` | A-001 | 요청 값이 올바르지 않습니다. | 400 |
| `INTERNAL_SERVER_ERROR` | A-002 | 서버 내부 오류가 발생했습니다. | 500 |
| `SETTLEMENT_NOT_FOUND` | A-003 | 정산을 찾을 수 없습니다. | 404 |
| `SETTLEMENT_INVALID_STATE` | A-004 | 현재 상태에서 변경할 수 없는 정산입니다. | 409 |
| `SETTLEMENT_ALREADY_PAID` | A-005 | 이미 지급 완료된 정산은 취소할 수 없습니다. | 409 |
| `SETTLEMENT_ALREADY_CANCELLED` | A-006 | 이미 취소된 정산입니다. | 409 |
| `USER_NOT_FOUND` | A-007 | 사용자를 찾을 수 없습니다. | 404 |
| `SELLER_REGISTER_NOT_FOUND` | A-008 | 판매자 등록 신청 내역이 없습니다. | 404 |
| `PRODUCT_NOT_FOUND` | A-009 | 상품을 찾을 수 없습니다. | 404 |
| `PRODUCT_INVALID_STATUS` | A-010 | 현재 상태에서 수행할 수 없는 작업입니다. | 409 |

> 코드는 `A-001`처럼 하이픈을 포함한다. AUTH 섹션의 `A001`(하이픈 없음, user-service)과는 다른 체계다.

---

## 알림 (NOTIFICATION) — notification-service

| enum | code | 의미 (message) | HTTP |
|------|------|----------------|------|
| `NOTIFICATION_NOT_FOUND` | N001 | 알림을 찾을 수 없습니다. | 404 |
| `SSE_CONNECTION_LIMIT_EXCEEDED` | N002 | 알림 스트림 연결 한도를 초과했습니다. | 429 |
| `NOTIFICATION_SETTING_NOT_CONFIGURABLE` | N003 | 변경할 수 없는 알림 카테고리입니다. | 400 |

---

## AI 정산 (AI)

| enum/code | 의미 (message) | HTTP |
|------|------|------|
| `INVALID_CHAT_MESSAGE` | 질문은 1자 이상 2,000자 이하여야 합니다. | 400 |
| `AI_RUN_NOT_FOUND` | AI 실행을 찾을 수 없습니다. | 404 |
| `RUN_IN_PROGRESS` | 이미 실행 중인 질문이 있습니다. | 409 |
| `AI_CAPACITY_EXCEEDED` | AI 요청이 많습니다. 잠시 후 다시 시도해 주세요. | 429 |
| `AI_CHAT_DISABLED` | AI 정산 서비스가 현재 비활성화되어 있습니다. | 503 |
| `AI_STATE_UNAVAILABLE` | AI 정산 상태를 처리할 수 없습니다. | 503 |
| `SETTLEMENT_DATA_UNAVAILABLE` | 정산 데이터를 조회할 수 없습니다. | 503 |
| `AI_PROVIDER_UNAVAILABLE` | AI 답변을 생성할 수 없습니다. | 503 |
| `AI_RESPONSE_POLICY_VIOLATION` | AI 답변을 생성할 수 없습니다. | 503 |
| `TOOL_LOOP_LIMIT_EXCEEDED` | 정확한 답변에 필요한 조회 횟수를 초과했습니다. | 500 |
| `RUN_TIMEOUT` | AI 답변 생성 시간을 초과했습니다. | 504 |
| `AI_INTERNAL_ERROR` | 예상하지 못한 AI 서비스 오류가 발생했습니다. | 500 |
