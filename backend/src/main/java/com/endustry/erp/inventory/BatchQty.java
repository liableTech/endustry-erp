package com.endustry.erp.inventory;

import java.math.BigDecimal;

/** A batch with stock left. */
public record BatchQty(String batchNo, BigDecimal qty) {
}
