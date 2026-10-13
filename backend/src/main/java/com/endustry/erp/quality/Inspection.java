package com.endustry.erp.quality;

import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.Party;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Incoming inspection of one received batch (QLT-02, QLT-04). */
@Entity
@Table(name = "inspection", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "inspectionNo" }))
@Getter
@Setter
public class Inspection extends BaseEntity {

	public enum Status {
		PENDING, COMPLETED
	}

	@Column(nullable = false)
	private String inspectionNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.PENDING;

	@ManyToOne(optional = false)
	private Item item;

	@ManyToOne(optional = false)
	private Warehouse warehouse;

	@ManyToOne
	private Party vendor;

	private String batchNo;
	private Long grnId;
	private String grnNo;

	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal receivedQty;

	@Column(precision = 19, scale = 3)
	private BigDecimal acceptedQty = BigDecimal.ZERO;

	@Column(precision = 19, scale = 3)
	private BigDecimal rejectedQty = BigDecimal.ZERO;

	private String reasonCode;

	@Column(length = 400)
	private String remarks;

	private String inspectedBy;
	private Instant inspectedAt;
}
