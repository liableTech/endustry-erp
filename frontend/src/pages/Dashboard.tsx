import { Link } from 'react-router-dom';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Banknote, Boxes, CircleAlert, ClipboardCheck, ClipboardList, Factory, HandCoins, IndianRupee, Info, TrendingDown, TrendingUp, TriangleAlert } from 'lucide-react';
import { Banner, Card, DataTable, PageHeader, Progress, Spinner, Stat, StatusBadge } from '../components/ui';
import { DOCUMENTS } from '../config/documents';
import { useAuth } from '../core/auth';
import { formatCompact, formatDate, formatMoney, formatQty, humanize } from '../core/format';
import { useFetch } from '../core/hooks';
import { t } from '../core/i18n';

const AXIS = { fontSize: 12, fill: 'var(--text-muted)' };
const TOOLTIP = { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 10, color: 'var(--text)', fontSize: 13 };
const STATUS_COLOURS: Record<string, string> = {
  PLANNED: 'var(--text-muted)', RELEASED: 'var(--accent)', IN_PROGRESS: 'var(--warning)', COMPLETED: 'var(--success)', CANCELLED: 'var(--danger)',
};
const ALERT_ICONS = { danger: CircleAlert, warning: TriangleAlert, info: Info } as const;

/** RPT-01: the owner's view of sales, production, stock, cash and what needs attention. */
export function Dashboard() {
  const { user } = useAuth();
  const { data, loading, error } = useFetch<any>('/dashboard/summary');
  if (loading && !data) return <Spinner />;
  if (error || !data) return <div className="page"><Banner tone="danger">{error?.message ?? 'The dashboard could not be loaded.'}</Banner></div>;

  const k = data.kpis;
  // The server leaves out modules switched off for this client; so does every tile and chart here.
  const on = (module: string) => (data.modules as string[] | undefined)?.includes(module) ?? true;
  const trading = on('sales') || on('purchase');
  const growth = Number(k.salesLastMonth) > 0 ? ((Number(k.salesThisMonth) - Number(k.salesLastMonth)) / Number(k.salesLastMonth)) * 100 : null;
  const topMax = Math.max(1, ...data.topCustomers.map((c: any) => Number(c.value)));
  const stockMax = Math.max(1, ...data.stockByCategory.map((c: any) => Number(c.value)));

  return (
    <div className="page">
      <PageHeader title={t(greeting(), { name: user?.fullName.split(' ')[0] })} subtitle={t('Here is where the business stands today.')} />

      <div className="grid kpi">
        {on('sales') && <>
        <Stat icon={IndianRupee} tone="primary" label={t('Sales this month')} value={formatCompact(k.salesThisMonth)}
          foot={growth === null ? t('Today {0}', { 0: formatCompact(k.salesToday) }) : (
            <span className={growth >= 0 ? 'up' : 'down'}>
              {growth >= 0 ? <TrendingUp size={13} /> : <TrendingDown size={13} />} {Math.abs(growth).toFixed(0)}% <span className="muted" style={{ fontWeight: 400 }}>{t('vs last month')}</span>
            </span>
          )} />
        <Stat icon={ClipboardList} tone="info" label={t('Orders in hand')} value={formatCompact(k.openOrdersValue)} foot={t(k.openOrdersCount === 1 ? '{0} open order' : '{0} open orders', { 0: k.openOrdersCount })} />
        <Stat icon={HandCoins} tone="success" label={t('Receivables')} value={formatCompact(k.receivables)}
          foot={Number(k.overdueReceivables) > 0 ? <span className="down">{t('{0} overdue', { 0: formatCompact(k.overdueReceivables) })}</span> : 'Nothing overdue'} />
        </>}
        {on('purchase') && (
        <Stat icon={Banknote} tone="warning" label={t('Payables')} value={formatCompact(k.payables)}
          foot={Number(k.msmeAtRisk) > 0 ? <span className="down">{t('{0} MSME due soon', { 0: formatCompact(k.msmeAtRisk) })}</span> : 'No MSME dues at risk'} />
        )}
        {on('inventory') && (
        <Stat icon={Boxes} tone="primary" label={t('Stock value')} value={formatCompact(k.stockValue)}
          foot={k.lowStockCount > 0 ? <span className="down">{t(k.lowStockCount === 1 ? '{0} item below reorder level' : '{0} items below reorder level', { 0: k.lowStockCount })}</span> : 'All items above reorder level'} />
        )}
        {on('production') && <>
        <Stat icon={Factory} tone="info" label={t('Work orders open')} value={k.openWorkOrders}
          foot={<span className="row" style={{ gap: 8 }}><Progress value={Number(k.producedQty)} max={Number(k.plannedQty)} />{t('{0} of {1}', { 0: formatQty(k.producedQty), 1: formatQty(k.plannedQty) })}</span>} />
        <Stat icon={TriangleAlert} tone={Number(k.rejectionPct) > 3 ? 'danger' : 'success'} label={t('Rejection rate')} value={`${k.rejectionPct}%`} foot="Rejected of all output booked" />
        </>}
        {on('quality') && (
        <Stat icon={ClipboardCheck} tone={k.pendingInspections > 0 ? 'warning' : 'success'} label={t('Awaiting inspection')} value={k.pendingInspections} foot="Received batches held in QC" />
        )}
      </div>

      <div className={trading ? 'grid two' : 'stack'}>
        {trading && (
        <Card title={on('sales') && on('purchase') ? 'Sales and purchases, last 6 months' : on('sales') ? 'Sales, last 6 months' : 'Purchases, last 6 months'}>
          <ResponsiveContainer width="100%" height={280}>
            <AreaChart data={data.trend} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id="salesFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.35} /><stop offset="100%" stopColor="var(--primary)" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="purchaseFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--accent)" stopOpacity={0.25} /><stop offset="100%" stopColor="var(--accent)" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke="var(--border)" vertical={false} />
              <XAxis dataKey="label" tick={AXIS} axisLine={false} tickLine={false} />
              <YAxis tick={AXIS} axisLine={false} tickLine={false} tickFormatter={(v) => formatCompact(v)} width={64} />
              <Tooltip contentStyle={TOOLTIP} formatter={(value) => formatMoney(value, true)} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
              {on('sales') && <Area type="monotone" name={t('Sales')} dataKey="sales" stroke="var(--primary)" strokeWidth={2.5} fill="url(#salesFill)" />}
              {on('purchase') && <Area type="monotone" name={t('Purchases')} dataKey="purchases" stroke="var(--accent)" strokeWidth={2.5} fill="url(#purchaseFill)" />}
            </AreaChart>
          </ResponsiveContainer>
        </Card>
        )}
        <Card title={t('Needs attention')}>
          {data.alerts.length === 0 ? <p className="muted">{t('Nothing needs your attention right now.')}</p> : data.alerts.map((a: any) => {
            const Icon = ALERT_ICONS[a.severity as keyof typeof ALERT_ICONS] ?? Info;
            return (
              <Link key={a.title} to={a.link} className="alert-item">
                <div className={`alert-dot tone-${a.severity}`}><Icon size={17} /></div>
                <div><div style={{ fontWeight: 600 }}>{a.title}</div><div className="muted" style={{ fontSize: 13 }}>{a.detail}</div></div>
              </Link>
            );
          })}
        </Card>
      </div>

      {(on('production') || on('inventory')) && (
      <div className={on('production') && on('inventory') ? 'grid three' : on('production') ? 'grid two' : 'stack'}>
        {on('production') && <>
        <Card title={t('Production output')}>
          <ResponsiveContainer width="100%" height={230}>
            <BarChart data={data.trend} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid stroke="var(--border)" vertical={false} />
              <XAxis dataKey="label" tick={AXIS} axisLine={false} tickLine={false} />
              <YAxis tick={AXIS} axisLine={false} tickLine={false} width={40} />
              <Tooltip contentStyle={TOOLTIP} cursor={{ fill: 'var(--surface-2)' }} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
              <Bar name={t('Good')} dataKey="produced" stackId="a" fill="var(--primary)" radius={[0, 0, 0, 0]} />
              <Bar name={t('Rejected')} dataKey="rejected" stackId="a" fill="var(--danger)" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </Card>
        <Card title={t('Work orders by status')}>
          <ResponsiveContainer width="100%" height={230}>
            <PieChart>
              <Pie data={data.workOrdersByStatus.map((w: any) => ({ ...w, name: humanize(w.name), key: w.name, value: Number(w.value) }))}
                dataKey="value" nameKey="name" innerRadius={58} outerRadius={86} paddingAngle={3} stroke="none">
                {data.workOrdersByStatus.map((w: any) => <Cell key={w.name} fill={STATUS_COLOURS[w.name] ?? 'var(--primary)'} />)}
              </Pie>
              <Tooltip contentStyle={TOOLTIP} />
              <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
            </PieChart>
          </ResponsiveContainer>
        </Card>
        </>}
        {on('inventory') && (
        <Card title={t('Stock value by category')}>
          {data.stockByCategory.map((c: any) => (
            <div key={c.name} className="bar-row">
              <span>{humanize(c.name)}</span>
              <Progress value={Number(c.value)} max={stockMax} />
              <span className="mono strong">{formatCompact(c.value)}</span>
            </div>
          ))}
        </Card>
        )}
      </div>
      )}

      {(trading || on('inventory')) && (
      <div className={on('sales') ? 'grid two' : 'stack'}>
        <Card title={t('Recent documents')} flush>
          <DataTable rows={data.recentDocuments} rowKey={(d) => `${d.kind}-${d.id}`} columns={[
            { key: 'docNo', label: 'Number', render: (d) => <><Link className="strong" to={`${DOCUMENTS[d.kind].route}/${d.id}`}>{d.docNo}</Link><div className="muted" style={{ fontSize: 12 }}>{formatDate(d.docDate)}</div></> },
            { key: 'kind', label: 'Type', render: (d) => humanize(DOCUMENTS[d.kind].singular) },
            { key: 'party', label: 'Party', wrap: true, render: (d) => d.party },
            { key: 'total', label: 'Total', align: 'right', render: (d) => formatMoney(d.total) },
            { key: 'status', label: 'Status', render: (d) => <StatusBadge status={d.status} labels={DOCUMENTS[d.kind].statusLabels} /> },
          ]} />
        </Card>
        {on('sales') && (
        <Card title={t('Top customers')}>
          {data.topCustomers.length === 0 ? <p className="muted">{t('No sales yet.')}</p> : data.topCustomers.map((c: any) => (
            <div key={c.name} className="bar-row">
              <span>{c.name}</span>
              <Progress value={Number(c.value)} max={topMax} />
              <span className="mono strong">{formatCompact(c.value)}</span>
            </div>
          ))}
        </Card>
        )}
      </div>
      )}
    </div>
  );
}

function greeting(): string {
  const hour = new Date().getHours();
  return hour < 12 ? 'Good morning, {name}' : hour < 17 ? 'Good afternoon, {name}' : 'Good evening, {name}';
}
