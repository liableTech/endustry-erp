import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Download, FileBarChart } from 'lucide-react';
import { FilterButton, FilterPanel, optionsFrom, useFilters, type FilterDef } from '../components/Filters';
import { Field } from '../components/FormFields';
import { renderCell, type CellType } from '../components/ResourcePage';
import { Banner, Button, Card, DataTable, PageHeader, SearchBox, Spinner } from '../components/ui';
import { query } from '../core/api';
import { downloadCsv, formatMoney, formatQty, humanize, today } from '../core/format';
import { useFetch } from '../core/hooks';
import { t } from '../core/i18n';

/** RPT-03: the report library, grouped by area. */
export function ReportIndex() {
  const { data, loading, error } = useFetch<any[]>('/reports');
  const groups = useMemo(() => {
    const map = new Map<string, any[]>();
    (data ?? []).forEach((d) => map.set(d.group, [...(map.get(d.group) ?? []), d]));
    return [...map.entries()];
  }, [data]);
  if (loading) return <Spinner />;
  return (
    <div className="page">
      <PageHeader title={t('Reports')} subtitle={t('Every report can be filtered, totalled and exported to Excel (CSV)')} />
      {error && <Banner tone="danger">{error.message}</Banner>}
      {groups.map(([group, reports]) => (
        <div key={group} className="stack tight">
          <h3 className="card-title">{group}</h3>
          <div className="grid three">
            {reports.map((r) => (
              <Link key={r.key} to={`/reports/${r.key}`} className="card report-card">
                <div className="stat-icon tone-primary"><FileBarChart size={20} /></div>
                <div><div className="strong" style={{ fontWeight: 600 }}>{r.title}</div><div className="muted" style={{ fontSize: 13 }}>{r.description}</div></div>
              </Link>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

const firstOfMonthsAgo = (months: number) => {
  const d = new Date();
  d.setMonth(d.getMonth() - months, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
};

/** One screen renders any report: the server says which columns it has and what type each is. */
export function ReportView() {
  const { key } = useParams();
  const [from, setFrom] = useState(firstOfMonthsAgo(5));
  const [to, setTo] = useState(today());
  const [search, setSearch] = useState('');
  const invalidPeriod = from > to;
  const { data, loading, error } = useFetch<any>(invalidPeriod ? null : `/reports/${key}${query({ from, to })}`);

  const columns: { key: string; label: string; type: CellType }[] = data?.columns ?? [];
  // Every column of the report can be filtered, by the kind of value it holds.
  const filterDefs = useMemo<FilterDef[]>(() => columns.map((c) => {
    const base = { key: c.key, label: c.label };
    if (c.type === 'money' || c.type === 'number') return { ...base, kind: 'number' as const };
    if (c.type === 'date') return { ...base, kind: 'date' as const };
    if (c.type === 'bool') return { ...base, kind: 'bool' as const };
    if (c.type === 'status' || c.type === 'label') return { ...base, kind: 'choice' as const, options: optionsFrom(data?.rows ?? [], (r) => r[c.key], humanize) };
    return { ...base, kind: 'text' as const };
  }), [data]);
  const filters = useFilters(filterDefs);
  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const all: any[] = data?.rows ?? [];
    return filters.apply(needle ? all.filter((r) => Object.values(r).some((v) => String(v ?? '').toLowerCase().includes(needle))) : all);
  }, [data, search, filters.values]);
  const totals = useMemo(() => {
    const sums: Record<string, number> = {};
    columns.filter((c) => c.type === 'money' || c.type === 'number').forEach((c) => {
      sums[c.key] = rows.reduce((sum, r) => sum + Number(r[c.key] ?? 0), 0);
    });
    return sums;
  }, [columns, rows]);
  // Per-unit and rate columns are not meaningful when added up.
  const summable = (c: { key: string; type: CellType }) => (c.type === 'money' || c.type === 'number') && !/cost|rate|level|days/i.test(c.key);

  return (
    <div className="page">
      <PageHeader title={data?.definition.title ?? 'Report'} subtitle={data?.definition.description} actions={
        <>
          <Link to="/reports" className="btn"><ArrowLeft size={16} />{t('All reports')}</Link>
          <Button variant="primary" icon={Download} disabled={rows.length === 0}
            onClick={() => downloadCsv(`${key}-${to}.csv`, columns.map((c) => c.label), rows.map((r) => columns.map((c) => r[c.key])))}>{t('Export')}</Button>
        </>
      } />
      <Card flush>
        <div className="toolbar">
          {data?.definition.dated !== false && (
            <>
              <div style={{ width: 170 }}><Field label={t('From')}><input className={`input ${invalidPeriod ? 'invalid' : ''}`} type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field></div>
              <div style={{ width: 170 }}><Field label={t('To')}><input className="input" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field></div>
            </>
          )}
          <span className="grow" />
          <SearchBox value={search} onChange={setSearch} placeholder={t('Filter rows')} />
          <FilterButton filters={filters} />
        </div>
        <FilterPanel filters={filters} note={t('{0} of {1} match', { 0: rows.length, 1: data?.rows?.length ?? 0 })} />
        {invalidPeriod ? <div className="card-body"><Banner tone="danger">{t('The "from" date cannot be after the "to" date.')}</Banner></div>
          : error ? <div className="card-body"><Banner tone="danger">{error.message}</Banner></div> : (
            <DataTable rows={rows} loading={loading} rowKey={(r) => JSON.stringify(r)} emptyTitle={t('No rows for this period')}
              columns={columns.map((c) => ({ key: c.key, label: c.label, align: c.type === 'money' || c.type === 'number' ? 'right' as const : undefined, render: (r: any) => renderCell(c.type, r[c.key]) }))}
              footer={rows.length > 0 && columns.some(summable) && (
                <tr>
                  {columns.map((c, i) => (
                    <td key={c.key} className={summable(c) ? 'num' : ''}>
                      {i === 0 ? t('Total ({0})', { 0: rows.length }) : summable(c) ? (c.type === 'money' ? formatMoney(totals[c.key]) : formatQty(totals[c.key])) : ''}
                    </td>
                  ))}
                </tr>
              )} />
          )}
      </Card>
    </div>
  );
}
