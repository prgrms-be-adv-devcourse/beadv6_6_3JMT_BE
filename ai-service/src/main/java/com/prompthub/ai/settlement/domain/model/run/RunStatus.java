package com.prompthub.ai.settlement.domain.model.run;

public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
