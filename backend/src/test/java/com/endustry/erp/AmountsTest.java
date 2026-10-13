package com.endustry.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.endustry.erp.platform.common.Amounts;

class AmountsTest {

	@Test
	void groupsInLakhsAndCrores() {
		assertThat(Amounts.inr(new BigDecimal("0"))).isEqualTo("₹0");
		assertThat(Amounts.inr(new BigDecimal("999"))).isEqualTo("₹999");
		assertThat(Amounts.inr(new BigDecimal("1000"))).isEqualTo("₹1,000");
		assertThat(Amounts.inr(new BigDecimal("101952"))).isEqualTo("₹1,01,952");
		assertThat(Amounts.inr(new BigDecimal("2500000"))).isEqualTo("₹25,00,000");
		assertThat(Amounts.inr(new BigDecimal("14175587.8"))).isEqualTo("₹1,41,75,587.80");
		assertThat(Amounts.inr(new BigDecimal("123456789.005"))).isEqualTo("₹12,34,56,789.01");
	}

	@Test
	void keepsTheSignAndDropsZeroPaise() {
		assertThat(Amounts.inr(new BigDecimal("-1500.50"))).isEqualTo("-₹1,500.50");
		assertThat(Amounts.inr(new BigDecimal("500000.00"))).isEqualTo("₹5,00,000");
	}
}
