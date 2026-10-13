package com.endustry.erp.platform.labels;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.labels.Terms.Term;
import com.endustry.erp.platform.tenancy.Tenant;
import com.endustry.erp.platform.tenancy.TenantRegistry;

import lombok.RequiredArgsConstructor;

/**
 * The label service: the words each client's screens use for customers, items, machines and the
 * rest. A client starts with its industry template's words and can rename any of them; only the
 * renamed ones are stored, so changing the client's industry later moves the rest along with it.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LabelService {

	public interface TermLabelRepository extends JpaRepository<TermLabel, Long> {
		Optional<TermLabel> findByTermKey(String termKey);
	}

	/** One term on the settings screen: what it is for, the template's word and the client's. */
	public record LabelRow(String key, String description, Term standard, Term current, boolean renamed) {
	}

	private final TermLabelRepository labels;
	private final TenantRegistry tenants;
	private final AuditService audit;

	/** The words for the current client, keyed by term. */
	@Transactional(readOnly = true)
	public Map<String, Term> current() {
		Map<String, Term> terms = new LinkedHashMap<>(defaults());
		labels.findAll().stream().filter(l -> Terms.ALL.containsKey(l.getTermKey()))
				.forEach(l -> terms.put(l.getTermKey(), new Term(l.getSingular(), l.getPlural())));
		return terms;
	}

	@Transactional(readOnly = true)
	public List<LabelRow> rows() {
		Map<String, Term> standard = defaults();
		Map<String, Term> current = current();
		return Terms.ALL.values().stream().map(d -> new LabelRow(d.key(), d.description(), standard.get(d.key()), current.get(d.key()),
				!standard.get(d.key()).equals(current.get(d.key())))).toList();
	}

	/** Renames a term. Saving the template's own words removes the override. */
	public LabelRow rename(String key, String singular, String plural) {
		requireKnown(key);
		Term term = new Term(clean(singular, "Singular"), clean(plural, "Plural"));
		Term previous = current().get(key);
		Optional<TermLabel> existing = labels.findByTermKey(key);
		if (term.equals(defaults().get(key))) {
			existing.ifPresent(labels::delete);
		} else {
			TermLabel label = existing.orElseGet(TermLabel::new);
			label.setTermKey(key);
			label.setSingular(term.singular());
			label.setPlural(term.plural());
			labels.save(label);
		}
		if (!term.equals(previous)) {
			audit.note("Label", null, "UPDATE", Map.of("term", key, "from", previous.plural(), "to", term.plural()));
		}
		return row(key);
	}

	/** Goes back to the industry template's words for one term. */
	public LabelRow reset(String key) {
		requireKnown(key);
		Term standard = defaults().get(key);
		return rename(key, standard.singular(), standard.plural());
	}

	private LabelRow row(String key) {
		return rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
	}

	private Map<String, Term> defaults() {
		return Terms.defaults(tenants.current().map(Tenant::getIndustry).orElse(null));
	}

	private static void requireKnown(String key) {
		if (!Terms.ALL.containsKey(key)) {
			throw ApiException.notFound("Label", key);
		}
	}

	private static String clean(String word, String field) {
		String value = word == null ? "" : word.trim().replaceAll("\\s+", " ");
		if (value.isEmpty()) {
			throw ApiException.badRequest("INVALID_LABEL", field + " is required");
		}
		if (value.length() > 40) {
			throw ApiException.badRequest("INVALID_LABEL", field + " must be at most 40 characters");
		}
		return value;
	}

}
