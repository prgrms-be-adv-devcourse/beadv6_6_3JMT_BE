package com.prompthub.user.sellersettlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SellerSettlementRepositoryAdapterTest {

    @Mock
    private SellerSettlementJpaRepository jpaRepository;

    @InjectMocks
    private SellerSettlementRepositoryAdapter adapter;

    @Test
    void existsBySettlementId_JpaRepository에_위임() {
        UUID settlementId = UUID.randomUUID();
        given(jpaRepository.existsBySettlementId(settlementId)).willReturn(true);

        assertThat(adapter.existsBySettlementId(settlementId)).isTrue();
    }

    @Test
    void findPageBySeller_기간을_월경계로_변환해_조회() {
        UUID sellerId = UUID.randomUUID();
        given(jpaRepository.findPageBySeller(
                eq(sellerId), eq(SettlementDisplayStatus.WAITING),
                eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        SellerSettlementRepository.SellerSettlementPage page = adapter.findPageBySeller(
                sellerId, SettlementDisplayStatus.WAITING, YearMonth.of(2026, 6), 0, 10);

        assertThat(page.totalElements()).isZero();
    }

    @Test
    void findPageBySeller_기간이_null이면_경계도_null() {
        UUID sellerId = UUID.randomUUID();
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
        given(jpaRepository.findPageBySeller(
                eq(sellerId), any(), startCaptor.capture(), endCaptor.capture(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        adapter.findPageBySeller(sellerId, null, null, 0, 10);

        assertThat(startCaptor.getValue()).isNull();
        assertThat(endCaptor.getValue()).isNull();
    }
}
