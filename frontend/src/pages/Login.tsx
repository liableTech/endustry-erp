import { useState, type FormEvent } from 'react';
import { Factory, PackageCheck, ReceiptIndianRupee, Smartphone } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Field } from '../components/FormFields';
import { Banner, Button } from '../components/ui';
import { ApiError } from '../core/api';
import { useAuth } from '../core/auth';
import { useTheme } from '../core/theme';
import { PATTERNS } from '../core/validation';
import { LanguageSwitcher, t } from '../core/i18n';

export function Login() {
  const { signIn } = useAuth();
  const { theme, companyName } = useTheme();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const found: typeof errors = {};
    if (!email.trim()) found.email = 'is required';
    else if (!PATTERNS.email.test(email.trim())) found.email = 'is not a valid email address';
    if (!password) found.password = 'is required';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;
    setBusy(true);
    try {
      await signIn(email.trim(), password);
    } catch (error) {
      setFailure((error as ApiError).message);
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <div className="login-art">
        <div className="row" style={{ gap: 12, fontWeight: 700, fontSize: 18 }}>
          <Factory size={26} /> {theme.appName}
        </div>
        <div>
          <h1>{t('Run the whole factory from one place.')}</h1>
          <div className="login-points">
            <div className="login-point"><PackageCheck size={20} /><span>{t('Orders, stock, production and quality in one flow, from enquiry to cash.')}</span></div>
            <div className="login-point"><ReceiptIndianRupee size={20} /><span>{t('GST worked out on every document, with MSME 45-day payment tracking.')}</span></div>
            <div className="login-point"><Smartphone size={20} /><span>{t('Job cards your supervisors can run from the shop floor.')}</span></div>
          </div>
        </div>
        <div style={{ opacity: 0.8, fontSize: 13 }}>{companyName}</div>
      </div>
      <div className="login-form">
        <form className="login-box" onSubmit={submit} noValidate>
          <div style={{ alignSelf: 'flex-end' }}><LanguageSwitcher /></div>
          <div>
            <h2 style={{ fontSize: 24, fontWeight: 700 }}>{t('Sign in')}</h2>
            <p className="muted" style={{ marginTop: 4 }}>{t('Use the email and password given by your administrator.')}</p>
          </div>
          {failure && <Banner tone="danger">{failure}</Banner>}
          <Field label={t('Email')} required error={errors.email}>
            <input className={`input ${errors.email ? 'invalid' : ''}`} type="email" autoComplete="username" autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Field label={t('Password')} required error={errors.password}>
            <input className={`input ${errors.password ? 'invalid' : ''}`} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <Button variant="primary" type="submit" busy={busy} style={{ height: 42 }}>{t('Sign in')}</Button>
          {!new URLSearchParams(window.location.search).get('client') && <Link to="/" className="muted" style={{ fontSize: 13, textAlign: 'center' }}>{t('Back to the home page')}</Link>}
        </form>
      </div>
    </div>
  );
}
