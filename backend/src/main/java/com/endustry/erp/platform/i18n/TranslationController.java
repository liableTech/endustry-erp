package com.endustry.erp.platform.i18n;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.common.PageResponse;
import com.endustry.erp.platform.i18n.TranslationService.Bundle;
import com.endustry.erp.platform.i18n.TranslationService.Language;
import com.endustry.erp.platform.i18n.TranslationService.Row;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TranslationController {

	private final TranslationService translations;

	public record SaveText(String key, String text) {
	}

	/** Public: the landing and sign-in pages are translated too. */
	@GetMapping("/public/i18n/languages")
	public List<Language> languages() {
		return translations.languages();
	}

	/**
	 * The whole language as one JSON object. Browsers ask with ?v=version, so a URL never changes
	 * content and can be cached for a long time; a new version is a new URL.
	 */
	@GetMapping("/public/i18n/{code}")
	public ResponseEntity<Bundle> bundle(@PathVariable String code, @RequestParam(required = false) Long v) {
		Bundle bundle = translations.bundle(code);
		CacheControl cache = v != null && v == bundle.version() ? CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic() : CacheControl.noCache();
		return ResponseEntity.ok().cacheControl(cache).body(bundle);
	}

	@GetMapping("/platform/translations/{code}")
	public PageResponse<Row> rows(@PathVariable String code, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "false") boolean missing, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return translations.rows(code, q, missing, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
	}

	@PutMapping("/platform/translations/{code}")
	public Row save(@PathVariable String code, @RequestBody SaveText body) {
		return translations.save(code, body.key(), body.text());
	}
}
