import type { ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useSearchParams } from 'react-router-dom';
import { DocumentPage } from './components/DocumentPage';
import { ResourcePage } from './components/ResourcePage';
import { Empty, FeedbackProvider, Spinner } from './components/ui';
import { DOCUMENTS } from './config/documents';
import * as resources from './config/resources';
import { AuthProvider, useAuth } from './core/auth';
import { humanize } from './core/format';
import { ThemeProvider } from './core/theme';
import { AppShell } from './layout/AppShell';
import { Boms } from './pages/Boms';
import { Dashboard } from './pages/Dashboard';
import { Landing } from './pages/Landing';
import { Login } from './pages/Login';
import { Signup } from './pages/Signup';
import { Inspections, MaterialPlan } from './pages/Planning';
import { ReportIndex, ReportView } from './pages/Reports';
import { Stock, StockLedger } from './pages/Stock';
import { WorkOrders } from './pages/WorkOrders';
import { AccessSettings, AuditTrail, CompanySettings, NumberingSettings, ParameterSettings } from './pages/settings/SettingsPages';
import { LabelSettings } from './pages/settings/LabelSettings';
import { PrintTemplates } from './pages/settings/PrintTemplates';
import { ThemeSettings } from './pages/settings/ThemeSettings';
import { Clients } from './pages/platform/Clients';
import { Signups } from './pages/platform/Signups';
import { Translations } from './pages/platform/Translations';
import { I18nProvider, t } from './core/i18n';

/**
 * Shows the page only if the module is switched on for the client and the role may open it;
 * the server enforces the same rules.
 */
function Guard({ module, children }: { module: string; children: ReactNode }) {
  const { can, enabled } = useAuth();
  if (!enabled(module)) return <ModuleNotEnabled module={module} />;
  return can(module) ? <>{children}</> : <Empty title={t('You do not have access to this page')} hint={t('Ask the Owner to change your role\'s access.')} />;
}

function ModuleNotEnabled({ module }: { module: string }) {
  return (
    <div className="page">
      <Empty title={t('The {0} module is not enabled', { 0: humanize(module) })}
        hint={t('It is not part of your company\'s subscription. Contact your provider to switch it on.')}
        action={<Link className="btn" to="/">{t('Go to the dashboard')}</Link>} />
    </div>
  );
}

const page = (module: string, element: ReactNode) => <Guard module={module}>{element}</Guard>;

/**
 * Before sign-in: the landing page at /, the sign-in page at /login and sign-up at /signup. Any other address shows the
 * sign-in page in place, so a bookmarked screen opens once the user has signed in. A white-label
 * client's link (/?client=short-name) goes straight to its branded sign-in page.
 */
function PublicScreens() {
  const [params] = useSearchParams();
  return (
    <Routes>
      <Route index element={params.get('client') ? <Login /> : <Landing />} />
      <Route path="signup" element={<Signup />} />
      <Route path="*" element={<Login />} />
    </Routes>
  );
}

function Screens() {
  const { user, loading, isProductAdmin } = useAuth();
  if (loading) return <Spinner />;
  if (!user) return <PublicScreens />;
  // A product admin has a workspace of their own; a client's screens open only in a support session.
  if (isProductAdmin) {
    return (
      <Routes>
        <Route element={<AppShell />}>
          <Route path="platform/clients/:id?" element={<Clients />} />
          <Route path="platform/signups" element={<Signups />} />
          <Route path="platform/translations" element={<Translations />} />
          <Route path="platform/theme" element={<ThemeSettings endpoint="/platform/theme" />} />
          <Route path="platform/audit" element={<AuditTrail endpoint="/platform/audit" />} />
          <Route path="*" element={<Navigate to="/platform/clients" replace />} />
        </Route>
      </Routes>
    );
  }
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={page('dashboard', <Dashboard />)} />

        <Route path="sales/quotations/:id?/:mode?" element={page('sales', <DocumentPage config={DOCUMENTS.quotation} />)} />
        <Route path="sales/orders/:id?/:mode?" element={page('sales', <DocumentPage config={DOCUMENTS['sales-order']} />)} />
        <Route path="sales/invoices/:id?/:mode?" element={page('sales', <DocumentPage config={DOCUMENTS['sales-invoice']} />)} />
        <Route path="purchase/orders/:id?/:mode?" element={page('purchase', <DocumentPage config={DOCUMENTS['purchase-order']} />)} />
        <Route path="purchase/invoices/:id?/:mode?" element={page('purchase', <DocumentPage config={DOCUMENTS['purchase-invoice']} />)} />

        <Route path="inventory/stock" element={page('inventory', <Stock />)} />
        <Route path="inventory/receipts/:id?/:mode?" element={page('inventory', <DocumentPage config={DOCUMENTS.grn} />)} />
        <Route path="inventory/ledger" element={page('inventory', <StockLedger />)} />

        <Route path="production/work-orders/:id?" element={page('production', <WorkOrders />)} />
        <Route path="production/boms/:id?/:mode?" element={page('production', <Boms />)} />
        <Route path="production/material-plan" element={page('production', <MaterialPlan />)} />
        <Route path="quality/inspections" element={page('quality', <Inspections />)} />

        <Route path="reports" element={page('reports', <ReportIndex />)} />
        <Route path="reports/:key" element={page('reports', <ReportView />)} />

        <Route path="masters/items/:id?" element={page('masters', <ResourcePage config={resources.items} />)} />
        <Route path="masters/customers/:id?" element={page('masters', <ResourcePage config={resources.customers} />)} />
        <Route path="masters/vendors/:id?" element={page('masters', <ResourcePage config={resources.vendors} />)} />
        <Route path="masters/warehouses/:id?" element={page('masters', <ResourcePage config={resources.warehouses} />)} />
        <Route path="masters/work-centres/:id?" element={page('masters', <ResourcePage config={resources.workCentres} />)} />
        <Route path="masters/employees/:id?" element={page('masters', <ResourcePage config={resources.employees} />)} />
        <Route path="masters/tax-rates/:id?" element={page('masters', <ResourcePage config={resources.taxRates} />)} />
        <Route path="masters/lists/:id?" element={page('masters', <ResourcePage config={resources.lists} />)} />

        <Route path="settings/company" element={page('settings', <CompanySettings />)} />
        <Route path="settings/theme" element={page('settings', <ThemeSettings />)} />
        <Route path="settings/labels" element={page('settings', <LabelSettings />)} />
        <Route path="settings/parameters" element={page('settings', <ParameterSettings />)} />
        <Route path="settings/numbering" element={page('settings', <NumberingSettings />)} />
        <Route path="settings/print-templates" element={page('settings', <PrintTemplates />)} />
        <Route path="settings/users/:id?" element={page('admin', <ResourcePage config={resources.users} />)} />
        <Route path="settings/access" element={page('admin', <AccessSettings />)} />
        <Route path="settings/audit" element={page('admin', <AuditTrail />)} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

export function App() {
  return (
    <I18nProvider>
    <ThemeProvider>
      <FeedbackProvider>
        <AuthProvider>
          <BrowserRouter>
            <Screens />
          </BrowserRouter>
        </AuthProvider>
      </FeedbackProvider>
    </ThemeProvider>
    </I18nProvider>
  );
}
