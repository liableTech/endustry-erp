package com.endustry.erp.documents;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.endustry.erp.platform.common.BaseRepository;

public final class DocumentRepositories {

	private DocumentRepositories() {
	}

	public interface TradeDocumentRepository extends BaseRepository<TradeDocument> {

		List<TradeDocument> findByKind(DocKind kind);

		List<TradeDocument> findByKindAndStatusIn(DocKind kind, Collection<DocStatus> statuses);

		List<TradeDocument> findByKindIn(Collection<DocKind> kinds, Pageable pageable);

		/** Unpaid value of a party's posted invoices of one kind. */
		@Query("""
				select coalesce(sum(d.grandTotal - d.paidAmount), 0) from TradeDocument d
				where d.party.id = :partyId and d.kind = :kind
				  and d.status in (com.endustry.erp.documents.DocStatus.POSTED, com.endustry.erp.documents.DocStatus.PARTLY_PAID)""")
		BigDecimal outstanding(Long partyId, DocKind kind);

		@Query("""
				select count(d) > 0 from TradeDocument d
				where d.kind = :kind and d.party.id = :partyId and lower(d.referenceNo) = lower(:referenceNo)
				  and d.id <> :excludeId
				  and d.status not in (com.endustry.erp.documents.DocStatus.DRAFT, com.endustry.erp.documents.DocStatus.CANCELLED)""")
		boolean referenceUsed(DocKind kind, Long partyId, String referenceNo, Long excludeId);
	}

	public interface PaymentRepository extends JpaRepository<Payment, Long> {
		List<Payment> findByDocumentIdOrderByPaymentDateAscIdAsc(Long documentId);
	}
}
