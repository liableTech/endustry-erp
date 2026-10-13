import { useState } from 'react';
import { BookOpen, Download, Eye, FileCode2, Pencil, Trash2, Upload } from 'lucide-react';
import { Field, Select } from '../../components/FormFields';
import { openBlob, saveBlob } from '../../components/PrintActions';
import { Badge, Banner, Button, Card, DataTable, IconButton, Modal, PageHeader, useFeedback } from '../../components/ui';
import { api, ApiError } from '../../core/api';
import { useAuth } from '../../core/auth';
import { formatDateTime } from '../../core/format';
import { useFetch } from '../../core/hooks';
import { t } from '../../core/i18n';

const MAX_BYTES = 3_000_000;
const slug = (docType: string) => docType.toLowerCase().replace(/_/g, '-');

/**
 * Lets a company print with its own layout: upload an HTML template with {{placeholders}},
 * choose it per document type, or stay on the standard layout.
 */
export function PrintTemplates() {
  const { can } = useAuth();
  const feedback = useFeedback();
  const editable = can('settings', 'FULL');
  const types = useFetch<any[]>('/settings/print-templates/types');
  const templates = useFetch<any[]>('/settings/print-templates');
  const [editing, setEditing] = useState<any | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const all = templates.data ?? [];
  const titleOf = (docType: string) => types.data?.find((tpl) => tpl.docType === docType)?.title ?? docType;
  const refresh = () => { types.reload(); templates.reload(); };

  async function setDefault(docType: string, templateId: string) {
    setBusy(`default-${docType}`);
    try {
      await api.put(`/settings/print-templates/default/${docType}`, { templateId: templateId ? Number(templateId) : null });
      feedback.success(t('{0} now prints with {1}', { 0: titleOf(docType), 1: templateId ? all.find((tpl) => String(tpl.id) === templateId)?.name : 'the standard layout' }));
      refresh();
    } catch (e) {
      feedback.error(e);
    } finally {
      setBusy(null);
    }
  }

  async function download(key: string, load: () => Promise<{ blob: Blob; fileName: string }>) {
    setBusy(key);
    try {
      const { blob, fileName } = await load();
      saveBlob(blob, fileName);
    } catch (e) {
      feedback.error(e);
    } finally {
      setBusy(null);
    }
  }

  const builtIn = (docType: string) => download(`builtin-${docType}`, async () => {
    const { blob } = await api.file(`/settings/print-templates/built-in/${docType}`);
    return { blob: new Blob([blob], { type: 'text/html' }), fileName: `standard-${slug(docType)}.html` };
  });

  const sampleData = (docType: string) => download(`data-${docType}`, async () => {
    const data = await api.get(`/settings/print-templates/sample-data/${docType}`);
    return { blob: new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), fileName: `${slug(docType)}-data.json` };
  });

  const downloadTemplate = (tpl: any) => download(`html-${tpl.id}`, async () => {
    const full = await api.get(`/settings/print-templates/${tpl.id}`);
    return { blob: new Blob([full.content], { type: 'text/html' }), fileName: `${tpl.name.replace(/[^A-Za-z0-9_-]+/g, '-')}.html` };
  });

  async function preview(tpl: any) {
    setBusy(`preview-${tpl.id}`);
    try {
      const full = await api.get(`/settings/print-templates/${tpl.id}`);
      const { blob } = await api.postFile('/settings/print-templates/preview', { docType: tpl.docType, content: full.content });
      openBlob(blob);
    } catch (e) {
      feedback.error(e);
    } finally {
      setBusy(null);
    }
  }

  async function remove(tpl: any) {
    const ok = await feedback.confirm({ title: 'Delete template', danger: true, confirmLabel: 'Delete',
      message: <>{t('Delete {0}?', { 0: tpl.name })}{tpl.defaultForType && <> {t('{0} will print with the standard layout again.', { 0: titleOf(tpl.docType) })}</>}</> });
    if (!ok) return;
    try {
      await api.delete(`/settings/print-templates/${tpl.id}`);
      feedback.success(`${tpl.name} deleted`);
      refresh();
    } catch (e) {
      feedback.error(e);
    }
  }

  return (
    <div className="page" style={{ maxWidth: 1100 }}>
      <PageHeader title={t('Print templates')} subtitle={t('Choose how each document looks when printed or downloaded as PDF. Upload your own layout if the standard one does not suit you.')}
        actions={editable && <Button variant="primary" icon={Upload} onClick={() => setEditing({})}>{t('Upload template')}</Button>} />

      <Card title={t('How to make your own layout')}>
        <div className="stack tight">
          <p className="muted" style={{ margin: 0 }}>
            {t('A template is an HTML file with placeholders such as')}{' '}<code>{'{{document.number}}'}</code>{' '}{t('or')}{' '}<code>{'{{party.name}}'}</code>{t(', and')}
           {' '}<code>{' {{#lines}} ... {{/lines}} '}</code>{' '}{t('to repeat each line item. When you print, the document\'s data is filled in and turned into a PDF. Start from the sample or from the standard layout of a document below, change it, and upload it here.')}
         {' '}</p>
          <div className="row wrap">
            <a className="btn" href="/print-templates/sample-classic-invoice.html" download><FileCode2 size={16} />{t('Sample template (classic GST invoice)')}</a>
            <a className="btn" href="/print-templates/print-template-guide.html" target="_blank" rel="noreferrer"><BookOpen size={16} />{t('Placeholder guide')}</a>
          </div>
        </div>
      </Card>

      <Card title={t('What each document prints with')} flush>
        <DataTable rows={types.data ?? []} loading={types.loading} rowKey={(tpl) => tpl.docType} columns={[
          { key: 'title', label: 'Document', render: (tpl) => <span className="strong">{tpl.title}</span> },
          { key: 'uses', label: 'Prints with', render: (tpl) => {
            const options = all.filter((x) => x.docType === tpl.docType);
            const current = options.find((x) => x.defaultForType);
            return editable && options.length > 0
              ? <Select value={current ? String(current.id) : ''} placeholder={t('Standard layout')} disabled={busy === `default-${tpl.docType}`}
                  onChange={(v) => setDefault(tpl.docType, v)} options={options.map((x) => ({ value: String(x.id), label: x.name }))} />
              : <span>{tpl.usesTemplate ?? 'Standard layout'}</span>;
          } },
          { key: 'get', label: 'To start your own', render: (tpl) => (
            <div className="row wrap">
              <Button small icon={Download} busy={busy === `builtin-${tpl.docType}`} onClick={() => builtIn(tpl.docType)}>{t('Standard layout')}</Button>
              <Button small icon={Download} busy={busy === `data-${tpl.docType}`} onClick={() => sampleData(tpl.docType)}>{t('Sample data')}</Button>
            </div>
          ) },
        ]} />
      </Card>

      <Card title={t('Uploaded templates')} flush>
        <DataTable rows={all} loading={templates.loading} emptyTitle={t('No templates uploaded yet')}
          emptyHint={t('Every document prints with the standard layout until you upload one.')} columns={[
          { key: 'name', label: 'Name', render: (tpl) => <><span className="strong">{tpl.name}</span>{tpl.description && <div className="muted" style={{ fontSize: 12.5 }}>{tpl.description}</div>}</> },
          { key: 'docType', label: 'Document', render: (tpl) => titleOf(tpl.docType) },
          { key: 'default', label: 'In use', render: (tpl) => tpl.defaultForType ? <Badge tone="success">{t('Default')}</Badge> : <span className="muted">{t('Pick when printing')}</span> },
          { key: 'updated', label: 'Updated', render: (tpl) => <span className="muted">{formatDateTime(tpl.updatedAt)}{tpl.updatedBy ? ` by ${tpl.updatedBy}` : ''}</span> },
          { key: 'actions', label: '', align: 'right', render: (tpl) => (
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <IconButton icon={Eye} label={t('Preview with sample data')} disabled={busy === `preview-${tpl.id}`} onClick={() => preview(t)} />
              <IconButton icon={Download} label={t('Download HTML')} disabled={busy === `html-${tpl.id}`} onClick={() => downloadTemplate(t)} />
              {editable && <IconButton icon={Pencil} label={t('Edit or replace')} onClick={() => setEditing(t)} />}
              {editable && <IconButton icon={Trash2} label={t('Delete')} onClick={() => remove(t)} />}
            </div>
          ) },
        ]} />
      </Card>

      {editing && <TemplateEditor template={editing.id ? editing : null} types={types.data ?? []} onClose={() => setEditing(null)}
        onSaved={() => { setEditing(null); refresh(); }} />}
    </div>
  );
}

function TemplateEditor({ template, types, onClose, onSaved }: { template: any | null; types: any[]; onClose: () => void; onSaved: () => void }) {
  const feedback = useFeedback();
  const [name, setName] = useState(template?.name ?? '');
  const [docType, setDocType] = useState(template?.docType ?? 'SALES_INVOICE');
  const [description, setDescription] = useState(template?.description ?? '');
  const [makeDefault, setMakeDefault] = useState<boolean>(template?.defaultForType ?? true);
  const [content, setContent] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'save' | 'preview' | null>(null);

  async function pick(file: File | undefined) {
    setError(null);
    if (!file) return;
    if (file.size > MAX_BYTES) return setError('The file is larger than 3 MB. Make embedded images smaller and try again.');
    if (!/\.html?$/i.test(file.name)) return setError('Choose an .html file.');
    setContent(await file.text());
    setFileName(file.name);
    if (!name) setName(file.name.replace(/\.html?$/i, '').replace(/[-_]+/g, ' '));
  }

  /** The new file if one was chosen, else what is saved already. */
  async function currentContent(): Promise<string | null> {
    if (content !== null) return content;
    if (template) return (await api.get(`/settings/print-templates/${template.id}`)).content;
    return null;
  }

  async function preview() {
    setBusy('preview');
    setError(null);
    try {
      const text = await currentContent();
      if (!text) { setError('Choose the template file first.'); return; }
      const { blob } = await api.postFile('/settings/print-templates/preview', { docType, content: text });
      openBlob(blob);
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(null);
    }
  }

  async function save() {
    if (!name.trim()) return setError('Give the template a name.');
    if (!template && content === null) return setError('Choose the template file to upload.');
    setBusy('save');
    setError(null);
    try {
      const body = { name: name.trim(), docType, description: description || null, defaultForType: makeDefault, content: await currentContent() };
      if (template) await api.put(`/settings/print-templates/${template.id}`, body);
      else await api.post('/settings/print-templates', body);
      feedback.success(makeDefault ? t('{0} saved and used for every {1}', { 0: body.name, 1: types.find((tpl) => tpl.docType === docType)?.title ?? t('document') }) : t('{0} saved', { 0: body.name }));
      onSaved();
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(null);
    }
  }

  return (
    <Modal title={template ? t('Edit {0}', { 0: template.name }) : 'Upload print template'} onClose={onClose}
      footer={<><Button onClick={onClose}>{t('Cancel')}</Button><Button icon={Eye} busy={busy === 'preview'} onClick={preview}>{t('Preview')}</Button><Button variant="primary" busy={busy === 'save'} onClick={save}>{t('Save')}</Button></>}>
      <div className="stack tight">
        {error && <Banner tone="danger">{error}</Banner>}
        <div className="form-grid">
          <Field label={t('Template file (.html)')} required={!template} span={2}
            help={fileName ? `Chosen: ${fileName}` : template ? 'Leave empty to keep the current file.' : 'An HTML file with {{placeholders}}. Preview checks it against your latest document.'}>
            <input className="input" type="file" accept=".html,.htm,text/html" onChange={(e) => pick(e.target.files?.[0])} />
          </Field>
          <Field label={t('Name')} required><input className="input" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={t('For document')} required>
            <Select value={docType} onChange={setDocType} placeholder={t('Choose')} options={types.map((tpl) => ({ value: tpl.docType, label: tpl.title }))} />
          </Field>
          <Field label={t('Description')} span={2}><input className="input" value={description} maxLength={250} onChange={(e) => setDescription(e.target.value)} /></Field>
          <div className="field span-2">
            <label className="check"><input type="checkbox" checked={makeDefault} onChange={(e) => setMakeDefault(e.target.checked)} />{t('Use this layout whenever this document is printed')}</label>
            <span className="field-help">{t('Otherwise it can be picked from the layout list next to the Print button.')}</span>
          </div>
        </div>
      </div>
    </Modal>
  );
}
