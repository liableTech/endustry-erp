package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.endustry.erp.masters.Item;
import com.endustry.erp.masters.MasterRepositories.TaxRateRepository;
import com.endustry.erp.masters.SimpleMasters.TaxRate;

import lombok.RequiredArgsConstructor;

/** Line amounts, document totals and the CGST / SGST / IGST split (SAL-10, GST-01). */
@Component
@RequiredArgsConstructor
public class TaxCalculator {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal TWO = BigDecimal.valueOf(2);

	private final TaxRateRepository taxRates;

	/** The dated rate for the item's HSN code if one exists, otherwise the rate on the item. */
	public BigDecimal rateFor(Item item, LocalDate date) {
		if (item.getHsnCode() == null || item.getHsnCode().isBlank()) {
			return item.getGstRate();
		}
		return taxRates.findFirstByHsnCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(item.getHsnCode(), date)
				.map(TaxRate::getRatePct).orElse(item.getGstRate());
	}

	/**
	 * Recomputes every amount on the document.
	 *
	 * @param companyState the company's GST state code; a supply within it is CGST + SGST
	 */
	public void compute(TradeDocument doc, String companyState) {
		BigDecimal subTotal = BigDecimal.ZERO;
		BigDecimal discount = BigDecimal.ZERO;
		BigDecimal taxable = BigDecimal.ZERO;
		BigDecimal tax = BigDecimal.ZERO;
		for (DocumentLine line : doc.getLines()) {
			BigDecimal gross = money(line.getQty().multiply(line.getRate()));
			BigDecimal lineDiscount = money(gross.multiply(line.getDiscountPct()).divide(HUNDRED));
			line.setTaxRatePct(rateFor(line.getItem(), doc.getDocDate()));
			line.setTaxableAmount(gross.subtract(lineDiscount));
			line.setTaxAmount(money(line.getTaxableAmount().multiply(line.getTaxRatePct()).divide(HUNDRED)));
			line.setLineTotal(line.getTaxableAmount().add(line.getTaxAmount()));
			subTotal = subTotal.add(gross);
			discount = discount.add(lineDiscount);
			taxable = taxable.add(line.getTaxableAmount());
			tax = tax.add(line.getTaxAmount());
		}
		doc.setSubTotal(subTotal);
		doc.setDiscountTotal(discount);
		doc.setTaxableTotal(taxable);
		if (companyState.equals(doc.getPlaceOfSupply())) {
			// Halve once and give the remainder to SGST so the two always add back to the total.
			BigDecimal cgst = tax.divide(TWO, 2, RoundingMode.HALF_UP);
			doc.setCgst(cgst);
			doc.setSgst(tax.subtract(cgst));
			doc.setIgst(BigDecimal.ZERO);
		} else {
			doc.setCgst(BigDecimal.ZERO);
			doc.setSgst(BigDecimal.ZERO);
			doc.setIgst(tax);
		}
		doc.setGrandTotal(taxable.add(tax));
	}

	private static BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}
}
