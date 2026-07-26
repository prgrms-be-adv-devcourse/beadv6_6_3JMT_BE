package com.prompthub.admin.home.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class HomeQueryRepository {

	private static final String USER_SUMMARY_SQL = """
		SELECT
			(SELECT count(*) FROM user_service."user") AS total_users,
			(SELECT count(*)
			 FROM user_service."user"
			 WHERE created_at >= :startInclusive
			   AND created_at < :endExclusive) AS today_new_users
		""";

	private static final String MONTHLY_TRANSACTION_SQL = """
		SELECT
			coalesce((SELECT sum(total_order_amount)
			          FROM order_service."order"
			          WHERE completed_at >= :startInclusive
			            AND completed_at < :endExclusive), 0)
			-
			coalesce((SELECT sum(product_amount_snapshot)
			          FROM order_service.order_product
			          WHERE refunded_at >= :startInclusive
			            AND refunded_at < :endExclusive), 0)
			AS transaction_amount
		""";

	private static final String DAILY_TRANSACTION_SQL = """
		WITH completed AS (
			SELECT cast(completed_at AS date) AS transaction_date,
			       count(*) AS transaction_count,
			       sum(total_order_amount) AS transaction_amount
			FROM order_service."order"
			WHERE completed_at >= :startInclusive
			  AND completed_at < :endExclusive
			GROUP BY cast(completed_at AS date)
		), refunded AS (
			SELECT cast(refunded_at AS date) AS transaction_date,
			       sum(product_amount_snapshot) AS refund_amount
			FROM order_service.order_product
			WHERE refunded_at >= :startInclusive
			  AND refunded_at < :endExclusive
			GROUP BY cast(refunded_at AS date)
		)
		SELECT coalesce(c.transaction_date, r.transaction_date) AS transaction_date,
		       coalesce(c.transaction_count, 0) AS transaction_count,
		       coalesce(c.transaction_amount, 0) - coalesce(r.refund_amount, 0) AS transaction_amount
		FROM completed c
		FULL OUTER JOIN refunded r ON r.transaction_date = c.transaction_date
		ORDER BY transaction_date
		""";

	private static final String SETTLEMENT_SUMMARY_SQL = """
		SELECT coalesce(sum(settlement_total_amount), 0) AS pending_amount,
		       count(*) AS pending_count
		FROM user_service.seller_settlement
		WHERE status IN ('WAITING', 'APPROVAL_ON_HOLD')
		""";

	private static final String PENDING_PRODUCT_COUNT_SQL = """
		SELECT count(*)
		FROM product_service.product
		WHERE status = 'PENDING_REVIEW'
		  AND deleted_at IS NULL
		""";

	private static final String PENDING_PRODUCT_ITEMS_SQL = """
		SELECT p.id AS product_id,
		       p.name AS title,
		       coalesce(u.name, '알 수 없음') AS seller_nickname,
		       p.product_type,
		       p.model,
		       p.amount,
		       p.status,
		       p.created_at
		FROM product_service.product p
		LEFT JOIN user_service."user" u ON u.id = p.seller_id
		WHERE p.status = 'PENDING_REVIEW'
		  AND p.deleted_at IS NULL
		ORDER BY p.created_at ASC, p.id ASC
		LIMIT :limit
		""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public UserSummary findUserSummary(
		LocalDateTime todayStartInclusive,
		LocalDateTime tomorrowStartExclusive
	) {
		MapSqlParameterSource parameters = periodParameters(todayStartInclusive, tomorrowStartExclusive);
		return jdbcTemplate.queryForObject(
			USER_SUMMARY_SQL,
			parameters,
			(resultSet, rowNum) -> new UserSummary(
				resultSet.getLong("total_users"),
				resultSet.getLong("today_new_users")
			)
		);
	}

	public long findMonthlyTransactionAmount(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		Long result = jdbcTemplate.queryForObject(
			MONTHLY_TRANSACTION_SQL,
			periodParameters(startInclusive, endExclusive),
			Long.class
		);
		return result == null ? 0L : result;
	}

	public List<DailyTransaction> findDailyTransactions(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		return jdbcTemplate.query(
			DAILY_TRANSACTION_SQL,
			periodParameters(startInclusive, endExclusive),
			(resultSet, rowNum) -> new DailyTransaction(
				resultSet.getObject("transaction_date", LocalDate.class),
				resultSet.getLong("transaction_count"),
				resultSet.getLong("transaction_amount")
			)
		);
	}

	public SettlementSummary findPendingApprovalSettlementSummary() {
		return jdbcTemplate.queryForObject(
			SETTLEMENT_SUMMARY_SQL,
			Map.of(),
			(resultSet, rowNum) -> new SettlementSummary(
				valueOrZero(resultSet.getBigDecimal("pending_amount")),
				resultSet.getLong("pending_count")
			)
		);
	}

	public PendingProductPreview findPendingProductPreview(int limit) {
		Long totalCount = jdbcTemplate.queryForObject(PENDING_PRODUCT_COUNT_SQL, Map.of(), Long.class);
		List<PendingProduct> items = jdbcTemplate.query(
			PENDING_PRODUCT_ITEMS_SQL,
			Map.of("limit", limit),
			this::mapPendingProduct
		);
		return new PendingProductPreview(totalCount == null ? 0L : totalCount, items);
	}

	private PendingProduct mapPendingProduct(ResultSet resultSet, int rowNum) throws SQLException {
		return new PendingProduct(
			resultSet.getObject("product_id", UUID.class),
			resultSet.getString("title"),
			resultSet.getString("seller_nickname"),
			resultSet.getString("product_type"),
			resultSet.getString("model"),
			resultSet.getInt("amount"),
			resultSet.getString("status"),
			resultSet.getObject("created_at", LocalDateTime.class)
		);
	}

	private MapSqlParameterSource periodParameters(
		LocalDateTime startInclusive,
		LocalDateTime endExclusive
	) {
		return new MapSqlParameterSource()
			.addValue("startInclusive", startInclusive)
			.addValue("endExclusive", endExclusive);
	}

	private BigDecimal valueOrZero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	public record UserSummary(long totalUsers, long todayNewUsers) {
	}

	public record DailyTransaction(LocalDate date, long transactionCount, long transactionAmount) {
	}

	public record SettlementSummary(BigDecimal pendingApprovalAmount, long pendingApprovalCount) {
	}

	public record PendingProductPreview(long totalCount, List<PendingProduct> items) {
		public PendingProductPreview {
			items = List.copyOf(items);
		}
	}

	public record PendingProduct(
		UUID productId,
		String title,
		String sellerNickname,
		String productType,
		String model,
		int amount,
		String status,
		LocalDateTime createdAt
	) {
	}
}
