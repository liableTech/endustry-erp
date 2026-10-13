package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.endustry.erp.platform.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Money received against a sales invoice or paid against a purchase invoice. */
@Entity
@Table(name = "payment")
@Getter
@Setter
public class Payment extends BaseEntity {

	@ManyToOne(optional = false)
	@JsonIgnore
	private TradeDocument document;

	@Column(nullable = false)
	private LocalDate paymentDate;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	private String mode;
	private String reference;
}
