package com.endustry.erp.platform.i18n;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.common.PageResponse;
import com.endustry.erp.platform.security.CurrentUser;

/**
 * Screen text in each language, read from the database. Every language carries a version that goes
 * up with each change, so a browser can keep its copy until the version moves. Text is shared by all
 * clients and changed only by product admins.
 */
@Service
@Transactional
public class TranslationService {

	public record Language(String code, String name, String nativeName, long version) {
	}

	/** Everything a browser needs to show the application in one language. */
	public record Bundle(String code, long version, Map<String, String> texts) {
	}

	/** One line of the translation editor: the English wording and this language's text for it. */
	public record Row(String key, String text, boolean translated) {
	}

	private final JdbcTemplate jdbc;

	public TranslationService(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Transactional(readOnly = true)
	public List<Language> languages() {
		return jdbc.query("select code, name, native_name, version from ui_language where active = true order by sort_order",
				(rs, i) -> new Language(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
	}

	@Transactional(readOnly = true)
	public Bundle bundle(String code) {
		Language language = language(code);
		Map<String, String> texts = new LinkedHashMap<>();
		jdbc.query("select text_key, text_value from ui_text where lang = ? order by text_key", rs -> {
			texts.put(rs.getString(1), rs.getString(2));
		}, language.code());
		return new Bundle(language.code(), language.version(), texts);
	}

	/** The English wording with this language's text beside it; {@code missing} lists only untranslated lines. */
	@Transactional(readOnly = true)
	public PageResponse<Row> rows(String code, String search, boolean missing, int page, int size) {
		language(code);
		String like = "%" + (search == null ? "" : search.trim().toLowerCase()) + "%";
		String from = """
				from ui_text e left join ui_text t on t.lang = ? and t.text_key = e.text_key
				where e.lang = 'en' and (lower(e.text_key) like ? or lower(coalesce(t.text_value, '')) like ?)""" + (missing ? " and t.id is null" : "");
		long total = jdbc.queryForObject("select count(*) " + from, Long.class, code, like, like);
		List<Row> rows = jdbc.query("select e.text_key, t.text_value " + from + " order by e.text_key limit ? offset ?",
				(rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(2) != null), code, like, like, size, (long) page * size);
		return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
	}

	/** Sets one text and raises the language's version so browsers pick it up. */
	public Row save(String code, String key, String text) {
		language(code);
		if (key == null || key.isBlank() || key.length() > 1000) {
			throw ApiException.badRequest("INVALID_TEXT", "The English text is required and must be at most 1000 characters");
		}
		String value = text == null ? "" : text.trim();
		if (value.length() > 2000) {
			throw ApiException.badRequest("INVALID_TEXT", "The text must be at most 2000 characters");
		}
		if (value.isEmpty()) {
			// Clearing a translation falls back to English; English itself cannot be removed.
			if ("en".equals(code)) {
				throw ApiException.badRequest("INVALID_TEXT", "English text cannot be empty");
			}
			jdbc.update("delete from ui_text where lang = ? and text_key = ?", code, key);
		} else if (jdbc.update("update ui_text set text_value = ?, updated_at = ?, updated_by = ? where lang = ? and text_key = ?",
				value, Timestamp.from(Instant.now()), CurrentUser.email(), code, key) == 0) {
			jdbc.update("insert into ui_text (lang, text_key, text_value, updated_at, updated_by) values (?, ?, ?, ?, ?)",
					code, key, value, Timestamp.from(Instant.now()), CurrentUser.email());
		}
		if (!"en".equals(code) && jdbc.queryForObject("select count(*) from ui_text where lang = 'en' and text_key = ?", Long.class, key) == 0) {
			// A new line of text: English is its own translation, so it appears in every language's list.
			jdbc.update("insert into ui_text (lang, text_key, text_value, updated_at, updated_by) values ('en', ?, ?, ?, ?)",
					key, key, Timestamp.from(Instant.now()), CurrentUser.email());
			jdbc.update("update ui_language set version = version + 1 where code = 'en'");
		}
		jdbc.update("update ui_language set version = version + 1 where code = ?", code);
		return new Row(key, value.isEmpty() ? null : value, !value.isEmpty());
	}

	private Language language(String code) {
		return languages().stream().filter(l -> l.code().equals(code)).findFirst()
				.orElseThrow(() -> ApiException.notFound("Language", code));
	}
}
