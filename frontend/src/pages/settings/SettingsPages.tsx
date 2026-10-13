import { useEffect, useMemo, useState } from 'react';
import { Check, Pencil, Save } from 'lucide-react';
import { FILTER_ROW_LIMIT, FilterButton, FilterPanel, useFilteredPage, useFilters, type FilterDef } from '../../components/Filters';
import { Field, FormFields, Select } from '../../components/FormFields';
import { Banner, Button, Card, DataTable, Modal, PageHeader, Pager, Spinner, StatusBadge, useFeedback } from '../../components/ui';
import { api, ApiError } from '../../core/api';
import { formatDateTime, humanize } from '../../core/format';
import { useFetch } from '../../core/hooks';
import { useTheme } from '../../core/theme';
import { PATTERNS, toPayload, validate, type FieldDef } from '../../core/validation';
import { t } from '../../core/i18n';

/* ---- company profile (ORG-02) ---- */

const COMPANY_FIELDS: FieldDef[] = [
  { name: 'legalName', label: 'Legal name', type: 'text', required: true, maxLength: 150, section: 'Identity', span: 2 },
  { name: 'tradeName', label: 'Trade name', type: 'text', maxLength: 60, help: 'Short name shown in the application.' },
  { name: 'baseCurrency', label: 'Base currency', type: 'text', required: true, uppercase: true, pattern: /^[A-Z]{3}$/, patternMessage: 'must be a 3-letter code' },
  { name: 'stateCode', label: 'State', type: 'lookup', lookupType: 'STATE', required: true, section: 'Tax registration', help: 'Sales within this state are charged CGST + SGST; others IGST.' },
  { name: 'gstin', label: 'GSTIN', type: 'text', uppercase: true, maxLength: 15 },
  { name: 'pan', label: 'PAN', type: 'text', uppercase: true, pattern: PATTERNS.pan, patternMessage: 'is not a valid PAN' },
  { name: 'financialYearStartMonth', label: 'Financial year starts in month', type: 'number', required: true, min: 1, max: 12, help: '4 for April. Used in document numbers.' },
  { name: 'udyamNumber', label: 'Udyam number', type: 'text', uppercase: true, pattern: PATTERNS.udyam, patternMessage: 'must look like UDYAM-MH-12-0012345', section: 'MSME' },
  { name: 'msmeCategory', label: 'MSME category', type: 'select', options: ['MICRO', 'SMALL', 'MEDIUM'].map((v) => ({ value: v, label: humanize(v) })) },
  { name: 'address', label: 'Address', type: 'textarea', maxLength: 250, span: 2, section: 'Contact' },
  { name: 'city', label: 'City', type: 'text' },
  { name: 'pincode', label: 'PIN code', type: 'text', pattern: PATTERNS.pincode, patternMessage: 'must be 6 digits' },
  { name: 'phone', label: 'Phone', type: 'text', pattern: PATTERNS.phone, patternMessage: 'is not a valid phone number' },
  { name: 'email', label: 'Email', type: 'text', pattern: PATTERNS.email, patternMessage: 'is not a valid email address' },
];

export function CompanySettings() {
  const feedback = useFeedback();
  const { reload } = useTheme();
  const company = useFetch<any>('/settings/company');
  const [values, setValues] = useState<Record<string, any> | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (company.data) setValues(company.data);
  }, [company.data]);

  async function save() {
    if (!values) return;
    const found = validate(COMPANY_FIELDS, values);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      await api.put('/settings/company', toPayload(COMPANY_FIELDS, values));
      await reload();
      feedback.success('Company profile saved');
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(Object.keys(error.fieldErrors).length ? 'Please correct the highlighted fields.' : error.message);
    } finally {
      setSaving(false);
    }
  }

  if (!values) return company.error ? <div className="page"><Banner tone="danger">{company.error.message}</Banner></div> : <Spinner />;
  return (
    <div className="page" style={{ maxWidth: 900 }}>
      <PageHeader title={t('Company')} subtitle={t('Your legal and tax identity, printed on documents and used to work out GST')}
        actions={<Button variant="primary" icon={Save} busy={saving} onClick={save}>{t('Save')}</Button>} />
      {failure && <Banner tone="danger">{failure}</Banner>}
      <Card><FormFields fields={COMPANY_FIELDS} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev!, [name]: value }))} /></Card>
    </div>
  );
}

/* ---- business rules and statutory limits ---- */

export function ParameterSettings() {
  const feedback = useFeedback();
  const parameters = useFetch<any[]>('/settings/parameters');
  const [editing, setEditing] = useState<any | null>(null);
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const groups = useMemo(() => {
    const map = new Map<string, any[]>();
    (parameters.data ?? []).forEach((p) => map.set(p.groupName, [...(map.get(p.groupName) ?? []), p]));
    return [...map.entries()];
  }, [parameters.data]);

  function edit(parameter: any) {
    setEditing(parameter);
    setValue(parameter.paramValue);
    setError(null);
  }

  async function save() {
    if (editing.valueType === 'NUMBER' && (value.trim() === '' || !(Number(value) >= 0))) return setError('Enter a number of zero or more.');
    setSaving(true);
    try {
      await api.put(`/settings/parameters/${editing.id}`, { value });
      feedback.success(`${editing.label} updated`);
      setEditing(null);
      parameters.reload();
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setSaving(false);
    }
  }

  const display = (p: any) => (p.valueType === 'BOOLEAN' ? (p.paramValue === 'true' ? 'Yes' : 'No') : p.valueType === 'NUMBER' ? Number(p.paramValue).toLocaleString('en-IN') : humanize(p.paramValue));

  return (
    <div className="page" style={{ maxWidth: 1000 }}>
      <PageHeader title={t('Rules and limits')} subtitle={t('Approval limits, tolerances and statutory thresholds. Change them here, not in code.')} />
      <Banner tone="info">{t('Statutory values (GST thresholds, MSME payment days) change by notification. Confirm them against the current CBIC and MSME rules before relying on them.')}</Banner>
      {parameters.loading && !parameters.data ? <Spinner /> : groups.map(([group, rows]) => (
        <Card key={group} title={group} flush>
          <DataTable rows={rows} columns={[
            { key: 'label', label: 'Setting', wrap: true, render: (p) => <div style={{ padding: '10px 0' }}><div className="strong">{p.label}</div><div className="muted" style={{ fontSize: 13 }}>{p.description}</div></div> },
            { key: 'value', label: 'Value', align: 'right', render: (p) => <span className="strong">{display(p)}</span> },
            { key: 'edit', label: '', align: 'right', render: (p) => <Button small icon={Pencil} onClick={() => edit(p)}>{t('Change')}</Button> },
          ]} />
        </Card>
      ))}
      {editing && (
        <Modal title={editing.label} onClose={() => setEditing(null)} footer={<><Button onClick={() => setEditing(null)}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save')}</Button></>}>
          <div className="stack tight">
            {error && <Banner tone="danger">{error}</Banner>}
            <p className="muted">{editing.description}</p>
            <Field label={t('Value')} required>
              {editing.valueType === 'NUMBER' ? <input className="input num" type="number" min="0" step="any" value={value} onChange={(e) => setValue(e.target.value)} autoFocus />
                : <Select value={value} onChange={setValue} placeholder={t('Choose')} options={editing.valueType === 'BOOLEAN'
                  ? [{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }]
                  : editing.valueType.split('|').map((v: string) => ({ value: v, label: humanize(v) }))} />}
            </Field>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ---- document numbering ---- */

const SERIES_FIELDS: FieldDef[] = [
  { name: 'prefix', label: 'Prefix', type: 'text', required: true, uppercase: true, pattern: /^[A-Z0-9]{1,5}$/, patternMessage: 'must be 1 to 5 capital letters or digits' },
  { name: 'padding', label: 'Digits', type: 'number', required: true, min: 3, max: 6 },
  { name: 'nextNumber', label: 'Next number', type: 'number', required: true, min: 1 },
  { name: 'includeFinancialYear', label: 'Include the financial year (e.g. 26-27)', type: 'checkbox', span: 2 },
];

const sample = (s: any) => `${s.prefix}/${s.includeFinancialYear ? '26-27/' : ''}${String(s.nextNumber).padStart(Number(s.padding), '0')}`;

export function NumberingSettings() {
  const feedback = useFeedback();
  const series = useFetch<any[]>('/settings/number-series');
  const [editing, setEditing] = useState<Record<string, any> | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    if (!editing) return;
    const found = validate(SERIES_FIELDS, editing);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      await api.put(`/settings/number-series/${editing.id}`, { ...editing, ...toPayload(SERIES_FIELDS, editing) });
      feedback.success('Numbering saved');
      setEditing(null);
      series.reload();
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(error.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page" style={{ maxWidth: 900 }}>
      <PageHeader title={t('Numbering')} subtitle={t('How each kind of document is numbered. GST allows at most 16 characters.')} />
      <Card flush>
        <DataTable rows={series.data ?? []} loading={series.loading} onRowClick={(s) => { setEditing(s); setErrors({}); setFailure(null); }} columns={[
          { key: 'key', label: 'Document', render: (s) => <span className="strong">{humanize(s.seriesKey)}</span> },
          { key: 'prefix', label: 'Prefix', render: (s) => s.prefix },
          { key: 'next', label: 'Next number', align: 'right', render: (s) => s.nextNumber },
          { key: 'sample', label: 'Next document will be', render: (s) => <code>{sample(s)}</code> },
        ]} />
      </Card>
      {editing && (
        <Modal title={`${humanize(editing.seriesKey)} numbering`} onClose={() => setEditing(null)} footer={<><Button onClick={() => setEditing(null)}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save')}</Button></>}>
          <div className="stack tight">
            {failure && <Banner tone="danger">{failure}</Banner>}
            <FormFields fields={SERIES_FIELDS} values={editing} errors={errors} onChange={(name, value) => setEditing((prev) => ({ ...prev!, [name]: value }))} />
            <p className="muted">{t('Next document:')}{' '}<code>{sample(editing)}</code> ({t('{0} characters', { 0: sample(editing).length })})</p>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ---- roles and access (BRD 7.2) ---- */

const LEVELS = ['NONE', 'VIEW', 'FULL'];

export function AccessSettings() {
  const feedback = useFeedback();
  const access = useFetch<any>('/admin/permissions');
  const [saving, setSaving] = useState<string | null>(null);

  async function change(role: string, module: string, level: string) {
    setSaving(`${role}-${module}`);
    try {
      await api.put('/admin/permissions', { role, module, level });
      feedback.success(t('{0} now has {1} access to {2}', { 0: humanize(role), 1: level === 'NONE' ? 'no' : level.toLowerCase(), 2: module }));
      access.reload();
    } catch (e) {
      feedback.error(e);
    } finally {
      setSaving(null);
    }
  }

  if (!access.data) return access.error ? <div className="page"><Banner tone="danger">{access.error.message}</Banner></div> : <Spinner />;
  const { modules, roles, matrix } = access.data as { modules: string[]; roles: string[]; matrix: Record<string, Record<string, string>> };
  return (
    <div className="page">
      <PageHeader title={t('Roles and access')} subtitle={t('What each role may see (View) or change (Full) in each module. Changes take effect on the next request.')} />
      <Card flush>
        <div className="table-wrap">
          <table className="table matrix">
            <thead><tr><th>{t('Module')}</th>{roles.map((r) => <th key={r}>{humanize(r)}</th>)}</tr></thead>
            <tbody>
              {modules.map((module) => (
                <tr key={module}>
                  <td className="strong">{humanize(module)}</td>
                  {roles.map((role) => {
                    const level = matrix[role]?.[module] ?? 'NONE';
                    // The Owner's own access is fixed so nobody can lock the business out.
                    return (
                      <td key={role}>
                        {role === 'OWNER' ? <StatusBadge status={level} /> : (
                          <select className="input" value={level} disabled={saving === `${role}-${module}`}
                            aria-label={t('{0} access to {1}', { 0: humanize(role), 1: module })} onChange={(e) => change(role, module, e.target.value)}>
                            {LEVELS.map((l) => <option key={l} value={l}>{humanize(l)}</option>)}
                          </select>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}

/* ---- audit trail (ADM-03) ---- */

const AUDIT_PAGE = 25;
const AUDIT_FILTERS: FilterDef[] = [
  { key: 'at', label: 'When', kind: 'date' },
  { key: 'actor', label: 'Who', kind: 'text' },
  { key: 'action', label: 'Action', kind: 'text', value: (a) => `${a.action} ${humanize(a.action)}` },
  { key: 'entity', label: 'Record', kind: 'text', value: (a) => `${a.entityType} #${a.entityId ?? '-'}` },
  { key: 'changes', label: 'Details', kind: 'text' },
];
const ENTITY_TYPES = ['Document', 'Item', 'Party', 'WorkOrder', 'BOM', 'Stock', 'Inspection', 'User', 'RolePermission', 'Parameter', 'ThemeSettings', 'CompanyProfile', 'NumberSeries'];

export function AuditTrail({ endpoint = '/admin/audit' }: { endpoint?: string }) {
  const product = endpoint !== '/admin/audit';
  const [entityType, setEntityType] = useState('');
  const [page, setPage] = useState(0);
  const [viewing, setViewing] = useState<any | null>(null);
  const filters = useFilters(AUDIT_FILTERS, { onChange: () => setPage(0) });
  const list = useFilteredPage(filters, endpoint, { entityType }, page, AUDIT_PAGE);

  return (
    <div className="page">
      <PageHeader title={product ? 'Admin activity' : 'Audit trail'} subtitle={product ? 'Clients created or changed, passwords reset and every support session' : 'Who changed what, and when. Entries cannot be edited or deleted.'} />
      <Card flush>
        <div className="toolbar">
          {!product && <div style={{ width: 220 }}><Select value={entityType} onChange={(v) => { setEntityType(v); setPage(0); }} placeholder={t('All record types')} options={ENTITY_TYPES.map((tpl) => ({ value: tpl, label: humanize(tpl.replace(/([a-z])([A-Z])/g, '$1 $2')) }))} /></div>}
          <FilterButton filters={filters} />
        </div>
        <FilterPanel filters={filters} note={list.capped ? t('Filters look at the newest {0} records only', { 0: FILTER_ROW_LIMIT }) : undefined} />
        <DataTable rows={list.rows} loading={list.loading} onRowClick={setViewing} emptyTitle={filters.count ? 'No matches' : t('No entries')} columns={[
          { key: 'at', label: 'When', render: (a) => formatDateTime(a.at) },
          { key: 'actor', label: 'Who', render: (a) => a.actor },
          { key: 'action', label: 'Action', render: (a) => <span className="strong">{humanize(a.action)}</span> },
          { key: 'entity', label: 'Record', render: (a) => `${a.entityType} #${a.entityId ?? '-'}` },
          { key: 'changes', label: 'Details', render: (a) => <span className="muted" style={{ display: 'inline-block', maxWidth: 520, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>{a.changes}</span> },
        ]} />
        <Pager page={page} totalPages={list.totalPages} totalElements={list.totalElements} onPage={setPage} />
      </Card>
      {viewing && (
        <Modal wide title={`${humanize(viewing.action)} ${viewing.entityType} #${viewing.entityId ?? '-'}`} onClose={() => setViewing(null)} footer={<Button icon={Check} onClick={() => setViewing(null)}>{t('Close')}</Button>}>
          <p className="muted" style={{ marginBottom: 12 }}>{t('{0} on {1}', { 0: viewing.actor, 1: formatDateTime(viewing.at) })}</p>
          <pre style={{ margin: 0, padding: 14, background: 'var(--surface-2)', borderRadius: 'var(--radius-sm)', overflow: 'auto', fontSize: 12.5 }}>{pretty(viewing.changes)}</pre>
        </Modal>
      )}
    </div>
  );
}

function pretty(json: string | null): string {
  if (!json) return '';
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}
