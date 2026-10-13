package com.endustry.erp.platform.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.settings.SettingsRepositories.CompanyProfileRepository;
import com.endustry.erp.platform.settings.SettingsRepositories.NumberSeriesRepository;
import com.endustry.erp.platform.settings.SettingsRepositories.ParameterRepository;
import com.endustry.erp.platform.settings.SettingsRepositories.ThemeSettingsRepository;

import lombok.RequiredArgsConstructor;

/** Company profile, theme, configurable parameters and document numbering. */
@Service
@RequiredArgsConstructor
@Transactional
public class SettingsService {

	/** GST invoice numbers may be at most 16 characters (BRD section 10). */
	public static final int MAX_DOC_NO_LENGTH = 16;
	private static final String[] SYSTEM_FIELDS = { "id", "version", "tenantId", "createdAt", "updatedAt", "createdBy", "updatedBy" };

	private final CompanyProfileRepository companies;
	private final ThemeSettingsRepository themes;
	private final ParameterRepository parameters;
	private final NumberSeriesRepository series;
	private final AuditService audit;

	// ---- company ----

	@Transactional(readOnly = true)
	public CompanyProfile company() {
		return companies.findAll().stream().findFirst()
				.orElseThrow(() -> ApiException.rule("COMPANY_NOT_SET_UP", "Company profile has not been set up yet"));
	}

	public CompanyProfile saveCompany(CompanyProfile incoming) {
		String gstin = incoming.getGstin();
		if (gstin != null && !gstin.isBlank()) {
			if (!gstin.startsWith(incoming.getStateCode())) {
				throw ApiException.rule("GSTIN_STATE_MISMATCH", "The first two digits of the GSTIN must match the state code");
			}
			if (incoming.getPan() != null && !incoming.getPan().isBlank() && !gstin.substring(2, 12).equals(incoming.getPan())) {
				throw ApiException.rule("GSTIN_PAN_MISMATCH", "Characters 3 to 12 of the GSTIN must be the company PAN");
			}
		}
		CompanyProfile existing = companies.findAll().stream().findFirst().orElseGet(CompanyProfile::new);
		Map<String, Object> before = existing.getId() == null ? null : audit.snapshot(existing);
		BeanUtils.copyProperties(incoming, existing, SYSTEM_FIELDS);
		CompanyProfile saved = companies.saveAndFlush(existing);
		audit.record("CompanyProfile", saved.getId(), before == null ? "CREATE" : "UPDATE", before, saved);
		return saved;
	}

	// ---- theme ----

	@Transactional(readOnly = true)
	public ThemeSettings theme() {
		return themes.findAll().stream().findFirst().orElseGet(ThemeSettings::new);
	}

	public ThemeSettings saveTheme(ThemeSettings incoming) {
		ThemeSettings existing = themes.findAll().stream().findFirst().orElseGet(ThemeSettings::new);
		Map<String, Object> before = existing.getId() == null ? null : audit.snapshot(existing);
		BeanUtils.copyProperties(incoming, existing, SYSTEM_FIELDS);
		ThemeSettings saved = themes.saveAndFlush(existing);
		audit.record("ThemeSettings", saved.getId(), before == null ? "CREATE" : "UPDATE", before, saved);
		return saved;
	}

	// ---- parameters ----

	@Transactional(readOnly = true)
	public List<Parameter> parameters() {
		return parameters.findAll(Sort.by("groupName", "paramKey"));
	}

	public Parameter setParameter(Long id, String value) {
		Parameter p = parameters.findById(id).orElseThrow(() -> ApiException.notFound("Parameter", id));
		String clean = value == null ? "" : value.trim();
		switch (p.getValueType()) {
			case "NUMBER" -> {
				try {
					if (new BigDecimal(clean).signum() < 0) {
						throw new NumberFormatException();
					}
				} catch (NumberFormatException e) {
					throw ApiException.badRequest("INVALID_PARAMETER", p.getLabel() + " must be a number of zero or more");
				}
			}
			case "BOOLEAN" -> {
				if (!clean.equals("true") && !clean.equals("false")) {
					throw ApiException.badRequest("INVALID_PARAMETER", p.getLabel() + " must be true or false");
				}
			}
			default -> {
				if (!Arrays.asList(p.getValueType().split("\\|")).contains(clean)) {
					throw ApiException.badRequest("INVALID_PARAMETER",
							p.getLabel() + " must be one of " + p.getValueType().replace("|", ", "));
				}
			}
		}
		String previous = p.getParamValue();
		p.setParamValue(clean);
		audit.note("Parameter", p.getId(), "UPDATE", Map.of("key", p.getParamKey(), "from", previous, "to", clean));
		return parameters.save(p);
	}

	@Transactional(readOnly = true)
	public String text(String key) {
		return parameters.findByParamKey(key).map(Parameter::getParamValue)
				.orElseThrow(() -> new IllegalStateException("Missing parameter " + key));
	}

	@Transactional(readOnly = true)
	public BigDecimal number(String key) {
		return new BigDecimal(text(key));
	}

	@Transactional(readOnly = true)
	public boolean flag(String key) {
		return Boolean.parseBoolean(text(key));
	}

	// ---- numbering ----

	@Transactional(readOnly = true)
	public List<NumberSeries> allSeries() {
		return series.findAll(Sort.by("seriesKey"));
	}

	public NumberSeries saveSeries(Long id, NumberSeries incoming) {
		NumberSeries existing = series.findById(id).orElseThrow(() -> ApiException.notFound("Number series", id));
		if (incoming.getNextNumber() < existing.getNextNumber()) {
			throw ApiException.rule("SERIES_REWIND", "The next number cannot go backwards; it would create duplicate document numbers");
		}
		Map<String, Object> before = audit.snapshot(existing);
		existing.setPrefix(incoming.getPrefix());
		existing.setIncludeFinancialYear(incoming.isIncludeFinancialYear());
		existing.setPadding(incoming.getPadding());
		existing.setNextNumber(incoming.getNextNumber());
		if (format(existing, LocalDate.now(), existing.getNextNumber()).length() > MAX_DOC_NO_LENGTH) {
			throw ApiException.rule("DOC_NO_TOO_LONG",
					"Document numbers from this series would exceed " + MAX_DOC_NO_LENGTH + " characters");
		}
		NumberSeries saved = series.saveAndFlush(existing);
		audit.record("NumberSeries", id, "UPDATE", before, saved);
		return saved;
	}

	/** Issues the next number of a series. Locks the row so concurrent documents never collide. */
	public String nextNumber(String seriesKey, LocalDate date) {
		NumberSeries s = series.findBySeriesKey(seriesKey)
				.orElseThrow(() -> new IllegalStateException("No number series for " + seriesKey));
		String number = format(s, date, s.getNextNumber());
		s.setNextNumber(s.getNextNumber() + 1);
		return number;
	}

	private String format(NumberSeries s, LocalDate date, long number) {
		String padded = String.format("%0" + s.getPadding() + "d", number);
		return s.isIncludeFinancialYear() ? s.getPrefix() + "/" + financialYear(date) + "/" + padded
				: s.getPrefix() + "/" + padded;
	}

	/** "26-27" for any date in the year starting on the configured month of 2026. */
	public String financialYear(LocalDate date) {
		int startMonth = companies.findAll().stream().findFirst().map(CompanyProfile::getFinancialYearStartMonth).orElse(4);
		int startYear = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
		return String.format("%02d-%02d", startYear % 100, (startYear + 1) % 100);
	}
}
