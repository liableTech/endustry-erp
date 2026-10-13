package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** What a client sends. Totals and tax are never accepted from the client; the server computes them. */
public final class DocumentRequests {

	private DocumentRequests() {
	}

	public record DocumentRequest(
			@NotNull(message = "is required") Long partyId,
			@NotNull(message = "is required") @PastOrPresent(message = "cannot be in the future") LocalDate docDate,
			Long warehouseId,
			LocalDate dueDate,
			@Size(max = 40) String referenceNo,
			@Size(max = 500) String notes,
			@NotEmpty(message = "needs at least one line") @Size(max = 200) @Valid List<Line> lines) {
	}

	public record Line(
			@NotNull(message = "is required") Long itemId,
			@NotNull(message = "is required") @Positive(message = "must be more than zero") @Digits(integer = 12, fraction = 3) BigDecimal qty,
			@NotNull(message = "is required") @PositiveOrZero(message = "cannot be negative") @Digits(integer = 12, fraction = 4) BigDecimal rate,
			@DecimalMin(value = "0", message = "cannot be negative") @DecimalMax(value = "100", message = "cannot be more than 100") BigDecimal discountPct,
			@Size(max = 40) String batchNo,
			@Size(max = 200) String description,
			Long sourceLineId) {
	}

	public record ActionRequest(Boolean override, @Size(max = 300) String note) {

		public boolean overrideRequested() {
			return Boolean.TRUE.equals(override);
		}
	}

	public record PaymentRequest(
			@NotNull(message = "is required") @Positive(message = "must be more than zero") @Digits(integer = 14, fraction = 2) BigDecimal amount,
			@NotNull(message = "is required") @PastOrPresent(message = "cannot be in the future") LocalDate paymentDate,
			@Size(max = 30) String mode,
			@Size(max = 60) String reference) {
	}
}
