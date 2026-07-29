package com.prompthub.settlement.application.port;

public interface RequiresNewTransactionExecutor {

    void execute(Runnable action);
}
