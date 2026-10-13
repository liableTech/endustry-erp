import { useState } from 'react';
import { CheckCircle2, Copy, Pencil, Plus, Trash2 } from 'lucide-react';
import { FILTER_ROW_LIMIT, FilterButton, FilterPanel, useFilteredPage, useFilters, type FilterDef } from '../components/Filters';
import { Field, Select } from '../components/FormFields';
import { PrintActions } from '../components/PrintActions';
import { Banner, Button, Card, DataTable, FullPage, IconButton, PageHeader, Pager, SearchBox, Spinner, StatusBadge, Tabs, useFeedback } from '../components/ui';
import { api, ApiError } from '../core/api';
import { useAuth, useLabels } from '../core/auth';
import { formatMoney, formatQty } from '../core/format';
import { useDebounced, useFetch, useRecordRoute, useRecords } from '../core/hooks';
import { t } from '../core/i18n';

const PAGE_SIZE = 15;
const BOM_FILTERS: FilterDef[] = [
  { key: 'item', label: 'Product', kind: 'text', value: (b) => `${b.item.code} ${b.item.name}` },
  { key: 'versionNo', label: 'Version', kind: 'number' },
  { key: 'outputQty', label: 'Makes', kind: 'number' },
  { key: 'components', label: 'Components', kind: 'number', value: (b) => b.lines.length },
  { key: 'operations', label: 'Operations', kind: 'number', value: (b) => b.operations.length },
];

/** BOM-01 to BOM-03: what each product is made from, in which steps, by version. */
export function Boms() {
  const { can } = useAuth();
  const { word } = useLabels();
  const editable = can('production', 'FULL');
  const [status, setStatus] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const record = useRecordRoute();
  const q = useDebounced(search);
  const filters = useFilters(BOM_FILTERS, { onChange: () => setPage(0) });
  const list = useFilteredPage(filters, '/production/boms', { q, status: status === 'ALL' ? '' : status }, page, PAGE_SIZE);

  if (record.id) {
    return (
      <BomSheet key={record.id} id={record.id === 'new' ? 'new' : Number(record.id)} editable={editable} editing={record.id === 'new' || record.mode === 'edit'}
        onEdit={(on) => record.open(record.id!, { mode: on ? 'edit' : undefined, replace: !on })}
        onClose={record.close} onChanged={list.reload} onOpen={(id, replace) => record.open(id, { replace })} />
    );
  }

  return (
    <div className="page">
      <PageHeader title={word('bom').plural} subtitle={t('Components and routing for every product; only an approved version is used in production')}
        actions={editable && <Button variant="primary" icon={Plus} onClick={() => record.open('new')}>{t('New BOM')}</Button>} />
      <Card flush>
        <div className="toolbar between">
          <Tabs value={status} onChange={(s) => { setStatus(s); setPage(0); }} options={['ALL', 'DRAFT', 'APPROVED', 'OBSOLETE'].map((s) => ({ value: s, label: s === 'ALL' ? 'All' : s.charAt(0) + s.slice(1).toLowerCase() }))} />
          <div className="row wrap">
            <SearchBox value={search} onChange={(v) => { setSearch(v); setPage(0); }} placeholder={t('Search by product')} />
            <FilterButton filters={filters} />
          </div>
        </div>
        <FilterPanel filters={filters} note={list.capped ? t('Filters look at the newest {0} records only', { 0: FILTER_ROW_LIMIT }) : undefined} />
        <DataTable rows={list.rows} loading={list.loading} onRowClick={(b) => record.open(b.id)} emptyTitle={t('No BOMs here')} columns={[
          { key: 'item', label: 'Product', render: (b) => <><span className="strong">{b.item.code}</span> <span className="muted">{b.item.name}</span></> },
          { key: 'version', label: 'Version', render: (b) => `v${b.versionNo}` },
          { key: 'output', label: 'Makes', align: 'right', render: (b) => `${formatQty(b.outputQty)} ${b.item.uom}` },
          { key: 'components', label: 'Components', align: 'right', render: (b) => b.lines.length },
          { key: 'operations', label: 'Operations', align: 'right', render: (b) => b.operations.length },
          { key: 'status', label: 'Status', render: (b) => <StatusBadge status={b.status} /> },
        ]} />
        <Pager page={page} totalPages={list.totalPages} totalElements={list.totalElements} onPage={setPage} />
      </Card>
    </div>
  );
}

interface LineState { componentId: string; qty: string; scrapPct: string }
interface OpState { name: string; workCentreId: string; setupMinutes: string; runMinutesPerUnit: string }

function BomSheet({ id, editable, editing, onEdit, onClose, onChanged, onOpen }: {
  id: number | 'new'; editable: boolean; editing: boolean; onEdit: (on: boolean) => void; onClose: () => void; onChanged: () => void; onOpen: (id: number, replace?: boolean) => void;
}) {
  const feedback = useFeedback();
  const isNew = id === 'new';
  const bom = useFetch<any>(isNew ? null : `/production/boms/${id}`);
  const cost = useFetch<any>(isNew ? null : `/production/boms/${id}/cost`);
  const [busy, setBusy] = useState<string | null>(null);

  if (!isNew && bom.loading && !bom.data) return <FullPage wide title={t('BOM')} onClose={onClose}><Spinner /></FullPage>;
  if (editing && editable && (isNew || bom.data?.status === 'DRAFT')) {
    return <BomEditor bom={bom.data} onClose={isNew ? onClose : () => onEdit(false)}
      onSaved={(saved) => { onChanged(); if (isNew) onOpen(saved.id, true); else { onEdit(false); bom.reload(); cost.reload(); } }} />;
  }
  const b = bom.data;
  if (!b) return <FullPage wide title={t('BOM')} onClose={onClose}><Banner tone="danger">{bom.error?.message ?? (isNew ? 'You cannot create BOMs.' : 'Not found')}</Banner></FullPage>;

  async function approve() {
    if (!(await feedback.confirm({ title: 'Approve BOM', message: 'Approving makes this the version used for new work orders and retires the current approved version. An approved BOM can no longer be edited.', confirmLabel: 'Approve' }))) return;
    setBusy('approve');
    try {
      await api.post(`/production/boms/${id}/approve`);
      feedback.success('BOM approved');
      bom.reload();
      onChanged();
    } catch (e) { feedback.error(e); } finally { setBusy(null); }
  }

  async function copy() {
    setBusy('copy');
    try {
      const created = await api.post(`/production/boms/${id}/copy`);
      feedback.success(t('Draft version {0} created', { 0: created.versionNo }));
      onChanged();
      onOpen(created.id);
    } catch (e) { feedback.error(e); setBusy(null); }
  }

  return (
    <FullPage wide onClose={onClose} title={<span className="row">{b.item.code} v{b.versionNo}<StatusBadge status={b.status} /></span>}
      footer={(
        <>
          <PrintActions path={`/production/boms/${id}`} templatesPath="/production/boms/print-templates" fallbackName={`BOM-${b.item.code}-v${b.versionNo}`} />
          {editable && b.status === 'DRAFT' && <Button icon={Pencil} onClick={() => onEdit(true)}>{t('Edit')}</Button>}
          <span className="grow" />
          {editable && <Button icon={Copy} busy={busy === 'copy'} onClick={copy}>{t('Copy to new version')}</Button>}
          {editable && b.status === 'DRAFT' && <Button variant="primary" icon={CheckCircle2} busy={busy === 'approve'} onClick={approve}>{t('Approve')}</Button>}
        </>
      )}>
      <div className="stack">
        <dl className="kv">
          <div><dt>{t('Product')}</dt><dd>{b.item.name}</dd></div>
          <div><dt>{t('One run makes')}</dt><dd>{formatQty(b.outputQty)} {b.item.uom}</dd></div>
          {cost.data && <>
            <div><dt>{t('Material cost')}</dt><dd>{formatMoney(cost.data.materialCost)}</dd></div>
            <div><dt>{t('Operation cost')}</dt><dd>{formatMoney(cost.data.operationCost)}</dd></div>
            <div><dt>{t('Cost per {0}', { 0: b.item.uom })}</dt><dd style={{ color: 'var(--primary)' }}>{formatMoney(cost.data.unitCost)}</dd></div>
          </>}
        </dl>
        {b.notes && <p className="muted">{b.notes}</p>}
        <Card title={t('Components')} flush>
          <DataTable rows={b.lines} columns={[
            { key: 'component', label: 'Component', render: (l) => <><span className="strong">{l.component.code}</span> <span className="muted">{l.component.name}</span></> },
            { key: 'qty', label: 'Quantity', align: 'right', render: (l) => `${formatQty(l.qty)} ${l.component.uom}` },
            { key: 'scrap', label: 'Scrap %', align: 'right', render: (l) => formatQty(l.scrapPct) },
            { key: 'avgCost', label: 'Avg cost', align: 'right', render: (l) => formatMoney(l.component.avgCost) },
          ]} />
        </Card>
        <Card title={t('Routing')} flush>
          <DataTable rows={b.operations} emptyTitle={t('No operations defined')} columns={[
            { key: 'seq', label: 'Step', render: (o) => o.seqNo },
            { key: 'name', label: 'Operation', render: (o) => <span className="strong">{o.name}</span> },
            { key: 'wc', label: 'Machine', render: (o) => o.workCentre.name },
            { key: 'setup', label: 'Setup (min)', align: 'right', render: (o) => formatQty(o.setupMinutes) },
            { key: 'run', label: 'Run (min / unit)', align: 'right', render: (o) => formatQty(o.runMinutesPerUnit) },
            { key: 'rate', label: 'Cost / hour', align: 'right', render: (o) => formatMoney(o.workCentre.hourlyCost) },
          ]} />
        </Card>
      </div>
    </FullPage>
  );
}

function BomEditor({ bom, onClose, onSaved }: { bom?: any; onClose: () => void; onSaved: (bom: any) => void }) {
  const feedback = useFeedback();
  const items = useRecords<any>('/masters/items?active=true');
  const workCentres = useRecords<any>('/masters/work-centres?active=true');
  const [itemId, setItemId] = useState(bom ? String(bom.item.id) : '');
  const [outputQty, setOutputQty] = useState(bom ? String(bom.outputQty) : '1');
  const [notes, setNotes] = useState(bom?.notes ?? '');
  const [lines, setLines] = useState<LineState[]>(() => bom
    ? bom.lines.map((l: any) => ({ componentId: String(l.component.id), qty: String(l.qty), scrapPct: String(l.scrapPct) }))
    : [{ componentId: '', qty: '1', scrapPct: '0' }]);
  const [ops, setOps] = useState<OpState[]>(() => bom
    ? bom.operations.map((o: any) => ({ name: o.name, workCentreId: String(o.workCentre.id), setupMinutes: String(o.setupMinutes), runMinutesPerUnit: String(o.runMinutesPerUnit) }))
    : []);
  const [problems, setProblems] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  const products = items.filter((i) => i.type === 'FINISHED' || i.type === 'SEMI_FINISHED');
  const components = items.filter((i) => i.type !== 'SERVICE' && String(i.id) !== itemId);
  const setLine = (i: number, change: Partial<LineState>) => setLines((prev) => prev.map((l, j) => (j === i ? { ...l, ...change } : l)));
  const setOp = (i: number, change: Partial<OpState>) => setOps((prev) => prev.map((o, j) => (j === i ? { ...o, ...change } : o)));

  function check(): string[] {
    const found: string[] = [];
    if (!itemId) found.push('Choose the product this BOM makes.');
    if (!(Number(outputQty) > 0)) found.push('The quantity one run makes must be more than zero.');
    if (lines.length === 0) found.push('Add at least one component.');
    const seen = new Set<string>();
    lines.forEach((l, i) => {
      if (!l.componentId) found.push(t('Component {0}: choose an item.', { 0: i + 1 }));
      else if (seen.has(l.componentId)) found.push(t('Component {0} is listed more than once.', { 0: i + 1 }));
      seen.add(l.componentId);
      if (!(Number(l.qty) > 0)) found.push(t('Component {0}: quantity must be more than zero.', { 0: i + 1 }));
      if (Number(l.scrapPct) < 0 || Number(l.scrapPct) > 50) found.push(t('Component {0}: scrap must be between 0 and 50%.', { 0: i + 1 }));
    });
    ops.forEach((o, i) => {
      if (!o.name.trim()) found.push(t('Operation {0}: enter a name.', { 0: i + 1 }));
      if (!o.workCentreId) found.push(t('Operation {0}: choose a machine.', { 0: i + 1 }));
      if (Number(o.setupMinutes) < 0 || Number(o.runMinutesPerUnit) < 0) found.push(t('Operation {0}: times cannot be negative.', { 0: i + 1 }));
    });
    return found;
  }

  async function save() {
    const found = check();
    setProblems(found);
    if (found.length) return;
    setSaving(true);
    const body = {
      itemId: Number(itemId), outputQty: Number(outputQty), notes: notes || null,
      lines: lines.map((l) => ({ componentId: Number(l.componentId), qty: Number(l.qty), scrapPct: Number(l.scrapPct || 0) })),
      operations: ops.map((o) => ({ name: o.name.trim(), workCentreId: Number(o.workCentreId), setupMinutes: Number(o.setupMinutes || 0), runMinutesPerUnit: Number(o.runMinutesPerUnit || 0) })),
    };
    try {
      const saved = bom ? await api.put(`/production/boms/${bom.id}`, body) : await api.post('/production/boms', body);
      feedback.success('BOM saved as draft');
      onSaved(saved);
    } catch (e) {
      const error = e as ApiError;
      const fields = Object.entries(error.fieldErrors).map(([f, m]) => `${f} ${m}`);
      setProblems(fields.length ? fields : [error.message]);
      setSaving(false);
    }
  }

  return (
    <FullPage wide title={bom ? t('Edit BOM {0} v{1}', { 0: bom.item.code, 1: bom.versionNo }) : 'New BOM'} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save draft')}</Button></>}>
      <div className="stack">
        {problems.length > 0 && <Banner tone="danger"><ul style={{ margin: 0, paddingLeft: 18 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul></Banner>}
        <div className="form-grid">
          <Field label={t('Product')} required><Select value={itemId} onChange={setItemId} disabled={Boolean(bom)} options={products.map((p) => ({ value: String(p.id), label: `${p.code} - ${p.name}` }))} /></Field>
          <Field label={t('One run makes')} required help={t('Component quantities below are for this much output.')}><input className="input num" type="number" min="0" step="any" value={outputQty} onChange={(e) => setOutputQty(e.target.value)} /></Field>
          <Field label={t('Notes')} span={2}><textarea className="input" value={notes} maxLength={400} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>

        <div>
          <div className="row between" style={{ marginBottom: 8 }}><h3 className="card-title">{t('Components')}</h3><Button small icon={Plus} onClick={() => setLines((p) => [...p, { componentId: '', qty: '1', scrapPct: '0' }])}>{t('Add component')}</Button></div>
          <table className="table lines">
            <thead><tr><th style={{ width: '55%' }}>{t('Component')}</th><th className="right">{t('Quantity')}</th><th className="right">{t('Scrap %')}</th><th /></tr></thead>
            <tbody>
              {lines.map((l, i) => (
                <tr key={i}>
                  <td><Select value={l.componentId} onChange={(v) => setLine(i, { componentId: v })} placeholder={t('Choose component')} options={components.map((c) => ({ value: String(c.id), label: `${c.code} - ${c.name} (${c.uom})` }))} /></td>
                  <td><input className="input num" type="number" min="0" step="any" value={l.qty} onChange={(e) => setLine(i, { qty: e.target.value })} aria-label={t('Component {0} quantity', { 0: i + 1 })} /></td>
                  <td><input className="input num" type="number" min="0" max="50" step="any" value={l.scrapPct} onChange={(e) => setLine(i, { scrapPct: e.target.value })} aria-label={t('Component {0} scrap', { 0: i + 1 })} /></td>
                  <td><IconButton icon={Trash2} label={t('Remove component')} disabled={lines.length === 1} onClick={() => setLines((p) => p.filter((_, j) => j !== i))} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div>
          <div className="row between" style={{ marginBottom: 8 }}><h3 className="card-title">{t('Routing')}</h3><Button small icon={Plus} onClick={() => setOps((p) => [...p, { name: '', workCentreId: '', setupMinutes: '0', runMinutesPerUnit: '0' }])}>{t('Add operation')}</Button></div>
          {ops.length === 0 ? <p className="muted">{t('No operations. Add the steps in the order they are done.')}</p> : (
            <table className="table lines">
              <thead><tr><th style={{ width: '30%' }}>{t('Operation')}</th><th style={{ width: '30%' }}>{t('Machine')}</th><th className="right">{t('Setup (min)')}</th><th className="right">{t('Run (min / unit)')}</th><th /></tr></thead>
              <tbody>
                {ops.map((o, i) => (
                  <tr key={i}>
                    <td><input className="input" value={o.name} maxLength={80} placeholder={t('e.g. Turning')} onChange={(e) => setOp(i, { name: e.target.value })} aria-label={t('Operation {0} name', { 0: i + 1 })} /></td>
                    <td><Select value={o.workCentreId} onChange={(v) => setOp(i, { workCentreId: v })} placeholder={t('Choose machine')} options={workCentres.map((w) => ({ value: String(w.id), label: w.name }))} /></td>
                    <td><input className="input num" type="number" min="0" step="any" value={o.setupMinutes} onChange={(e) => setOp(i, { setupMinutes: e.target.value })} aria-label={t('Operation {0} setup minutes', { 0: i + 1 })} /></td>
                    <td><input className="input num" type="number" min="0" step="any" value={o.runMinutesPerUnit} onChange={(e) => setOp(i, { runMinutesPerUnit: e.target.value })} aria-label={t('Operation {0} run minutes', { 0: i + 1 })} /></td>
                    <td><IconButton icon={Trash2} label={t('Remove operation')} onClick={() => setOps((p) => p.filter((_, j) => j !== i))} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </FullPage>
  );
}
