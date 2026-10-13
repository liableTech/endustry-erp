import { useState } from 'react';
import { Check, PackagePlus, Pause, Play, Plus, Send } from 'lucide-react';
import { FILTER_ROW_LIMIT, FilterButton, FilterPanel, useFilteredPage, useFilters, type FilterDef } from '../components/Filters';
import { FormFields } from '../components/FormFields';
import { PrintActions } from '../components/PrintActions';
import { Banner, Button, Card, DataTable, FullPage, Modal, PageHeader, Pager, Progress, SearchBox, Spinner, StatusBadge, Tabs, useFeedback } from '../components/ui';
import { api, ApiError } from '../core/api';
import { useAuth, useLabels } from '../core/auth';
import { formatDate, formatMoney, formatQty, today } from '../core/format';
import { useDebounced, useFetch, useRecordRoute, useRecords } from '../core/hooks';
import { defaults, toPayload, validate, type FieldDef } from '../core/validation';
import { t } from '../core/i18n';

const PAGE_SIZE = 15;
const STATUSES = ['PLANNED', 'RELEASED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
const WORK_ORDER_FILTERS: FilterDef[] = [
  { key: 'item', label: 'Product', kind: 'text', value: (w) => `${w.item.code} ${w.item.name}` },
  { key: 'dueDate', label: 'Due', kind: 'date' },
  { key: 'plannedQty', label: 'Planned quantity', kind: 'number' },
  { key: 'producedQty', label: 'Produced quantity', kind: 'number' },
  { key: 'rejectedQty', label: 'Rejected', kind: 'number' },
];
const label = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());

/** PPC-01 and SHP-01 to SHP-07: plan, release, run on the floor and book output. */
export function WorkOrders() {
  const { can } = useAuth();
  const { word } = useLabels();
  const editable = can('production', 'FULL');
  const [status, setStatus] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const record = useRecordRoute();
  const [creating, setCreating] = useState(false);
  const q = useDebounced(search);
  const filters = useFilters(WORK_ORDER_FILTERS, { onChange: () => setPage(0) });
  const list = useFilteredPage(filters, '/production/work-orders', { q, status: status === 'ALL' ? '' : status }, page, PAGE_SIZE);

  if (record.id) {
    return <WorkOrderSheet key={record.id} id={Number(record.id)} editable={editable} onClose={record.close} onChanged={list.reload} />;
  }

  return (
    <div className="page">
      <PageHeader title={word('workOrder').plural} subtitle={t('What is being made, how far along it is, and what it is costing')}
        actions={editable && <Button variant="primary" icon={Plus} onClick={() => setCreating(true)}>{t('New work order')}</Button>} />
      <Card flush>
        <div className="toolbar between">
          <Tabs value={status} onChange={(s) => { setStatus(s); setPage(0); }} options={[{ value: 'ALL', label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: label(s) }))]} />
          <div className="row wrap">
            <SearchBox value={search} onChange={(v) => { setSearch(v); setPage(0); }} placeholder={t('Search number or product')} />
            <FilterButton filters={filters} />
          </div>
        </div>
        <FilterPanel filters={filters} note={list.capped ? t('Filters look at the newest {0} records only', { 0: FILTER_ROW_LIMIT }) : undefined} />
        <DataTable rows={list.rows} loading={list.loading} onRowClick={(w) => record.open(w.id)} emptyTitle={t('No work orders here')} columns={[
          { key: 'woNo', label: 'Number', render: (w) => <span className="strong">{w.woNo}</span> },
          { key: 'item', label: 'Product', render: (w) => <><span className="strong">{w.item.code}</span> <span className="muted">{w.item.name}</span></> },
          { key: 'due', label: 'Due', render: (w) => <span className={w.dueDate < today() && ['PLANNED', 'RELEASED', 'IN_PROGRESS'].includes(w.status) ? 'down' : ''}>{formatDate(w.dueDate)}</span> },
          { key: 'progress', label: 'Progress', render: (w) => <span className="row" style={{ gap: 10 }}><Progress value={Number(w.producedQty) + Number(w.rejectedQty)} max={Number(w.plannedQty)} /><span className="mono nowrap">{formatQty(w.producedQty)} / {formatQty(w.plannedQty)}</span></span> },
          { key: 'rejected', label: 'Rejected', align: 'right', render: (w) => (Number(w.rejectedQty) > 0 ? <span className="down">{formatQty(w.rejectedQty)}</span> : <span className="muted">-</span>) },
          { key: 'status', label: 'Status', render: (w) => <StatusBadge status={w.status} /> },
        ]} />
        <Pager page={page} totalPages={list.totalPages} totalElements={list.totalElements} onPage={setPage} />
      </Card>
      {creating && <NewWorkOrder onClose={() => setCreating(false)} onSaved={(wo) => { setCreating(false); list.reload(); record.open(wo.id); }} />}
    </div>
  );
}

function NewWorkOrder({ onClose, onSaved }: { onClose: () => void; onSaved: (wo: any) => void }) {
  const feedback = useFeedback();
  const items = useRecords<any>('/masters/items?active=true&type=FINISHED,SEMI_FINISHED');
  const warehouses = useRecords<any>('/masters/warehouses?active=true');
  const fields: FieldDef[] = [
    { name: 'itemId', label: 'Product', type: 'select', required: true, span: 2, options: items.map((i) => ({ value: String(i.id), label: `${i.code} - ${i.name}` })), help: 'The approved BOM version is used automatically.' },
    { name: 'plannedQty', label: 'Quantity to make', type: 'number', required: true, min: 0.001 },
    { name: 'warehouseId', label: 'Warehouse', type: 'select', required: true, options: warehouses.map((w) => ({ value: String(w.id), label: w.name })), help: 'Components are issued from, and output received into, this warehouse.' },
    { name: 'startDate', label: 'Start date', type: 'date', required: true, default: today() },
    { name: 'dueDate', label: 'Due date', type: 'date', required: true },
    { name: 'salesOrderNo', label: 'For sales order', type: 'text', maxLength: 16, help: 'Optional, for make-to-order.' },
    { name: 'notes', label: 'Notes', type: 'textarea', maxLength: 400, span: 2 },
  ];
  const [values, setValues] = useState(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const found = validate(fields, values);
    if (!found.dueDate && values.startDate && values.dueDate < values.startDate) found.dueDate = 'cannot be before the start date';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      const saved = await api.post('/production/work-orders', { ...payload, itemId: Number(payload.itemId), warehouseId: Number(payload.warehouseId) });
      feedback.success(`${saved.woNo} planned`);
      onSaved(saved);
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(error.message);
      setSaving(false);
    }
  }

  return (
    <Modal wide title={t('New work order')} onClose={onClose} footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Plan work order')}</Button></>}>
      <div className="stack tight">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      </div>
    </Modal>
  );
}

function WorkOrderSheet({ id, editable, onClose, onChanged }: { id: number; editable: boolean; onClose: () => void; onChanged: () => void }) {
  const feedback = useFeedback();
  const wo = useFetch<any>(`/production/work-orders/${id}`);
  const needs = useFetch<any[]>(`/production/work-orders/${id}/availability`);
  const outputs = useFetch<any[]>(`/production/work-orders/${id}/outputs`);
  const [booking, setBooking] = useState(false);
  const [cardAction, setCardAction] = useState<{ card: any; action: 'pause' | 'complete' } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = () => { wo.reload(); needs.reload(); outputs.reload(); onChanged(); };
  const w = wo.data;

  async function call(key: string, path: string, body?: unknown, success?: string) {
    setBusy(key);
    try {
      await api.post(path, body);
      if (success) feedback.success(success);
      refresh();
      return true;
    } catch (e) {
      return e as ApiError;
    } finally {
      setBusy(null);
    }
  }

  async function release() {
    const result = await call('release', `/production/work-orders/${id}/release`, undefined, 'Released to the shop floor');
    if (result === true) return;
    // PPC-06: a shortage holds the release, but the planner may start with what is available.
    if (result.code === 'MATERIAL_SHORTAGE') {
      const short = result.details as any[];
      const ok = await feedback.confirm({
        title: 'Material shortage', confirmLabel: 'Release anyway',
        message: <>
          <p>{result.message}</p>
          <ul style={{ paddingLeft: 18 }}>{short.map((s) => <li key={s.itemCode}><strong>{s.itemCode}</strong>{': '}{t('short by {0} {1}', { 0: formatQty(s.shortage), 1: s.uom })}</li>)}</ul>
        </>,
      });
      if (ok) {
        const forced = await call('release', `/production/work-orders/${id}/release?force=true`, undefined, 'Released with a shortage');
        if (forced !== true) feedback.error(forced);
      }
    } else {
      feedback.error(result);
    }
  }

  async function cancel() {
    if (!(await feedback.confirm({ title: 'Cancel work order', message: 'Cancel this work order? This cannot be undone.', confirmLabel: 'Cancel work order', danger: true }))) return;
    const result = await call('cancel', `/production/work-orders/${id}/cancel`, undefined, 'Work order cancelled');
    if (result !== true) feedback.error(result);
  }

  async function startCard(card: any) {
    const result = await call(`card-${card.id}`, `/production/job-cards/${card.id}/start`, undefined, `${card.operationName} started`);
    if (result !== true) feedback.error(result);
  }

  const open = w && (w.status === 'RELEASED' || w.status === 'IN_PROGRESS');
  const variance = w && Number(w.producedQty) > 0 ? Number(w.actualUnitCost) - Number(w.standardUnitCost) : null;

  return (
    <FullPage wide onClose={onClose} title={w ? <span className="row">{w.woNo}<StatusBadge status={w.status} /></span> : 'Work order'}
      footer={w && (
        <>
          <PrintActions path={`/production/work-orders/${id}`} templatesPath="/production/work-orders/print-templates" fallbackName={w.woNo.replace(/\//g, '-')} />
          {editable && (w.status === 'PLANNED' || w.status === 'RELEASED') && <Button variant="ghost" busy={busy === 'cancel'} onClick={cancel}>{t('Cancel work order')}</Button>}
          <span className="grow" />
          {editable && w.status === 'PLANNED' && <Button variant="primary" icon={Send} busy={busy === 'release'} onClick={release}>{t('Release to floor')}</Button>}
          {editable && open && <Button variant="primary" icon={PackagePlus} onClick={() => setBooking(true)}>{t('Book output')}</Button>}
        </>
      )}>
      {wo.loading && !w ? <Spinner /> : !w ? <Banner tone="danger">{wo.error?.message ?? 'Not found'}</Banner> : (
        <div className="stack">
          <dl className="kv">
            <div><dt>{t('Product')}</dt><dd>{w.item.code} {w.item.name}</dd></div>
            <div><dt>{t('BOM version')}</dt><dd>v{w.bom.versionNo}</dd></div>
            <div><dt>{t('Warehouse')}</dt><dd>{w.warehouse.name}</dd></div>
            <div><dt>{t('Start')}</dt><dd>{formatDate(w.startDate)}</dd></div>
            <div><dt>{t('Due')}</dt><dd>{formatDate(w.dueDate)}</dd></div>
            {w.salesOrderNo && <div><dt>{t('For sales order')}</dt><dd>{w.salesOrderNo}</dd></div>}
            <div><dt>{t('Planned')}</dt><dd>{formatQty(w.plannedQty)} {w.item.uom}</dd></div>
            <div><dt>{t('Good')}</dt><dd>{formatQty(w.producedQty)}</dd></div>
            <div><dt>{t('Rejected')}</dt><dd>{formatQty(w.rejectedQty)}</dd></div>
            <div><dt>{t('Remaining')}</dt><dd>{formatQty(w.remainingQty)}</dd></div>
          </dl>
          {w.notes && <p className="muted">{w.notes}</p>}

          <Card title={t('Cost per unit')}>
            <dl className="kv">
              <div><dt>{t('Standard')}</dt><dd>{formatMoney(w.standardUnitCost)}</dd></div>
              <div><dt>{t('Actual')}</dt><dd>{Number(w.producedQty) > 0 ? formatMoney(w.actualUnitCost) : '-'}</dd></div>
              <div><dt>{t('Variance')}</dt><dd className={variance === null ? '' : variance > 0 ? 'down' : 'up'}>{variance === null ? '-' : `${variance > 0 ? '+' : ''}${formatMoney(variance)}`}</dd></div>
              <div><dt>{t('Material issued')}</dt><dd>{formatMoney(w.materialCost)}</dd></div>
              <div><dt>{t('Operations')}</dt><dd>{formatMoney(w.operationCost)}</dd></div>
            </dl>
          </Card>

          {w.jobCards.length > 0 && (
            <Card title={t('Job cards')}>
              <div className="stack tight">
                {w.jobCards.map((card: any) => (
                  <div key={card.id} className="jobcard">
                    <div className="avatar">{card.seqNo}</div>
                    <div className="grow">
                      <div className="strong">{card.operationName}</div>
                      <div className="muted" style={{ fontSize: 13 }}>
                        {card.workCentre.name}{card.operator ? ` · ${card.operator.name}` : ''} · {t('{0} min worked', { 0: card.minutesWorked })}
                       {' '}{card.status === 'DONE' && ` · ${formatQty(card.goodQty)} good, ${formatQty(card.rejectedQty)} rejected`}
                        {card.pauseReason && card.status === 'PAUSED' && ` · paused: ${label(card.pauseReason)}`}
                      </div>
                    </div>
                    <StatusBadge status={card.status} />
                    {editable && open && (card.status === 'PENDING' || card.status === 'PAUSED') && <Button small icon={Play} busy={busy === `card-${card.id}`} onClick={() => startCard(card)}>{card.status === 'PAUSED' ? 'Resume' : 'Start'}</Button>}
                    {editable && open && card.status === 'RUNNING' && <Button small icon={Pause} onClick={() => setCardAction({ card, action: 'pause' })}>{t('Pause')}</Button>}
                    {editable && open && (card.status === 'RUNNING' || card.status === 'PAUSED') && <Button small variant="primary" icon={Check} onClick={() => setCardAction({ card, action: 'complete' })}>{t('Complete')}</Button>}
                  </div>
                ))}
              </div>
            </Card>
          )}

          {w.status !== 'COMPLETED' && w.status !== 'CANCELLED' && (
            <Card title={t('Material for the remaining quantity')} flush>
              <DataTable rows={needs.data ?? []} loading={needs.loading} rowKey={(n) => n.itemId} columns={[
                { key: 'item', label: 'Component', render: (n) => <><span className="strong">{n.itemCode}</span> <span className="muted">{n.itemName}</span></> },
                { key: 'required', label: 'Required', align: 'right', render: (n) => `${formatQty(n.required)} ${n.uom}` },
                { key: 'available', label: 'Available', align: 'right', render: (n) => formatQty(n.available) },
                { key: 'shortage', label: 'Shortage', align: 'right', render: (n) => (Number(n.shortage) > 0 ? <span className="down">{formatQty(n.shortage)}</span> : <span className="up">{t('None')}</span>) },
              ]} />
            </Card>
          )}

          {outputs.data && outputs.data.length > 0 && (
            <Card title={t('Output booked')} flush>
              <DataTable rows={outputs.data} columns={[
                { key: 'date', label: 'Date', render: (o) => formatDate(o.outputDate) },
                { key: 'good', label: 'Good', align: 'right', render: (o) => formatQty(o.goodQty) },
                { key: 'rejected', label: 'Rejected', align: 'right', render: (o) => formatQty(o.rejectedQty) },
                { key: 'reason', label: 'Reason', render: (o) => (o.reasonCode ? label(o.reasonCode) : '-') },
                { key: 'material', label: 'Material', align: 'right', render: (o) => formatMoney(o.materialCost) },
                { key: 'operation', label: 'Operations', align: 'right', render: (o) => formatMoney(o.operationCost) },
              ]} />
            </Card>
          )}
        </div>
      )}
      {booking && w && <OutputModal wo={w} onClose={() => setBooking(false)} onSaved={() => { setBooking(false); refresh(); }} />}
      {cardAction && w && <JobCardModal wo={w} {...cardAction} onClose={() => setCardAction(null)} onSaved={() => { setCardAction(null); refresh(); }} />}
    </FullPage>
  );
}

function OutputModal({ wo, onClose, onSaved }: { wo: any; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const fields: FieldDef[] = [
    { name: 'goodQty', label: 'Good quantity', type: 'number', required: true, min: 0, default: Number(wo.remainingQty) },
    { name: 'rejectedQty', label: 'Rejected quantity', type: 'number', required: true, min: 0, default: 0 },
    { name: 'reasonCode', label: 'Rejection reason', type: 'lookup', lookupType: 'REJECTION_REASON', showIf: (v) => Number(v.rejectedQty) > 0, required: true },
    { name: 'outputDate', label: 'Date', type: 'date', required: true, default: today() },
  ];
  const [values, setValues] = useState(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const found = validate(fields, values);
    const total = Number(values.goodQty || 0) + Number(values.rejectedQty || 0);
    if (!Object.keys(found).length && total <= 0) found.goodQty = 'or rejected quantity must be more than zero';
    if (!Object.keys(found).length && total > Number(wo.remainingQty)) found.goodQty = t('plus rejected cannot be more than the {0} remaining', { 0: formatQty(wo.remainingQty) });
    if (!found.outputDate && values.outputDate > today()) found.outputDate = 'cannot be in the future';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      await api.post(`/production/work-orders/${wo.id}/output`, toPayload(fields, values));
      feedback.success('Output booked; components consumed and finished goods received');
      onSaved();
    } catch (e) {
      setFailure((e as ApiError).message);
      setSaving(false);
    }
  }

  return (
    <Modal title={t('Book output on {0}', { 0: wo.woNo })} onClose={onClose} footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Book output')}</Button></>}>
      <div className="stack tight">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <p className="muted">{t('Components are consumed from {0} as per the BOM, and good units are received into stock at their actual cost.', { 0: wo.warehouse.name })}</p>
        <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      </div>
    </Modal>
  );
}

function JobCardModal({ wo, card, action, onClose, onSaved }: { wo: any; card: any; action: 'pause' | 'complete'; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const fields: FieldDef[] = action === 'pause'
    ? [{ name: 'reasonCode', label: 'Reason for stopping', type: 'lookup', lookupType: 'DOWNTIME_REASON', required: true, span: 2 }]
    : [
      { name: 'goodQty', label: 'Good quantity', type: 'number', required: true, min: 0, default: Number(wo.plannedQty) },
      { name: 'rejectedQty', label: 'Rejected quantity', type: 'number', required: true, min: 0, default: 0 },
    ];
  const [values, setValues] = useState(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const found = validate(fields, values);
    setErrors(found);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      await api.post(`/production/job-cards/${card.id}/${action}`, toPayload(fields, values));
      feedback.success(t(action === 'pause' ? '{0} paused' : '{0} completed', { 0: card.operationName }));
      onSaved();
    } catch (e) {
      setFailure((e as ApiError).message);
      setSaving(false);
    }
  }

  return (
    <Modal title={t(action === 'pause' ? 'Pause {0}' : 'Complete {0}', { 0: card.operationName })} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{action === 'pause' ? 'Pause' : 'Complete'}</Button></>}>
      <div className="stack tight">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      </div>
    </Modal>
  );
}
