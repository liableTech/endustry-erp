package com.endustry.erp.platform.settings;

import jakarta.persistence.UniqueConstraint;
import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** Document numbering rule, e.g. INV/26-27/0001. */
@Entity
@Table(name = "number_series", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "seriesKey" }))
@Getter
@Setter
public class NumberSeries extends BaseEntity {

	@NotBlank
	@Column(nullable = false)
	private String seriesKey;

	@NotBlank
	@Pattern(regexp = "[A-Z0-9]{1,5}", message = "must be 1 to 5 capital letters or digits")
	private String prefix;

	private boolean includeFinancialYear = true;

	@Min(3)
	@Max(6)
	private int padding = 4;

	@Min(1)
	private long nextNumber = 1;

	public static NumberSeries of(String seriesKey, String prefix) {
		NumberSeries series = new NumberSeries();
		series.seriesKey = seriesKey;
		series.prefix = prefix;
		return series;
	}
}
