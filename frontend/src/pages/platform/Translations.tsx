import { useState } from 'react';
import { Languages, Pencil } from 'lucide-react';
import { Field, Select } from '../../components/FormFields';
import { Badge, Banner, Button, Card, DataTable, Modal, PageHeader, Pager, SearchBox, useFeedback } from '../../components/ui';
import { api, ApiError, query, type Page } from '../../core/api';
import { useDebounced, useFetch } from '../../core/hooks';
import { t, useI18n } from '../../core/i18n';

interface Row {
  key: string;
  text: string | null;
  translated: boolean;
}

/**
 * Product admin > Translations: the screen text of every language, kept in the database. A change
 * raises the language's version, so every open browser picks it up the next time it looks.
 */
export function Translations() {
  const feedback = useFeedback();
  const { languages, setLanguage, language: shown } = useI18n();
  const others = languages.filter((l) => l.code !== 'en');
  const [lang, setLang] = useState(others[0]?.code ?? 'hi');
  const [search, setSearch] = useState('');
  const [missing, setMissing] = useState(false);
  const [page, setPage] = useState(0);
  const q = useDebounced(search, 300);
  const list = useFetch<Page<Row>>(`/platform/translations/${lang}${query({ q, missing, page, size: 50 })}`);
  const [editing, setEditing] = useState<Row | null>(null);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function edit(row: Row) {
    setEditing(row);
    setText(row.text ?? '');
    setError(null);
  }

  async function save() {
    setSaving(true);
    try {
      await api.put(`/platform/translations/${lang}`, { key: editing!.key, text });
      setEditing(null);
      list.reload();
      feedback.success('Translation saved');
      // Show the change at once if this is the language on screen.
      if (shown === lang) setLanguage(lang);
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page" style={{ maxWidth: 1100 }}>
      <PageHeader title="Translations" subtitle="The text of every screen in each language. The English wording is the key; anything without a translation shows in English." />
      <Card flush title={
        <div className="row wrap" style={{ gap: 10 }}>
          <div style={{ width: 170 }}><Select value={lang} placeholder="Language" onChange={(v) => { setLang(v || lang); setPage(0); }}
            options={languages.map((l) => ({ value: l.code, label: `${l.nativeName} (${l.name})` }))} /></div>
          <SearchBox value={search} onChange={(v) => { setSearch(v); setPage(0); }} placeholder="Search English or translated text" />
          <label className="check"><input type="checkbox" checked={missing} onChange={(e) => { setMissing(e.target.checked); setPage(0); }} />{t('Only untranslated')}</label>
        </div>
      }>
        <DataTable rows={list.data?.content ?? []} loading={list.loading} rowKey={(r) => r.key} emptyTitle="No text matches" columns={[
          { key: 'key', label: 'English', wrap: true, render: (r) => r.key },
          { key: 'text', label: 'Translation', wrap: true, render: (r) => r.translated ? r.text : <Badge tone="warning">{t('Not translated')}</Badge> },
          { key: 'edit', label: '', align: 'right', render: (r) => <Button small icon={Pencil} onClick={() => edit(r)}>Edit</Button> },
        ]} />
        {list.data && <Pager page={page} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onPage={setPage} />}
      </Card>
      {editing && (
        <Modal title="Edit translation" onClose={() => setEditing(null)}
          footer={<><Button onClick={() => setEditing(null)}>Cancel</Button><Button variant="primary" icon={Languages} busy={saving} onClick={save}>Save</Button></>}>
          <div className="stack tight">
            {error && <Banner tone="danger">{error}</Banner>}
            <Field label="English"><div className="input" style={{ height: 'auto', padding: 10 }}>{editing.key}</div></Field>
            <Field label="Translation" help="Leave empty to show the English text.">
              <textarea className="input" value={text} autoFocus onChange={(e) => setText(e.target.value)} />
            </Field>
          </div>
        </Modal>
      )}
    </div>
  );
}
