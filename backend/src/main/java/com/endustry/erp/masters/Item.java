package com.endustry.erp.masters;

import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;

import com.endustry.erp.platform.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Anything bought, made, stocked or sold (ORG-03). */
@Entity
@Table(name = "item", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "code" }))
@Getter
@Setter
public class Item extends BaseEntity {

	public enum Type {
		RAW_MATERIAL, COMPONENT, CONSUMABLE, SEMI_FINISHED, FINISHED, SCRAP, SERVICE;

		public boolean isStocked() {
			return this != SERVICE;
		}

		public boolean isManufactured() {
			return this == FINISHED || this == SEMI_FINISHED;
		}
	}

	@NotBlank
	@Pattern(regexp = "[A-Za-z0-9._-]{2,20}", message = "must be 2 to 20 letters, digits, dot, dash or underscore")
	@Column(nullable = false)
	private String code;

	@NotBlank
	@Size(max = 120)
	private String name;

	/** Name in the shop-floor language, shown on job cards. */
	@Size(max = 120)
	private String aliasName;

	@NotNull
	@Enumerated(EnumType.STRING)
	private Type type;

	private String category;

	@NotBlank
	@Size(max = 10)
	private String uom;

	@Pattern(regexp = "^$|\\d{4}(\\d{2})?(\\d{2})?", message = "must be 4, 6 or 8 digits")
	private String hsnCode;

	/** Used when no dated rate exists for the HSN code in the tax rate master. */
	@NotNull
	@DecimalMin("0")
	@DecimalMax(value = "40", message = "cannot be more than 40%")
	private BigDecimal gstRate = BigDecimal.ZERO;

	@PositiveOrZero
	private BigDecimal purchasePrice = BigDecimal.ZERO;

	@PositiveOrZero
	private BigDecimal salePrice = BigDecimal.ZERO;

	@PositiveOrZero
	private BigDecimal standardCost = BigDecimal.ZERO;

	/** Weighted average cost, maintained by stock receipts. */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private BigDecimal avgCost = BigDecimal.ZERO;

	@PositiveOrZero
	private BigDecimal reorderLevel = BigDecimal.ZERO;

	@Min(0)
	private int leadTimeDays;

	private boolean batchTracked;
	private boolean qcRequired;
	private boolean active = true;
}
