package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementAnalysisQueryRepository.AnalysisQueryRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerSettlementAnalysisQueryRepositoryAdapter
        implements SellerSettlementAnalysisQueryRepository {

    private static final String AGGREGATE_SQL = """
            SELECT COUNT(CASE WHEN d.line_type = 'SALE' THEN 1 END) AS sale_count,
                   COUNT(CASE WHEN d.line_type = 'REFUND' THEN 1 END) AS refund_count,
                   COALESCE(SUM(CASE WHEN d.line_type = 'SALE'
                       THEN d.line_amount ELSE 0 END), 0) AS gross_sale_amount,
                   ABS(COALESCE(SUM(CASE WHEN d.line_type = 'REFUND'
                       THEN d.line_amount ELSE 0 END), 0)) AS gross_refund_amount,
                   COALESCE(SUM(CASE WHEN d.line_type = 'SALE'
                       THEN d.fee_amount ELSE 0 END), 0) AS sale_fee_amount,
                   ABS(COALESCE(SUM(CASE WHEN d.line_type = 'REFUND'
                       THEN d.fee_amount ELSE 0 END), 0)) AS refunded_fee_amount,
                   COALESCE(SUM(d.fee_amount), 0) AS net_fee_amount,
                   COALESCE(SUM(d.line_settlement_amount), 0) AS payout_amount
            FROM seller_settlement s
            JOIN seller_settlement_detail d
              ON d.seller_settlement_id = s.seller_settlement_id
            WHERE s.seller_id = :sellerId
              AND s.status <> 'CANCELLED'
              AND s.period_end <= :completedThrough
              AND d.occurred_at >= :includedStart
              AND d.occurred_at < :includedEndExclusive
            """;

    private static final String WEEKLY_BREAKDOWN_SQL = """
            SELECT s.period_start AS period_start,
                   s.period_end AS period_end,
                   COUNT(CASE WHEN d.line_type = 'SALE' THEN 1 END) AS sale_count,
                   COUNT(CASE WHEN d.line_type = 'REFUND' THEN 1 END) AS refund_count,
                   COALESCE(SUM(CASE WHEN d.line_type = 'SALE'
                       THEN d.line_amount ELSE 0 END), 0) AS gross_sale_amount,
                   ABS(COALESCE(SUM(CASE WHEN d.line_type = 'REFUND'
                       THEN d.line_amount ELSE 0 END), 0)) AS gross_refund_amount,
                   COALESCE(SUM(CASE WHEN d.line_type = 'SALE'
                       THEN d.fee_amount ELSE 0 END), 0) AS sale_fee_amount,
                   ABS(COALESCE(SUM(CASE WHEN d.line_type = 'REFUND'
                       THEN d.fee_amount ELSE 0 END), 0)) AS refunded_fee_amount,
                   COALESCE(SUM(d.fee_amount), 0) AS net_fee_amount,
                   COALESCE(SUM(d.line_settlement_amount), 0) AS payout_amount
            FROM seller_settlement s
            JOIN seller_settlement_detail d
              ON d.seller_settlement_id = s.seller_settlement_id
            WHERE s.seller_id = :sellerId
              AND s.status <> 'CANCELLED'
              AND s.period_end <= :completedThrough
              AND d.occurred_at >= :includedStart
              AND d.occurred_at < :includedEndExclusive
            GROUP BY s.period_start, s.period_end
            ORDER BY s.period_start ASC
            """;

    private static final String PAYOUT_STATUS_SQL = """
            SELECT s.period_start AS period_start,
                   s.period_end AS period_end,
                   s.status AS status,
                   s.paid_at AS paid_at
            FROM seller_settlement s
            WHERE s.seller_id = :sellerId
              AND s.period_start >= :periodStart
              AND s.period_start < :periodEndExclusive
            ORDER BY s.period_start ASC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public AnalysisAggregate aggregate(UUID sellerId, AnalysisQueryRange range) {
        if (!range.hasIncludedDays()) {
            return AnalysisAggregate.zero();
        }
        MapSqlParameterSource parameters = analysisParameters(
                sellerId,
                range.includedStart(),
                range.includedEnd(),
                range.completedThrough());
        return jdbcTemplate.queryForObject(
                AGGREGATE_SQL, parameters, this::mapAnalysisAggregate);
    }

    @Override
    public List<WeeklyAnalysisAggregate> findWeeklyBreakdown(
            UUID sellerId,
            YearMonth month,
            LocalDate includedEnd,
            LocalDate completedThrough) {
        LocalDate monthStart = month.atDay(1);
        if (includedEnd == null || includedEnd.isBefore(monthStart)) {
            return List.of();
        }
        LocalDate requestedIncludedEnd = earlier(month.atEndOfMonth(), includedEnd);
        MapSqlParameterSource parameters = analysisParameters(
                sellerId, monthStart, requestedIncludedEnd, completedThrough);
        return jdbcTemplate.query(
                WEEKLY_BREAKDOWN_SQL,
                parameters,
                (resultSet, rowNumber) -> mapWeeklyAggregate(
                        resultSet, monthStart, requestedIncludedEnd));
    }

    @Override
    public PayoutStatusSnapshot findPayoutStatuses(
            UUID sellerId,
            YearMonth settlementMonth) {
        LocalDate periodStart = settlementMonth.atDay(1).minusDays(3);
        LocalDate periodEndExclusive = settlementMonth.plusMonths(1).atDay(1).minusDays(3);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("periodStart", periodStart)
                .addValue("periodEndExclusive", periodEndExclusive);
        List<PayoutStatusRow> rows = jdbcTemplate.query(
                PAYOUT_STATUS_SQL, parameters, this::mapPayoutStatusRow);

        Map<SettlementDisplayStatus, Long> counts = new EnumMap<>(SettlementDisplayStatus.class);
        rows.forEach(row -> counts.merge(row.status(), 1L, Long::sum));
        List<PayoutStatusCount> statusCounts = counts.entrySet().stream()
                .map(entry -> new PayoutStatusCount(entry.getKey(), entry.getValue()))
                .toList();
        return new PayoutStatusSnapshot(statusCounts, rows);
    }

    private MapSqlParameterSource analysisParameters(
            UUID sellerId,
            LocalDate includedStart,
            LocalDate includedEnd,
            LocalDate completedThrough) {
        return new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("completedThrough", completedThrough)
                .addValue("includedStart", includedStart.atStartOfDay())
                .addValue("includedEndExclusive", includedEnd.plusDays(1).atStartOfDay());
    }

    private AnalysisAggregate mapAnalysisAggregate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AnalysisAggregate(
                resultSet.getLong("sale_count"),
                resultSet.getLong("refund_count"),
                resultSet.getBigDecimal("gross_sale_amount"),
                resultSet.getBigDecimal("gross_refund_amount"),
                resultSet.getBigDecimal("sale_fee_amount"),
                resultSet.getBigDecimal("refunded_fee_amount"),
                resultSet.getBigDecimal("net_fee_amount"),
                resultSet.getBigDecimal("payout_amount"));
    }

    private WeeklyAnalysisAggregate mapWeeklyAggregate(
            ResultSet resultSet,
            LocalDate monthStart,
            LocalDate completedMonthEnd) throws SQLException {
        LocalDate weekStart = resultSet.getDate("period_start").toLocalDate();
        LocalDate weekEnd = resultSet.getDate("period_end").toLocalDate();
        LocalDate includedStart = later(weekStart, monthStart);
        LocalDate includedEnd = earlier(weekEnd, completedMonthEnd);
        boolean boundaryWeek = !includedStart.equals(weekStart) || !includedEnd.equals(weekEnd);
        return new WeeklyAnalysisAggregate(
                weekStart,
                weekEnd,
                includedStart,
                includedEnd,
                boundaryWeek,
                mapAnalysisAggregate(resultSet, 0));
    }

    private PayoutStatusRow mapPayoutStatusRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Timestamp paidAt = resultSet.getTimestamp("paid_at");
        LocalDateTime paidAtValue = paidAt == null ? null : paidAt.toLocalDateTime();
        return new PayoutStatusRow(
                resultSet.getDate("period_start").toLocalDate(),
                resultSet.getDate("period_end").toLocalDate(),
                SettlementDisplayStatus.valueOf(resultSet.getString("status")),
                paidAtValue);
    }

    private LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }
}
