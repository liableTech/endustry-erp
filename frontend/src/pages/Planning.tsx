import { useState } from 'react';
import { ClipboardCheck, Download } from 'lucide-react';
import { FILTER_ROW_LIMIT, FilterButton, FilterPanel, useFilteredPage, useFilters, type FilterDef } from '../components/Filters';
import { FormFields } from '../components/FormFields';
import { Banner, Button, Card, DataTable, Modal, PageHeader, Pager, StatusBadge, Tabs, useFeedback } from '../components/ui';
import { api, ApiError } from '../core/api';
import { useAuth, useLabels } from '../core/auth';
import { inSentence } from '../core/labels';
import { downloadCsv, formatDate, formatDateTime, formatQty, humanize } from '../core/format';
import { useFetch } from '../core/hooks';
import { defaults, toPayload, validate, type FieldDef } from '../core/validation';
import { t } from '../core/i18n';

const PLAN_FILTERS: FilterDef[] = [
  { key: 'item', label: 'Item', kind: 'text', value: (r) => `${r.itemCode} ${r.itemName}` },
  { key: 'required', label: 'Required for work orders', kind: 'number' },
  { key: 'available', label: 'Available', kind: 'number' },
  { key: 'onOrder', label: 'On order', kind: 'number' },
  { key: 'suggestedQty', label: 'Suggested purchase', kind: 'number' },
  { key: 'leadTimeDays', label: 'Lead time days', kind: 'number' },
];
const INSPECTION_FILTERS: FilterDef[] = [
  { key: 'item', label: 'Item', kind: 'text', value: (i) => `${i.item.code} ${i.item.name}` },
  { key: 'vendor', label: 'Vendor', kind: 'text', value: (i) => i.vendor?.name },
  { key: 'grnNo', label: 'Receipt', kind: 'text' },
  { key: 'batchNo', label: 'Batch', kind: 'text' },
  { key: 'receivedQty', label: 'Received', kind: 'number' },
  { key: 'createdAt', label: 'Received on', kind: 'date' },
];

/** PPC-04 and INV-08: what to buy, from open work orders and reorder levels. */
export function MaterialPlan() {
  const plan = useFetch<any[]>('/production/material-plan');
  const filters = useFilters(PLAN_FILTERS);
  const all = plan.data ?? [];
  const rows = filters.apply(all);
  return (
    <div className="page">
      <PageHeader title={t('Material plan')} subtitle={t('What to buy now: demand from open work orders, less stock and open purchase orders, topped up to reorder level')}
        actions={<Button icon={Download} disabled={rows.length === 0} onClick={() => downloadCsv('material-plan.csv',
          ['Item code', 'Item', 'Unit', 'Required', 'Available', 'On order', 'Reorder level', 'Suggested purchase', 'Lead time days'],
          rows.map((r) => [r.itemCode, r.itemName, r.uom, r.required, r.available, r.onOrder, r.reorderLevel, r.suggestedQty, r.leadTimeDays]))}>{t('Export')}</Button>} />
      {plan.error && <Banner tone="danger">{plan.error.message}</Banner>}
      <Card flush>
        <div className="toolbar"><FilterButton filters={filters} /></div>
        <FilterPanel filters={filters} note={t('{0} of {1} match', { 0: rows.length, 1: all.length })} />
        <DataTable rows={rows} loading={plan.loading} rowKey={(r) => r.itemId} emptyTitle={filters.count ? 'No matches' : t('Nothing to buy')}
          emptyHint={filters.count ? 'No record matches these filters.' : t('Stock and open purchase orders cover every open work order and reorder level.')}
          columns={[
            { key: 'item', label: 'Item', render: (r) => <><span className="strong">{r.itemCode}</span> <span className="muted">{r.itemName}</span></> },
            { key: 'required', label: 'Required for work orders', align: 'right', render: (r) => `${formatQty(r.required)} ${r.uom}` },
            { key: 'available', label: 'Available', align: 'right', render: (r) => formatQty(r.available) },
            { key: 'onOrder', label: 'On order', align: 'right', render: (r) => formatQty(r.onOrder) },
            { key: 'reorder', label: 'Reorder level', align: 'right', render: (r) => formatQty(r.reorderLevel) },
            { key: 'suggested', label: 'Suggested purchase', align: 'right', render: (r) => <span className="strong" style={{ color: 'var(--primary)' }}>{formatQty(r.suggestedQty)} {r.uom}</span> },
            { key: 'lead', label: 'Lead time', align: 'right', render: (r) => `${r.leadTimeDays} days` },
          ]} />
      </Card>
    </div>
  );
}

const PAGE_SIZE = 15;

/** QLT-02 and QLT-04: incoming inspection of received batches. */
export function Inspections() {
  const { can } = useAuth();
  const { word } = useLabels();
  const editable = can('quality', 'FULL');
  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [inspecting, setInspecting] = useState<any | null>(null);
  const filters = useFilters(INSPECTION_FILTERS, { onChange: () => setPage(0) });
  const list = useFilteredPage(filters, '/quality/inspections', { status }, page, PAGE_SIZE);

  return (
    <div className="page">
      <PageHeader title={t('Incoming {0}', { 0: inSentence(word('inspection').plural) })} subtitle={t('Received batches wait here until quality accepts or rejects them')} />
      <Card flush>
        <div className="toolbar">
          <Tabs value={status} onChange={(s) => { setStatus(s); setPage(0); }} options={[{ value: 'PENDING', label: 'Waiting' }, { value: 'COMPLETED', label: 'Completed' }]} />
          <span className="grow" />
          <FilterButton filters={filters} />
        </div>
        <FilterPanel filters={filters} note={list.capped ? t('Filters look at the newest {0} records only', { 0: FILTER_ROW_LIMIT }) : undefined} />
        <DataTable rows={list.rows} loading={list.loading}
          emptyTitle={filters.count ? 'No matches' : status === 'PENDING' ? 'Nothing waiting for inspection' : 'No completed inspections yet'}
          columns={[
            { key: 'no', label: 'Number', render: (i) => <span className="strong">{i.inspectionNo}</span> },
            { key: 'item', label: 'Item', render: (i) => <><span className="strong">{i.item.code}</span> <span className="muted">{i.item.name}</span></> },
            { key: 'vendor', label: 'Vendor', render: (i) => i.vendor?.name ?? '-' },
            { key: 'grn', label: 'Receipt', render: (i) => i.grnNo },
            { key: 'batch', label: 'Batch', render: (i) => i.batchNo || '-' },
            { key: 'received', label: 'Received', align: 'right', render: (i) => `${formatQty(i.receivedQty)} ${i.item.uom}` },
            ...(status === 'COMPLETED' ? [
              { key: 'accepted', label: 'Accepted', align: 'right' as const, render: (i: any) => formatQty(i.acceptedQty) },
              { key: 'rejected', label: 'Rejected', align: 'right' as const, render: (i: any) => (Number(i.rejectedQty) > 0 ? <span className="down">{formatQty(i.rejectedQty)}</span> : '-') },
              { key: 'reason', label: 'Reason', render: (i: any) => (i.reasonCode ? humanize(i.reasonCode) : '-') },
              { key: 'by', label: 'Inspected', render: (i: any) => <span className="muted">{formatDateTime(i.inspectedAt)}</span> },
            ] : [
              { key: 'date', label: 'Received on', render: (i: any) => formatDate(i.createdAt) },
              { key: 'status', label: 'Status', render: (i: any) => <StatusBadge status={i.status} labels={{ PENDING: 'Waiting' }} /> },
              ...(editable ? [{ key: 'act', label: '', align: 'right' as const, render: (i: any) => <Button small variant="primary" icon={ClipboardCheck} onClick={() => setInspecting(i)}>{t('Inspect')}</Button> }] : []),
            ]),
          ]} />
        <Pager page={page} totalPages={list.totalPages} totalElements={list.totalElements} onPage={setPage} />
      </Card>
      {inspecting && <InspectModal inspection={inspecting} onClose={() => setInspecting(null)} onSaved={() => { setInspecting(null); list.reload(); }} />}
    </div>
  );
}

function InspectModal({ inspection, onClose, onSaved }: { inspection: any; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const received = Number(inspection.receivedQty);
  const fields: FieldDef[] = [
    { name: 'acceptedQty', label: 'Accepted quantity', type: 'number', required: true, min: 0, max: received, default: received },
    { name: 'rejectedQty', label: 'Rejected quantity', type: 'number', required: true, min: 0, max: received, default: 0 },
    { name: 'reasonCode', label: 'Rejection reason', type: 'lookup', lookupType: 'REJECTION_REASON', required: true, showIf: (v) => Number(v.rejectedQty) > 0, span: 2 },
    { name: 'remarks', label: 'Remarks', type: 'textarea', maxLength: 400, span: 2 },
  ];
  const [values, setValues] = useState(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Typing one quantity fills in the other, since the two must add up to what was received.
  function change(name: string, value: any) {
    setValues((prev) => {
      const next = { ...prev, [name]: value };
      const n = Number(value);
      if (value !== '' && Number.isFinite(n) && n >= 0 && n <= received) {
        const other = Math.round((received - n) * 1000) / 1000;
        if (name === 'acceptedQty') next.rejectedQty = other;
        if (name === 'rejectedQty') next.acceptedQty = other;
      }
      return next;
    });
  }

  async function save() {
    const found = validate(fields, values);
    if (!Object.keys(found).length && Math.abs(Number(values.acceptedQty) + Number(values.rejectedQty) - received) > 0.0005) {
      found.rejectedQty = t('plus accepted must equal the {0} received', { 0: formatQty(received) });
    }
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      await api.post(`/quality/inspections/${inspection.id}/complete`, toPayload(fields, values));
      feedback.success(t('{0} completed; stock released', { 0: inspection.inspectionNo }));
      onSaved();
    } catch (e) {
      setFailure((e as ApiError).message);
      setSaving(false);
    }
  }

  return (
    <Modal title={t('Inspect {0}', { 0: inspection.item.code })} onClose={onClose} footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Record result')}</Button></>}>
      <div className="stack tight">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <p className="muted">{t('{0} {1} of {2} received on {3}', { 0: formatQty(received), 1: inspection.item.uom, 2: inspection.item.name, 3: inspection.grnNo })}{inspection.batchNo ? `, ${t('batch')} ${inspection.batchNo}` : ''}.</p>
        <FormFields fields={fields} values={values} errors={errors} onChange={change} />
      </div>
    </Modal>
  );
}
