package com.prompthub.user.global.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "V001", "입력값이 올바르지 않습니다."),
    AUTH_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "사용자가 없습니다."),
    AUTH_INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "A002", "비밀번호가 일치하지 않습니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "A003", "토큰이 만료되었습니다."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "A004", "권한이 없습니다."),
    AUTH_SELLER_ALREADY_APPLIED(HttpStatus.CONFLICT, "A005", "이미 신청된 판매자입니다."),
    AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "A006", "리프레시 토큰이 유효하지 않습니다."),
    AUTH_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "A007", "이미 사용 중인 이메일입니다."),
    AUTH_SELLER_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "A008", "판매자 등록 신청 내역이 없습니다."),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "A009", "지원하지 않는 OAuth 공급자입니다."),
    WISHLIST_DUPLICATED(HttpStatus.CONFLICT, "W001", "이미 찜한 상품입니다."),
    WISHLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "W002", "찜 항목이 존재하지 않습니다."),
    WISHLIST_FORBIDDEN(HttpStatus.FORBIDDEN, "W003", "본인의 찜 항목이 아닙니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS001", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public HttpStatus getStatus() {
        return httpStatus;
    }
}
