package com.endustry.erp.printing;

import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A client's own print layout: HTML with {{placeholders}} filled from the document's print data.
 * One template per document type can be the default; without one the built-in layout is used.
 */
@Entity
@Table(name = "print_template")
@Getter
@Setter
public class PrintTemplate extends BaseEntity {

	@Column(nullable = false, length = 80)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PrintDocType docType;

	@Column(length = 250)
	private String description;

	/** Used whenever this type is printed and no other template is picked. */
	private boolean defaultForType;

	@Lob
	@Column(nullable = false)
	private String content;
}
