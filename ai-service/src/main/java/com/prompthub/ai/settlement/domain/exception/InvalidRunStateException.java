package com.prompthub.ai.settlement.domain.exception;

import com.prompthub.ai.settlement.domain.run.RunStatus;

public class InvalidRunStateException extends RuntimeException {

    public InvalidRunStateException(RunStatus currentStatus) {
        super("RUNNING run만 상태를 변경할 수 있습니다. current=" + currentStatus);
    }
}
