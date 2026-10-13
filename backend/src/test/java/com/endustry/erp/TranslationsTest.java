package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.endustry.erp.platform.security.Role;

import tools.jackson.databind.JsonNode;

/** Screen text from the database, per language, with a version browsers cache by. */
class TranslationsTest extends ApiTest {

	@Test
	void englishAndHindiAreServedWithoutSigningIn() {
		JsonNode languages = call(200, null, HttpMethod.GET, "/api/public/i18n/languages", null);
		assertThat(languages.toString()).contains("\"en\"", "\"hi\"", "हिन्दी");

		JsonNode hindi = call(200, null, HttpMethod.GET, "/api/public/i18n/hi", null);
		assertThat(hindi.path("texts").path("Sign in").asString()).isEqualTo("साइन इन करें");
		assertThat(hindi.path("version").asLong()).isPositive();
		// English text is its own key, so English carries every line too.
		assertThat(call(200, null, HttpMethod.GET, "/api/public/i18n/en", null).path("texts").path("Sign in").asString()).isEqualTo("Sign in");
		assertCode(call(404, null, HttpMethod.GET, "/api/public/i18n/xx", null), "NOT_FOUND");
	}

	@Test
	void aProductAdminCorrectsATranslationAndTheVersionMoves() {
		long before = call(200, null, HttpMethod.GET, "/api/public/i18n/hi", null).path("version").asLong();
		put(200, Role.PLATFORM_ADMIN, "/api/platform/translations/hi", Map.of("key", "Brand new screen text", "text", "नया पाठ"));

		JsonNode hindi = call(200, null, HttpMethod.GET, "/api/public/i18n/hi", null);
		assertThat(hindi.path("version").asLong()).isGreaterThan(before);
		assertThat(hindi.path("texts").path("Brand new screen text").asString()).isEqualTo("नया पाठ");
		// The new line shows in the editor for every language.
		assertThat(get(Role.PLATFORM_ADMIN, "/api/platform/translations/hi?q=brand new").path("totalElements").asLong()).isEqualTo(1);

		// Clearing it falls back to English.
		put(200, Role.PLATFORM_ADMIN, "/api/platform/translations/hi", Map.of("key", "Brand new screen text", "text", ""));
		assertThat(call(200, null, HttpMethod.GET, "/api/public/i18n/hi", null).path("texts").has("Brand new screen text")).isFalse();
		assertThat(get(Role.PLATFORM_ADMIN, "/api/platform/translations/hi?missing=true&q=brand new").path("totalElements").asLong()).isEqualTo(1);

		assertCode(put(403, Role.OWNER, "/api/platform/translations/hi", Map.of("key", "Sign in", "text", "x")), "FORBIDDEN");
	}
}
