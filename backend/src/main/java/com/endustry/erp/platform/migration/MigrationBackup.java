package com.endustry.erp.platform.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * Runs the database migrations, first copying a file database to data/backup-before-migration-*.zip
 * whenever there is something to apply to a database that already holds data. Restore by stopping
 * the server and unzipping the copy over data/.
 */
@Component
public class MigrationBackup implements FlywayMigrationStrategy {

	private static final Logger log = LoggerFactory.getLogger(MigrationBackup.class);

	@Override
	public void migrate(Flyway flyway) {
		MigrationInfo[] pending = flyway.info().pending();
		if (pending.length > 0) {
			backupIfFileDatabase(flyway, pending.length);
		}
		flyway.migrate();
	}

	private void backupIfFileDatabase(Flyway flyway, int pending) {
		try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
			String url = c.getMetaData().getURL();
			if (!"H2".equals(c.getMetaData().getDatabaseProductName()) || url.contains(":mem:") || !hasTables(c)) {
				return;
			}
			String file = "./data/backup-before-migration-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
			try (Statement s = c.createStatement()) {
				s.execute("BACKUP TO '" + file + "'");
			}
			log.info("Database backed up to {} before applying {} migration(s)", file, pending);
		} catch (SQLException e) {
			// Never migrate data that could not be copied first.
			throw new IllegalStateException("Could not back up the database before migrating it, so it was left unchanged", e);
		}
	}

	private static boolean hasTables(Connection c) throws SQLException {
		try (Statement s = c.createStatement();
				var rs = s.executeQuery("select count(*) from information_schema.tables where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'")) {
			return rs.next() && rs.getLong(1) > 0;
		}
	}
}
