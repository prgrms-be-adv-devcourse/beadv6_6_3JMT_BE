package com.prompthub.apigateway.client;

/** authorize() 타임아웃/5xx/네트워크 오류 — API 자체 장애, fail-closed. */
public class AuthorizeUnavailableException extends RuntimeException {

    public AuthorizeUnavailableException(Throwable cause) {
        super(cause);
    }
}
