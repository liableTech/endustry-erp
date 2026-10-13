import { useEffect, useState } from 'react';
import { RotateCcw, Save } from 'lucide-react';
import { FormFields } from '../../components/FormFields';
import { Badge, Banner, Button, Card, PageHeader, Progress, Spinner, Tabs, useFeedback } from '../../components/ui';
import { api, ApiError } from '../../core/api';
import { useAuth } from '../../core/auth';
import { useFetch } from '../../core/hooks';
import { DEFAULT_THEME, THEME_PRESETS, useTheme, type Theme } from '../../core/theme';
import { PATTERNS, validate, type FieldDef } from '../../core/validation';
import { t } from '../../core/i18n';

const hex = { pattern: PATTERNS.hex, patternMessage: 'must be a hex colour like #4F46E5', required: true } as const;

const FIELDS: FieldDef[] = [
  { name: 'appName', label: 'Application name', type: 'text', required: true, maxLength: 40, section: 'Identity', span: 2, help: 'Shown in the sidebar, the sign-in page and the browser tab.' },
  { name: 'primaryColor', label: 'Primary colour', type: 'color', section: 'Colours', ...hex, help: 'Buttons, links and the active menu item.' },
  { name: 'accentColor', label: 'Accent colour', type: 'color', ...hex, help: 'Charts and secondary highlights.' },
  { name: 'successColor', label: 'Success', type: 'color', ...hex },
  { name: 'warningColor', label: 'Warning', type: 'color', ...hex },
  { name: 'dangerColor', label: 'Danger', type: 'color', ...hex },
  { name: 'mode', label: 'Appearance', type: 'select', required: true, section: 'Layout', options: [{ value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }, { value: 'system', label: 'Follow the device' }] },
  { name: 'sidebarStyle', label: 'Sidebar', type: 'select', required: true, options: [{ value: 'dark', label: 'Dark' }, { value: 'light', label: 'Light' }, { value: 'brand', label: 'Primary colour' }] },
  { name: 'fontFamily', label: 'Font', type: 'select', required: true, options: ['Inter', 'Poppins', 'Roboto', 'System'].map((f) => ({ value: f, label: f })) },
  { name: 'density', label: 'Density', type: 'select', required: true, options: [{ value: 'comfortable', label: 'Comfortable' }, { value: 'compact', label: 'Compact (more rows per screen)' }] },
  { name: 'radius', label: 'Corner radius (px)', type: 'number', required: true, min: 0, max: 24 },
];

/** Colours, font, density and layout for the whole application, previewed live as you edit. */
export function ThemeSettings({ endpoint = '/settings/theme' }: { endpoint?: string }) {
  const feedback = useFeedback();
  const { preview, reload } = useTheme();
  const { client } = useAuth();
  // A client without white-label uses the product's branding and cannot change it.
  const locked = Boolean(client && !client.whiteLabel);
  const saved = useFetch<Theme & { id?: number }>(endpoint);
  const [values, setValues] = useState<Record<string, any> | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [tab, setTab] = useState('one');

  useEffect(() => {
    if (saved.data) setValues({ ...DEFAULT_THEME, ...saved.data });
  }, [saved.data]);

  // Leaving the page without saving puts the saved look back.
  useEffect(() => () => { reload(); }, [reload]);

  function apply(next: Record<string, any>) {
    setValues(next);
    const found = validate(FIELDS, next);
    setErrors(found);
    // Only a fully valid theme is pushed to the screen, so a half-typed colour never breaks the page.
    if (Object.keys(found).length === 0) preview({ ...(next as Theme), radius: Number(next.radius) });
  }

  async function save() {
    if (!values) return;
    const found = validate(FIELDS, values);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      await api.put(endpoint, { ...values, radius: Number(values.radius) });
      await reload();
      saved.reload();
      feedback.success(endpoint === '/settings/theme' ? 'Theme saved for everyone' : 'Product theme saved for every client without their own branding');
    } catch (e) {
      const error = e as ApiError;
      setErrors(error.fieldErrors);
      setFailure(error.message);
    } finally {
      setSaving(false);
    }
  }

  if (!values) return saved.error ? <div className="page"><Banner tone="danger">{saved.error.message}</Banner></div> : <Spinner />;

  return (
    <div className="page">
      <PageHeader title={endpoint === '/settings/theme' ? 'Theme' : 'Product theme'}
        subtitle={endpoint === '/settings/theme' ? 'Changes preview instantly here and apply to every user once saved' : 'The default look: the sign-in page and every client that does not have its own branding'}
        actions={!locked && (
        <>
          <Button icon={RotateCcw} onClick={() => apply({ ...values, ...DEFAULT_THEME, appName: values.appName })}>{t('Reset to default')}</Button>
          <Button variant="primary" icon={Save} busy={saving} onClick={save}>{t('Save theme')}</Button>
        </>
      )} />
      {locked && <Banner tone="info">{t('Your account uses the standard look. Own branding (white-label) can be switched on by your provider; until then changes here are a preview only and cannot be saved.')}</Banner>}
      {failure && <Banner tone="danger">{failure}</Banner>}
      <div className="grid two">
        <Card title={t('Settings')}>
          <div className="stack">
            <div>
              <div className="field-label" style={{ marginBottom: 8 }}>{t('Presets')}</div>
              <div className="row wrap">
                {THEME_PRESETS.map((p) => (
                  <button key={p.name} type="button" className="preset" onClick={() => apply({ ...values, ...p.theme })}>
                    <span className="swatch" style={{ background: p.theme.primaryColor }} />
                    <span className="swatch" style={{ background: p.theme.accentColor, marginLeft: -14 }} />
                    {p.name}
                  </button>
                ))}
              </div>
            </div>
            <FormFields fields={FIELDS} values={values} errors={errors} onChange={(name, value) => apply({ ...values, [name]: value })} />
          </div>
        </Card>
        <Card title={t('Preview')}>
          <div className="stack">
            <div className="row wrap">
              <Button variant="primary">{t('Primary action')}</Button>
              <Button>{t('Secondary')}</Button>
              <Button variant="danger">{t('Delete')}</Button>
            </div>
            <div className="row wrap">
              <Badge tone="success">{t('Paid')}</Badge><Badge tone="info">{t('Confirmed')}</Badge><Badge tone="warning">{t('Partly paid')}</Badge><Badge tone="danger">{t('Overdue')}</Badge><Badge>{t('Draft')}</Badge>
            </div>
            <Tabs value={tab} onChange={setTab} options={[{ value: 'one', label: 'All' }, { value: 'two', label: 'Open' }, { value: 'three', label: 'Closed' }]} />
            <input className="input" defaultValue="Text field" aria-label={t('Sample text field')} />
            <div className="row"><Progress value={64} max={100} /><span className="muted">64%</span></div>
            <Banner tone="info">{t('This is how a notice looks in the chosen colours.')}</Banner>
            <p className="muted">{t('The sidebar, top bar and every screen behind this card are already showing the theme you are editing.')}</p>
          </div>
        </Card>
      </div>
    </div>
  );
}
