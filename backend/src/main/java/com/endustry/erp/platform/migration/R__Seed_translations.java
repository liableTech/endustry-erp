package com.endustry.erp.platform.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the bundled screen text (src/main/resources/i18n/*.json, English wording to translation)
 * into the database. Flyway runs it again whenever those files change. It only adds text the
 * database does not have yet, so a translation corrected in the database is never overwritten,
 * and it raises a language's version when it adds anything so browsers fetch the new text.
 */
@Component
public class R__Seed_translations extends BaseJavaMigration {

	/** Languages with a bundled file. English needs none: its text is the key itself. */
	static final String[] LANGUAGES = { "hi" };

	@Override
	public Integer getChecksum() {
		CRC32 crc = new CRC32();
		for (String lang : LANGUAGES) {
			crc.update(read(lang));
		}
		return (int) crc.getValue();
	}

	@Override
	public void migrate(Context context) throws Exception {
		Connection c = context.getConnection();
		Map<String, Map<String, String>> bundles = new LinkedHashMap<>();
		Set<String> keys = new HashSet<>();
		for (String lang : LANGUAGES) {
			Map<String, String> texts = JsonMapper.builder().build().readValue(read(lang), new TypeReference<LinkedHashMap<String, String>>() {
			});
			bundles.put(lang, texts);
			keys.addAll(texts.keySet());
		}
		Map<String, String> english = new LinkedHashMap<>();
		keys.forEach(k -> english.put(k, k));
		addMissing(c, "en", english);
		for (Map.Entry<String, Map<String, String>> bundle : bundles.entrySet()) {
			addMissing(c, bundle.getKey(), bundle.getValue());
		}
	}

	private static void addMissing(Connection c, String lang, Map<String, String> texts) throws SQLException {
		Set<String> existing = new HashSet<>();
		try (PreparedStatement select = c.prepareStatement("select text_key from ui_text where lang = ?")) {
			select.setString(1, lang);
			try (ResultSet rs = select.executeQuery()) {
				while (rs.next()) {
					existing.add(rs.getString(1));
				}
			}
		}
		int added = 0;
		try (PreparedStatement insert = c.prepareStatement("insert into ui_text (lang, text_key, text_value, updated_at, updated_by) values (?, ?, ?, ?, 'system')")) {
			for (Map.Entry<String, String> text : texts.entrySet()) {
				if (existing.contains(text.getKey()) || text.getValue() == null || text.getValue().isBlank()) {
					continue;
				}
				insert.setString(1, lang);
				insert.setString(2, text.getKey());
				insert.setString(3, text.getValue());
				insert.setTimestamp(4, Timestamp.from(Instant.now()));
				insert.addBatch();
				added++;
			}
			insert.executeBatch();
		}
		if (added > 0) {
			try (PreparedStatement bump = c.prepareStatement("update ui_language set version = version + 1 where code = ?")) {
				bump.setString(1, lang);
				bump.executeUpdate();
			}
		}
	}

	private static byte[] read(String lang) {
		try (InputStream in = R__Seed_translations.class.getResourceAsStream("/i18n/" + lang + ".json")) {
			if (in == null) {
				throw new IllegalStateException("Missing i18n/" + lang + ".json");
			}
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
