package com.prompthub.user.sellersettlement.infrastructure.persistence;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerSettlementQueryRepositoryAdapter implements SellerSettlementQueryRepository {

    private static final String SETTLEMENT_YEAR =
            "CAST(EXTRACT(YEAR FROM (s.period_start + 3)) AS INTEGER)";
    private static final String SETTLEMENT_MONTH =
            "CAST(EXTRACT(MONTH FROM (s.period_start + 3)) AS INTEGER)";
    private static final String FILTER_YEAR =
            "CAST(EXTRACT(YEAR FROM (sf.period_start + 3)) AS INTEGER)";
    private static final String FILTER_MONTH =
            "CAST(EXTRACT(MONTH FROM (sf.period_start + 3)) AS INTEGER)";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SellerSettlementJpaRepository jpaRepository;

    @Override
    public MonthlyPage findMonthlyPage(
            UUID sellerId, SettlementDisplayStatus status, YearMonth settlementMonth,
            int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("size", size)
                .addValue("offset", (long) page * size);
        String whereClause = createWhereClause(status, settlementMonth, parameters);

        String contentSql = """
                SELECT s.seller_id AS seller_id,
                       %s AS settlement_year,
                       %s AS settlement_month,
                       COUNT(*) AS weekly_settlement_count,
                       SUM(CASE WHEN s.status <> 'CANCELLED' THEN 1 ELSE 0 END)
                           AS aggregated_settlement_count,
                       COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED'
                           THEN s.product_count ELSE 0 END), 0) AS sales_count,
                       COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED'
                           THEN s.total_amount ELSE 0 END), 0) AS gross_amount,
                       COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED'
                           THEN s.fee_total_amount ELSE 0 END), 0) AS fee_amount,
                       COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED'
                           THEN COALESCE(s.refund_amount, 0) ELSE 0 END), 0) AS refund_amount,
                       COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED'
                           THEN s.settlement_total_amount ELSE 0 END), 0) AS payout_amount
                FROM seller_settlement s
                %s
                GROUP BY s.seller_id, %s, %s
                ORDER BY settlement_year DESC, settlement_month DESC
                LIMIT :size OFFSET :offset
                """.formatted(
                SETTLEMENT_YEAR, SETTLEMENT_MONTH, whereClause,
                SETTLEMENT_YEAR, SETTLEMENT_MONTH);
        List<MonthlyAggregate> content = jdbcTemplate.query(
                contentSql, parameters, this::mapMonthlyAggregate);

        String countSql = """
                SELECT COUNT(*)
                FROM (
                    SELECT s.seller_id, %s, %s
                    FROM seller_settlement s
                    %s
                    GROUP BY s.seller_id, %s, %s
                ) monthly_settlements
                """.formatted(
                SETTLEMENT_YEAR, SETTLEMENT_MONTH, whereClause,
                SETTLEMENT_YEAR, SETTLEMENT_MONTH);
        Long totalElements = jdbcTemplate.queryForObject(countSql, parameters, Long.class);

        return new MonthlyPage(content, totalElements == null ? 0 : totalElements);
    }

    @Override
    public Optional<MonthlyAggregate> findMonthlyAggregate(
            UUID sellerId, YearMonth settlementMonth) {
        return findMonthlyPage(sellerId, null, settlementMonth, 0, 1)
                .content()
                .stream()
                .findFirst();
    }

    @Override
    public List<MonthlyStatusCount> findStatusCounts(List<MonthlyKey> keys) {
        Set<MonthlyKey> distinctKeys = new LinkedHashSet<>(keys);
        if (distinctKeys.isEmpty()) {
            return List.of();
        }

        List<MonthlyKey> queryKeys = new ArrayList<>(distinctKeys);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String keyConditions = IntStream.range(0, queryKeys.size())
                .mapToObj(index -> addKeyCondition(parameters, queryKeys.get(index), index))
                .collect(Collectors.joining(" OR "));
        String sql = """
                SELECT s.seller_id AS seller_id,
                       %s AS settlement_year,
                       %s AS settlement_month,
                       s.status AS status,
                       COUNT(*) AS status_count
                FROM seller_settlement s
                WHERE %s
                GROUP BY s.seller_id, %s, %s, s.status
                """.formatted(
                SETTLEMENT_YEAR, SETTLEMENT_MONTH, keyConditions,
                SETTLEMENT_YEAR, SETTLEMENT_MONTH);

        Comparator<MonthlyStatusCount> order = Comparator
                .comparing((MonthlyStatusCount count) -> count.key().settlementMonth())
                .reversed()
                .thenComparing(count -> count.key().sellerId())
                .thenComparingInt(count -> count.status().ordinal());
        return jdbcTemplate.query(sql, parameters, this::mapMonthlyStatusCount)
                .stream()
                .sorted(order)
                .toList();
    }

    @Override
    public List<SellerSettlement> findWeeklySettlements(
            UUID sellerId, YearMonth settlementMonth) {
        LocalDate periodStart = settlementMonth.atDay(1).minusDays(3);
        LocalDate periodEnd = settlementMonth.plusMonths(1).atDay(1).minusDays(3);
        return jpaRepository.findWeeklySettlements(sellerId, periodStart, periodEnd);
    }

    private String createWhereClause(
            SettlementDisplayStatus status,
            YearMonth settlementMonth,
            MapSqlParameterSource parameters) {
        StringBuilder whereClause = new StringBuilder("WHERE s.seller_id = :sellerId");
        if (settlementMonth != null) {
            parameters.addValue("periodStart", settlementMonth.atDay(1).minusDays(3));
            parameters.addValue(
                    "periodEnd", settlementMonth.plusMonths(1).atDay(1).minusDays(3));
            whereClause.append(" AND s.period_start >= :periodStart")
                    .append(" AND s.period_start < :periodEnd");
        }
        if (status != null) {
            parameters.addValue("status", status.name());
            whereClause.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM seller_settlement sf
                         WHERE sf.seller_id = s.seller_id
                           AND %s = %s
                           AND %s = %s
                           AND sf.status = :status
                     )
                    """.formatted(
                    FILTER_YEAR, SETTLEMENT_YEAR, FILTER_MONTH, SETTLEMENT_MONTH));
        }
        return whereClause.toString();
    }

    private String addKeyCondition(
            MapSqlParameterSource parameters, MonthlyKey key, int index) {
        parameters.addValue("sellerId" + index, key.sellerId());
        parameters.addValue("settlementYear" + index, key.settlementMonth().getYear());
        parameters.addValue("settlementMonth" + index, key.settlementMonth().getMonthValue());
        return "(s.seller_id = :sellerId" + index
                + " AND " + SETTLEMENT_YEAR + " = :settlementYear" + index
                + " AND " + SETTLEMENT_MONTH + " = :settlementMonth" + index + ")";
    }

    private MonthlyAggregate mapMonthlyAggregate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        MonthlyKey key = new MonthlyKey(
                UUID.fromString(resultSet.getString("seller_id")),
                YearMonth.of(
                        resultSet.getInt("settlement_year"),
                        resultSet.getInt("settlement_month")));
        return new MonthlyAggregate(
                key,
                resultSet.getLong("weekly_settlement_count"),
                resultSet.getLong("aggregated_settlement_count"),
                resultSet.getLong("sales_count"),
                resultSet.getBigDecimal("gross_amount"),
                resultSet.getBigDecimal("fee_amount"),
                resultSet.getBigDecimal("refund_amount"),
                resultSet.getBigDecimal("payout_amount"));
    }

    private MonthlyStatusCount mapMonthlyStatusCount(ResultSet resultSet, int rowNumber)
            throws SQLException {
        MonthlyKey key = new MonthlyKey(
                UUID.fromString(resultSet.getString("seller_id")),
                YearMonth.of(
                        resultSet.getInt("settlement_year"),
                        resultSet.getInt("settlement_month")));
        return new MonthlyStatusCount(
                key,
                SettlementDisplayStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("status_count"));
    }
}
