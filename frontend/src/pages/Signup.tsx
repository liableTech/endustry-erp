import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Check, CheckCircle2, Clock, Factory, Gift, Lock, Mail, TicketPercent } from 'lucide-react';
import { FormFields } from '../components/FormFields';
import { Banner, Button, Modal, Spinner } from '../components/ui';
import { api, ApiError, query } from '../core/api';
import { humanize } from '../core/format';
import { LanguageSwitcher, t } from '../core/i18n';
import { useTheme } from '../core/theme';
import { defaults, PATTERNS, toPayload, validate, type FieldDef, type Option } from '../core/validation';
import { CONTACT_EMAIL, enterpriseDemoLink } from './Landing';
import { STATES } from './platform/Clients';
import './landing.css';

export interface PlanOption {
  code: string;
  name: string;
  monthlyPrice: number;
  users: number;
  modules: string[];
}

export interface SignupOptions {
  enabled: boolean;
  autoApprove: boolean;
  /** Months of free membership a new business gets; 0 when none is on offer. */
  freeMonths: number;
  plans: PlanOption[];
  industries: Option[];
}

/** What a coupon does to the chosen plan's monthly price. */
interface Quote {
  code: string;
  percentOff: number;
  discount: number;
  monthlyPrice: number;
}

interface Receipt {
  status: 'APPROVED' | 'PENDING';
  reference: string;
  email: string;
  plan: string;
  amount: number;
  couponCode: string | null;
  discount: number;
  freeMonths: number;
}

const GST_RATE = 18;
const POPULAR_PLAN = 'GROWTH';
const PAYMENT_METHODS = ['UPI', 'Card', 'Net banking'];
export const rupees = (amount: number) => `₹${amount.toLocaleString('en-IN')}`;
/** "1 month" or "3 months", in the user's language. */
export const months = (count: number) => (count === 1 ? t('1 month') : t('{0} months', { 0: count }));

/** One plan as a card: its price, how many people can sign in and what is switched on. */
export function PlanCard({ plan, freeMonths = 0, selected, onPick, action }: { plan: PlanOption; freeMonths?: number; selected?: boolean; onPick?: () => void; action?: React.ReactNode }) {
  const body = (
    <>
      {plan.code === POPULAR_PLAN && <span className="lp-plan-flag">{t('Most popular')}</span>}
      <h3>{t(plan.name)}</h3>
      <div className="lp-price"><strong>{rupees(plan.monthlyPrice)}</strong><span>{t('a month, plus GST')}</span></div>
      {freeMonths > 0 && <span className="lp-plan-free"><Gift size={14} />{' '}{t('Free for the first {0}', { 0: months(freeMonths) })}</span>}
      <ul className="lp-plan-list">
        <li><Check size={15} />{' '}{t('Up to {0} users', { 0: plan.users })}</li>
        {plan.modules.map((m) => <li key={m}><Check size={15} />{' '}{t(humanize(m))}</li>)}
      </ul>
      {action}
    </>
  );
  return onPick
    ? <button type="button" className={`lp-plan ${selected ? 'on' : ''}`} aria-pressed={selected} onClick={onPick}>{body}</button>
    : <div className={`lp-plan ${plan.code === POPULAR_PLAN ? 'on' : ''}`}>{body}</div>;
}

/** The public sign-up page: pick a plan, describe the business, pay and get a workspace. */
export function Signup() {
  const { theme } = useTheme();
  const [options, setOptions] = useState<SignupOptions | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [plan, setPlan] = useState(new URLSearchParams(window.location.search).get('plan') ?? POPULAR_PLAN);
  const [values, setValues] = useState<Record<string, any>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [coupon, setCoupon] = useState('');
  const [quote, setQuote] = useState<Quote | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [paying, setPaying] = useState(false);
  const [receipt, setReceipt] = useState<Receipt | null>(null);

  const fields: FieldDef[] = [
    { name: 'companyName', label: 'Company name', type: 'text', required: true, maxLength: 120, span: 2 },
    { name: 'industry', label: 'Industry', type: 'select', required: true, options: options?.industries ?? [] },
    { name: 'stateCode', label: 'State', type: 'select', required: true, options: STATES, default: '27' },
    { name: 'ownerName', label: 'Your name', type: 'text', required: true, maxLength: 100 },
    { name: 'phone', label: 'Phone', type: 'text', pattern: /^[0-9+ -]{7,15}$/, patternMessage: 'is not a valid phone number', help: 'Optional' },
    { name: 'ownerEmail', label: 'Email', type: 'text', required: true, pattern: PATTERNS.email, patternMessage: 'is not a valid email address', span: 2 },
    { name: 'password', label: 'Password', type: 'password', required: true, pattern: /^.{8,72}$/, patternMessage: 'must be 8 to 72 characters', span: 2, help: 'At least 8 characters. You will sign in with this email and password.' },
  ];

  useEffect(() => {
    api.get<SignupOptions>('/public/signup').then((loaded) => {
      setOptions(loaded);
      setValues(defaults(fields));
      if (!loaded.plans.some((p) => p.code === plan)) setPlan(loaded.plans[0]?.code ?? '');
    }).catch((e) => setLoadError((e as ApiError).message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const chosen = options?.plans.find((p) => p.code === plan);

  /** Checks the typed coupon against the plan; with no code typed, clears any discount. */
  async function applyCoupon(forPlan = plan) {
    const code = coupon.trim();
    setCouponError(null);
    if (!code) {
      setQuote(null);
      return;
    }
    setChecking(true);
    try {
      setQuote(await api.get<Quote>(`/public/signup/coupon${query({ code, plan: forPlan })}`));
    } catch (e) {
      setQuote(null);
      setCouponError((e as ApiError).message);
    } finally {
      setChecking(false);
    }
  }

  function pickPlan(code: string) {
    setPlan(code);
    // The discount is a share of the plan's price, so it is worked out again for the new plan.
    if (quote) applyCoupon(code);
  }

  function removeCoupon() {
    setCoupon('');
    setQuote(null);
    setCouponError(null);
  }

  function toPayment() {
    const found = validate(fields, values);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length === 0 && chosen) setPaying(true);
  }

  async function pay() {
    const payload = toPayload(fields, values);
    try {
      setReceipt(await api.post<Receipt>('/public/signup', { ...payload, phone: payload.phone || null, plan, couponCode: quote?.code ?? null }));
      setPaying(false);
    } catch (e) {
      const error = e as ApiError;
      setPaying(false);
      setErrors(error.fieldErrors);
      if (error.code.startsWith('COUPON_')) {
        setQuote(null);
        setCouponError(error.message);
      }
      setFailure(Object.keys(error.fieldErrors).length ? 'Please correct the highlighted fields.' : error.message);
    }
  }

  return (
    <div className="lp lp-signup">
      <header className="lp-nav">
        <div className="lp-wrap lp-nav-inner">
          <Link className="lp-logo" to="/"><span className="lp-logo-mark"><Factory size={18} /></span>{theme.appName}</Link>
          <span className="grow" />
          <div className="lp-nav-actions">
            <LanguageSwitcher className="lp-lang" />
            <Link className="lp-btn lp-btn-ghost" to="/login">{t('Sign in')}</Link>
          </div>
        </div>
      </header>

      <main className="lp-wrap lp-signup-main">
        {loadError ? <Banner tone="danger">{loadError}</Banner>
          : !options ? <Spinner />
            : receipt ? <Done receipt={receipt} />
              : !options.enabled ? (
                <div className="lp-signup-note">
                  <span className="lp-icon"><Lock size={22} /></span>
                  <h1>{t('Sign-up is not open right now')}</h1>
                  <p>{t('Write to us and we will set up your account with you.')}</p>
                  <div className="lp-cta-row">
                    <a className="lp-btn lp-btn-primary lp-btn-lg" href={`mailto:${CONTACT_EMAIL}`}><Mail size={18} />{' '}{t('Contact us')}</a>
                    <Link className="lp-btn lp-btn-outline lp-btn-lg" to="/">{t('Back to the home page')}</Link>
                  </div>
                </div>
              ) : (
                <>
                  <div className="lp-head">
                    <h1>{t('Create your account')}</h1>
                    <p>{t('Pick a plan, tell us about your business and start using {0} today.', { 0: theme.appName })}</p>
                  </div>
                  {options.freeMonths > 0 && (
                    <div className="lp-free-note"><Gift size={18} />{' '}{t('Free membership: your first {0} cost nothing. You pay only if you stay.', { 0: months(options.freeMonths) })}</div>
                  )}
                  <h2 className="lp-signup-step">{t('1. Choose your plan')}</h2>
                  <div className="lp-plans">
                    {options.plans.map((p) => <PlanCard key={p.code} plan={p} freeMonths={options.freeMonths} selected={p.code === plan} onPick={() => pickPlan(p.code)} />)}
                  </div>
                  <h2 className="lp-signup-step">{t('2. Your business')}</h2>
                  <div className="lp-signup-form">
                    {failure && <Banner tone="danger">{t(failure)}</Banner>}
                    <FormFields fields={fields} values={values} errors={errors} onChange={(name, value) => setValues((prev) => ({ ...prev, [name]: value }))} />
                    <div className="lp-coupon">
                      <label className="field-label" htmlFor="coupon-code"><TicketPercent size={15} />{' '}{t('Have a coupon code?')}</label>
                      <div className="lp-coupon-row">
                        <input id="coupon-code" className={`input ${couponError ? 'invalid' : ''}`} value={coupon} maxLength={20} disabled={Boolean(quote)}
                          placeholder={t('Coupon code')} onChange={(e) => setCoupon(e.target.value.toUpperCase())}
                          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); applyCoupon(); } }} />
                        {quote
                          ? <Button onClick={removeCoupon}>{t('Remove')}</Button>
                          : <Button busy={checking} disabled={!coupon.trim()} onClick={() => applyCoupon()}>{t('Apply')}</Button>}
                      </div>
                      {couponError && <span className="field-error" role="alert">{couponError}</span>}
                      {quote && chosen && (
                        <span className="lp-coupon-ok" role="status"><Check size={15} />{' '}
                          {t('{0}% off: {1} a month instead of {2}', { 0: quote.percentOff, 1: rupees(quote.monthlyPrice), 2: rupees(chosen.monthlyPrice) })}</span>
                      )}
                    </div>
                    <div className="lp-signup-foot">
                      <span className="lp-muted">{t('Already have an account?')}{' '}<Link to="/login">{t('Sign in')}</Link></span>
                      <Button variant="primary" style={{ height: 44 }} onClick={toPayment}>{options.freeMonths > 0 ? t('Continue') : t('Continue to payment')}{' '}<ArrowRight size={16} /></Button>
                    </div>
                  </div>
                  <p className="lp-signup-custom">
                    {t('Want it deployed in your own environment with your own database, or under your own brand?')}{' '}<a href={enterpriseDemoLink(theme.appName)}>{t('Book an Enterprise demo')}</a>
                  </p>
                </>
              )}
      </main>

      {paying && chosen && options && <PaymentModal plan={chosen} quote={quote} freeMonths={options.freeMonths} onPay={pay} onClose={() => setPaying(false)} />}
    </div>
  );
}

/**
 * A demonstration of the payment step: it shows what the customer would pay and confirms at once.
 * No gateway is called and no card or bank detail is asked for; a real gateway replaces this later.
 * During free membership nothing is due, so it only confirms what starts when.
 */
function PaymentModal({ plan, quote, freeMonths, onPay, onClose }: { plan: PlanOption; quote: Quote | null; freeMonths: number; onPay: () => Promise<void>; onClose: () => void }) {
  const [method, setMethod] = useState(PAYMENT_METHODS[0]);
  const [busy, setBusy] = useState(false);
  const free = freeMonths > 0;
  const monthly = quote ? quote.monthlyPrice : plan.monthlyPrice;
  const gst = Math.round(monthly * GST_RATE) / 100;
  const monthlyWithGst = Math.round((monthly + gst) * 100) / 100;
  const total = free ? 0 : monthlyWithGst;

  async function pay() {
    setBusy(true);
    // A short pause, as a gateway would take, so the step looks like the real thing.
    await new Promise((resolve) => window.setTimeout(resolve, total > 0 ? 900 : 300));
    await onPay();
  }

  const action = busy ? t('Processing payment...') : free ? t('Start free membership') : total > 0 ? t('Pay {0}', { 0: rupees(total) }) : t('Complete sign-up');
  return (
    <Modal title={free ? t('Free membership') : t('Payment')} onClose={busy ? () => {} : onClose}
      footer={<><Button onClick={onClose} disabled={busy}>{t('Cancel')}</Button><Button variant="primary" busy={busy} onClick={pay}>{action}</Button></>}>
      <div className="stack">
        <Banner tone="info">{free
          ? t('Your first {0} are free. Nothing is charged today, and no card is needed.', { 0: months(freeMonths) })
          : t('This is a demo payment. No card is needed and no money is taken.')}</Banner>
        <dl className="lp-bill">
          <div><dt>{t('Plan')}</dt><dd>{t(plan.name)}</dd></div>
          <div><dt>{free ? t('Monthly price') : t('First month')}</dt><dd>{rupees(plan.monthlyPrice)}</dd></div>
          {quote && <div className="off"><dt>{t('Coupon {0} ({1}% off)', { 0: quote.code, 1: quote.percentOff })}</dt><dd>-{rupees(quote.discount)}</dd></div>}
          <div><dt>{t('GST ({0}%)', { 0: GST_RATE })}</dt><dd>{rupees(gst)}</dd></div>
          {free && <div><dt>{t('After the free membership')}</dt><dd>{t('{0} a month', { 0: rupees(monthlyWithGst) })}</dd></div>}
          {free && <div className="off"><dt>{t('Free membership')}</dt><dd>{months(freeMonths)}</dd></div>}
          <div className="total"><dt>{t('Total today')}</dt><dd>{rupees(total)}</dd></div>
        </dl>
        {total > 0 && (
          <div>
            <div className="field-label" style={{ marginBottom: 8 }}>{t('Pay with')}</div>
            <div className="row wrap" style={{ gap: 18 }}>
              {PAYMENT_METHODS.map((m) => (
                <label key={m} className="check"><input type="radio" name="payment-method" checked={method === m} disabled={busy} onChange={() => setMethod(m)} />{t(m)}</label>
              ))}
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}

function Done({ receipt }: { receipt: Receipt }) {
  const ready = receipt.status === 'APPROVED';
  return (
    <div className="lp-signup-note" role="status">
      <span className={`lp-icon ${ready ? 'ok' : ''}`}>{ready ? <CheckCircle2 size={24} /> : <Clock size={24} />}</span>
      <span className="lp-kicker">{receipt.freeMonths > 0 ? t('Free membership started') : receipt.amount > 0 ? t('Payment successful') : t('Sign-up complete')}</span>
      <h1>{ready ? t('Your workspace is ready') : t('We have your sign-up')}</h1>
      <p>{ready
        ? t('Sign in with {0} and the password you chose.', { 0: receipt.email })
        : t('Your account will be ready as soon as our team approves it. We will write to {0}.', { 0: receipt.email })}</p>
      <dl className="lp-bill">
        <div><dt>{t('Plan')}</dt><dd>{t(humanize(receipt.plan))}</dd></div>
        {receipt.freeMonths > 0 && <div><dt>{t('Free membership')}</dt><dd>{months(receipt.freeMonths)}</dd></div>}
        {receipt.couponCode && <div><dt>{t('Coupon')}</dt><dd>{receipt.couponCode} (-{rupees(receipt.discount)})</dd></div>}
        <div><dt>{receipt.amount > 0 ? t('Payment reference') : t('Reference')}</dt><dd>{receipt.reference}</dd></div>
      </dl>
      <div className="lp-cta-row">
        {ready
          ? <Link className="lp-btn lp-btn-primary lp-btn-lg" to="/login">{t('Sign in')}{' '}<ArrowRight size={18} /></Link>
          : <Link className="lp-btn lp-btn-outline lp-btn-lg" to="/">{t('Back to the home page')}</Link>}
      </div>
    </div>
  );
}
