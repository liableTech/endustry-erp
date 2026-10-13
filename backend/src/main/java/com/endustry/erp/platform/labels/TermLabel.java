package com.endustry.erp.platform.labels;

import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** A client's own word for one of the {@link Terms}. Only renamed terms have a row. */
@Entity
@Table(name = "term_label", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "term_key" }))
@Getter
@Setter
public class TermLabel extends BaseEntity {

	@Column(name = "term_key", nullable = false, length = 40)
	private String termKey;

	@Column(nullable = false, length = 40)
	private String singular;

	@Column(nullable = false, length = 40)
	private String plural;
}
