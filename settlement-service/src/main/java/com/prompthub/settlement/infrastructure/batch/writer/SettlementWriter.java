package com.prompthub.settlement.infrastructure.batch.writer;

import com.prompthub.settlement.domain.model.Settlement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SettlementWriter implements ItemWriter<Settlement> {

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        // 정산 저장과 소스 라인 정산 연결(markSettled)은 calculate 유스케이스(@Transactional)에서 완료된다.
        // 이 writer 는 chunk 형식 충족 및 처리 건수 로깅만 담당한다.
        log.info("정산 처리 완료: {}건", chunk.size());
    }
}
