package com.prompthub.settlement.infrastructure.transaction;

import com.prompthub.settlement.application.port.RequiresNewTransactionExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringRequiresNewTransactionExecutor implements RequiresNewTransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public SpringRequiresNewTransactionExecutor(
            PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void execute(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }
}
