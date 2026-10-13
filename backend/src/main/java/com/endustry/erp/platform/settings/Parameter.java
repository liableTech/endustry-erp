package com.endustry.erp.platform.settings;

import jakarta.persistence.UniqueConstraint;
import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * A business or statutory value that changes without a code release: approval limits, tolerances,
 * the e-way bill threshold, MSME payment days and so on (BRD section 10).
 */
@Entity
@Table(name = "app_parameter", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "paramKey" }))
@Getter
@Setter
public class Parameter extends BaseEntity {

	@NotBlank
	@Column(nullable = false)
	private String paramKey;

	@NotBlank
	private String paramValue;

	/** NUMBER, BOOLEAN or a pipe-separated list of allowed choices such as BLOCK|WARN|OFF. */
	@NotBlank
	private String valueType;

	private String groupName;
	private String label;

	@Column(length = 400)
	private String description;

	public static Parameter of(String key, String value, String valueType, String groupName, String label, String description) {
		Parameter p = new Parameter();
		p.paramKey = key;
		p.paramValue = value;
		p.valueType = valueType;
		p.groupName = groupName;
		p.label = label;
		p.description = description;
		return p;
	}
}
