import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Check, Minus, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError, query, type Page } from '../core/api';
import { useAuth, useLabels } from '../core/auth';
import { formatDate, formatMoney, formatQty, humanize } from '../core/format';
import { useDebounced, useFetch, useRecordRoute } from '../core/hooks';
import { defaults, toPayload, validate, type FieldDef, type Option } from '../core/validation';
import { FilterButton, FilterPanel, useFilters, type FilterDef } from './Filters';
import { FormFields, Select } from './FormFields';
import { Banner, Button, Card, DataTable, FullPage, IconButton, PageHeader, Pager, SearchBox, Spinner, StatusBadge, useFeedback, type Column } from './ui';
import { t } from '../core/i18n';

export type CellType = 'text' | 'money' | 'number' | 'date' | 'status' | 'bool' | 'label';

export interface ColumnDef {
  key: string;
  label: string;
  type?: CellType;
  sortable?: boolean;
  /** Overrides the default rendering for the cell type. */
  render?: (row: any) => ReactNode;
}

/** Everything that makes one master screen different from another. */
export interface ResourceConfig {
  /** Label service term: title and singular follow the client's own word for it. */
  term?: string;
  title: string;
  singular: string;
  subtitle: string;
  /** API path under /api, e.g. /masters/items. Its first segment is the permission module. */
  endpoint: string;
  columns: ColumnDef[];
  fields: FieldDef[];
  /** Always applied to the list, e.g. only customers. */
  baseFilter?: Record<string, string>;
  /** Dropdown filters above the table. */
  filters?: { key: string; label: string; options: Option[] }[];
  canDelete?: boolean;
  searchPlaceholder?: string;
}

/** Formats a value by cell type; shared with the report viewer. */
export function renderCell(type: CellType | undefined, value: unknown): ReactNode {
  if (value === null || value === undefined || value === '') return <span className="muted">-</span>;
  switch (type) {
    case 'money': return formatMoney(value);
    case 'number': return formatQty(value);
    case 'date': return formatDate(value);
    case 'status': return <StatusBadge status={String(value)} />;
    case 'label': return humanize(value);
    case 'bool': return value ? <Check size={16} color="var(--success)" /> : <Minus size={16} color="var(--text-muted)" />;
    default: return String(value);
  }
}

export function toColumns(defs: ColumnDef[]): Column[] {
  return defs.map((d) => ({
    key: d.key,
    label: d.label,
    sortable: d.sortable,
    align: d.type === 'money' || d.type === 'number' ? 'right' : undefined,
    render: d.render ?? ((row) => renderCell(d.type, row[d.key])),
  }));
}

const PAGE_SIZE = 15;

/**
 * The filters of a master come from its fields: choices for dropdowns and lists, yes/no for
 * ticks, from-to for numbers and dates, and "contains" for the text shown as a column.
 */
function fieldFilters(config: ResourceConfig): FilterDef[] {
  const taken = new Set([...(config.filters ?? []).map((f) => f.key), ...Object.keys(config.baseFilter ?? {})]);
  const shown = new Set(config.columns.map((c) => c.key));
  return config.fields.filter((f) => !taken.has(f.name)).flatMap((f): FilterDef[] => {
    const base = { key: f.name, label: f.label };
    switch (f.type) {
      case 'select': return [{ ...base, kind: 'choice', options: f.options }];
      case 'lookup': return [{ ...base, kind: 'choice', lookupType: f.lookupType }];
      case 'checkbox': return [{ ...base, kind: 'bool' }];
      case 'number': return [{ ...base, kind: 'number' }];
      case 'date': return [{ ...base, kind: 'date' }];
      case 'text': return shown.has(f.name) ? [{ ...base, kind: 'text' }] : [];
      default: return [];
    }
  });
}

/** List, search, filter, page, create, edit and delete for any flat master, from a config. */
export function ResourcePage({ config: standard }: { config: ResourceConfig }) {
  const { can } = useAuth();
  const { word, one } = useLabels();
  const config = standard.term ? { ...standard, title: word(standard.term).plural, singular: one(standard.term) } : standard;
  const feedback = useFeedback();
  const module = config.endpoint.split('/')[1];
  const editable = can(module, 'FULL');

  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('id,desc');
  const record = useRecordRoute();
  const q = useDebounced(search);

  // A different master reuses this component, so reset list state when the config changes. The original
  // config, not the relabelled copy made on every render, or every keystroke and click would be undone.
  useEffect(() => {
    setSearch('');
    setFilters({});
    setPage(0);
    setSort('id,desc');
  }, [standard]);

  const filterDefs = useMemo(() => fieldFilters(standard), [standard]);
  const more = useFilters(filterDefs, { onChange: () => setPage(0) });
  const list = useFetch<Page<any>>(`${config.endpoint}${query({ q, page, size: PAGE_SIZE, sort, ...more.params, ...config.baseFilter, ...filters })}`);

  const columns: Column[] = [
    ...toColumns(config.columns),
    ...(editable ? [{
      key: '_actions', label: '', align: 'right' as const,
      render: (row: any) => (
        <span className="row" style={{ justifyContent: 'flex-end', gap: 2 }} onClick={(e) => e.stopPropagation()}>
          <IconButton icon={Pencil} label={t('Edit {0}', { 0: config.singular })} onClick={() => record.open(row.id)} />
          {config.canDelete !== false && <IconButton icon={Trash2} label={t('Delete {0}', { 0: config.singular })} onClick={() => remove(row)} />}
        </span>
      ),
    }] : []),
  ];

  async function remove(row: any) {
    const ok = await feedback.confirm({
      title: t('Delete {0}?', { 0: config.singular }),
      message: <><strong>{row.name ?? row.code ?? row.email}</strong>: {t('This permanently removes the record. Records already used in transactions cannot be deleted; deactivate them instead.')}</>,
      confirmLabel: 'Delete', danger: true,
    });
    if (!ok) return;
    try {
      await api.delete(`${config.endpoint}/${row.id}`);
      feedback.success(`${humanize(config.singular)} deleted`);
      list.reload();
    } catch (e) {
      feedback.error(e);
    }
  }

  if (record.id) {
    return editable
      ? <ResourceForm key={`${config.endpoint}-${record.id}`} config={config} id={record.id} onClose={record.close} onSaved={() => { record.close(); list.reload(); }} />
      : <FullPage title={humanize(config.singular)} onClose={record.close}><Banner tone="warning">{t('You can view this list but not change its records.')}</Banner></FullPage>;
  }

  return (
    <div className="page">
      <PageHeader title={config.title} subtitle={config.subtitle}
        actions={editable && <Button variant="primary" icon={Plus} onClick={() => record.open('new')}>{t('New {0}', { 0: config.singular })}</Button>} />
      <Card flush>
        <div className="toolbar">
          <SearchBox value={search} onChange={(v) => { setSearch(v); setPage(0); }} placeholder={config.searchPlaceholder ?? t('Search {0}', { 0: config.title })} />
          {config.filters?.map((f) => (
            <div key={f.key} style={{ width: 180 }}>
              <Select value={filters[f.key] ?? ''} placeholder={t('All {0}', { 0: t(f.label) })} options={f.options}
                onChange={(v) => { setFilters((prev) => ({ ...prev, [f.key]: v })); setPage(0); }} />
            </div>
          ))}
          <FilterButton filters={more} />
        </div>
        <FilterPanel filters={more} />
        {list.error ? <div className="card-body"><Banner tone="danger">{list.error.message}</Banner></div> : (
          <DataTable columns={columns} rows={list.data?.content ?? []} loading={list.loading} sort={sort} onSort={setSort}
            onRowClick={editable ? (row) => record.open(row.id) : undefined}
            emptyTitle={q || more.count ? 'No matches' : `No ${config.title.toLowerCase()} yet`}
            emptyHint={more.count ? 'No record matches these filters.' : q ? 'Try a different search.' : editable ? t('Use "New {0}" to add the first one.', { 0: config.singular }) : undefined} />
        )}
        <Pager page={page} totalPages={list.data?.totalPages ?? 0} totalElements={list.data?.totalElements ?? 0} onPage={setPage} />
      </Card>
    </div>
  );
}

/** The new or edit page of one record; an existing record is loaded by the id in the address. */
function ResourceForm({ config, id, onClose, onSaved }: { config: ResourceConfig; id: string; onClose: () => void; onSaved: () => void }) {
  const isNew = id === 'new';
  const existing = useFetch<Record<string, any>>(isNew ? null : `${config.endpoint}/${id}`);
  const title = t(isNew ? 'New {0}' : 'Edit {0}', { 0: config.singular });
  if (isNew) return <ResourceEditor config={config} record={{}} title={title} onClose={onClose} onSaved={onSaved} />;
  if (!existing.data) {
    return <FullPage title={title} onClose={onClose}>{existing.error ? <Banner tone="danger">{existing.error.message}</Banner> : <Spinner />}</FullPage>;
  }
  return <ResourceEditor config={config} record={existing.data} title={title} onClose={onClose} onSaved={onSaved} />;
}

function ResourceEditor({ config, record, title, onClose, onSaved }: { config: ResourceConfig; record: Record<string, any>; title: string; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const isNew = record.id === undefined;
  const [values, setValues] = useState<Record<string, any>>(() => ({ ...defaults(config.fields), ...record }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function change(name: string, value: any) {
    setValues((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: '' }));
  }

  async function save() {
    const found = validate(config.fields, values);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      const payload = toPayload(config.fields, values);
      if (isNew) await api.post(config.endpoint, payload);
      else await api.put(`${config.endpoint}/${record.id}`, payload);
      feedback.success(t(isNew ? '{0} created' : '{0} saved', { 0: config.singular }));
      onSaved();
    } catch (e) {
      // Field-level problems from the server land next to the field; anything else goes on top.
      const error = e as ApiError;
      setErrors(error.fieldErrors ?? {});
      setFailure(Object.keys(error.fieldErrors ?? {}).length ? 'Please correct the highlighted fields.' : error.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <FullPage title={title} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save')}</Button></>}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save(); }} noValidate>
        {failure && <Banner tone="danger">{failure}</Banner>}
        <FormFields fields={config.fields} values={values} errors={errors} onChange={change} editing={!isNew} />
        <button type="submit" hidden />
      </form>
    </FullPage>
  );
}
