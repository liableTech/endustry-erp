package com.endustry.erp.masters;

import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;

import com.endustry.erp.platform.common.BaseEntity;
import com.endustry.erp.platform.validation.Gstin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** A customer, a vendor, or a business that is both (ORG-05). */
@Entity
@Table(name = "party", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "code" }))
@Getter
@Setter
public class Party extends BaseEntity {

	public enum Type {
		CUSTOMER, VENDOR, BOTH;

		public boolean canBe(Type role) {
			return this == BOTH || this == role;
		}
	}

	@NotBlank
	@Pattern(regexp = "[A-Za-z0-9._-]{2,20}", message = "must be 2 to 20 letters, digits, dot, dash or underscore")
	@Column(nullable = false)
	private String code;

	@NotBlank
	@Size(max = 150)
	private String name;

	@NotNull
	@Enumerated(EnumType.STRING)
	private Type type;

	@Gstin
	private String gstin;

	@Pattern(regexp = "^$|[A-Z]{5}\\d{4}[A-Z]", message = "is not a valid PAN")
	private String pan;

	@NotBlank
	@Pattern(regexp = "\\d{2}", message = "must be a 2-digit GST state code")
	private String stateCode;

	@Size(max = 250)
	private String address;

	private String city;

	@Pattern(regexp = "^$|\\d{6}", message = "must be 6 digits")
	private String pincode;

	private String contactPerson;

	@Pattern(regexp = "^$|[0-9+ -]{7,15}", message = "is not a valid phone number")
	private String phone;

	@Email
	private String email;

	/** Zero means no limit is enforced. */
	@PositiveOrZero
	private BigDecimal creditLimit = BigDecimal.ZERO;

	@Min(0)
	@Max(365)
	private int creditDays = 30;

	/** Registered micro or small enterprise: payments fall under the MSMED Act 45-day rule. */
	private boolean msmeRegistered;

	@Pattern(regexp = "^$|UDYAM-[A-Z]{2}-\\d{2}-\\d{7}", message = "must look like UDYAM-MH-12-0012345")
	private String udyamNumber;

	/** Whether payment terms are agreed in writing; without them an MSME vendor is due in 15 days. */
	private boolean msmeAgreedTerms;

	private boolean active = true;
}
