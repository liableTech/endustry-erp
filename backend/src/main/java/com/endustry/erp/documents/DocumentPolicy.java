package com.endustry.erp.documents;

import java.util.List;

import com.endustry.erp.documents.DocumentRequests.ActionRequest;

/**
 * The rules of one document kind. Adding a new kind of document means adding a {@link DocKind}
 * constant and one of these; storage, numbering, tax, API and screens are already there.
 */
public interface DocumentPolicy {

	DocKind kind();

	/** Actions available on the document in its current state, in display order. */
	List<String> actions(TradeDocument doc);

	/** Carries out an action already confirmed to be in {@link #actions}. */
	void execute(String action, TradeDocument doc, ActionRequest request);
}
