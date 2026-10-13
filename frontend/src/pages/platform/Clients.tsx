import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Building2, Copy, KeyRound, LogIn, PauseCircle, Plus, Save, Sparkles, UserPlus, Users } from 'lucide-react';
import { FilterButton, FilterPanel, useFilters, type FilterDef } from '../../components/Filters';
import { Field, FormFields } from '../../components/FormFields';
import { Badge, Banner, Button, Card, DataTable, FullPage, Modal, PageHeader, SearchBox, Spinner, Stat, StatusBadge, useFeedback } from '../../components/ui';
import { api, ApiError } from '../../core/api';
import { useAuth } from '../../core/auth';
import { formatDate, humanize } from '../../core/format';
import { useFetch, useRecordRoute } from '../../core/hooks';
import { defaults, isValidGstin, PATTERNS, toPayload, validate, type FieldDef, type Option } from '../../core/validation';
import { t } from '../../core/i18n';

/** GST state codes offered when creating a client; the client can maintain its own list afterwards. */
export const STATES: Option[] = [
  ['27', 'Maharashtra'], ['24', 'Gujarat'], ['33', 'Tamil Nadu'], ['29', 'Karnataka'], ['07', 'Delhi'], ['06', 'Haryana'], ['03', 'Punjab'],
  ['09', 'Uttar Pradesh'], ['08', 'Rajasthan'], ['36', 'Telangana'], ['37', 'Andhra Pradesh'], ['19', 'West Bengal'], ['23', 'Madhya Pradesh'],
  ['32', 'Kerala'], ['10', 'Bihar'], ['21', 'Odisha'],
].map(([value, label]) => ({ value, label: `${value} - ${label}` }));

const HOSTING: Option[] = [
  { value: 'SHARED', label: 'Our hosting (shared)' },
  { value: 'DEDICATED', label: 'Dedicated or client-hosted' },
];
const SLUG = /^[a-z][a-z0-9-]{2,29}$/;
const signInLink = (slug: string) => `${window.location.origin}/?client=${slug}`;

/** An industry template: picking one on a new client fills in its usual modules and starting lists. */
interface IndustryOption extends Option {
  group: 'MANUFACTURING' | 'TRADE_AND_SERVICES';
  modules: string[];
}

interface Options {
  industries: IndustryOption[];
  modules: string[];
  roles: string[];
}

/** The product admin's home: every client, what is switched on for each, and a way in to help them. */
export function Clients() {
  const feedback = useFeedback();
  const navigate = useNavigate();
  const { startSupport } = useAuth();
  const clients = useFetch<any[]>('/platform/clients');
  const options = useFetch<Options>('/platform/options');
  const [search, setSearch] = useState('');
  const record = useRecordRoute();
  const [credentials, setCredentials] = useState<any | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const filterDefs = useMemo<FilterDef[]>(() => [
    { key: 'industry', label: 'Industry', kind: 'choice', dropdown: true, options: options.data?.industries ?? [], value: (r) => r.client.industry },
    { key: 'plan', label: 'Plan', kind: 'choice', options: ['STARTER', 'GROWTH', 'BUSINESS'].map((value) => ({ value, label: humanize(value) })), value: (r) => r.client.plan },
    { key: 'status', label: 'Status', kind: 'choice', options: [{ value: 'ACTIVE', label: 'Active' }, { value: 'SUSPENDED', label: 'Suspended' }], value: (r) => r.client.status },
    { key: 'whiteLabel', label: 'White-label', kind: 'bool', value: (r) => r.client.whiteLabel },
    { key: 'activeUsers', label: 'Users', kind: 'number' },
    { key: 'documents', label: 'Documents', kind: 'number' },
    { key: 'createdAt', label: 'Since', kind: 'date', value: (r) => r.client.createdAt },
  ], [options.data]);
  const filters = useFilters(filterDefs);
  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return filters.apply((clients.data ?? []).filter((r) => !needle || r.client.name.toLowerCase().includes(needle) || r.client.slug.includes(needle)));
  }, [clients.data, search, filters.values]);
  const all = clients.data ?? [];
  const industryLabel = (value: string) => options.data?.industries.find((i) => i.value === value)?.label ?? humanize(value);

  /** Starts a support session as a client user (the Owner when none is named) and opens the client's workspace. */
  async function signInAs(client: any, user?: any) {
    const who = user ? `${user.fullName} (${humanize(user.role)})` : 'the Owner';
    const ok = await feedback.confirm({
      title: 'Start a support session',
      confirmLabel: 'Sign in as this user',
      message: <>{t('You will work inside {0} as {1} for up to 30 minutes. Everything you change is recorded with your name next to theirs. Users and access rights cannot be changed in a support session.', { 0: client.name, 1: who })}</>,
    });
    if (!ok) return;
    setBusy(client.id);
    try {
      const session = await api.post(`/platform/clients/${client.id}/impersonate`, user ? { userId: user.id } : undefined);
      await startSupport(session.token);
      navigate('/');
    } catch (e) {
      feedback.error(e);
      setBusy(null);
    }
  }

  const credentialsModal = credentials && <CredentialsModal credentials={credentials} onClose={() => setCredentials(null)} />;

  if (record.id) {
    const title = record.id === 'new' ? 'New client' : 'Client';
    return (
      <>
        {!options.data ? <FullPage title={title} onClose={record.close}>{options.error ? <Banner tone="danger">{options.error.message}</Banner> : <Spinner />}</FullPage>
          : record.id === 'new' ? (
            <NewClient options={options.data} onClose={record.close}
              onCreated={(created) => { setCredentials(created); clients.reload(); record.close(); }} />
          ) : (
            <ClientSheet key={record.id} id={Number(record.id)} options={options.data} onClose={record.close} onChanged={clients.reload}
              onSignInAs={signInAs} onCredentials={setCredentials} />
          )}
        {credentialsModal}
      </>
    );
  }

  return (
    <div className="page">
      <PageHeader title={t('Clients')} subtitle={t('Bring a client on, choose what they get, and step in to help when they need it')}
        actions={<Button variant="primary" icon={Plus} onClick={() => record.open('new')}>{t('New client')}</Button>} />
      <div className="grid kpi">
        <Stat icon={Building2} label={t('Clients')} value={all.length} foot={t('{0} active', { 0: all.filter((r) => r.client.status === 'ACTIVE').length })} />
        <Stat icon={Users} tone="info" label={t('Active users')} value={all.reduce((sum, r) => sum + r.activeUsers, 0)} foot="Across all clients" />
        <Stat icon={Sparkles} tone="success" label={t('White-label')} value={all.filter((r) => r.client.whiteLabel).length} foot="Clients with their own branding" />
        <Stat icon={PauseCircle} tone={all.some((r) => r.client.status === 'SUSPENDED') ? 'warning' : 'success'} label={t('Suspended')} value={all.filter((r) => r.client.status === 'SUSPENDED').length} foot="Cannot sign in" />
      </div>
      <Card flush>
        <div className="toolbar"><SearchBox value={search} onChange={setSearch} placeholder={t('Search clients')} /><FilterButton filters={filters} /></div>
        <FilterPanel filters={filters} note={t('{0} of {1} match', { 0: rows.length, 1: all.length })} />
        {clients.error ? <div className="card-body"><Banner tone="danger">{clients.error.message}</Banner></div> : (
          <DataTable rows={rows} loading={clients.loading} rowKey={(r) => r.client.id} onRowClick={(r) => record.open(r.client.id)} emptyTitle={filters.count || search ? 'No matches' : t('No clients yet')}
            emptyHint={t('Use "New client" to bring the first one on.')}
            columns={[
              { key: 'name', label: 'Client', render: (r) => <><span className="strong">{r.client.name}</span><div className="muted" style={{ fontSize: 12 }}>{r.client.slug}</div></> },
              { key: 'industry', label: 'Industry', render: (r) => industryLabel(r.client.industry) },
              { key: 'plan', label: 'Plan', render: (r) => (r.client.plan ? t(humanize(r.client.plan)) : <span className="muted">-</span>) },
              { key: 'modules', label: 'Modules', render: (r) => t('{0} of {1}', { 0: r.client.modules.length, 1: options.data?.modules.length ?? 6 }) },
              { key: 'users', label: 'Users', align: 'right', render: (r) => `${r.activeUsers}${r.client.maxUsers ? ` / ${r.client.maxUsers}` : ''}` },
              { key: 'documents', label: 'Documents', align: 'right', render: (r) => r.documents },
              { key: 'branding', label: 'Branding', render: (r) => (r.client.whiteLabel ? <Badge tone="primary" plain>{t('White-label')}</Badge> : <span className="muted">{t('Standard')}</span>) },
              { key: 'since', label: 'Since', render: (r) => formatDate(r.client.createdAt) },
              { key: 'status', label: 'Status', render: (r) => <StatusBadge status={r.client.status} labels={{ ACTIVE: 'Active', SUSPENDED: 'Suspended' }} /> },
              { key: 'act', label: '', align: 'right', render: (r) => (
                <span onClick={(e) => e.stopPropagation()}>
                  <Button small icon={LogIn} busy={busy === r.client.id} disabled={r.client.status !== 'ACTIVE'} onClick={() => signInAs(r.client)}>{t('Sign in as owner')}</Button>
                </span>
              ) },
            ]} />
        )}
      </Card>

      {credentialsModal}
    </div>
  );
}

function ModulePicker({ all, value, onChange }: { all: string[]; value: string[]; onChange: (modules: string[]) => void }) {
  return (
    <div className="row wrap" style={{ gap: 18 }}>
      {all.map((m) => (
        <label key={m} className="check">
          <input type="checkbox" checked={value.includes(m)} onChange={(e) => onChange(e.target.checked ? [...value, m] : value.filter((v) => v !== m))} />
          {humanize(m)}
        </label>
      ))}
    </div>
  );
}

/** Turns "Acme Plastics Pvt Ltd" into "acme-plastics" as a starting short name. */
function suggestSlug(name: string): string {
  return name.toLowerCase().replace(/\b(pvt|private|ltd|limited|llp|and|the)\b/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30);
}

function NewClient({ options, onClose, onCreated }: { options: Options; onClose: () => void; onCreated: (credentials: any) => void }) {
  const fields: FieldDef[] = [
    { name: 'name', label: 'Company name', type: 'text', required: true, maxLength: 120, section: 'Client', span: 2 },
    { name: 'slug', label: 'Short name', type: 'text', required: true, pattern: SLUG, patternMessage: 'must be 3 to 30 lowercase letters, digits or dashes, starting with a letter', help: 'Permanent. Used in their sign-in link and to keep their data separate.' },
    { name: 'industry', label: 'Industry', type: 'select', required: true, options: options.industries, help: 'Fills in the usual modules, units, item categories and rejection reasons for that kind of business. The client can change all of them later.' },
    { name: 'stateCode', label: 'State', type: 'select', required: true, options: STATES, default: '27' },
    { name: 'gstin', label: 'GSTIN', type: 'text', uppercase: true, maxLength: 15, help: 'Optional; they can add it later.' },
    { name: 'ownerName', label: 'Owner name', type: 'text', required: true, maxLength: 100, section: 'First user (Owner)' },
    { name: 'ownerEmail', label: 'Owner email', type: 'text', required: true, pattern: PATTERNS.email, patternMessage: 'is not a valid email address' },
    { name: 'ownerPassword', label: 'Starting password', type: 'text', pattern: /^.{8,72}$/, patternMessage: 'must be 8 to 72 characters', span: 2, help: 'Leave blank and a strong one is generated and shown to you once.' },
    { name: 'hosting', label: 'Hosting', type: 'select', required: true, options: HOSTING, default: 'SHARED', section: 'What they get' },
    { name: 'maxUsers', label: 'User limit', type: 'number', min: 0, max: 10000, default: 0, help: 'Zero means no limit.' },
    { name: 'whiteLabel', label: 'White-label: their own name, colours and sign-in page', type: 'checkbox', span: 2 },
    { name: 'notes', label: 'Notes', type: 'textarea', maxLength: 400, span: 2 },
  ];
  const [values, setValues] = useState<Record<string, any>>(() => defaults(fields));
  const [modules, setModules] = useState<string[]>(options.modules);
  const [slugEdited, setSlugEdited] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function change(name: string, value: any) {
    setValues((prev) => {
      const next = { ...prev, [name]: value };
      if (name === 'name' && !slugEdited) next.slug = suggestSlug(value);
      return next;
    });
    if (name === 'slug') setSlugEdited(true);
    if (name === 'industry') {
      const industry = options.industries.find((i) => i.value === value);
      if (industry) setModules(industry.modules);
    }
  }

  async function save() {
    const found = validate(fields.filter((f) => f.name !== 'gstin'), values);
    if (values.gstin && !isValidGstin(values.gstin)) found.gstin = 'is not a valid GSTIN';
    else if (values.gstin && !String(values.gstin).startsWith(values.stateCode)) found.gstin = 'must start with the state code';
    setErrors(found);
    setFailure(modules.length === 0 ? 'Choose at least one module.' : null);
    if (Object.keys(found).length || modules.length === 0) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      onCreated(await api.post('/platform/clients', { ...payload, ownerPassword: payload.ownerPassword || null, modules }));
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(Object.keys(error.fieldErrors).length ? 'Please correct the highlighted fields.' : error.message);
      setSaving(false);
    }
  }

  return (
    <FullPage title={t('New client')} onClose={onClose} footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Create client')}</Button></>}>
      <div className="stack">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <FormFields fields={fields} values={values} errors={errors} onChange={change} />
        <Field label={t('Modules')} required help={t('Ticked to suit the industry you picked; change them if this client needs something different. Anything left off is hidden from this client and blocked on the server.')}>
          <ModulePicker all={options.modules} value={modules} onChange={setModules} />
        </Field>
      </div>
    </FullPage>
  );
}

function ClientSheet({ id, options, onClose, onChanged, onSignInAs, onCredentials }: {
  id: number; options: Options; onClose: () => void; onChanged: () => void;
  onSignInAs: (client: any, user?: any) => void; onCredentials: (credentials: any) => void;
}) {
  const feedback = useFeedback();
  const client = useFetch<any>(`/platform/clients/${id}`);
  const users = useFetch<any[]>(`/platform/clients/${id}/users`);
  const fields: FieldDef[] = [
    { name: 'name', label: 'Company name', type: 'text', required: true, maxLength: 120, span: 2 },
    { name: 'industry', label: 'Industry', type: 'select', required: true, options: options.industries, help: 'For your records; lists already created for the client are not changed.' },
    { name: 'status', label: 'Status', type: 'select', required: true, options: [{ value: 'ACTIVE', label: 'Active' }, { value: 'SUSPENDED', label: 'Suspended (nobody can sign in)' }] },
    { name: 'hosting', label: 'Hosting', type: 'select', required: true, options: HOSTING },
    { name: 'maxUsers', label: 'User limit', type: 'number', required: true, min: 0, max: 10000, help: 'Zero means no limit.' },
    { name: 'whiteLabel', label: 'White-label: their own name, colours and sign-in page', type: 'checkbox', span: 2 },
    { name: 'notes', label: 'Notes', type: 'textarea', maxLength: 400, span: 2 },
  ];
  const [values, setValues] = useState<Record<string, any> | null>(null);
  const [modules, setModules] = useState<string[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [addingUser, setAddingUser] = useState(false);

  useEffect(() => {
    if (client.data) {
      setValues(client.data);
      setModules(client.data.modules);
    }
  }, [client.data]);

  async function save() {
    if (!values) return;
    const found = validate(fields, values);
    setErrors(found);
    setFailure(modules.length === 0 ? 'Choose at least one module.' : null);
    if (Object.keys(found).length || modules.length === 0) return;
    if (values.status === 'SUSPENDED' && client.data.status === 'ACTIVE'
      && !(await feedback.confirm({ title: 'Suspend this client?', confirmLabel: 'Suspend', danger: true, message: <>{t('Everyone at {0} is signed out at once and cannot sign in until you reactivate the account. Their data is kept.', { 0: client.data.name })}</> }))) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      await api.put(`/platform/clients/${id}`, { name: payload.name, industry: payload.industry, status: payload.status, hosting: payload.hosting, whiteLabel: payload.whiteLabel, maxUsers: payload.maxUsers, notes: payload.notes, modules });
      feedback.success('Client saved; changes apply immediately');
      client.reload();
      onChanged();
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(error.message);
    } finally {
      setSaving(false);
    }
  }

  async function resetPassword(user: any) {
    if (!(await feedback.confirm({ title: 'Reset password', confirmLabel: 'Reset password', danger: true, message: <>{t('A new temporary password will replace the current one for {0}. It is shown to you once, to pass on to them.', { 0: user.email })}</> }))) return;
    try {
      onCredentials(await api.post(`/platform/clients/${id}/users/${user.id}/reset-password`));
    } catch (e) {
      feedback.error(e);
    }
  }

  const c = client.data;
  return (
    <FullPage wide onClose={onClose} title={c ? <span className="row">{c.name}<StatusBadge status={c.status} labels={{ ACTIVE: 'Active', SUSPENDED: 'Suspended' }} /></span> : 'Client'}
      footer={c && <><Button icon={LogIn} disabled={c.status !== 'ACTIVE'} onClick={() => onSignInAs(c)}>{t('Sign in as owner')}</Button><span className="grow" /><Button variant="primary" icon={Save} busy={saving} onClick={save}>{t('Save changes')}</Button></>}>
      {!c || !values ? (client.error ? <Banner tone="danger">{client.error.message}</Banner> : <Spinner />) : (
        <div className="stack">
          {failure && <Banner tone="danger">{failure}</Banner>}
          <dl className="kv">
            <div><dt>{t('Short name')}</dt><dd>{c.slug}</dd></div>
            <div><dt>{t('Client since')}</dt><dd>{formatDate(c.createdAt)}</dd></div>
            <div><dt>{t('Created by')}</dt><dd>{c.createdBy ?? '-'}</dd></div>
            <div><dt>{t('Sign-in link')}</dt><dd><CopyText text={signInLink(c.slug)} label={c.whiteLabel ? 'Their branded page' : 'Standard page'} /></dd></div>
          </dl>
          <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev!, [name]: value }))} />
          <Field label={t('Modules')} required help={t('Switching a module off hides it for this client at once and blocks it on the server.')}>
            <ModulePicker all={options.modules} value={modules} onChange={setModules} />
          </Field>
          <Card title={t('Users')} flush actions={<Button small icon={UserPlus} onClick={() => setAddingUser(true)}>{t('Add user')}</Button>}>
            <DataTable rows={users.data ?? []} loading={users.loading} emptyTitle={t('No users')} columns={[
              { key: 'name', label: 'Name', render: (u) => <><span className="strong">{u.fullName}</span><div className="muted" style={{ fontSize: 12 }}>{u.email}</div></> },
              { key: 'role', label: 'Role', render: (u) => humanize(u.role) },
              { key: 'active', label: 'Status', render: (u) => <StatusBadge status={u.active ? 'ACTIVE' : 'INACTIVE'} labels={{ ACTIVE: 'Active', INACTIVE: 'Deactivated' }} /> },
              { key: 'act', label: '', align: 'right', render: (u) => (
                <span className="row" style={{ justifyContent: 'flex-end' }}>
                  <Button small icon={KeyRound} onClick={() => resetPassword(u)}>{t('Reset password')}</Button>
                  <Button small icon={LogIn} disabled={!u.active || c.status !== 'ACTIVE'} onClick={() => onSignInAs(c, u)}>{t('Sign in as')}</Button>
                </span>
              ) },
            ]} />
          </Card>
          {addingUser && (
            <NewUser clientId={id} clientName={c.name} roles={options.roles} onClose={() => setAddingUser(false)}
              onCreated={(created) => { setAddingUser(false); users.reload(); onChanged(); onCredentials(created); }} />
          )}
        </div>
      )}
    </FullPage>
  );
}

/** The product admin creating a sign-in for someone at the client, in any of the client's roles. */
function NewUser({ clientId, clientName, roles, onClose, onCreated }: {
  clientId: number; clientName: string; roles: string[]; onClose: () => void; onCreated: (credentials: any) => void;
}) {
  const fields: FieldDef[] = [
    { name: 'fullName', label: 'Full name', type: 'text', required: true, maxLength: 100, span: 2 },
    { name: 'email', label: 'Email', type: 'text', required: true, pattern: PATTERNS.email, patternMessage: 'is not a valid email address', span: 2 },
    { name: 'role', label: 'Role', type: 'select', required: true, options: roles.map((r) => ({ value: r, label: humanize(r) })), span: 2, help: 'Decides which modules they can open. The client Owner can change it later.' },
    { name: 'password', label: 'Starting password', type: 'text', pattern: /^.{8,72}$/, patternMessage: 'must be 8 to 72 characters', span: 2, help: 'Leave blank and a strong one is generated and shown to you once.' },
  ];
  const [values, setValues] = useState<Record<string, any>>(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const found = validate(fields, values);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      onCreated(await api.post(`/platform/clients/${clientId}/users`, { ...payload, password: payload.password || null }));
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(Object.keys(error.fieldErrors).length ? 'Please correct the highlighted fields.' : error.message);
      setSaving(false);
    }
  }

  return (
    <Modal title={t('Add a user for {0}', { 0: clientName })} onClose={onClose} footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Add user')}</Button></>}>
      <div className="stack">
        {failure && <Banner tone="danger">{failure}</Banner>}
        <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      </div>
    </Modal>
  );
}

function CopyText({ text, label }: { text: string; label?: string }) {
  const feedback = useFeedback();
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      feedback.success('Copied');
    } catch {
      feedback.error('Could not copy; select the text and copy it manually.');
    }
  }
  return (
    <span className="row" style={{ gap: 6 }}>
      <code style={{ wordBreak: 'break-all' }}>{text}</code>
      <button type="button" className="icon-btn" title={`Copy ${label ?? ''}`} aria-label={t('Copy')} onClick={copy}><Copy size={15} /></button>
    </span>
  );
}

/** Shown once after a client is created or a password is reset: the only time the password can be seen. */
function CredentialsModal({ credentials, onClose }: { credentials: any; onClose: () => void }) {
  return (
    <Modal title={t('Sign-in details for {0}', { 0: credentials.client.name })} onClose={onClose} footer={<Button variant="primary" onClick={onClose}>{t('I have saved these')}</Button>}>
      <div className="stack tight">
        <Banner tone="warning">{t('Pass these to the client now. The password is not stored in readable form and cannot be shown again; if it is lost, reset it from the client\'s page.')}</Banner>
        <dl className="kv" style={{ gridTemplateColumns: '1fr' }}>
          <div><dt>{t('Sign-in link')}</dt><dd><CopyText text={signInLink(credentials.client.slug)} /></dd></div>
          <div><dt>{t('Email')}</dt><dd><CopyText text={credentials.email} /></dd></div>
          <div><dt>{credentials.passwordGenerated ? 'Temporary password (generated)' : 'Password you set'}</dt><dd><CopyText text={credentials.temporaryPassword} /></dd></div>
        </dl>
        <p className="muted">{t('Ask them to change the password after their first sign-in, under Settings > Users.')}</p>
      </div>
    </Modal>
  );
}
