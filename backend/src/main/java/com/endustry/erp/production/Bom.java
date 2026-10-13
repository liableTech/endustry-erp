package com.endustry.erp.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.SimpleMasters.WorkCentre;
import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Bill of materials with routing: what an item is made from and in which steps (BOM-01 to BOM-03). */
@Entity
@Table(name = "bom")
@Getter
@Setter
public class Bom extends BaseEntity {

	public enum Status {
		DRAFT, APPROVED, OBSOLETE
	}

	@ManyToOne(optional = false)
	private Item item;

	private int versionNo = 1;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.DRAFT;

	/** Quantity of the item one run of this BOM yields; component quantities are for this much output. */
	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal outputQty = BigDecimal.ONE;

	@Column(length = 400)
	private String notes;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "bom_id", nullable = false)
	@OrderBy("lineNo")
	private List<Line> lines = new ArrayList<>();

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "bom_id", nullable = false)
	@OrderBy("seqNo")
	private List<Operation> operations = new ArrayList<>();

	@Entity(name = "BomLine")
	@Table(name = "bom_line")
	@Getter
	@Setter
	public static class Line extends BaseEntity {

		private int lineNo;

		@ManyToOne(optional = false)
		private Item component;

		@Column(nullable = false, precision = 19, scale = 4)
		private BigDecimal qty;

		/** Expected loss; this much extra is consumed. */
		@Column(nullable = false, precision = 5, scale = 2)
		private BigDecimal scrapPct = BigDecimal.ZERO;
	}

	@Entity(name = "BomOperation")
	@Table(name = "bom_operation")
	@Getter
	@Setter
	public static class Operation extends BaseEntity {

		private int seqNo;

		@Column(nullable = false)
		private String name;

		@ManyToOne(optional = false)
		private WorkCentre workCentre;

		@Column(nullable = false, precision = 10, scale = 2)
		private BigDecimal setupMinutes = BigDecimal.ZERO;

		@Column(nullable = false, precision = 10, scale = 3)
		private BigDecimal runMinutesPerUnit = BigDecimal.ZERO;
	}
}
