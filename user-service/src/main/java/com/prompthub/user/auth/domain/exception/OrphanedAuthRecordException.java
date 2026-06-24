package com.prompthub.user.auth.domain.exception;

public class OrphanedAuthRecordException extends RuntimeException {

    public OrphanedAuthRecordException(String userId) {
        super("auth 레코드에 연결된 user를 찾을 수 없습니다. userId=" + userId);
    }
}
