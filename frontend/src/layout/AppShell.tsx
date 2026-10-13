import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { Factory, LifeBuoy, LogOut, Moon, PanelLeftClose, PanelLeftOpen, Sun } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../components/ui';
import { IconButton } from '../components/ui';
import { NAVIGATION, PRODUCT_NAVIGATION } from '../config/navigation';
import { useAuth } from '../core/auth';
import { term } from '../core/labels';
import { humanize } from '../core/format';
import { useTheme } from '../core/theme';
import { LanguageSwitcher, t } from '../core/i18n';

const COLLAPSED_KEY = 'endustry.sidebarCollapsed';

/** Sidebar, top bar and the page outlet. The menu shows only what the role may open. */
export function AppShell() {
  const { user, client, can, enabled, signOut, isProductAdmin, impersonatedBy, endSupport, labels } = useAuth();
  const navigate = useNavigate();
  const { theme, companyName, preview } = useTheme();
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSED_KEY) === '1');
  const dark = theme.mode === 'dark' || (theme.mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

  function toggleSidebar() {
    setCollapsed((c) => {
      localStorage.setItem(COLLAPSED_KEY, c ? '0' : '1');
      return !c;
    });
  }

  const initials = (user?.fullName ?? '?').split(' ').map((p) => p[0]).slice(0, 2).join('').toUpperCase();

  return (
    <div className={`shell ${collapsed ? 'collapsed' : ''}`}>
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><Factory size={19} /></div>
          <div className="brand-text grow">
            <div className="brand-name">{theme.appName}</div>
            <div className="brand-sub">{isProductAdmin ? 'Product admin' : companyName}</div>
          </div>
        </div>
        <nav className="nav" aria-label={t('Main')}>
          {(isProductAdmin ? PRODUCT_NAVIGATION : NAVIGATION).map((group) => {
            const items = group.items.filter((item) => can(item.module) && (!item.serves || item.serves.some(enabled)));
            if (items.length === 0) return null;
            return (
              <div key={group.label}>
                <div className="nav-group">{t(group.label)}</div>
                {items.map((item) => ({ ...item, label: t(item.term ? term(labels, item.term).plural : item.label) })).map((item) => (
                  <NavLink key={item.path} to={item.path} end={item.path === '/'} title={item.label}
                    className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
                    <item.icon size={18} />
                    <span className="nav-label">{item.label}</span>
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>
        <div className="sidebar-foot">
          <IconButton icon={collapsed ? PanelLeftOpen : PanelLeftClose} label={collapsed ? 'Expand menu' : 'Collapse menu'} onClick={toggleSidebar} />
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="grow">
            <strong>{isProductAdmin ? 'Product administration' : client?.name ?? companyName}</strong>
          </div>
          {/* A per-session switch; the saved default lives in Settings > Theme. */}
          <LanguageSwitcher />
          <IconButton icon={dark ? Sun : Moon} label={dark ? 'Switch to light mode' : 'Switch to dark mode'} onClick={() => preview({ ...theme, mode: dark ? 'light' : 'dark' })} />
          <div className="row" style={{ gap: 10 }}>
            <div className="avatar">{initials}</div>
            <div style={{ lineHeight: 1.2 }}>
              <div style={{ fontWeight: 600 }}>{user?.fullName}</div>
              <div className="muted" style={{ fontSize: 12 }}>{humanize(user?.role)}</div>
            </div>
          </div>
          <IconButton icon={LogOut} label={t('Sign out')} onClick={signOut} />
        </header>
        {impersonatedBy && (
          // Always visible, so it is never unclear whose account is being used.
          <div className="support-bar" role="status">
            <LifeBuoy size={18} />
            <span className="grow">{t('Support session: you are signed in as {0} ({1}) at {2}. Changes are recorded with your name.', { 0: user?.fullName, 1: humanize(user?.role), 2: client?.name })}</span>
            <Button small onClick={async () => { await endSupport(); navigate('/platform/clients'); }}>{t('End support session')}</Button>
          </div>
        )}
        <main className="content"><Outlet /></main>
      </div>
    </div>
  );
}
