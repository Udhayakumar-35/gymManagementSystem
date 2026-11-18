/* Shared Member Portal Runtime — Forge Gym Management System */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const MemberPortal = {
  user: null,
  notifications: [],
  unreadCount: 0
};

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c]);
}

function money(v) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: 2
  }).format(Number(v || 0));
}

function fmtDate(v) {
  if (!v) return '—';
  const d = new Date(`${String(v).slice(0, 10)}T00:00:00`);
  return Number.isNaN(+d) ? String(v) : d.toLocaleDateString('en-IN', {
    day: 'numeric', month: 'short', year: 'numeric'
  });
}

function fmtDateTime(v) {
  if (!v) return '—';
  const d = new Date(String(v).replace(' ', 'T'));
  return Number.isNaN(+d) ? String(v) : d.toLocaleString('en-IN', {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
  });
}

function statusBadge(status, labelOverride = null) {
  const raw = String(status ?? '—');
  const label = labelOverride || (raw === 'PAID' ? 'Success' : raw);
  const cls = raw.toLowerCase().replaceAll(' ', '-').replaceAll('_', '-');
  return `<span class="badge ${esc(cls)}">${esc(label)}</span>`;
}

function toast(message, kind = 'ok') {
  let el = $('#m-toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'm-toast';
    el.className = 'm-toast';
    el.setAttribute('role', 'status');
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.classList.remove('error', 'warn');
  if (kind === true || kind === 'error') el.classList.add('error');
  else if (kind === 'warn') el.classList.add('warn');
  el.classList.add('show');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.remove('show'), 4000);
}

function formBody(values) {
  return new URLSearchParams(
    Object.entries(values).filter(([, v]) => v !== undefined && v !== null)
  ).toString();
}

function formRequest(method, values = {}) {
  return {
    method,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: formBody(values)
  };
}

async function memberApi(path, options = {}) {
  const response = await fetch(`/api/${path}`, { credentials: 'same-origin', ...options });
  const data = await response.json().catch(() => ({}));
  if (response.status === 401) {
    window.location.href = '/';
    throw new Error(data.error || 'Session expired. Please sign in again.');
  }
  if (!response.ok) {
    throw new Error(data.error || 'Request failed.');
  }
  return data;
}

function renderShell(activeKey, pageTitle) {
  const navItems = [
    { key: 'dashboard', label: 'Dashboard', icon: '◫', href: '/member/member-dashboard.html' },
    { key: 'membership', label: 'Membership Plans', icon: '◇', href: '/member/membership.html' },
    { key: 'payments', label: 'Payments & Receipts', icon: '₹', href: '/member/payments.html' },
    { key: 'profile', label: 'My Profile', icon: '♙', href: '/member/profile.html' }
  ];

  const name = MemberPortal.user?.fullName || MemberPortal.user?.username || 'Member';
  const initials = name.split(/\s+/).map(p => p[0]).join('').slice(0, 2).toUpperCase() || 'M';

  const sidebar = $('#m-sidebar');
  if (sidebar) {
    sidebar.innerHTML = `
      <a class="m-brand" href="/member/member-dashboard.html">
        <span class="m-brand-mark">F</span>
        <span class="m-brand-text"><strong>FORGE GYM</strong><small>MEMBER PORTAL</small></span>
      </a>
      <div class="m-nav-label">MY FITNESS</div>
      <nav class="m-nav">
        ${navItems.map(n => `
          <a class="m-nav-link ${n.key === activeKey ? 'active' : ''}" href="${n.href}">
            <span class="ico">${n.icon}</span><span>${esc(n.label)}</span>
          </a>`).join('')}
      </nav>
      <div class="m-sidebar-footer">
        <div class="m-member-pill">
          <div class="m-avatar">${esc(initials)}</div>
          <div>
            <b>${esc(name)}</b>
            <small>@${esc(MemberPortal.user?.username || '')} · MEMBER</small>
          </div>
        </div>
        <button type="button" class="m-logout-btn" id="m-logout-btn"><span>↪</span> Sign out</button>
      </div>
    `;
  }

  const topbar = $('#m-topbar');
  if (topbar) {
    const todayStr = new Date().toLocaleDateString('en-IN', {
      weekday: 'short', day: 'numeric', month: 'short', year: 'numeric'
    });
    topbar.innerHTML = `
      <div class="m-topbar-left">
        <button type="button" class="m-menu-toggle" id="m-menu-toggle" aria-label="Toggle navigation">☰</button>
        <div class="m-crumb">Member Portal <span>/</span> <b>${esc(pageTitle)}</b></div>
      </div>
      <div class="m-topbar-right">
        <span class="m-date">${esc(todayStr)}</span>
        <button type="button" class="notif-btn" id="notif-toggle" aria-label="Notifications">
          🔔
          <span class="notif-count hidden" id="notif-badge">0</span>
        </button>
        <div class="notif-panel hidden" id="notif-panel">
          <div class="notif-header">
            <strong>Notifications</strong>
            <button type="button" class="btn-link" id="notif-mark-all">Mark all read</button>
          </div>
          <div class="notif-list" id="notif-list">
            <div class="loading-box"><span class="spinner"></span> Loading...</div>
          </div>
        </div>
      </div>
    `;
  }

  // Mobile sidebar backdrop
  let backdrop = $('#m-backdrop');
  if (!backdrop) {
    backdrop = document.createElement('div');
    backdrop.id = 'm-backdrop';
    backdrop.className = 'm-backdrop';
    document.body.appendChild(backdrop);
  }

  $('#m-menu-toggle')?.addEventListener('click', () => {
    $('#m-sidebar')?.classList.toggle('open');
    backdrop.classList.toggle('show');
  });
  backdrop.addEventListener('click', () => {
    $('#m-sidebar')?.classList.remove('open');
    backdrop.classList.remove('show');
  });

  $('#m-logout-btn')?.addEventListener('click', async () => {
    try { await memberApi('logout', formRequest('POST')); }
    finally { window.location.href = '/'; }
  });

  // Notification dropdown toggle
  const toggleBtn = $('#notif-toggle');
  const panel = $('#notif-panel');
  toggleBtn?.addEventListener('click', async e => {
    e.stopPropagation();
    const opening = panel.classList.contains('hidden');
    panel.classList.toggle('hidden');
    if (opening) await loadNotifications();
  });
  document.addEventListener('click', e => {
    if (panel && !panel.classList.contains('hidden') && !panel.contains(e.target) && e.target !== toggleBtn) {
      panel.classList.add('hidden');
    }
  });
  $('#notif-mark-all')?.addEventListener('click', async () => {
    try {
      await memberApi('member/notifications?all=true', { method: 'PUT' });
      await loadNotifications();
      toast('All notifications marked as read.');
    } catch (e) { toast(e.message, 'error'); }
  });
}

function updateNotifBadge(count) {
  MemberPortal.unreadCount = Number(count || 0);
  const badge = $('#notif-badge');
  if (!badge) return;
  if (MemberPortal.unreadCount > 0) {
    badge.textContent = MemberPortal.unreadCount > 99 ? '99+' : String(MemberPortal.unreadCount);
    badge.classList.remove('hidden');
  } else {
    badge.classList.add('hidden');
  }
}

async function loadNotifications() {
  try {
    const data = await memberApi('member/notifications');
    MemberPortal.notifications = data.notifications || [];
    updateNotifBadge(data.unreadCount);
    const list = $('#notif-list');
    if (!list) return;
    if (!MemberPortal.notifications.length) {
      list.innerHTML = `<div class="loading-box" style="color:var(--text-muted)">No notifications yet.</div>`;
      return;
    }
    list.innerHTML = MemberPortal.notifications.map(n => `
      <div class="notif-item ${n.is_read ? '' : 'unread'}" data-notif-id="${n.notification_id}">
        <span class="notif-dot"></span>
        <div class="notif-body">
          <b>${esc(n.title)}</b>
          <p>${esc(n.message)}</p>
          <small>${fmtDateTime(n.created_at)}</small>
        </div>
      </div>
    `).join('');

    $$('.notif-item', list).forEach(item => {
      item.addEventListener('click', async () => {
        if (!item.classList.contains('unread')) return;
        try {
          const res = await memberApi(`member/notifications?id=${item.dataset.notifId}`, { method: 'PUT' });
          item.classList.remove('unread');
          updateNotifBadge(res.unreadCount);
        } catch (err) { toast(err.message, 'error'); }
      });
    });
  } catch (e) {
    const list = $('#notif-list');
    if (list) list.innerHTML = `<div class="loading-box" style="color:var(--rose)">${esc(e.message)}</div>`;
  }
}

/** Auth & Role Guard — called on every Member Portal page load. */
async function initMemberPortal(activeKey, pageTitle) {
  try {
    const me = await memberApi('me');
    if (me.role !== 'MEMBER') {
      window.location.href = '/';
      return null;
    }
    MemberPortal.user = me;
    renderShell(activeKey, pageTitle);
    loadNotifications().catch(() => {});
    return me;
  } catch (e) {
    window.location.href = '/';
    return null;
  }
}

/** Launch Razorpay Test Mode Checkout for Member Renewal, Upgrade, or Pending Fee. */
async function launchMemberRazorpay({ purpose, plan_id, upgrade_request_id, payment_id, onSuccess, onFailure }) {
  if (typeof window.Razorpay !== 'function') {
    toast('Razorpay Checkout script did not load. Check your internet connection and reload.', 'error');
    return;
  }
  const payload = { purpose };
  if (plan_id) payload.plan_id = plan_id;
  if (upgrade_request_id) payload.upgrade_request_id = upgrade_request_id;
  if (payment_id) payload.payment_id = payment_id;

  const order = await memberApi('member/razorpay/order', formRequest('POST', payload));

  const options = {
    key: order.keyId,
    order_id: order.orderId,
    amount: String(order.amount),
    currency: order.currency,
    name: 'Forge Gym',
    description: order.description || 'Gym Membership Payment (Test Mode)',
    prefill: {
      name: order.name || '',
      email: order.email || '',
      contact: order.contact || ''
    },
    theme: { color: '#11192a' },
    handler: async response => {
      try {
        toast('Verifying payment with Razorpay...', 'ok');
        const verifyRes = await memberApi('member/razorpay/verify', formRequest('POST', response));
        toast(verifyRes.message || 'Payment verified!');
        await loadNotifications();
        if (typeof onSuccess === 'function') await onSuccess(verifyRes);
      } catch (err) {
        toast(err.message, 'error');
        if (typeof onFailure === 'function') await onFailure(err);
      }
    },
    modal: {
      ondismiss: () => {
        toast('Checkout closed. Payment remains pending until verified.', 'warn');
        if (typeof onFailure === 'function') onFailure(new Error('Checkout dismissed'));
      }
    }
  };

  const rzp = new window.Razorpay(options);
  rzp.on('payment.failed', async response => {
    const desc = response?.error?.description || 'Payment failed in Razorpay Checkout.';
    try {
      await memberApi('member/razorpay/fail', formRequest('POST', {
        order_id: order.orderId,
        reason: desc
      }));
    } catch (_) {}
    toast(`Payment failed: ${desc}`, 'error');
    await loadNotifications();
    if (typeof onFailure === 'function') await onFailure(new Error(desc));
  });
  rzp.open();
}

/** Generate and download a branded HTML Receipt for any PAID transaction. */
async function downloadReceipt(paymentId) {
  try {
    const r = await memberApi(`member/payments/receipt?id=${encodeURIComponent(paymentId)}`);
    const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Payment Receipt — ${esc(r.receiptNumber)}</title>
<style>
  body { font-family: 'Segoe UI', Arial, sans-serif; background: #f4f6fa; color: #111b2d; margin: 0; padding: 36px; }
  .receipt { max-width: 640px; margin: 0 auto; background: #fff; border-radius: 16px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 12px 30px rgba(17,27,45,0.08); }
  .head { background: #111b2d; color: #fff; padding: 28px 32px; display: flex; justify-content: space-between; align-items: center; }
  .brand { display: flex; align-items: center; gap: 12px; }
  .logo { width: 42px; height: 42px; border-radius: 11px; background: #c7f36b; color: #111b2d; font-weight: 900; font-size: 22px; display: grid; place-items: center; }
  .head h1 { margin: 0; font-size: 20px; letter-spacing: 1px; }
  .head small { color: #c7f36b; font-size: 11px; letter-spacing: 1.8px; }
  .badge { background: #dcfce7; color: #166534; padding: 6px 14px; border-radius: 99px; font-weight: 700; font-size: 12px; }
  .body { padding: 30px 32px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 24px; }
  .kv small { display: block; font-size: 10px; letter-spacing: 1px; color: #64748b; text-transform: uppercase; margin-bottom: 4px; }
  .kv b { font-size: 14px; }
  .amount-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 18px 22px; display: flex; justify-content: space-between; align-items: center; }
  .amount-box strong { font-size: 26px; color: #111b2d; }
  .foot { padding: 18px 32px; background: #f8fafc; border-top: 1px solid #e2e8f0; font-size: 11px; color: #64748b; display: flex; justify-content: space-between; }
  @media print { body { background: #fff; padding: 0; } .receipt { box-shadow: none; } }
</style>
</head>
<body>
  <div class="receipt">
    <div class="head">
      <div class="brand">
        <div class="logo">F</div>
        <div><h1>${esc(r.gymName)}</h1><small>OFFICIAL PAYMENT RECEIPT</small></div>
      </div>
      <span class="badge">${esc(r.paymentStatus)}</span>
    </div>
    <div class="body">
      <div class="grid">
        <div class="kv"><small>Receipt Number</small><b>${esc(r.receiptNumber)}</b></div>
        <div class="kv"><small>Transaction ID</small><b>#${esc(r.transactionId)}</b></div>
        <div class="kv"><small>Member Name</small><b>${esc(r.memberName)}</b></div>
        <div class="kv"><small>Payment Date</small><b>${esc(fmtDateTime(r.paymentDate))}</b></div>
        <div class="kv"><small>Membership Plan</small><b>${esc(r.membershipPlan)}</b></div>
        <div class="kv"><small>Payment Method</small><b>${esc(r.paymentMethod)}</b></div>
        ${r.razorpayPaymentId ? `<div class="kv"><small>Razorpay Payment ID</small><b>${esc(r.razorpayPaymentId)}</b></div>` : ''}
        ${r.startDate && r.endDate ? `<div class="kv"><small>Membership Period</small><b>${esc(fmtDate(r.startDate))} — ${esc(fmtDate(r.endDate))}</b></div>` : ''}
      </div>
      <div class="amount-box">
        <div><small style="color:#64748b;font-weight:700;letter-spacing:1px">TOTAL AMOUNT PAID</small><div style="font-size:12px;color:#475569;margin-top:3px">Status: ${esc(r.paymentStatus)}</div></div>
        <strong>${esc(money(r.amountPaid))}</strong>
      </div>
    </div>
    <div class="foot">
      <span>Forge Gym — Thank you for training with us!</span>
      <span>Generated on ${esc(new Date().toLocaleDateString('en-IN'))}</span>
    </div>
  </div>
</body>
</html>`;

    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `Forge-Gym-Receipt-${r.receiptNumber}.html`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
    toast(`Receipt ${r.receiptNumber} downloaded.`);
  } catch (err) {
    toast(err.message, 'error');
  }
}
