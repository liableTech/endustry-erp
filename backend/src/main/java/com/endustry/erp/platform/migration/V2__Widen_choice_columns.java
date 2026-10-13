package com.endustry.erp.platform.migration;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Turns fixed-choice columns (an industry, a status, an item type) into plain text in databases made
 * before Flyway. Hibernate used to create them as H2 ENUM columns, or on PostgreSQL with a check
 * constraint listing the allowed values, so a choice added in code, such as a new industry, was
 * refused by the database. The values in them are kept, and the code still checks them. A database
 * created by V1 already has text columns, so there this does nothing.
 */
@Component
public class V2__Widen_choice_columns extends BaseJavaMigration {

	@Override
	public void migrate(Context context) throws Exception {
		apply(context.getConnection());
	}

	/** The migration itself, on a connection the caller owns. */
	public void apply(Connection connection) throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
		String product = connection.getMetaData().getDatabaseProductName();
		if ("H2".equals(product)) {
			widenH2(jdbc);
		} else if ("PostgreSQL".equals(product)) {
			dropPostgresChoiceChecks(jdbc);
		}
	}

	private void widenH2(JdbcTemplate jdbc) {
		List<Map<String, Object>> columns = jdbc.queryForList(
				"select table_name, column_name from information_schema.columns where table_schema = 'PUBLIC' and data_type = 'ENUM'");
		for (Map<String, Object> column : columns) {
			jdbc.execute("alter table \"" + column.get("TABLE_NAME") + "\" alter column \"" + column.get("COLUMN_NAME") + "\" set data type varchar(255)");
		}
	}

	private void dropPostgresChoiceChecks(JdbcTemplate jdbc) {
		List<Map<String, Object>> checks = jdbc.queryForList("""
				select c.conrelid::regclass::text as table_name, c.conname as name from pg_constraint c
				join pg_namespace n on n.oid = c.connamespace
				where c.contype = 'c' and n.nspname = current_schema() and pg_get_constraintdef(c.oid) like '%ARRAY[%'""");
		for (Map<String, Object> check : checks) {
			jdbc.execute("alter table " + check.get("table_name") + " drop constraint \"" + check.get("name") + "\"");
		}
	}
}
