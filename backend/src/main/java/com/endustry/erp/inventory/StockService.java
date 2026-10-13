package com.endustry.erp.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.inventory.StockMovement.Status;
import com.endustry.erp.inventory.StockMovement.Type;
import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.SimpleMasters.Warehouse;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/** The only place stock changes. Every module moves stock through these few methods. */
@Service
@RequiredArgsConstructor
@Transactional
public class StockService {

	/** Where a movement came from, shown in the stock ledger. */
	public record Ref(String type, Long id, String no) {
	}

	private final StockMovementRepository movements;
	private final SettingsService settings;

	@Transactional(readOnly = true)
	public BigDecimal available(Item item, Warehouse warehouse) {
		return movements.onHand(item.getId(), warehouse == null ? null : warehouse.getId(), Status.AVAILABLE);
	}

	/** Brings stock in and folds the receipt into the item's weighted average cost (INV-07). */
	public void receive(Item item, Warehouse warehouse, String batchNo, Status status, BigDecimal qty, BigDecimal rate,
			Type type, Ref ref, LocalDate date) {
		requireStocked(item);
		BigDecimal before = movements.onHandAllStatuses(item.getId());
		if (before.signum() <= 0) {
			item.setAvgCost(rate.setScale(4, RoundingMode.HALF_UP));
		} else {
			BigDecimal value = before.multiply(item.getAvgCost()).add(qty.multiply(rate));
			item.setAvgCost(value.divide(before.add(qty), 4, RoundingMode.HALF_UP));
		}
		write(item, warehouse, batchNo, status, qty, rate, type, ref, date, null);
	}

	/**
	 * Takes available stock out, oldest batch first (INV-04), valued at average cost.
	 *
	 * @return the cost of what was issued
	 */
	public BigDecimal issue(Item item, Warehouse warehouse, BigDecimal qty, Type type, Ref ref, LocalDate date) {
		requireStocked(item);
		BigDecimal remaining = qty;
		for (BatchQty batch : movements.batchesOldestFirst(item.getId(), warehouse.getId(), Status.AVAILABLE)) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal take = remaining.min(batch.qty());
			write(item, warehouse, batch.batchNo(), Status.AVAILABLE, take.negate(), item.getAvgCost(), type, ref, date, null);
			remaining = remaining.subtract(take);
		}
		if (remaining.signum() > 0) {
			if (!settings.flag("inventory.allowNegativeStock")) {
				throw insufficient(item, warehouse, qty, qty.subtract(remaining));
			}
			write(item, warehouse, "", Status.AVAILABLE, remaining.negate(), item.getAvgCost(), type, ref, date, null);
		}
		return qty.multiply(item.getAvgCost()).setScale(2, RoundingMode.HALF_UP);
	}

	/** Moves a batch between statuses, e.g. quality releasing or rejecting received goods. */
	public void changeStatus(Item item, Warehouse warehouse, String batchNo, Status from, Status to, BigDecimal qty,
			Type type, Ref ref, LocalDate date) {
		if (qty.signum() == 0) {
			return;
		}
		BigDecimal held = movements.onHandBatch(item.getId(), warehouse.getId(), from, batchNo);
		if (held.compareTo(qty) < 0) {
			throw insufficient(item, warehouse, qty, held);
		}
		write(item, warehouse, batchNo, from, qty.negate(), item.getAvgCost(), type, ref, date, null);
		write(item, warehouse, batchNo, to, qty, item.getAvgCost(), type, ref, date, null);
	}

	/** Corrects stock up or down after a count, with a mandatory reason (INV-02). */
	public void adjust(Item item, Warehouse warehouse, String batchNo, BigDecimal qtyChange, String reason, LocalDate date) {
		requireStocked(item);
		String batch = batchNo == null ? "" : batchNo.trim();
		Ref ref = new Ref("ADJUSTMENT", null, reason);
		if (qtyChange.signum() > 0) {
			BigDecimal rate = item.getAvgCost().signum() > 0 ? item.getAvgCost() : item.getPurchasePrice();
			receive(item, warehouse, batch, Status.AVAILABLE, qtyChange, rate, Type.ADJUSTMENT, ref, date);
			return;
		}
		BigDecimal held = movements.onHandBatch(item.getId(), warehouse.getId(), Status.AVAILABLE, batch);
		if (held.add(qtyChange).signum() < 0) {
			throw insufficient(item, warehouse, qtyChange.negate(), held);
		}
		write(item, warehouse, batch, Status.AVAILABLE, qtyChange, item.getAvgCost(), Type.ADJUSTMENT, ref, date, reason);
	}

	/** Moves available stock between warehouses, keeping batch identity. */
	public void transfer(Item item, Warehouse from, Warehouse to, BigDecimal qty, LocalDate date) {
		requireStocked(item);
		if (from.getId().equals(to.getId())) {
			throw ApiException.rule("SAME_WAREHOUSE", "Choose two different warehouses for a transfer");
		}
		BigDecimal remaining = qty;
		Ref ref = new Ref("TRANSFER", null, from.getCode() + " to " + to.getCode());
		for (BatchQty batch : movements.batchesOldestFirst(item.getId(), from.getId(), Status.AVAILABLE)) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal take = remaining.min(batch.qty());
			write(item, from, batch.batchNo(), Status.AVAILABLE, take.negate(), item.getAvgCost(), Type.TRANSFER_OUT, ref, date, null);
			write(item, to, batch.batchNo(), Status.AVAILABLE, take, item.getAvgCost(), Type.TRANSFER_IN, ref, date, null);
			remaining = remaining.subtract(take);
		}
		if (remaining.signum() > 0) {
			throw insufficient(item, from, qty, qty.subtract(remaining));
		}
	}

	@Transactional(readOnly = true)
	public List<StockBalance> balances() {
		return movements.balances();
	}

	private void write(Item item, Warehouse warehouse, String batchNo, Status status, BigDecimal qty, BigDecimal rate,
			Type type, Ref ref, LocalDate date, String remarks) {
		StockMovement m = new StockMovement();
		m.setItem(item);
		m.setWarehouse(warehouse);
		m.setBatchNo(batchNo == null ? "" : batchNo);
		m.setStatus(status);
		m.setQty(qty);
		m.setRate(rate);
		m.setType(type);
		m.setMovementDate(date);
		m.setRefType(ref.type());
		m.setRefId(ref.id());
		m.setRefNo(ref.no());
		m.setRemarks(remarks);
		movements.saveAndFlush(m);
	}

	private static void requireStocked(Item item) {
		if (!item.getType().isStocked()) {
			throw ApiException.rule("NOT_A_STOCK_ITEM", item.getCode() + " is a service and carries no stock");
		}
	}

	private static ApiException insufficient(Item item, Warehouse warehouse, BigDecimal needed, BigDecimal available) {
		return ApiException.rule("INSUFFICIENT_STOCK",
				"Not enough stock of " + item.getCode() + " in " + warehouse.getName() + ": need "
						+ needed.stripTrailingZeros().toPlainString() + ", have " + available.stripTrailingZeros().toPlainString(),
				Map.of("item", item.getCode(), "needed", needed, "available", available));
	}
}
