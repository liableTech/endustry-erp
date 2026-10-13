package com.endustry.erp.masters;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** The smaller masters, kept together because each is only a handful of fields. */
public final class SimpleMasters {

	private static final String CODE = "[A-Za-z0-9._-]{2,20}";
	private static final String CODE_MESSAGE = "must be 2 to 20 letters, digits, dot, dash or underscore";

	private SimpleMasters() {
	}

	@Entity
	@Table(name = "warehouse", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "code" }))
	@Getter
	@Setter
	public static class Warehouse extends BaseEntity {

		@NotBlank
		@Pattern(regexp = CODE, message = CODE_MESSAGE)
		@Column(nullable = false)
		private String code;

		@NotBlank
		@Size(max = 100)
		private String name;

		@Size(max = 250)
		private String address;

		private boolean active = true;
	}

	/** A machine or work centre where operations run (ORG-06). */
	@Entity
	@Table(name = "work_centre", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "code" }))
	@Getter
	@Setter
	public static class WorkCentre extends BaseEntity {

		@NotBlank
		@Pattern(regexp = CODE, message = CODE_MESSAGE)
		@Column(nullable = false)
		private String code;

		@NotBlank
		@Size(max = 100)
		private String name;

		private String location;

		@NotNull
		@PositiveOrZero
		private BigDecimal hourlyCost = BigDecimal.ZERO;

		@NotNull
		@DecimalMin(value = "0.5", message = "must be at least 0.5 hours")
		@DecimalMax(value = "24", message = "cannot be more than 24 hours")
		private BigDecimal capacityHoursPerDay = BigDecimal.valueOf(8);

		@Min(1)
		@Max(3)
		private int shifts = 1;

		private boolean active = true;
	}

	/** An employee or operator (ORG-07). */
	@Entity
	@Table(name = "employee", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "code" }))
	@Getter
	@Setter
	public static class Employee extends BaseEntity {

		public enum WageType {
			PIECE_RATE, HOURLY, MONTHLY
		}

		@NotBlank
		@Pattern(regexp = CODE, message = CODE_MESSAGE)
		@Column(nullable = false)
		private String code;

		@NotBlank
		@Size(max = 100)
		private String name;

		private String department;
		private String skill;

		@NotNull
		@Enumerated(EnumType.STRING)
		private WageType wageType;

		@NotNull
		@PositiveOrZero
		private BigDecimal wageRate = BigDecimal.ZERO;

		@Pattern(regexp = "^$|[0-9+ -]{7,15}", message = "is not a valid phone number")
		private String phone;

		private boolean active = true;
	}

	/** GST rate for an HSN code from a given date, so a rate change never needs items edited (GST-01). */
	@Entity
	@Table(name = "tax_rate", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "hsnCode", "effectiveFrom" }))
	@Getter
	@Setter
	public static class TaxRate extends BaseEntity {

		@NotBlank
		@Pattern(regexp = "\\d{4}(\\d{2})?(\\d{2})?", message = "must be 4, 6 or 8 digits")
		private String hsnCode;

		private String description;

		@NotNull
		@DecimalMin("0")
		@DecimalMax(value = "40", message = "cannot be more than 40%")
		private BigDecimal ratePct;

		@NotNull
		private LocalDate effectiveFrom;
	}

	/**
	 * A generic, user-maintained list: units, item categories, payment terms, reason codes, states.
	 * Adding a new kind of list needs no new table or code.
	 */
	@Entity
	@Table(name = "lookup", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "lookupType", "code" }))
	@Getter
	@Setter
	public static class Lookup extends BaseEntity {

		@NotBlank
		@Pattern(regexp = "[A-Z_]{2,30}", message = "must be capital letters and underscores")
		private String lookupType;

		@NotBlank
		@Size(max = 30)
		private String code;

		@NotBlank
		@Size(max = 100)
		private String name;

		private int sortOrder;
		private boolean active = true;

		public static Lookup of(String lookupType, String code, String name, int sortOrder) {
			Lookup lookup = new Lookup();
			lookup.lookupType = lookupType;
			lookup.code = code;
			lookup.name = name;
			lookup.sortOrder = sortOrder;
			return lookup;
		}
	}
}
