import { useState } from 'react';
import { Check, Pencil, TicketPercent, X } from 'lucide-react';
import { FilterButton, FilterPanel, useFilters, type FilterDef } from '../../components/Filters';
import { Field, FormFields, Select } from '../../components/FormFields';
import { Badge, Banner, Button, Card, DataTable, Modal, PageHeader, StatusBadge, useFeedback } from '../../components/ui';
import { api, ApiError } from '../../core/api';
import { formatDate, formatMoney, humanize, today } from '../../core/format';
import { useFetch } from '../../core/hooks';
import { t } from '../../core/i18n';
import { defaults, toPayload, validate, type FieldDef } from '../../core/validation';
import { months } from '../Signup';

interface Settings {
  enabled: boolean;
  autoApprove: boolean;
  freeMonths: number;
}

interface Overview {
  settings: Settings;
  requests: any[];
  coupons: any[];
}

const STATUS_LABELS = { PENDING: 'Waiting for approval', APPROVED: 'Approved', REJECTED: 'Rejected' };
const COUPON_LABELS = { ACTIVE: 'Active', OFF: 'Switched off', EXPIRED: 'Expired', USED_UP: 'Used up' };
const MAX_FREE_MONTHS = 3;
const choices = (labels: Record<string, string>) => Object.entries(labels).map(([value, label]) => ({ value, label }));
const PLAN_OPTIONS = ['STARTER', 'GROWTH', 'BUSINESS'].map((value) => ({ value, label: humanize(value) }));
const REQUEST_FILTERS: FilterDef[] = [
  { key: 'company', label: 'Company', kind: 'text', value: (r) => `${r.companyName} ${r.ownerName} ${r.ownerEmail}` },
  { key: 'industry', label: 'Industry', kind: 'text', value: (r) => humanize(r.industry) },
  { key: 'plan', label: 'Plan', kind: 'choice', options: PLAN_OPTIONS },
  { key: 'paymentStatus', label: 'Payment', kind: 'choice', options: choices({ DEMO_PAID: 'Demo payment', FREE_MEMBERSHIP: 'Free membership', NO_CHARGE: 'No charge' }) },
  { key: 'couponCode', label: 'Coupon code', kind: 'text' },
  { key: 'amount', label: 'Amount', kind: 'number' },
  { key: 'createdAt', label: 'Requested', kind: 'date' },
  { key: 'status', label: 'Status', kind: 'choice', options: choices(STATUS_LABELS) },
];

/** Why a coupon can or cannot be used right now. */
function couponState(coupon: any): keyof typeof COUPON_LABELS {
  if (!coupon.active) return 'OFF';
  if (coupon.validUntil && coupon.validUntil < today()) return 'EXPIRED';
  if (coupon.maxUses > 0 && coupon.used >= coupon.maxUses) return 'USED_UP';
  return 'ACTIVE';
}

const COUPON_FILTERS: FilterDef[] = [
  { key: 'code', label: 'Coupon code', kind: 'text', value: (c) => `${c.code} ${c.note ?? ''}` },
  { key: 'percentOff', label: 'Discount %', kind: 'number' },
  { key: 'used', label: 'Used by', kind: 'number' },
  { key: 'validUntil', label: 'Valid until', kind: 'date' },
  { key: 'status', label: 'Status', kind: 'choice', options: choices(COUPON_LABELS), value: couponState },
];

/**
 * Product admin > Sign-ups: whether businesses may create their own account from the home page,
 * whether a sign-up needs approval first, how long free membership lasts, the coupon codes that
 * give a discount, and the requests themselves.
 */
export function Signups() {
  const feedback = useFeedback();
  const overview = useFetch<Overview>('/platform/signups');
  const [saving, setSaving] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);
  const [editing, setEditing] = useState<any | null>(null);
  const settings = overview.data?.settings;
  const waiting = (overview.data?.requests ?? []).filter((r) => r.status === 'PENDING').length;
  const couponFilters = useFilters(COUPON_FILTERS, { id: 'coupons' });
  const requestFilters = useFilters(REQUEST_FILTERS, { id: 'requests' });
  const coupons = couponFilters.apply(overview.data?.coupons ?? []);
  const requests = requestFilters.apply(overview.data?.requests ?? []);

  async function save(next: Settings) {
    setSaving(true);
    try {
      await api.put('/platform/signup-settings', next);
      feedback.success('Sign-up settings saved');
      overview.reload();
    } catch (e) {
      feedback.error(e);
    } finally {
      setSaving(false);
    }
  }

  async function decide(request: any, approve: boolean) {
    const ok = await feedback.confirm(approve
      ? { title: 'Approve this sign-up?', confirmLabel: 'Approve', message: <>{t('A workspace is created for {0} on the {1} plan, and {2} can sign in at once.', { 0: request.companyName, 1: t(humanize(request.plan)), 2: request.ownerEmail })}</> }
      : { title: 'Reject this sign-up?', confirmLabel: 'Reject', danger: true, message: <>{t('No workspace is created for {0}.', { 0: request.companyName })}</> });
    if (!ok) return;
    setBusy(request.id);
    try {
      await api.post(`/platform/signups/${request.id}/${approve ? 'approve' : 'reject'}`);
      feedback.success(approve ? 'Sign-up approved; the workspace is ready' : 'Sign-up rejected');
      overview.reload();
    } catch (e) {
      feedback.error(e);
    } finally {
      setBusy(null);
    }
  }

  async function setCouponActive(coupon: any, active: boolean) {
    try {
      await api.put(`/platform/coupons/${coupon.code}/active`, { active });
      overview.reload();
    } catch (e) {
      feedback.error(e);
    }
  }

  const payment = (r: any) => (r.paymentStatus === 'FREE_MEMBERSHIP' ? <Badge tone="success" plain>{t('Free for {0}', { 0: months(r.freeMonths) })}</Badge>
    : r.paymentStatus === 'NO_CHARGE' ? <Badge tone="success" plain>{t('No charge')}</Badge>
      : <Badge tone="info" plain>{t('Demo payment')}</Badge>);

  return (
    <div className="page">
      <PageHeader title="Sign-ups" subtitle="Let businesses create their own account from the home page, and decide who gets in" />
      {overview.error && <Banner tone="danger">{overview.error.message}</Banner>}
      <Card title="Settings">
        {settings && (
          <div className="stack">
            <label className="check">
              <input type="checkbox" checked={settings.enabled} disabled={saving} onChange={(e) => save({ ...settings, enabled: e.target.checked })} />
              <span><strong>{t('Allow self sign-up')}</strong><span className="field-help" style={{ display: 'block' }}>{t('Shows Sign up and the plans on the home page. When off, only the Enterprise option is shown.')}</span></span>
            </label>
            <label className="check">
              <input type="checkbox" checked={settings.autoApprove} disabled={saving} onChange={(e) => save({ ...settings, autoApprove: e.target.checked })} />
              <span><strong>{t('Approve sign-ups automatically')}</strong><span className="field-help" style={{ display: 'block' }}>{t('When on, a sign-up gets its workspace at once. When off, each one waits here until you approve it.')}</span></span>
            </label>
            <div style={{ maxWidth: 340 }}>
              <Field label="Free membership" help="How long a new business uses the product before it pays. Nothing is charged at sign-up while this is on.">
                <Select value={settings.freeMonths ? String(settings.freeMonths) : ''} disabled={saving} placeholder="No free membership"
                  onChange={(value) => save({ ...settings, freeMonths: Number(value || 0) })}
                  options={Array.from({ length: MAX_FREE_MONTHS }, (_, i) => ({ value: String(i + 1), label: months(i + 1) }))} />
              </Field>
            </div>
            <p className="muted" style={{ fontSize: 13 }}>{t('Enterprise is never sold by sign-up: a client who wants their own environment and database, or white-label, is asked to book a demo.')}</p>
          </div>
        )}
      </Card>

      <Card flush title={<span className="row"><TicketPercent size={18} />{t('Coupons')}</span>} actions={<FilterButton filters={couponFilters} />}>
        <NewCoupon onCreated={overview.reload} />
        <div style={{ paddingTop: 14 }}><FilterPanel filters={couponFilters} note={t('{0} of {1} match', { 0: coupons.length, 1: overview.data?.coupons.length ?? 0 })} /></div>
        <DataTable rows={coupons} loading={overview.loading} rowKey={(c) => c.code} emptyTitle={couponFilters.count ? 'No matches' : 'No coupons yet'}
          emptyHint={couponFilters.count ? 'No record matches these filters.' : 'Generate a code and give it to a business that asks for a discount. They type it when they sign up.'}
          columns={[
            { key: 'code', label: 'Coupon code', render: (c) => <><code className="strong">{c.code}</code>{c.note && <div className="muted" style={{ fontSize: 12 }}>{c.note}</div>}</> },
            { key: 'off', label: 'Discount', render: (c) => t('{0}% off', { 0: c.percentOff }) },
            { key: 'used', label: 'Used by', render: (c) => (c.maxUses > 0 ? t('{0} of {1} businesses', { 0: c.used, 1: c.maxUses }) : t('{0}, no limit', { 0: c.used })) },
            { key: 'until', label: 'Valid until', render: (c) => (c.validUntil ? formatDate(c.validUntil) : <span className="muted">{t('No end date')}</span>) },
            { key: 'status', label: 'Status', render: (c) => <StatusBadge status={couponState(c)} labels={COUPON_LABELS} /> },
            { key: 'act', label: '', align: 'right', render: (c) => (
              <span className="row" style={{ justifyContent: 'flex-end' }}>
                <Button small icon={Pencil} onClick={() => setEditing(c)}>{t('Edit')}</Button>
                <Button small onClick={() => setCouponActive(c, !c.active)}>{c.active ? t('Switch off') : t('Switch on')}</Button>
              </span>
            ) },
          ]} />
      </Card>

      <Card flush title={<span className="row">{t('Requests')}{waiting > 0 && <Badge tone="warning">{t('{0} waiting', { 0: waiting })}</Badge>}</span>} actions={<FilterButton filters={requestFilters} />}>
        <FilterPanel filters={requestFilters} note={t('{0} of {1} match', { 0: requests.length, 1: overview.data?.requests.length ?? 0 })} />
        <DataTable rows={requests} loading={overview.loading} emptyTitle={requestFilters.count ? 'No matches' : 'No sign-ups yet'}
          emptyHint={requestFilters.count ? 'No record matches these filters.' : 'Requests appear here when a business signs up from the home page.'}
          columns={[
            { key: 'company', label: 'Company', render: (r) => <><span className="strong">{r.companyName}</span><div className="muted" style={{ fontSize: 12 }}>{r.ownerName} · {r.ownerEmail}{r.phone ? ` · ${r.phone}` : ''}</div></> },
            { key: 'industry', label: 'Industry', render: (r) => t(humanize(r.industry)) },
            { key: 'plan', label: 'Plan', render: (r) => <>{t(humanize(r.plan))}{r.couponCode && <div className="muted" style={{ fontSize: 12 }}>{t('Coupon {0}', { 0: r.couponCode })}</div>}</> },
            { key: 'amount', label: 'Amount', align: 'right', render: (r) => formatMoney(r.amount, true) },
            { key: 'payment', label: 'Payment', render: (r) => <>{payment(r)}<div className="muted" style={{ fontSize: 12 }}>{r.paymentRef}</div></> },
            { key: 'requested', label: 'Requested', render: (r) => formatDate(r.createdAt) },
            { key: 'status', label: 'Status', render: (r) => <><StatusBadge status={r.status} labels={STATUS_LABELS} />{r.decidedBy && <div className="muted" style={{ fontSize: 12 }}>{t(r.decidedBy)}</div>}</> },
            { key: 'act', label: '', align: 'right', render: (r) => r.status === 'PENDING' && (
              <span className="row" style={{ justifyContent: 'flex-end' }}>
                <Button small variant="primary" icon={Check} busy={busy === r.id} onClick={() => decide(r, true)}>{t('Approve')}</Button>
                <Button small icon={X} disabled={busy === r.id} onClick={() => decide(r, false)}>{t('Reject')}</Button>
              </span>
            ) },
          ]} />
      </Card>
      {editing && <EditCoupon coupon={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); overview.reload(); }} />}
    </div>
  );
}

/** What a coupon is made of. One sign-up uses a coupon once, so the limit is a number of businesses. */
function couponFields(editing: boolean): FieldDef[] {
  return [
    { name: 'code', label: 'Coupon code', type: 'text', required: editing, uppercase: true, maxLength: 20, pattern: /^[A-Za-z0-9-]{4,20}$/, patternMessage: 'must be 4 to 20 letters, digits or dashes', help: editing ? 'Businesses that already used it keep their discount.' : 'Leave blank and a code is generated.' },
    { name: 'percentOff', label: 'Discount %', type: 'number', required: true, min: 1, max: 100, default: 10 },
    { name: 'maxUses', label: 'How many businesses can use it', type: 'number', min: 0, max: 100000, default: 0, help: 'Each sign-up uses the coupon once. Zero means no limit.' },
    { name: 'validUntil', label: 'Valid until', type: 'date', help: 'Leave blank for no end date.' },
    { name: 'note', label: 'Note', type: 'text', maxLength: 200, span: 2, help: 'For your own records, for example who it was given to.' },
  ];
}

/** Changes a coupon: its code, discount, how many businesses can use it, its end date or its note. */
function EditCoupon({ coupon, onClose, onSaved }: { coupon: any; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const fields = couponFields(true);
  const [values, setValues] = useState<Record<string, any>>({ ...coupon, validUntil: coupon.validUntil ?? '', note: coupon.note ?? '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const found = validate(fields, values);
    if (values.validUntil && values.validUntil !== coupon.validUntil && values.validUntil <= today()) found.validUntil = 'must be a future date';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      const saved = await api.put(`/platform/coupons/${coupon.code}`, { ...payload, validUntil: payload.validUntil || null, note: payload.note || null });
      feedback.success(t('Coupon {0} saved', { 0: saved.code }));
      onSaved();
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(Object.keys(error.fieldErrors).length ? 'Please correct the highlighted fields.' : error.message);
      setSaving(false);
    }
  }

  return (
    <Modal title={t('Edit coupon {0}', { 0: coupon.code })} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save')}</Button></>}>
      <div className="stack">
        {failure && <Banner tone="danger">{t(failure)}</Banner>}
        {coupon.used > 0 && <Banner tone="info">{t('Used by {0} so far.', { 0: coupon.used })}</Banner>}
        <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      </div>
    </Modal>
  );
}

/** Makes a coupon. Leaving the code empty generates one. */
function NewCoupon({ onCreated }: { onCreated: () => void }) {
  const feedback = useFeedback();
  const fields = couponFields(false);
  const [values, setValues] = useState<Record<string, any>>(() => defaults(fields));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function create() {
    const found = validate(fields, values);
    if (values.validUntil && values.validUntil <= today()) found.validUntil = 'must be a future date';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      const payload = toPayload(fields, values);
      const coupon = await api.post('/platform/coupons', { ...payload, code: payload.code || null, validUntil: payload.validUntil || null, note: payload.note || null });
      feedback.success(t('Coupon {0} created', { 0: coupon.code }));
      setValues(defaults(fields));
      onCreated();
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(Object.keys(error.fieldErrors).length ? 'Please correct the highlighted fields.' : error.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card-body stack" style={{ borderBottom: '1px solid var(--border)' }}>
      {failure && <Banner tone="danger">{t(failure)}</Banner>}
      <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
      <div><Button variant="primary" icon={TicketPercent} busy={saving} onClick={create}>{t('Generate coupon')}</Button></div>
    </div>
  );
}
