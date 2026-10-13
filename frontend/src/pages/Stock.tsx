import { useMemo, useState } from 'react';
import { ArrowRightLeft, Boxes, ClipboardCheck, SlidersHorizontal, TriangleAlert } from 'lucide-react';
import { FILTER_ROW_LIMIT, FilterButton, FilterPanel, useFilteredPage, useFilters, type FilterDef } from '../components/Filters';
import { FormFields, Select } from '../components/FormFields';
import { Banner, Button, Card, DataTable, Modal, PageHeader, Pager, SearchBox, Stat, StatusBadge, Tabs, useFeedback } from '../components/ui';
import { api, ApiError, query } from '../core/api';
import { useAuth } from '../core/auth';
import { formatCompact, formatDate, formatMoney, formatQty, humanize } from '../core/format';
import { useDebounced, useFetch, useRecords } from '../core/hooks';
import { defaults, toPayload, validate, type FieldDef } from '../core/validation';
import { t } from '../core/i18n';

const STATUS_LABELS = { AVAILABLE: 'Available', QC_PENDING: 'In QC', REJECTED: 'Rejected' };
const STATUS_OPTIONS = Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }));
const MOVEMENT_TYPES = ['OPENING', 'GRN', 'SALE', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN', 'QC_RELEASE', 'QC_REJECT', 'PRODUCTION_ISSUE', 'PRODUCTION_RECEIPT'];
const STOCK_FILTERS: FilterDef[] = [
  { key: 'item', label: 'Item', kind: 'text', value: (r) => `${r.itemCode} ${r.itemName}` },
  { key: 'batchNo', label: 'Batch', kind: 'text' },
  { key: 'qty', label: 'Quantity', kind: 'number' },
  { key: 'avgCost', label: 'Avg cost', kind: 'number' },
  { key: 'value', label: 'Value', kind: 'number', value: (r) => Number(r.qty) * Number(r.avgCost) },
];
const LEDGER_FILTERS: FilterDef[] = [
  { key: 'movementDate', label: 'Date', kind: 'date' },
  { key: 'item', label: 'Item', kind: 'text', value: (m) => `${m.item.code} ${m.item.name}` },
  { key: 'type', label: 'Movement', kind: 'choice', options: MOVEMENT_TYPES.map((value) => ({ value, label: humanize(value) })) },
  { key: 'refNo', label: 'Document', kind: 'text' },
  { key: 'warehouse', label: 'Warehouse', kind: 'text', value: (m) => m.warehouse.name },
  { key: 'batchNo', label: 'Batch', kind: 'text' },
  { key: 'status', label: 'Status', kind: 'choice', options: STATUS_OPTIONS },
  { key: 'qty', label: 'Quantity', kind: 'number' },
  { key: 'rate', label: 'Rate', kind: 'number' },
];

/** INV-01: stock on hand by item, warehouse, batch and status, with adjust and transfer. */
export function Stock() {
  const { can } = useAuth();
  const editable = can('inventory', 'FULL');
  const [search, setSearch] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [status, setStatus] = useState('ALL');
  const [dialog, setDialog] = useState<'adjust' | 'transfer' | null>(null);
  const q = useDebounced(search);
  const warehouses = useRecords<any>('/masters/warehouses');
  const stock = useFetch<any[]>(`/inventory/stock${query({ q, warehouseId })}`);

  const filters = useFilters(STOCK_FILTERS);
  const inTab = useMemo(() => (stock.data ?? []).filter((r) => status === 'ALL' || r.status === status), [stock.data, status]);
  const rows = filters.apply(inTab);
  const summary = useMemo(() => {
    const all = stock.data ?? [];
    const availableByItem = new Map<string, { qty: number; reorder: number }>();
    all.forEach((r) => {
      const entry = availableByItem.get(r.itemCode) ?? { qty: 0, reorder: Number(r.reorderLevel) };
      if (r.status === 'AVAILABLE') entry.qty += Number(r.qty);
      availableByItem.set(r.itemCode, entry);
    });
    return {
      value: all.filter((r) => r.status !== 'REJECTED').reduce((sum, r) => sum + Number(r.qty) * Number(r.avgCost), 0),
      inQc: all.filter((r) => r.status === 'QC_PENDING').length,
      low: [...availableByItem.values()].filter((e) => e.reorder > 0 && e.qty < e.reorder).length,
    };
  }, [stock.data]);

  return (
    <div className="page">
      <PageHeader title={t('Stock')} subtitle={t('What is on hand, where, in which batch and in what state')} actions={editable && (
        <>
          <Button icon={ArrowRightLeft} onClick={() => setDialog('transfer')}>{t('Transfer')}</Button>
          <Button variant="primary" icon={SlidersHorizontal} onClick={() => setDialog('adjust')}>{t('Adjust stock')}</Button>
        </>
      )} />
      <div className="grid kpi">
        <Stat icon={Boxes} label={t('Stock value')} value={formatCompact(summary.value)} foot="At weighted average cost" />
        <Stat icon={ClipboardCheck} tone={summary.inQc ? 'warning' : 'success'} label={t('Batches in QC')} value={summary.inQc} foot="Not usable until released" />
        <Stat icon={TriangleAlert} tone={summary.low ? 'danger' : 'success'} label={t('Below reorder level')} value={summary.low} foot="Items to replenish" />
      </div>
      <Card flush>
        <div className="toolbar between">
          <Tabs value={status} onChange={setStatus} options={[{ value: 'ALL', label: 'All' }, ...Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))]} />
          <div className="row wrap">
            <div style={{ width: 200 }}><Select value={warehouseId} onChange={setWarehouseId} placeholder={t('All warehouses')} options={warehouses.map((w) => ({ value: String(w.id), label: w.name }))} /></div>
            <SearchBox value={search} onChange={setSearch} placeholder={t('Search item or batch')} />
            <FilterButton filters={filters} />
          </div>
        </div>
        <FilterPanel filters={filters} note={t('{0} of {1} match', { 0: rows.length, 1: inTab.length })} />
        {stock.error ? <div className="card-body"><Banner tone="danger">{stock.error.message}</Banner></div> : (
          <DataTable rows={rows} loading={stock.loading} rowKey={(r) => `${r.itemId}-${r.warehouseId}-${r.batchNo}-${r.status}`} emptyTitle={t('No stock matches')}
            columns={[
              { key: 'item', label: 'Item', render: (r) => <><span className="strong">{r.itemCode}</span> <span className="muted">{r.itemName}</span></> },
              { key: 'warehouse', label: 'Warehouse', render: (r) => r.warehouseName },
              { key: 'batch', label: 'Batch', render: (r) => r.batchNo || <span className="muted">-</span> },
              { key: 'status', label: 'Status', render: (r) => <StatusBadge status={r.status} labels={STATUS_LABELS} /> },
              { key: 'qty', label: 'Quantity', align: 'right', render: (r) => `${formatQty(r.qty)} ${r.uom}` },
              { key: 'avgCost', label: 'Avg cost', align: 'right', render: (r) => formatMoney(r.avgCost) },
              { key: 'value', label: 'Value', align: 'right', render: (r) => formatMoney(Number(r.qty) * Number(r.avgCost)) },
            ]} />
        )}
      </Card>
      {dialog && <StockDialog kind={dialog} onClose={() => setDialog(null)} onDone={() => { setDialog(null); stock.reload(); }} />}
    </div>
  );
}

function StockDialog({ kind, onClose, onDone }: { kind: 'adjust' | 'transfer'; onClose: () => void; onDone: () => void }) {
  const feedback = useFeedback();
  const items = useRecords<any>('/masters/items?active=true');
  const warehouses = useRecords<any>('/masters/warehouses?active=true');
  const itemOptions = items.filter((i) => i.type !== 'SERVICE').map((i) => ({ value: String(i.id), label: `${i.code} - ${i.name}` }));
  const warehouseOptions = warehouses.map((w) => ({ value: String(w.id), label: w.name }));

  const fields: FieldDef[] = kind === 'adjust' ? [
    { name: 'itemId', label: 'Item', type: 'select', required: true, options: itemOptions, span: 2 },
    { name: 'warehouseId', label: 'Warehouse', type: 'select', required: true, options: warehouseOptions },
    { name: 'batchNo', label: 'Batch', type: 'text', maxLength: 40, help: 'Only for batch-tracked items.' },
    { name: 'qtyChange', label: 'Quantity change', type: 'number', required: true, help: 'Positive adds stock, negative removes it.' },
    { name: 'reasonCode', label: 'Reason', type: 'lookup', lookupType: 'ADJUSTMENT_REASON', required: true },
  ] : [
    { name: 'itemId', label: 'Item', type: 'select', required: true, options: itemOptions, span: 2 },
    { name: 'fromWarehouseId', label: 'From warehouse', type: 'select', required: true, options: warehouseOptions },
    { name: 'toWarehouseId', label: 'To warehouse', type: 'select', required: true, options: warehouseOptions },
    { name: 'qty', label: 'Quantity', type: 'number', required: true, min: 0.001 },
  ];
  const [values, setValues] = useState(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const found = validate(fields, values);
    if (kind === 'adjust' && !found.qtyChange && Number(values.qtyChange) === 0) found.qtyChange = 'cannot be zero';
    if (kind === 'transfer' && values.fromWarehouseId && values.fromWarehouseId === values.toWarehouseId) found.toWarehouseId = 'must be different from the source';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      ['itemId', 'warehouseId', 'fromWarehouseId', 'toWarehouseId'].forEach((k) => { if (payload[k]) payload[k] = Number(payload[k]); });
      await api.post(kind === 'adjust' ? '/inventory/adjustments' : '/inventory/transfers', payload);
      feedback.success(kind === 'adjust' ? 'Stock adjusted' : 'Stock transferred');
      onDone();
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(error.message);
      setSaving(false);
    }
  }

  return (
    <Modal wide title={kind === 'adjust' ? 'Adjust stock' : 'Transfer stock'} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{kind === 'adjust' ? 'Post adjustment' : 'Transfer'}</Button></>}>
      <div className="stack tight">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      </div>
    </Modal>
  );
}

const PAGE_SIZE = 25;

/** INV-02: every movement in and out, newest first. */
export function StockLedger() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const q = useDebounced(search);
  const filters = useFilters(LEDGER_FILTERS, { onChange: () => setPage(0) });
  const list = useFilteredPage(filters, '/inventory/movements', { q }, page, PAGE_SIZE);
  return (
    <div className="page">
      <PageHeader title={t('Stock ledger')} subtitle={t('Every movement in and out, with the document behind it')} />
      <Card flush>
        <div className="toolbar">
          <SearchBox value={search} onChange={(v) => { setSearch(v); setPage(0); }} placeholder={t('Search item or document number')} />
          <FilterButton filters={filters} />
        </div>
        <FilterPanel filters={filters} note={list.capped ? t('Filters look at the newest {0} records only', { 0: FILTER_ROW_LIMIT }) : undefined} />
        <DataTable rows={list.rows} loading={list.loading} emptyTitle={filters.count ? 'No matches' : t('No movements yet')} columns={[
          { key: 'date', label: 'Date', render: (m) => formatDate(m.movementDate) },
          { key: 'item', label: 'Item', render: (m) => <><span className="strong">{m.item.code}</span> <span className="muted">{m.item.name}</span></> },
          { key: 'type', label: 'Movement', render: (m) => humanize(m.type) },
          { key: 'ref', label: 'Document', render: (m) => m.refNo ?? '-' },
          { key: 'warehouse', label: 'Warehouse', render: (m) => m.warehouse.name },
          { key: 'batch', label: 'Batch', render: (m) => m.batchNo || <span className="muted">-</span> },
          { key: 'status', label: 'Status', render: (m) => <StatusBadge status={m.status} labels={STATUS_LABELS} /> },
          { key: 'qty', label: 'Quantity', align: 'right', render: (m) => <span className={Number(m.qty) >= 0 ? 'up' : 'down'}>{Number(m.qty) > 0 ? '+' : ''}{formatQty(m.qty)}</span> },
          { key: 'rate', label: 'Rate', align: 'right', render: (m) => formatMoney(m.rate) },
        ]} />
        <Pager page={page} totalPages={list.totalPages} totalElements={list.totalElements} onPage={setPage} />
      </Card>
    </div>
  );
}
