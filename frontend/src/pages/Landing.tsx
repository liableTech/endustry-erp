import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight, BarChart3, Boxes, Building2, CalendarCheck, Check, ClipboardCheck, Factory, FileSpreadsheet, FlaskConical, Languages, LifeBuoy,
  Mail, Palette, ReceiptIndianRupee, ShieldCheck, ShoppingCart, Smartphone, Sparkles, Sprout, Store, Truck, Users, Wrench,
  type LucideIcon,
} from 'lucide-react';
import { useTheme } from '../core/theme';
import './landing.css';
import { LanguageSwitcher, t } from '../core/i18n';
import { api } from '../core/api';
import { months, PlanCard, type SignupOptions } from './Signup';

/** Where the Enterprise "Book a demo" and "Contact us" send their email. Change this to your sales inbox. */
export const CONTACT_EMAIL = 'sales@endustry.in';

/**
 * The email a visitor sends to book an Enterprise demo: the product deployed in their own
 * environment with their own database, or under their own brand. Everyone else signs up.
 */
export function enterpriseDemoLink(appName: string): string {
  const body = 'Hi, I would like a demo of the Enterprise option.\n\nCompany:\nOur own environment and database (yes/no):\nWhite-label under our brand (yes/no):\nNumber of users:\nPhone:\nBest time to call:';
  return `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(`${appName} Enterprise demo`)}&body=${encodeURIComponent(body)}`;
}

interface IndustryShowcase {
  key: string;
  name: string;
  icon: LucideIcon;
  line: string;
  /** The words the label service gives this industry out of the box. */
  words: { customers: string; items: string; orders: string; place: string };
  modules: string[];
  tiles: { label: string; value: string; trend: string }[];
  bars: number[];
}

const SHOWCASE: IndustryShowcase[] = [
  {
    key: 'manufacturing', name: 'Manufacturing', icon: Factory,
    line: 'BOMs, work orders, job cards and actual job costing, with stock that moves as the shop floor does.',
    words: { customers: 'Customers', items: 'Items', orders: 'Work orders', place: 'Machines' },
    modules: ['Sales', 'Purchase', 'Inventory', 'Production', 'Quality', 'Reports'],
    tiles: [{ label: 'Sales this month', value: '₹18.4 L', trend: '+12%' }, { label: 'Work orders running', value: '23', trend: '6 due today' }, { label: 'On-time delivery', value: '94%', trend: '+3 pts' }],
    bars: [42, 55, 48, 66, 72, 61, 80, 77, 88, 84, 92, 97],
  },
  {
    key: 'agriculture', name: 'Agriculture', icon: Sprout,
    line: 'Seeds, fertilisers and produce by godown and lot, with processing batches, moisture and grade checks, and GST on every sale.',
    words: { customers: 'Customers', items: 'Items', orders: 'Processing batches', place: 'Godowns' },
    modules: ['Sales', 'Purchase', 'Inventory', 'Production', 'Quality', 'Reports'],
    tiles: [{ label: 'Sales this month', value: '₹26.3 L', trend: '+9%' }, { label: 'Stock value', value: '₹84 L', trend: '5 godowns' }, { label: 'Batches in QC', value: '4', trend: 'grading pending' }],
    bars: [38, 34, 46, 58, 71, 86, 92, 74, 60, 52, 66, 81],
  },
  {
    key: 'chemicals', name: 'Chemicals', icon: FlaskConical,
    line: 'Formulations, production batches and batch-wise stock in drums and bags, with purity and pH checks before anything is released.',
    words: { customers: 'Customers', items: 'Products', orders: 'Production batches', place: 'Formulations' },
    modules: ['Sales', 'Purchase', 'Inventory', 'Production', 'Quality', 'Reports'],
    tiles: [{ label: 'Sales this month', value: '₹31.6 L', trend: '+11%' }, { label: 'Production batches running', value: '12', trend: '3 due today' }, { label: 'Batches in QC', value: '5', trend: 'awaiting release' }],
    bars: [50, 54, 51, 62, 68, 65, 73, 78, 75, 84, 89, 94],
  },
  {
    key: 'trading', name: 'Trading', icon: Truck,
    line: 'Buy, stock and sell across warehouses, with supplier terms, batch tracking and GST on every bill.',
    words: { customers: 'Customers', items: 'Products', orders: 'Purchase orders', place: 'Suppliers' },
    modules: ['Sales', 'Purchase', 'Inventory', 'Reports'],
    tiles: [{ label: 'Sales this month', value: '₹42.7 L', trend: '+8%' }, { label: 'Stock value', value: '₹1.2 Cr', trend: '312 products' }, { label: 'Receivables due', value: '₹6.1 L', trend: '14 parties' }],
    bars: [60, 58, 64, 70, 66, 74, 79, 73, 82, 86, 83, 90],
  },
  {
    key: 'retail', name: 'Retail', icon: Store,
    line: 'Products, stores and bills that keep up with the counter, and reorder levels that keep shelves full.',
    words: { customers: 'Customers', items: 'Products', orders: 'Bills', place: 'Stores' },
    modules: ['Sales', 'Purchase', 'Inventory', 'Reports'],
    tiles: [{ label: 'Bills today', value: '186', trend: '+21' }, { label: 'Low on stock', value: '9', trend: 'reorder now' }, { label: 'Average bill', value: '₹1,240', trend: '+6%' }],
    bars: [30, 44, 39, 52, 61, 58, 70, 64, 76, 71, 85, 80],
  },
  {
    key: 'services', name: 'Services', icon: Users,
    line: 'Proposals, clients and invoices for consulting, AMC and repair work, without inventory getting in the way.',
    words: { customers: 'Clients', items: 'Services', orders: 'Proposals', place: 'Equipment' },
    modules: ['Sales', 'Purchase', 'Reports'],
    tiles: [{ label: 'Billed this month', value: '₹9.8 L', trend: '+15%' }, { label: 'Proposals open', value: '17', trend: '₹22 L in play' }, { label: 'AMC renewals', value: '11', trend: 'due in 30 days' }],
    bars: [35, 40, 46, 43, 55, 60, 58, 66, 63, 74, 79, 86],
  },
];

const FEATURES: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: ReceiptIndianRupee, title: 'Sales and GST billing', text: 'Quotations to tax invoices, CGST, SGST or IGST worked out by place of supply, credit limits and e-way bill flags.' },
  { icon: ShoppingCart, title: 'Purchase with control', text: 'Approval limits, three-way match and MSME 45-day payment tracking so nothing slips past 43B(h).' },
  { icon: Boxes, title: 'Inventory you can trust', text: 'Stock by warehouse, batch and status, FIFO issue, weighted average cost and a full ledger behind every number.' },
  { icon: Wrench, title: 'Production and job work', text: 'Versioned BOMs, work orders, shop-floor job cards and actual cost against standard, for every job.' },
  { icon: ClipboardCheck, title: 'Quality built in', text: 'Incoming inspection holds stock in QC until it is accepted, with reasons for every rejection.' },
  { icon: BarChart3, title: 'Reports owners read', text: 'A live dashboard and registers, GST summary, ageing and cost variance, all exportable in a click.' },
];

const PLATFORM: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: Languages, title: 'Speaks your language', text: 'Call them Clients, Products or Stores. Every screen uses your words.' },
  { icon: ShieldCheck, title: 'Roles and audit trail', text: 'View or full access per role and module, and a record of every change.' },
  { icon: Palette, title: 'Your brand', text: 'Your colours, name and theme, with white-label for partners.' },
  { icon: Smartphone, title: 'Works on any screen', text: 'From the owner\'s desk to the supervisor\'s phone on the shop floor.' },
];

const STEPS = [
  { title: 'Pick your industry', text: 'Your modules, units, categories and words are set up for you in minutes.' },
  { title: 'Bring your data', text: 'Import items, parties and opening stock. We help you move from spreadsheets or Tally.' },
  { title: 'Go live in 30 days', text: 'Train your team on real transactions and run the business from day one.' },
];

/** The public front page. Signed-in users never see it: they go straight to their dashboard. */
export function Landing() {
  const { theme } = useTheme();
  const [active, setActive] = useState(0);
  const [paused, setPaused] = useState(false);
  const industry = SHOWCASE[active];
  // Sign-up and the plans show only while the product admin has self sign-up switched on.
  const [signup, setSignup] = useState<SignupOptions | null>(null);
  useEffect(() => {
    api.get<SignupOptions>('/public/signup').then(setSignup).catch(() => setSignup(null));
  }, []);
  const canSignUp = Boolean(signup?.enabled);
  const freeMonths = canSignUp ? signup!.freeMonths : 0;

  // The preview cycles through industries until the visitor picks one.
  useEffect(() => {
    if (paused) return;
    const timer = window.setInterval(() => setActive((i) => (i + 1) % SHOWCASE.length), 4200);
    return () => window.clearInterval(timer);
  }, [paused]);

  const pick = (i: number) => {
    setActive(i);
    setPaused(true);
  };

  return (
    <div className="lp">
      <header className="lp-nav">
        <div className="lp-wrap lp-nav-inner">
          <a className="lp-logo" href="#top"><span className="lp-logo-mark"><Factory size={18} /></span>{theme.appName}</a>
          <nav className="lp-links" aria-label={t('Sections')}>
            <a href="#features">{t('Features')}</a>
            <a href="#industries">{t('Industries')}</a>
            <a href="#how">{t('How it works')}</a>
            <a href="#pricing">{canSignUp ? t('Pricing') : t('Enterprise')}</a>
            <a href="#contact">{t('Contact')}</a>
          </nav>
          <div className="lp-nav-actions">
            <LanguageSwitcher className="lp-lang" />
            <Link className="lp-btn lp-btn-ghost" to="/login">{t('Sign in')}</Link>
            {canSignUp && <Link className="lp-btn lp-btn-primary lp-hide-sm" to="/signup">{t('Sign up')}</Link>}
          </div>
        </div>
      </header>

      <section className="lp-hero" id="top">
        <div className="lp-glow" aria-hidden />
        <div className="lp-wrap lp-hero-grid">
          <div className="lp-hero-copy">
            <span className="lp-eyebrow"><Sparkles size={14} />{' '}{t('Cloud ERP for growing Indian businesses')}</span>
            <h1>{t('One ERP that fits')}{' '}<span className="lp-gradient">{t(industry.name)}</span>{' '}{t('and every business like yours.')}</h1>
            <p className="lp-lead">{t('Sales, purchase, stock, production and GST in one place. Choose your industry and {0} sets itself up with the modules, lists and words your team already uses.', { 0: theme.appName })}</p>
            <div className="lp-cta-row">
              {canSignUp
                ? <Link className="lp-btn lp-btn-primary lp-btn-lg" to="/signup">{freeMonths > 0 ? t('Start free for {0}', { 0: months(freeMonths) }) : t('Sign up')}{' '}<ArrowRight size={18} /></Link>
                : <Link className="lp-btn lp-btn-primary lp-btn-lg" to="/login">{t('Sign in')}{' '}<ArrowRight size={18} /></Link>}
              {canSignUp
                ? <Link className="lp-btn lp-btn-outline lp-btn-lg" to="/login">{t('Sign in')}</Link>
                : <a className="lp-btn lp-btn-outline lp-btn-lg" href="#pricing">{t('Enterprise')}</a>}
            </div>
            <ul className="lp-ticks">
              <li><Check size={16} />{' '}{t('GST and MSME rules built in')}</li>
              <li><Check size={16} />{' '}{t('Live in 30 days')}</li>
              <li><Check size={16} />{' '}{t('No server to run')}</li>
            </ul>
          </div>

          <div className="lp-preview" onMouseEnter={() => setPaused(true)}>
            <div className="lp-switch" role="tablist" aria-label={t('See it for your industry')}>
              {SHOWCASE.map((s, i) => (
                <button key={s.key} role="tab" aria-selected={i === active} className={i === active ? 'on' : ''} onClick={() => pick(i)}>
                  <s.icon size={15} /> {t(s.name)}
                </button>
              ))}
            </div>
            <div className="lp-window" key={industry.key}>
              <div className="lp-window-bar"><i /><i /><i /><span>{t('{0} · Dashboard', { 0: theme.appName })}</span></div>
              <div className="lp-window-body">
                <aside className="lp-mini-nav">
                  {[industry.words.customers, industry.words.items, industry.words.orders, industry.words.place, 'Reports'].map((w, i) => (
                    <span key={w} className={i === 0 ? 'on' : ''}>{t(w)}</span>
                  ))}
                </aside>
                <div className="lp-mini-main">
                  <div className="lp-tiles">
                    {industry.tiles.map((tpl) => (
                      <div className="lp-tile" key={tpl.label}><small>{t(tpl.label)}</small><strong>{tpl.value}</strong><em>{t(tpl.trend)}</em></div>
                    ))}
                  </div>
                  <div className="lp-chart">
                    <small>{t('Last 12 months')}</small>
                    <div className="lp-bars">{industry.bars.map((b, i) => <span key={i} style={{ height: `${b}%`, animationDelay: `${i * 35}ms` }} />)}</div>
                  </div>
                  <div className="lp-mods">{industry.modules.map((m) => <span key={m}>{t(m)}</span>)}</div>
                </div>
              </div>
            </div>
            <p className="lp-preview-note">{t(industry.line)}</p>
          </div>
        </div>
      </section>

      <section className="lp-strip">
        <div className="lp-wrap lp-strip-inner">
          <span><ReceiptIndianRupee size={18} />{' '}{t('GST invoices and e-way bill checks')}</span>
          <span><Building2 size={18} />{' '}{t('MSME 45-day payment tracking')}</span>
          <span><FileSpreadsheet size={18} />{' '}{t('Export every report to Excel')}</span>
          <span><LifeBuoy size={18} />{' '}{t('Help from people who know your trade')}</span>
        </div>
      </section>

      <section className="lp-section" id="features">
        <div className="lp-wrap">
          <div className="lp-head">
            <span className="lp-kicker">{t('Everything in one flow')}</span>
            <h2>{t('From enquiry to cash, without the spreadsheets')}</h2>
            <p>{t('Every module shares the same parties, items and stock, so a sale, a purchase or a job on the floor updates everything it touches.')}</p>
          </div>
          <div className="lp-grid-3">
            {FEATURES.map((f) => (
              <article className="lp-card" key={f.title}>
                <span className="lp-icon"><f.icon size={20} /></span>
                <h3>{t(f.title)}</h3>
                <p>{t(f.text)}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="lp-section lp-alt" id="industries">
        <div className="lp-wrap">
          <div className="lp-head">
            <span className="lp-kicker">{t('Made for your industry')}</span>
            <h2>{t('Switch on only what your business needs')}</h2>
            <p>{t('An industry template sets up modules, units, categories and words on day one. Change any of it later as you grow.')}</p>
          </div>
          <div className="lp-grid-3">
            {SHOWCASE.map((s, i) => (
              <button className={`lp-industry ${i === active ? 'on' : ''}`} key={s.key} onClick={() => { pick(i); document.getElementById('top')?.scrollIntoView({ behavior: 'smooth' }); }}>
                <span className="lp-icon"><s.icon size={22} /></span>
                <h3>{t(s.name)}</h3>
                <p>{t(s.line)}</p>
                <div className="lp-tags">{s.modules.map((m) => <span key={m}>{t(m)}</span>)}</div>
              </button>
            ))}
          </div>
          <div className="lp-grid-4 lp-platform">
            {PLATFORM.map((f) => (
              <div className="lp-mini-feature" key={f.title}>
                <f.icon size={20} />
                <div><strong>{t(f.title)}</strong><p>{t(f.text)}</p></div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="lp-section" id="how">
        <div className="lp-wrap">
          <div className="lp-head">
            <span className="lp-kicker">{t('How it works')}</span>
            <h2>{t('Up and running in three steps')}</h2>
          </div>
          <ol className="lp-steps">
            {STEPS.map((s, i) => (
              <li key={s.title}><span className="lp-step-no">{i + 1}</span><h3>{t(s.title)}</h3><p>{t(s.text)}</p></li>
            ))}
          </ol>
          <div className="lp-stats">
            <div><strong>6</strong><span>{t('modules, one database')}</span></div>
            <div><strong>14</strong><span>{t('industry templates')}</span></div>
            <div><strong>30</strong><span>{t('days to go live')}</span></div>
            <div><strong>100%</strong><span>{t('GST-ready documents')}</span></div>
          </div>
        </div>
      </section>

      {/* The Enterprise option is always offered; the monthly plans only while sign-up is open. */}
      <section className="lp-section lp-alt" id="pricing">
        <div className="lp-wrap">
          {canSignUp ? (
            <div className="lp-head">
              <span className="lp-kicker">{t('Pricing')}</span>
              <h2>{freeMonths > 0 ? t('Start free for {0}, then pay monthly', { 0: months(freeMonths) }) : t('Start today on a monthly plan')}</h2>
              <p>{t('Create your own account in a few minutes. Change plan or stop whenever you like.')}</p>
            </div>
          ) : (
            <div className="lp-head">
              <span className="lp-kicker">{t('Enterprise')}</span>
              <h2>{t('Run {0} in your own environment', { 0: theme.appName })}</h2>
              <p>{t('Your own installation, your own database and your own brand, set up with our team.')}</p>
            </div>
          )}
          <div className={canSignUp ? 'lp-plans lp-plans-4' : 'lp-plans lp-plans-1'}>
            {canSignUp && signup!.plans.map((p) => (
              <PlanCard key={p.code} plan={p} freeMonths={freeMonths} action={<Link className="lp-btn lp-btn-primary" to={`/signup?plan=${p.code}`}>{freeMonths > 0 ? t('Start free') : t('Sign up')}</Link>} />
            ))}
              <div className="lp-plan lp-plan-custom">
                <h3>{t('Enterprise')}</h3>
                <div className="lp-price"><strong>{t('Let us talk')}</strong><span>{t('Your own installation of {0}', { 0: theme.appName })}</span></div>
                <ul className="lp-plan-list">
                  <li><Check size={15} />{' '}{t('Deployed in your own environment')}</li>
                  <li><Check size={15} />{' '}{t('Your own database, kept separate')}</li>
                  <li><Check size={15} />{' '}{t('White-label: your name, colours and sign-in page')}</li>
                  <li><Check size={15} />{' '}{t('Changes made for the way you work')}</li>
                </ul>
                <a className="lp-btn lp-btn-outline" href={enterpriseDemoLink(theme.appName)}><CalendarCheck size={16} />{' '}{t('Book a demo')}</a>
              </div>
          </div>
        </div>
      </section>

      <section className="lp-cta" id="contact">
        <div className="lp-wrap lp-cta-inner">
          <div>
            <h2>{t('See {0} with your own data', { 0: theme.appName })}</h2>
            <p>{t('Tell us what you make, trade or sell. We will show you the screens your team would use, set up for your industry.')}</p>
          </div>
          <div className="lp-cta-row">
            {canSignUp && <Link className="lp-btn lp-btn-light lp-btn-lg" to="/signup">{t('Sign up')}{' '}<ArrowRight size={18} /></Link>}
            <a className="lp-btn lp-btn-glass lp-btn-lg" href={`mailto:${CONTACT_EMAIL}`}><Mail size={18} />{' '}{t('Contact us')}</a>
          </div>
        </div>
      </section>

      <footer className="lp-footer">
        <div className="lp-wrap lp-footer-inner">
          <a className="lp-logo" href="#top"><span className="lp-logo-mark"><Factory size={16} /></span>{theme.appName}</a>
          <span className="lp-muted">{t('ERP for manufacturing, trading, retail and services.')}</span>
          <span className="grow" />
          <Link to="/login">{t('Sign in')}</Link>
          <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
          <span className="lp-muted">© {new Date().getFullYear()} {theme.appName}</span>
        </div>
      </footer>
    </div>
  );
}
