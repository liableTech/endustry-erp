import { useState } from 'react';
import { Pencil, RotateCcw } from 'lucide-react';
import { Field } from '../../components/FormFields';
import { Badge, Banner, Button, Card, DataTable, Modal, PageHeader, Spinner, useFeedback } from '../../components/ui';
import { api, ApiError } from '../../core/api';
import { useAuth } from '../../core/auth';
import { useFetch } from '../../core/hooks';
import { inSentence, type Term } from '../../core/labels';
import { t } from '../../core/i18n';

interface LabelRow {
  key: string;
  description: string;
  standard: Term;
  current: Term;
  renamed: boolean;
}

/**
 * Settings > Labels: the client's own words for customers, items, machines and the rest. The
 * industry template sets where each starts; a rename shows in every menu and screen at once.
 */
export function LabelSettings() {
  const feedback = useFeedback();
  const { can, refresh } = useAuth();
  const rows = useFetch<LabelRow[]>('/settings/labels');
  const [editing, setEditing] = useState<LabelRow | null>(null);
  const [singular, setSingular] = useState('');
  const [plural, setPlural] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const editable = can('settings', 'FULL');

  function edit(row: LabelRow) {
    setEditing(row);
    setSingular(row.current.singular);
    setPlural(row.current.plural);
    setError(null);
  }

  async function done(message: string) {
    await refresh();
    rows.reload();
    feedback.success(message);
  }

  async function save() {
    if (!singular.trim() || !plural.trim()) return setError('Enter both the singular and the plural.');
    setSaving(true);
    try {
      const saved = await api.put<LabelRow>(`/settings/labels/${editing!.key}`, { singular, plural });
      setEditing(null);
      await done(t('Now called {0}', { 0: saved.current.plural }));
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setSaving(false);
    }
  }

  async function reset(row: LabelRow) {
    try {
      await api.delete(`/settings/labels/${row.key}`);
      await done(t('Back to {0}', { 0: t(row.standard.plural) }));
    } catch (e) {
      feedback.error(e);
    }
  }

  return (
    <div className="page" style={{ maxWidth: 1000 }}>
      <PageHeader title={t('Labels')} subtitle={t('Call things what your business calls them: Clients instead of Customers, Products instead of Items.')} />
      <Banner tone="info">{t('Your industry template sets the starting words. A change shows in the menu and on every screen for everyone in your company.')}</Banner>
      {rows.error && <Banner tone="danger">{rows.error.message}</Banner>}
      {rows.loading && !rows.data ? <Spinner /> : (
        <Card flush>
          <DataTable rows={rows.data ?? []} rowKey={(r) => r.key} columns={[
            { key: 'term', label: 'Used for', wrap: true, render: (r) => <div style={{ padding: '10px 0' }}><div className="strong">{r.standard.plural}</div><div className="muted" style={{ fontSize: 13 }}>{r.description}</div></div> },
            { key: 'current', label: 'Your word', render: (r) => <span className="row" style={{ gap: 8 }}><span className="strong">{r.current.singular} / {r.current.plural}</span>{r.renamed && <Badge tone="primary">{t('Renamed')}</Badge>}</span> },
            { key: 'edit', label: '', align: 'right', render: (r) => editable && (
              <span className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                {r.renamed && <Button small icon={RotateCcw} onClick={() => reset(r)}>{t('Reset')}</Button>}
                <Button small icon={Pencil} onClick={() => edit(r)}>{t('Rename')}</Button>
              </span>
            ) },
          ]} />
        </Card>
      )}
      {editing && (
        <Modal title={t('Rename {0}', { 0: editing.current.plural })} onClose={() => setEditing(null)}
          footer={<><Button onClick={() => setEditing(null)}>{t('Cancel')}</Button><Button variant="primary" busy={saving} onClick={save}>{t('Save')}</Button></>}>
          <div className="stack tight">
            {error && <Banner tone="danger">{error}</Banner>}
            <p className="muted">{t('{0}. Your template calls this {1} / {2}.', { 0: editing.description, 1: editing.standard.singular, 2: editing.standard.plural })}</p>
            <Field label={t('One')} required help={t('As in "New {0}"', { 0: inSentence(editing.current.singular) })}>
              <input className="input" value={singular} maxLength={40} autoFocus onChange={(e) => setSingular(e.target.value)} />
            </Field>
            <Field label={t('Many')} required help={t('As shown in the menu and page titles')}>
              <input className="input" value={plural} maxLength={40} onChange={(e) => setPlural(e.target.value)} />
            </Field>
          </div>
        </Modal>
      )}
    </div>
  );
}
