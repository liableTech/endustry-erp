import type { ReactNode } from 'react';
import { useLookup } from '../core/hooks';
import { t } from '../core/i18n';
import type { FieldDef, Option } from '../core/validation';

/** Label, control, help text and error for one input. */
export function Field({ label, required, error, help, children, span }: { label: string; required?: boolean; error?: string; help?: string; children: ReactNode; span?: 1 | 2 }) {
  return (
    <label className={`field ${span === 2 ? 'span-2' : ''}`}>
      <span className="field-label">{t(label)}{required && <span className="req">*</span>}</span>
      {children}
      {error ? <span className="field-error">{t(label)} {t(error)}</span> : help && <span className="field-help">{t(help)}</span>}
    </label>
  );
}

export function Select({ value, onChange, options, placeholder = 'Select', disabled, invalid }: { value: unknown; onChange: (value: string) => void; options: Option[]; placeholder?: string; disabled?: boolean; invalid?: boolean }) {
  return (
    <select className={`input ${invalid ? 'invalid' : ''}`} value={value === null || value === undefined ? '' : String(value)} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t(placeholder)}</option>
      {options.map((o) => <option key={o.value} value={o.value}>{t(o.label)}</option>)}
    </select>
  );
}

function LookupSelect({ type, ...rest }: { type: string; value: unknown; onChange: (value: string) => void; disabled?: boolean; invalid?: boolean }) {
  const options = useLookup(type);
  return <Select options={options.map((o) => ({ value: o.value, label: `${o.value} - ${t(o.label)}` }))} {...rest} />;
}

interface FormFieldsProps {
  fields: FieldDef[];
  values: Record<string, any>;
  errors: Record<string, string>;
  onChange: (name: string, value: any) => void;
  /** True when editing an existing record, so lockedOnEdit fields are disabled. */
  editing?: boolean;
  readOnly?: boolean;
}

/** Draws a form from field definitions. Used by every master, settings and modal form. */
export function FormFields({ fields, values, errors, onChange, editing, readOnly }: FormFieldsProps) {
  let lastSection: string | undefined;
  const nodes: ReactNode[] = [];
  for (const field of fields) {
    if (field.showIf && !field.showIf(values)) continue;
    if (field.section && field.section !== lastSection) {
      nodes.push(<div key={`section-${field.section}`} className="form-section">{t(field.section)}</div>);
      lastSection = field.section;
    }
    const disabled = readOnly || (editing && field.lockedOnEdit);
    const invalid = Boolean(errors[field.name]);
    const value = values[field.name];
    const set = (v: any) => onChange(field.name, v);
    let control: ReactNode;
    switch (field.type) {
      case 'checkbox':
        nodes.push(
          <div key={field.name} className={`field ${field.span === 2 ? 'span-2' : ''}`}>
            <label className="check">
              <input type="checkbox" checked={Boolean(value)} disabled={disabled} onChange={(e) => set(e.target.checked)} />
              {t(field.label)}
            </label>
            {field.help && <span className="field-help">{t(field.help)}</span>}
          </div>,
        );
        continue;
      case 'select':
        control = <Select value={value} onChange={set} options={field.options ?? []} disabled={disabled} invalid={invalid} />;
        break;
      case 'lookup':
        control = <LookupSelect type={field.lookupType!} value={value} onChange={set} disabled={disabled} invalid={invalid} />;
        break;
      case 'textarea':
        control = <textarea className={`input ${invalid ? 'invalid' : ''}`} value={value ?? ''} disabled={disabled} placeholder={field.placeholder} onChange={(e) => set(e.target.value)} />;
        break;
      case 'color':
        control = (
          <div className="color-input">
            <input type="color" value={/^#[0-9a-fA-F]{6}$/.test(value ?? '') ? value : '#000000'} disabled={disabled} onChange={(e) => set(e.target.value.toUpperCase())} aria-label={field.label} />
            <input className={`input ${invalid ? 'invalid' : ''}`} value={value ?? ''} disabled={disabled} onChange={(e) => set(e.target.value)} />
          </div>
        );
        break;
      default:
        control = (
          <input
            className={`input ${field.type === 'number' ? 'num' : ''} ${invalid ? 'invalid' : ''}`}
            type={field.type === 'number' ? 'number' : field.type}
            step={field.type === 'number' ? 'any' : undefined}
            value={value ?? ''}
            disabled={disabled}
            placeholder={field.placeholder}
            autoComplete={field.type === 'password' ? 'new-password' : 'off'}
            onChange={(e) => set(field.uppercase ? e.target.value.toUpperCase() : e.target.value)}
          />
        );
    }
    nodes.push(
      <Field key={field.name} label={field.label} required={field.required} error={errors[field.name]} help={field.help} span={field.span}>
        {control}
      </Field>,
    );
  }
  return <div className="form-grid">{nodes}</div>;
}
