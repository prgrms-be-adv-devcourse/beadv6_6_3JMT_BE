package com.prompthub.settlement.infrastructure.batch.writer;

import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementWriter implements ItemWriter<Settlement> {

    private final SettlementRepository settlementRepository;

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        settlementRepository.saveAll(List.copyOf(chunk.getItems()));
    }
}
