package com.prompthub.settlement.application.usecase.delivery;

public interface RequiresNewTransactionExecutor {

    void execute(Runnable action);
}
