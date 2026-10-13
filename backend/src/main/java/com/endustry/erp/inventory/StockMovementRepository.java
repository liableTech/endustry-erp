package com.endustry.erp.inventory;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.Query;

import com.endustry.erp.inventory.StockMovement.Status;
import com.endustry.erp.platform.common.BaseRepository;

public interface StockMovementRepository extends BaseRepository<StockMovement> {

	@Query("""
			select new com.endustry.erp.inventory.StockBalance(
			  i.id, i.code, i.name, i.uom, i.category, w.id, w.name, m.batchNo, m.status, sum(m.qty), i.avgCost, i.reorderLevel)
			from StockMovement m join m.item i join m.warehouse w
			group by i.id, i.code, i.name, i.uom, i.category, w.id, w.name, m.batchNo, m.status, i.avgCost, i.reorderLevel
			having sum(m.qty) <> 0
			order by i.code, w.name, m.batchNo""")
	List<StockBalance> balances();

	@Query("""
			select coalesce(sum(m.qty), 0) from StockMovement m
			where m.item.id = :itemId and m.status = :status and (:warehouseId is null or m.warehouse.id = :warehouseId)""")
	BigDecimal onHand(Long itemId, Long warehouseId, Status status);

	@Query("select coalesce(sum(m.qty), 0) from StockMovement m where m.item.id = :itemId")
	BigDecimal onHandAllStatuses(Long itemId);

	@Query("""
			select coalesce(sum(m.qty), 0) from StockMovement m
			where m.item.id = :itemId and m.warehouse.id = :warehouseId and m.status = :status and m.batchNo = :batchNo""")
	BigDecimal onHandBatch(Long itemId, Long warehouseId, Status status, String batchNo);

	@Query("""
			select new com.endustry.erp.inventory.BatchQty(m.batchNo, sum(m.qty))
			from StockMovement m
			where m.item.id = :itemId and m.warehouse.id = :warehouseId and m.status = :status
			group by m.batchNo having sum(m.qty) > 0 order by min(m.id)""")
	List<BatchQty> batchesOldestFirst(Long itemId, Long warehouseId, Status status);
}
