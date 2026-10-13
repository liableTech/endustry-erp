package com.endustry.erp.inventory;

import java.math.BigDecimal;

import com.endustry.erp.inventory.StockMovement.Status;

/** Stock on hand per item, warehouse, batch and status. */
public record StockBalance(Long itemId, String itemCode, String itemName, String uom, String category, Long warehouseId,
		String warehouseName, String batchNo, Status status, BigDecimal qty, BigDecimal avgCost, BigDecimal reorderLevel) {

	public BigDecimal value() {
		return qty.multiply(avgCost);
	}
}
