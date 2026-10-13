package com.endustry.erp.platform.labels;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.labels.LabelService.LabelRow;

import lombok.RequiredArgsConstructor;

/** Settings > Labels. Everyone signed in receives the words with their session; renaming needs settings access. */
@RestController
@RequestMapping("/api/settings/labels")
@RequiredArgsConstructor
public class LabelController {

	private final LabelService labels;

	public record Rename(String singular, String plural) {
	}

	@GetMapping
	public List<LabelRow> list() {
		return labels.rows();
	}

	@PutMapping("/{key}")
	public LabelRow rename(@PathVariable String key, @RequestBody Rename body) {
		return labels.rename(key, body.singular(), body.plural());
	}

	@DeleteMapping("/{key}")
	public LabelRow reset(@PathVariable String key) {
		return labels.reset(key);
	}
}
