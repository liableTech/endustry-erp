import { useState } from 'react';
import { Download, Printer } from 'lucide-react';
import { api } from '../core/api';
import { useFetch } from '../core/hooks';
import { Button, useFeedback } from './ui';
import { t } from '../core/i18n';

/** Hands a file to the browser as a download. */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Opens a PDF in a new tab, for previews. */
export function openBlob(blob: Blob) {
  const url = URL.createObjectURL(blob);
  if (!window.open(url, '_blank')) saveBlob(blob, 'preview.pdf');
  setTimeout(() => URL.revokeObjectURL(url), 300_000);
}

/** Sends a PDF straight to the browser's print dialog through a hidden frame; opens it in a tab if that is blocked. */
function printBlob(blob: Blob) {
  const url = URL.createObjectURL(blob);
  const frame = document.createElement('iframe');
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
  frame.src = url;
  frame.onload = () => {
    setTimeout(() => {
      try {
        frame.contentWindow?.focus();
        frame.contentWindow?.print();
      } catch {
        window.open(url, '_blank');
      }
    }, 300);
  };
  document.body.appendChild(frame);
  setTimeout(() => { frame.remove(); URL.revokeObjectURL(url); }, 300_000);
}

/**
 * Print and Download PDF for a saved document, plus a choice of layout when the company has
 * uploaded its own templates. {@code path} is the document's API path, e.g. /sales/docs/sales-invoice/12.
 */
export function PrintActions({ path, templatesPath, fallbackName }: { path: string; templatesPath: string; fallbackName: string }) {
  const feedback = useFeedback();
  const templates = useFetch<any[]>(templatesPath);
  const [templateId, setTemplateId] = useState('');
  const [busy, setBusy] = useState<'print' | 'download' | null>(null);
  const list = templates.data ?? [];
  const defaultTemplate = list.find((tpl) => tpl.defaultForType);

  async function run(kind: 'print' | 'download') {
    setBusy(kind);
    try {
      const params = new URLSearchParams();
      if (templateId) params.set('templateId', templateId);
      if (kind === 'download') params.set('download', 'true');
      const { blob, fileName } = await api.file(`${path}/print${params.size ? `?${params}` : ''}`);
      if (kind === 'download') saveBlob(blob, fileName ?? `${fallbackName}.pdf`);
      else printBlob(blob);
    } catch (e) {
      feedback.error(e);
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      {list.length > 0 && (
        <select className="input print-template-pick" value={templateId} onChange={(e) => setTemplateId(e.target.value)} aria-label={t('Print layout')} title={t('Print layout')}>
          <option value="">{defaultTemplate ? `${defaultTemplate.name} (default)` : 'Standard layout'}</option>
          {list.filter((tpl) => !tpl.defaultForType).map((tpl) => <option key={tpl.id} value={tpl.id}>{tpl.name}</option>)}
          {defaultTemplate && <option value="0">{t('Standard layout')}</option>}
        </select>
      )}
      <Button icon={Printer} busy={busy === 'print'} onClick={() => run('print')}>{t('Print')}</Button>
      <Button icon={Download} busy={busy === 'download'} onClick={() => run('download')}>{t('Download PDF')}</Button>
    </>
  );
}
