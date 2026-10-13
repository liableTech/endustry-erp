import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowRight, FileInput, Pencil, Plus, Trash2, Truck, Wallet } from 'lucide-react';
import { api, ApiError, query, type Page } from '../core/api';
import { useAuth, useLabels } from '../core/auth';
import { DERIVE_LABELS, DOCUMENTS, type DocumentConfig, relabelDocument } from '../config/documents';
import { formatDate, formatMoney, formatQty, today } from '../core/format';
import { useDebounced, useFetch, useRecordRoute, useRecords } from '../core/hooks';
import { useTheme } from '../core/theme';
import { computeTotals } from '../core/validation';
import { FilterButton, FilterPanel, useFilters, type FilterDef } from './Filters';
import { Field, Select } from './FormFields';
import { PrintActions } from './PrintActions';
import { Banner, Button, Card, DataTable, FullPage, IconButton, Modal, PageHeader, Pager, SearchBox, Spinner, StatusBadge, Tabs, useFeedback, type Column } from './ui';
import { t } from '../core/i18n';

const PAGE_SIZE = 15;
const PAYMENT_MODES = ['NEFT', 'RTGS', 'UPI', 'Cheque', 'Cash'].map((m) => ({ value: m, label: m }));

const apiPath = (config: DocumentConfig) => `/${config.module}/docs/${config.slug}`;

/** List screen for one document kind; its editor and detail sheet open as pages of their own. */
export function DocumentPage({ config: standard }: { config: DocumentConfig }) {
  const { can } = useAuth();
  const { word, one } = useLabels();
  const config = relabelDocument(standard, word, one);
  const [params] = useSearchParams();
  const record = useRecordRoute();
  const editable = can(config.module, 'FULL');

  const [status, setStatus] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [picking, setPicking] = useState(false);
  const q = useDebounced(search);
  // Links saved before documents had their own address used ?open=<id>.
  const legacyOpen = params.get('open');

  // Reset when another document kind reuses this screen. Keyed on the original config: the relabelled
  // copy is new on every render and would undo each tab, search or picker click.
  useEffect(() => {
    setStatus('ALL');
    setSearch('');
    setPage(0);
    setPicking(false);
  }, [standard]);

  useEffect(() => {
    if (legacyOpen) record.open(legacyOpen, { replace: true });
  }, [legacyOpen]);

  const parties = useRecords<any>(`/masters/parties?type=${config.partyRole},BOTH`);
  const filterDefs = useMemo<FilterDef[]>(() => [
    { key: 'partyId', label: config.partyLabel, kind: 'choice', dropdown: true, options: parties.map((p) => ({ value: String(p.id), label: p.name })) },
    { key: 'docDate', label: 'Date', kind: 'date' },
    { key: 'dueDate', label: config.dueLabel, kind: 'date' },
    { key: 'grandTotal', label: 'Total', kind: 'number' },
  ], [parties.length, config.partyLabel, config.dueLabel]);
  const filters = useFilters(filterDefs, { onChange: () => setPage(0) });

  const list = useFetch<Page<any>>(`${apiPath(config)}${query({ q, page, size: PAGE_SIZE, status: status === 'ALL' ? '' : status, ...filters.params })}`);
  const isInvoice = config.slug.endsWith('invoice');

  const columns: Column[] = [
    { key: 'docNo', label: 'Number', render: (d) => <span className="strong">{d.docNo}</span> },
    { key: 'docDate', label: 'Date', render: (d) => formatDate(d.docDate) },
    { key: 'party', label: config.partyLabel, render: (d) => d.party.name },
    { key: 'referenceNo', label: config.referenceLabel, render: (d) => d.referenceNo ?? <span className="muted">-</span> },
    { key: 'grandTotal', label: 'Total', align: 'right', render: (d) => formatMoney(d.grandTotal) },
    ...(isInvoice ? [
      { key: 'balance', label: 'Balance', align: 'right' as const, render: (d: any) => (Number(d.balance) > 0 && d.status !== 'DRAFT' && d.status !== 'CANCELLED' ? formatMoney(d.balance) : <span className="muted">-</span>) },
      { key: 'dueDate', label: config.dueLabel, render: (d: any) => formatDate(d.dueDate) },
    ] : []),
    { key: 'status', label: 'Status', render: (d) => <StatusBadge status={d.status} labels={config.statusLabels} /> },
  ];

  if (record.id === 'new') {
    return editable
      ? <DocumentEditor config={config} onClose={record.close} onSaved={(doc) => { list.reload(); record.open(doc.id, { replace: true }); }} />
      : <FullPage title={t('New {0}', { 0: config.singular })} onClose={record.close}><Banner tone="warning">{t('You can view {0} but not create them.', { 0: config.title })}</Banner></FullPage>;
  }
  if (record.id) {
    return (
      <DocumentSheet key={record.id} config={config} id={Number(record.id)} editable={editable} editing={record.mode === 'edit'}
        onEdit={(on) => record.open(record.id!, { mode: on ? 'edit' : undefined, replace: !on })} onClose={record.close} onChanged={list.reload} />
    );
  }

  return (
    <div className="page">
      <PageHeader title={config.title} subtitle={config.subtitle} actions={editable && (
        <>
          {config.source && can(DOCUMENTS[config.source.slug].module) && <Button icon={FileInput} variant={config.allowDirect ? 'default' : 'primary'} onClick={() => setPicking(true)}>{config.source.buttonLabel}</Button>}
          {config.allowDirect && <Button variant="primary" icon={Plus} onClick={() => record.open('new')}>{t('New {0}', { 0: config.singular })}</Button>}
        </>
      )} />
      <Card flush>
        <div className="toolbar between">
          <Tabs value={status} onChange={(s) => { setStatus(s); setPage(0); }}
            options={[{ value: 'ALL', label: 'All' }, ...config.statuses.map((s) => ({ value: s, label: config.statusLabels?.[s] ?? s.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()) }))]} />
          <div className="row wrap">
            <SearchBox value={search} onChange={(v) => { setSearch(v); setPage(0); }} placeholder={t('Search number, {0} or reference', { 0: config.partyLabel.toLowerCase() })} />
            <FilterButton filters={filters} />
          </div>
        </div>
        <FilterPanel filters={filters} />
        {list.error ? <div className="card-body"><Banner tone="danger">{list.error.message}</Banner></div> : (
          <DataTable columns={columns} rows={list.data?.content ?? []} loading={list.loading} onRowClick={(d) => record.open(d.id)}
            emptyTitle={t('No {0} here', { 0: config.title.toLowerCase() })} emptyHint={filters.count ? 'No record matches these filters.' : status !== 'ALL' || q ? 'Try another tab or search.' : undefined} />
        )}
        <Pager page={page} totalPages={list.data?.totalPages ?? 0} totalElements={list.data?.totalElements ?? 0} onPage={setPage} />
      </Card>

      {picking && config.source && <SourcePicker config={config} onClose={() => setPicking(false)} onCreated={(doc) => { setPicking(false); list.reload(); record.open(doc.id); }} />}
    </div>
  );
}

/* ---- pick a source document to raise this one from ---- */

function SourcePicker({ config, onClose, onCreated }: { config: DocumentConfig; onClose: () => void; onCreated: (doc: any) => void }) {
  const feedback = useFeedback();
  const { word, one } = useLabels();
  const source = relabelDocument(DOCUMENTS[config.source!.slug], word, one);
  const list = useFetch<Page<any>>(`${apiPath(source)}${query({ status: config.source!.statuses, size: 50 })}`);
  const [busy, setBusy] = useState<number | null>(null);

  async function pick(doc: any) {
    setBusy(doc.id);
    try {
      const created = await api.post(`${apiPath(config)}/from/${doc.id}`);
      feedback.success(t('Draft {0} created from {1}', { 0: created.docNo, 1: doc.docNo }));
      onCreated(created);
    } catch (e) {
      feedback.error(e);
      setBusy(null);
    }
  }

  return (
    <Modal wide title={config.source!.buttonLabel} onClose={onClose}>
      {list.loading ? <Spinner /> : list.error ? <Banner tone="danger">{list.error.message}</Banner> : (
        <DataTable rows={list.data?.content ?? []} emptyTitle={t('No open {0}', { 0: source.title.toLowerCase() })}
          emptyHint={t('A {0} can be raised once a {1} is ready for it.', { 0: config.singular, 1: source.singular })}
          columns={[
            { key: 'docNo', label: 'Number', render: (d) => <span className="strong">{d.docNo}</span> },
            { key: 'docDate', label: 'Date', render: (d) => formatDate(d.docDate) },
            { key: 'party', label: source.partyLabel, render: (d) => d.party.name },
            { key: 'total', label: 'Total', align: 'right', render: (d) => formatMoney(d.grandTotal) },
            { key: 'pick', label: '', align: 'right', render: (d) => <Button small variant="primary" busy={busy === d.id} onClick={() => pick(d)}>{t('Select')}</Button> },
          ]} />
      )}
    </Modal>
  );
}

/* ---- create or edit a draft ---- */

interface LineState {
  itemId: string;
  qty: string;
  rate: string;
  discountPct: string;
  sourceLineId: number | null;
}

const blankLine = (): LineState => ({ itemId: '', qty: '1', rate: '', discountPct: '0', sourceLineId: null });

function DocumentEditor({ config, doc, onClose, onSaved }: { config: DocumentConfig; doc?: any; onClose: () => void; onSaved: (doc: any) => void }) {
  const feedback = useFeedback();
  const { companyState } = useTheme();
  const parties = useRecords<any>(`/masters/parties?type=${config.partyRole},BOTH&active=true`);
  const items = useRecords<any>('/masters/items?active=true');
  const warehouses = useRecords<any>('/masters/warehouses?active=true');
  const derived = Boolean(doc?.sourceDoc);

  const [partyId, setPartyId] = useState(doc ? String(doc.party.id) : '');
  const [docDate, setDocDate] = useState(doc?.docDate ?? today());
  const [warehouseId, setWarehouseId] = useState(doc?.warehouse ? String(doc.warehouse.id) : '');
  const [referenceNo, setReferenceNo] = useState(doc?.referenceNo ?? '');
  const [dueDate, setDueDate] = useState(doc?.dueDate ?? '');
  const [notes, setNotes] = useState(doc?.notes ?? '');
  const [lines, setLines] = useState<LineState[]>(() => doc
    ? doc.lines.map((l: any) => ({ itemId: String(l.item.id), qty: String(l.qty), rate: String(l.rate), discountPct: String(l.discountPct ?? 0), sourceLineId: l.sourceLineId }))
    : [blankLine()]);
  const [problems, setProblems] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  // A single warehouse is the common case; pre-select it.
  useEffect(() => {
    if (!warehouseId && config.needsWarehouse && warehouses.length > 0) setWarehouseId(String(warehouses[0].id));
  }, [warehouses, warehouseId, config.needsWarehouse]);

  const itemById = useMemo(() => new Map(items.map((i) => [String(i.id), i])), [items]);
  const party = parties.find((p) => String(p.id) === partyId);
  const totals = computeTotals(
    lines.map((l) => ({ ...l, taxRatePct: itemById.get(l.itemId)?.gstRate ?? 0 })),
    !party || party.stateCode === companyState,
  );
  // Receipts are always at the ordered price, so the rate is not editable there.
  const rateLocked = config.slug === 'grn';

  function setLine(index: number, change: Partial<LineState>) {
    setLines((prev) => prev.map((l, i) => {
      if (i !== index) return l;
      const next = { ...l, ...change };
      if (change.itemId !== undefined && !l.sourceLineId) {
        const item = itemById.get(change.itemId);
        if (item) next.rate = String(item[config.priceField] ?? 0);
      }
      return next;
    }));
  }

  function check(): string[] {
    const found: string[] = [];
    if (!partyId) found.push(t('Choose a {0}.', { 0: config.partyLabel }));
    if (!docDate) found.push('Enter the document date.');
    else if (docDate > today()) found.push('The document date cannot be in the future.');
    if (dueDate && docDate && dueDate < docDate) found.push(t('{0} cannot be before the document date.', { 0: t(config.dueLabel) }));
    if (lines.length === 0) found.push('Add at least one line.');
    lines.forEach((l, i) => {
      const n = i + 1;
      if (!l.itemId) found.push(t('Line {0}: choose an item.', { 0: n }));
      if (!(Number(l.qty) > 0)) found.push(t('Line {0}: quantity must be more than zero.', { 0: n }));
      if (l.rate === '' || Number(l.rate) < 0 || Number.isNaN(Number(l.rate))) found.push(t('Line {0}: enter a rate of zero or more.', { 0: n }));
      if (Number(l.discountPct) < 0 || Number(l.discountPct) > 100) found.push(t('Line {0}: discount must be between 0 and 100.', { 0: n }));
    });
    return found;
  }

  async function save() {
    const found = check();
    setProblems(found);
    if (found.length > 0) return;
    setSaving(true);
    const body = {
      partyId: Number(partyId), docDate, warehouseId: warehouseId ? Number(warehouseId) : null,
      referenceNo: referenceNo || null, dueDate: dueDate || null, notes: notes || null,
      lines: lines.map((l) => ({ itemId: Number(l.itemId), qty: Number(l.qty), rate: Number(l.rate), discountPct: Number(l.discountPct || 0), sourceLineId: l.sourceLineId })),
    };
    try {
      const saved = doc ? await api.put(`${apiPath(config)}/${doc.id}`, body) : await api.post(apiPath(config), body);
      feedback.success(t('{0} saved as draft', { 0: saved.docNo }));
      onSaved(saved);
    } catch (e) {
      const error = e as ApiError;
      const fields = Object.entries(error.fieldErrors ?? {}).map(([field, message]) => `${field.replace(/lines\[(\d+)\]\./, (_, i) => t('Line {0}', { 0: Number(i) + 1 }) + ' ')} ${t(message)}`);
      setProblems(fields.length ? fields : [error.message]);
    } finally {
      setSaving(false);
    }
  }

  return (
    <FullPage wide title={doc ? t('Edit {0}', { 0: doc.docNo }) : t('New {0}', { 0: config.singular })} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save draft')}</Button></>}>
      <div className="stack">
        {problems.length > 0 && <Banner tone="danger">{problems.length === 1 ? problems[0] : <ul style={{ margin: 0, paddingLeft: 18 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul>}</Banner>}
        {derived && <Banner tone="info">{t('Raised from {0}. The {1} and items stay as on that document; reduce quantities for a part {2}.', { 0: doc.sourceDoc.docNo, 1: config.partyLabel, 2: config.singular })}</Banner>}
        <div className="form-grid">
          <Field label={config.partyLabel} required>
            <Select value={partyId} onChange={setPartyId} disabled={derived} options={parties.map((p) => ({ value: String(p.id), label: `${p.name} (${p.code})` }))} />
          </Field>
          <Field label={t('Date')} required><input className="input" type="date" value={docDate} max={today()} onChange={(e) => setDocDate(e.target.value)} /></Field>
          {config.needsWarehouse && (
            <Field label={t('Warehouse')}><Select value={warehouseId} onChange={setWarehouseId} options={warehouses.map((w) => ({ value: String(w.id), label: w.name }))} /></Field>
          )}
          <Field label={config.referenceLabel}><input className="input" value={referenceNo} maxLength={40} onChange={(e) => setReferenceNo(e.target.value)} /></Field>
          {config.slug !== 'grn' && (
            <Field label={config.dueLabel} help={config.slug.endsWith('invoice') ? 'Leave blank to use the party\'s payment terms.' : undefined}>
              <input className="input" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
            </Field>
          )}
          <Field label={t('Notes')} span={2}><textarea className="input" value={notes} maxLength={500} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>

        <div className="table-wrap">
          <table className="table lines">
            <thead>
              <tr><th style={{ width: '38%' }}>{t('Item')}</th><th className="right">{t('Qty')}</th><th className="right">{t('Rate')}</th><th className="right">{t('Disc %')}</th><th className="right">{t('GST %')}</th><th className="right">{t('Amount')}</th><th /></tr>
            </thead>
            <tbody>
              {lines.map((line, i) => {
                const item = itemById.get(line.itemId);
                const amount = Number(line.qty || 0) * Number(line.rate || 0) * (1 - Number(line.discountPct || 0) / 100);
                return (
                  <tr key={i}>
                    <td><Select value={line.itemId} disabled={Boolean(line.sourceLineId)} onChange={(v) => setLine(i, { itemId: v })} placeholder={t('Choose item')}
                      options={items.map((it) => ({ value: String(it.id), label: `${it.code} - ${it.name}` }))} /></td>
                    <td><input className="input num" type="number" min="0" step="any" value={line.qty} onChange={(e) => setLine(i, { qty: e.target.value })} aria-label={t('Line {0} quantity', { 0: i + 1 })} /></td>
                    <td><input className="input num" type="number" min="0" step="any" value={line.rate} disabled={rateLocked} onChange={(e) => setLine(i, { rate: e.target.value })} aria-label={t('Line {0} rate', { 0: i + 1 })} /></td>
                    <td><input className="input num" type="number" min="0" max="100" step="any" value={line.discountPct} disabled={rateLocked} onChange={(e) => setLine(i, { discountPct: e.target.value })} aria-label={t('Line {0} discount', { 0: i + 1 })} /></td>
                    <td className="num muted">{item ? formatQty(item.gstRate) : '-'}</td>
                    <td className="num strong">{formatMoney(amount)}</td>
                    <td><IconButton icon={Trash2} label={t('Remove line')} disabled={lines.length === 1} onClick={() => setLines((prev) => prev.filter((_, j) => j !== i))} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="row between" style={{ alignItems: 'flex-start' }}>
          {derived ? <span /> : <Button icon={Plus} small onClick={() => setLines((prev) => [...prev, blankLine()])}>{t('Add line')}</Button>}
          <TotalsBlock subTotal={totals.subTotal} discount={totals.discount} taxable={totals.taxable} cgst={totals.cgst} sgst={totals.sgst} igst={totals.igst} grandTotal={totals.grandTotal} />
        </div>
        <p className="muted" style={{ fontSize: 12.5 }}>{t('Totals here are a preview. Tax is finalised on saving, using the GST rate in force on the document date.')}</p>
      </div>
    </FullPage>
  );
}

function TotalsBlock(totals: { subTotal: unknown; discount: unknown; taxable: unknown; cgst: unknown; sgst: unknown; igst: unknown; grandTotal: unknown; paid?: unknown; balance?: unknown }) {
  return (
    <div className="totals mono">
      <div><span className="muted">{t('Sub total')}</span><span>{formatMoney(totals.subTotal)}</span></div>
      {Number(totals.discount) > 0 && <div><span className="muted">{t('Discount')}</span><span>- {formatMoney(totals.discount)}</span></div>}
      <div><span className="muted">{t('Taxable value')}</span><span>{formatMoney(totals.taxable)}</span></div>
      {Number(totals.igst) > 0
        ? <div><span className="muted">{t('IGST')}</span><span>{formatMoney(totals.igst)}</span></div>
        : <><div><span className="muted">{t('CGST')}</span><span>{formatMoney(totals.cgst)}</span></div><div><span className="muted">{t('SGST')}</span><span>{formatMoney(totals.sgst)}</span></div></>}
      <div className="grand"><span>{t('Total')}</span><span>{formatMoney(totals.grandTotal)}</span></div>
      {totals.paid !== undefined && Number(totals.paid) > 0 && (
        <><div><span className="muted">{t('Paid')}</span><span>{formatMoney(totals.paid)}</span></div><div className="strong"><span>{t('Balance due')}</span><span>{formatMoney(totals.balance)}</span></div></>
      )}
    </div>
  );
}

/* ---- view a document and move it forward ---- */

function DocumentSheet({ config, id, editable, editing, onEdit, onClose, onChanged }: {
  config: DocumentConfig; id: number; editable: boolean; editing: boolean; onEdit: (on: boolean) => void; onClose: () => void; onChanged: () => void;
}) {
  const feedback = useFeedback();
  const navigate = useNavigate();
  const { user, can } = useAuth();
  const { data: doc, loading, error, reload } = useFetch<any>(`${apiPath(config)}/${id}`);
  const isInvoice = config.slug.endsWith('invoice');
  const payments = useFetch<any[]>(isInvoice && doc && Number(doc.paidAmount) > 0 ? `${apiPath(config)}/${id}/payments` : null);
  const [paying, setPaying] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = () => { reload(); payments.reload(); onChanged(); };

  async function run(action: string, override = false) {
    const def = config.actions[action];
    if (!override && def?.confirm && !(await feedback.confirm({ title: def.label, message: def.confirm, confirmLabel: def.label, danger: def.variant === 'danger' }))) return;
    setBusy(action);
    try {
      const updated = await api.post(`${apiPath(config)}/${id}/actions/${action}`, override ? { override: true } : undefined);
      feedback.success(t('{0} is now {1}', { 0: updated.docNo, 1: (config.statusLabels?.[updated.status] ?? updated.status).replace(/_/g, ' ').toLowerCase() }));
      refresh();
    } catch (e) {
      const apiError = e as ApiError;
      // SAL-07: an Owner may knowingly let an order through past the credit limit.
      if (apiError.code === 'CREDIT_LIMIT_EXCEEDED' && user?.role === 'OWNER' && !override) {
        const ok = await feedback.confirm({ title: 'Credit limit exceeded', message: <>{apiError.message}<br /><br />{t('Override the limit for this document? Your name is recorded against the override.')}</>, confirmLabel: 'Override and continue', danger: true });
        if (ok) { setBusy(null); return run(action, true); }
      } else {
        feedback.error(e);
        // Nothing can be posted without a warehouse, so take the user straight to where it is set.
        if (apiError.code === 'WAREHOUSE_REQUIRED') onEdit(true);
      }
    } finally {
      setBusy(null);
    }
  }

  async function derive(targetSlug: string) {
    const target = DOCUMENTS[targetSlug];
    setBusy(targetSlug);
    try {
      const created = await api.post(`${apiPath(target)}/from/${id}`);
      feedback.success(t('Draft {0} created', { 0: created.docNo }));
      navigate(`${target.route}/${created.id}`);
    } catch (e) {
      feedback.error(e);
      setBusy(null);
    }
  }

  if (editing && doc) {
    return editable && doc.status === 'DRAFT'
      ? <DocumentEditor config={config} doc={doc} onClose={() => onEdit(false)} onSaved={() => { onEdit(false); refresh(); }} />
      : <FullPage wide title={t('Edit {0}', { 0: doc.docNo })} onClose={() => onEdit(false)}><Banner tone="warning">{t('Only a draft can be edited, by someone with full access to {0}.', { 0: config.module })}</Banner></FullPage>;
  }

  const actions: string[] = doc?.allowedActions ?? [];
  const footer = doc && (
    <>
      <PrintActions path={`${apiPath(config)}/${id}`} templatesPath={`${apiPath(config)}/print-templates`} fallbackName={doc.docNo.replace(/\//g, '-')} />
      {editable && doc.status === 'DRAFT' && <Button icon={Pencil} onClick={() => onEdit(true)}>{t('Edit')}</Button>}
      {editable && actions.filter((a) => a !== 'pay' && config.actions[a]?.variant === 'danger').map((a) => (
        <Button key={a} variant="ghost" busy={busy === a} onClick={() => run(a)}>{config.actions[a].label}</Button>
      ))}
      <span className="grow" />
      {(doc.derivations as string[]).filter((slug) => can(DOCUMENTS[slug].module, 'FULL')).map((slug) => (
        <Button key={slug} icon={ArrowRight} busy={busy === slug} onClick={() => derive(slug)}>{DERIVE_LABELS[slug]}</Button>
      ))}
      {editable && actions.includes('pay') && <Button variant="primary" icon={Wallet} onClick={() => setPaying(true)}>{t(config.partyRole === 'CUSTOMER' ? 'Record receipt' : 'Record payment')}</Button>}
      {editable && actions.filter((a) => a !== 'pay' && config.actions[a]?.variant !== 'danger').map((a) => (
        <Button key={a} variant="primary" busy={busy === a} onClick={() => run(a)}>{config.actions[a]?.label ?? a}</Button>
      ))}
    </>
  );

  return (
    <FullPage wide onClose={onClose} footer={footer}
      title={doc ? <span className="row">{doc.docNo}<StatusBadge status={doc.status} labels={config.statusLabels} /></span> : config.singular}>
      {loading && !doc ? <Spinner /> : error ? <Banner tone="danger">{error.message}</Banner> : doc && (
        <div className="stack">
          {doc.alertNote && <Banner tone="warning">{doc.alertNote}</Banner>}
          {doc.status === 'DRAFT' && config.needsWarehouse && !doc.warehouse && <Banner tone="info">{t('No warehouse is chosen yet. Use Edit to pick the warehouse this {0} moves stock in or out of.', { 0: config.singular })}</Banner>}
          {doc.ewayBillRequired && <Banner tone="info"><span className="row"><Truck size={16} />{t('This invoice is above the e-way bill threshold; generate an e-way bill before the goods move.')}</span></Banner>}
          <dl className="kv">
            <div><dt>{config.partyLabel}</dt><dd>{doc.party.name}</dd></div>
            <div><dt>{t('GSTIN')}</dt><dd>{doc.party.gstin ?? 'Unregistered'}</dd></div>
            <div><dt>{t('Date')}</dt><dd>{formatDate(doc.docDate)}</dd></div>
            <div><dt>{config.dueLabel}</dt><dd>{formatDate(doc.dueDate)}</dd></div>
            <div><dt>{config.referenceLabel}</dt><dd>{doc.referenceNo ?? '-'}</dd></div>
            <div><dt>{t('Warehouse')}</dt><dd>{doc.warehouse?.name ?? '-'}</dd></div>
            <div><dt>{t('Place of supply')}</dt><dd>{t('State {0}', { 0: doc.placeOfSupply })}</dd></div>
            {doc.sourceDoc && <div><dt>{t('Raised from')}</dt><dd>{doc.sourceDoc.docNo}</dd></div>}
          </dl>
          {doc.notes && <p className="muted">{doc.notes}</p>}
          <div className="table-wrap card">
            <table className="table">
              <thead><tr><th>#</th><th>{t('Item')}</th><th>{t('Batch')}</th><th className="right">{t('Qty')}</th><th className="right">{t('Rate')}</th><th className="right">{t('Disc %')}</th><th className="right">{t('GST %')}</th><th className="right">{t('Amount')}</th><th className="right">{t('Pending')}</th></tr></thead>
              <tbody>
                {doc.lines.map((l: any) => (
                  <tr key={l.id}>
                    <td className="muted">{l.lineNo}</td>
                    <td><span className="strong">{l.item.code}</span> <span className="muted">{l.item.name}</span></td>
                    <td>{l.batchNo ?? <span className="muted">-</span>}</td>
                    <td className="num">{formatQty(l.qty)} {l.uom}</td>
                    <td className="num">{formatMoney(l.rate)}</td>
                    <td className="num">{formatQty(l.discountPct)}</td>
                    <td className="num">{formatQty(l.taxRatePct)}</td>
                    <td className="num strong">{formatMoney(l.taxableAmount)}</td>
                    <td className="num muted">{isInvoice || doc.status === 'DRAFT' ? '-' : formatQty(l.pendingQty)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <TotalsBlock subTotal={doc.subTotal} discount={doc.discountTotal} taxable={doc.taxableTotal} cgst={doc.cgst} sgst={doc.sgst} igst={doc.igst}
            grandTotal={doc.grandTotal} paid={doc.paidAmount} balance={doc.balance} />
          {payments.data && payments.data.length > 0 && (
            <Card title={config.partyRole === 'CUSTOMER' ? 'Receipts' : 'Payments'} flush>
              <DataTable rows={payments.data} columns={[
                { key: 'date', label: 'Date', render: (p) => formatDate(p.paymentDate) },
                { key: 'mode', label: 'Mode', render: (p) => p.mode ?? '-' },
                { key: 'reference', label: 'Reference', render: (p) => p.reference ?? '-' },
                { key: 'amount', label: 'Amount', align: 'right', render: (p) => formatMoney(p.amount) },
              ]} />
            </Card>
          )}
        </div>
      )}
      {paying && doc && <PaymentModal config={config} doc={doc} onClose={() => setPaying(false)} onSaved={() => { setPaying(false); refresh(); }} />}
    </FullPage>
  );
}

function PaymentModal({ config, doc, onClose, onSaved }: { config: DocumentConfig; doc: any; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const [amount, setAmount] = useState(String(doc.balance));
  const [paymentDate, setPaymentDate] = useState(today());
  const [mode, setMode] = useState('NEFT');
  const [reference, setReference] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    if (!(Number(amount) > 0)) return setError('Enter an amount of more than zero.');
    if (Number(amount) > Number(doc.balance)) return setError(t('The amount cannot be more than the balance due of {0}.', { 0: formatMoney(doc.balance) }));
    if (!paymentDate || paymentDate > today()) return setError('The date cannot be in the future.');
    if (paymentDate < doc.docDate) return setError('The date cannot be before the invoice date.');
    setSaving(true);
    try {
      await api.post(`${apiPath(config)}/${doc.id}/payments`, { amount: Number(amount), paymentDate, mode, reference: reference || null });
      feedback.success('Payment recorded');
      onSaved();
    } catch (e) {
      setError((e as ApiError).message);
      setSaving(false);
    }
  }

  return (
    <Modal title={t(config.partyRole === 'CUSTOMER' ? 'Receipt against {0}' : 'Payment against {0}', { 0: doc.docNo })} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save')}</Button></>}>
      <div className="stack tight">
        {error && <Banner tone="danger">{error}</Banner>}
        <div className="form-grid">
          <Field label={t('Amount')} required help={t('Balance due {0}', { 0: formatMoney(doc.balance) })}><input className="input num" type="number" min="0" step="any" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
          <Field label={t('Date')} required><input className="input" type="date" value={paymentDate} max={today()} onChange={(e) => setPaymentDate(e.target.value)} /></Field>
          <Field label={t('Mode')}><Select value={mode} onChange={setMode} options={PAYMENT_MODES} /></Field>
          <Field label={t('Reference')}><input className="input" value={reference} maxLength={60} placeholder={t('UTR or cheque no')} onChange={(e) => setReference(e.target.value)} /></Field>
        </div>
      </div>
    </Modal>
  );
}
