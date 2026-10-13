package com.endustry.erp.platform.settings;

import com.endustry.erp.platform.common.BaseEntity;
import com.endustry.erp.platform.validation.Gstin;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** The company using the system (ORG-02). One row. */
@Entity
@Table(name = "company_profile")
@Getter
@Setter
public class CompanyProfile extends BaseEntity {

	@NotBlank
	@Size(max = 150)
	private String legalName;

	@Size(max = 60)
	private String tradeName;

	@Gstin
	private String gstin;

	@Pattern(regexp = "^$|[A-Z]{5}\\d{4}[A-Z]", message = "is not a valid PAN")
	private String pan;

	@Pattern(regexp = "^$|UDYAM-[A-Z]{2}-\\d{2}-\\d{7}", message = "must look like UDYAM-MH-12-0012345")
	private String udyamNumber;

	@Pattern(regexp = "^$|MICRO|SMALL|MEDIUM", message = "must be MICRO, SMALL or MEDIUM")
	private String msmeCategory;

	@NotBlank
	@Pattern(regexp = "\\d{2}", message = "must be a 2-digit GST state code")
	private String stateCode;

	@Size(max = 250)
	private String address;

	private String city;

	@Pattern(regexp = "^$|\\d{6}", message = "must be 6 digits")
	private String pincode;

	@Pattern(regexp = "^$|[0-9+ -]{7,15}", message = "is not a valid phone number")
	private String phone;

	@Email
	private String email;

	@Min(1)
	@Max(12)
	private int financialYearStartMonth = 4;

	@NotBlank
	@Size(min = 3, max = 3)
	private String baseCurrency = "INR";
}
