const memState = {
  plans: [],
  currentMembership: null,
  upgradeRequests: [],
  subscriptions: []
};

async function loadMembershipPage() {
  try {
    const [plansRes, upgradesRes, subsRes] = await Promise.all([
      memberApi('member/plans'),
      memberApi('member/upgrade-requests'),
      memberApi('member/subscriptions')
    ]);
    memState.plans = plansRes.plans || [];
    memState.currentMembership = plansRes.currentMembership || null;
    memState.upgradeRequests = upgradesRes || [];
    memState.subscriptions = subsRes || [];

    renderCurrentStrip();
    renderPlansGrid();
    renderUpgradeRequests();
    renderSubscriptions();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function renderCurrentStrip() {
  const m = memState.currentMembership;
  const el = $('#current-membership-strip');
  if (!m) {
    el.innerHTML = `
      <div class="banner banner-info">
        <div>
          <h3>No Active Subscription</h3>
          <p>Select any plan below and complete checkout in Razorpay Test Mode to start your membership.</p>
        </div>
      </div>`;
    return;
  }
  const cls = m.status === 'EXPIRED' ? 'banner-danger'
    : (Number(m.effective_days_remaining) <= 7 ? 'banner-warn' : 'banner-success');
  el.innerHTML = `
    <div class="banner ${cls}">
      <div>
        <h3>Current Plan: ${esc(m.plan_name)} (${esc(m.membership_type || 'Standard')}) ${statusBadge(m.status)}</h3>
        <p>Valid from <b>${fmtDate(m.start_date)}</b> to <b>${fmtDate(m.end_date)}</b> · ${m.days_remaining} day(s) remaining · Status: <b>${esc(m.renewal_status)}</b></p>
      </div>
      <button type="button" class="btn btn-lime btn-sm" id="strip-renew-btn">Renew Current Plan</button>
    </div>`;
  $('#strip-renew-btn')?.addEventListener('click', () => openRenewModal(m.plan_id));
}

function renderPlansGrid() {
  const grid = $('#plans-grid');
  if (!memState.plans.length) {
    grid.innerHTML = `<div class="m-card" style="grid-column:1/-1;text-align:center;color:var(--text-muted)">No membership plans available yet.</div>`;
    return;
  }
  const curPlanId = memState.currentMembership?.plan_id;
  const curActive = memState.currentMembership && ['ACTIVE', 'UPCOMING'].includes(memState.currentMembership.status);

  grid.innerHTML = memState.plans.map(p => {
    const isCurrent = curActive && String(p.plan_id) === String(curPlanId);
    const typeLabel = p.membership_type || 'Standard';
    const benefits = String(p.benefits || p.description || 'Full gym floor access')
      .split(/[,;\n]+/)
      .map(s => s.trim())
      .filter(Boolean);

    return `
      <article class="plan-card ${isCurrent ? 'current-plan' : ''}">
        <div class="plan-head">
          <span class="plan-duration">${p.duration_months} ${p.duration_months === 1 ? 'MONTH' : 'MONTHS'}</span>
          ${statusBadge(typeLabel, typeLabel)}
        </div>
        <h3>${esc(p.plan_name)} ${isCurrent ? '<span class="badge active" style="margin-left:4px">CURRENT</span>' : ''}</h3>
        <div class="plan-price">${money(p.price)} <small>/ ${p.duration_months} mo</small></div>
        <p class="plan-desc">${esc(p.description || 'Complete access to Forge Gym facilities.')}</p>
        <ul class="plan-benefits">
          ${benefits.map(b => `<li>${esc(b)}</li>`).join('')}
        </ul>
        <div class="plan-actions">
          <button type="button" class="btn btn-lime" data-renew-plan="${p.plan_id}">
            ${isCurrent ? '↻ Renew This Plan' : 'Choose & Pay Online'}
          </button>
          ${!isCurrent ? `<button type="button" class="btn btn-ghost" data-upgrade-plan="${p.plan_id}">⬆ Request Upgrade</button>` : ''}
        </div>
      </article>`;
  }).join('');

  $$('[data-renew-plan]', grid).forEach(btn => {
    btn.addEventListener('click', () => openRenewModal(btn.dataset.renewPlan));
  });
  $$('[data-upgrade-plan]', grid).forEach(btn => {
    btn.addEventListener('click', () => openUpgradeModal(btn.dataset.upgradePlan));
  });
}

function renderUpgradeRequests() {
  const wrap = $('#upgrade-requests-table');
  const rows = memState.upgradeRequests;
  if (!rows.length) {
    wrap.innerHTML = `<table><tbody><tr><td class="empty-cell">You have not submitted any upgrade requests yet.</td></tr></tbody></table>`;
    return;
  }
  wrap.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Request ID</th>
          <th>Current Plan</th>
          <th>Requested Plan</th>
          <th>Payable Amount</th>
          <th>Status</th>
          <th>Admin Notes</th>
          <th>Submitted</th>
          <th>Action</th>
        </tr>
      </thead>
      <tbody>
        ${rows.map(r => {
          let actionHtml = '—';
          if (r.status === 'APPROVED') {
            actionHtml = `<button type="button" class="btn btn-lime btn-sm" data-pay-upgrade="${r.request_id}">Pay ${money(r.price_difference)} via Razorpay</button>`;
          } else if (r.status === 'PENDING') {
            actionHtml = `<button type="button" class="btn btn-danger btn-sm" data-cancel-upgrade="${r.request_id}">Cancel</button>`;
          }
          return `
            <tr>
              <td>#UPG-${r.request_id}</td>
              <td>${esc(r.current_plan_name || 'None')}</td>
              <td><b>${esc(r.requested_plan_name)}</b> (${r.requested_duration_months} mo)</td>
              <td><b>${money(r.price_difference)}</b></td>
              <td>${statusBadge(r.status)}</td>
              <td>${esc(r.admin_notes || r.reason || '—')}</td>
              <td>${fmtDate(r.created_at)}</td>
              <td>${actionHtml}</td>
            </tr>`;
        }).join('')}
      </tbody>
    </table>`;

  $$('[data-pay-upgrade]', wrap).forEach(btn => {
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      try {
        await launchMemberRazorpay({
          purpose: 'UPGRADE',
          upgrade_request_id: btn.dataset.payUpgrade,
          onSuccess: () => loadMembershipPage(),
          onFailure: () => loadMembershipPage()
        });
      } catch (err) { toast(err.message, 'error'); }
      finally { btn.disabled = false; }
    });
  });

  $$('[data-cancel-upgrade]', wrap).forEach(btn => {
    btn.addEventListener('click', async () => {
      if (!confirm('Cancel this pending upgrade request?')) return;
      try {
        await memberApi(`member/upgrade-requests?id=${btn.dataset.cancelUpgrade}`, { method: 'DELETE' });
        toast('Upgrade request cancelled.');
        await loadMembershipPage();
      } catch (err) { toast(err.message, 'error'); }
    });
  });
}

function renderSubscriptions() {
  const wrap = $('#subscriptions-table');
  const rows = memState.subscriptions;
  if (!rows.length) {
    wrap.innerHTML = `<table><tbody><tr><td class="empty-cell">No subscription history found.</td></tr></tbody></table>`;
    return;
  }
  wrap.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Membership Plan</th>
          <th>Type</th>
          <th>Start Date</th>
          <th>Expiry Date</th>
          <th>Amount Paid</th>
          <th>Status</th>
          <th>Renewal Date</th>
        </tr>
      </thead>
      <tbody>
        ${rows.map(r => `
          <tr>
            <td><b>${esc(r.plan_name)}</b> (${r.duration_months} mo)</td>
            <td>${statusBadge(r.membership_type || 'Standard', r.membership_type || 'Standard')}</td>
            <td>${fmtDate(r.start_date)}</td>
            <td>${fmtDate(r.end_date)}</td>
            <td><b>${money(r.amount_paid)}</b></td>
            <td>${statusBadge(r.status)}</td>
            <td>${fmtDate(r.renewal_date)}</td>
          </tr>`).join('')}
      </tbody>
    </table>`;
}

function updateRenewSummary() {
  const sel = $('#renew-plan-select');
  const plan = memState.plans.find(p => String(p.plan_id) === String(sel.value));
  if (!plan) return;
  $('#renew-duration').textContent = `${plan.duration_months} ${plan.duration_months === 1 ? 'Month' : 'Months'} (${plan.membership_type || 'Standard'})`;
  $('#renew-amount').textContent = money(plan.price);
}

function openRenewModal(preselectPlanId = null) {
  if (!memState.plans.length) { toast('No active plans available.', 'warn'); return; }
  const sel = $('#renew-plan-select');
  sel.innerHTML = memState.plans.map(p =>
    `<option value="${p.plan_id}">${esc(p.plan_name)} (${p.duration_months} mo) — ${money(p.price)}</option>`
  ).join('');
  if (preselectPlanId) sel.value = String(preselectPlanId);
  else if (memState.currentMembership?.plan_id) sel.value = String(memState.currentMembership.plan_id);
  updateRenewSummary();
  $('#renew-dialog').showModal();
}

function updateUpgradeEstimate() {
  const sel = $('#upgrade-plan-select');
  const target = memState.plans.find(p => String(p.plan_id) === String(sel.value));
  const cur = memState.currentMembership;
  const curActive = cur && ['ACTIVE', 'UPCOMING'].includes(cur.status);
  $('#upg-current-plan').textContent = curActive ? `${cur.plan_name} (${money(cur.price)})` : 'No active plan';
  if (!target) { $('#upg-payable').textContent = '—'; return; }
  let payable = Number(target.price);
  if (curActive) {
    const diff = Number(target.price) - Number(cur.price || 0);
    if (diff >= 1) payable = diff;
  }
  $('#upg-payable').textContent = money(payable);
}

function openUpgradeModal(preselectPlanId = null) {
  const curId = memState.currentMembership && ['ACTIVE', 'UPCOMING'].includes(memState.currentMembership.status)
    ? String(memState.currentMembership.plan_id) : null;
  const candidates = memState.plans.filter(p => String(p.plan_id) !== curId);
  if (!candidates.length) { toast('No other plans available for upgrade.', 'warn'); return; }
  const sel = $('#upgrade-plan-select');
  sel.innerHTML = candidates.map(p =>
    `<option value="${p.plan_id}">${esc(p.plan_name)} (${esc(p.membership_type || 'Standard')} · ${p.duration_months} mo) — ${money(p.price)}</option>`
  ).join('');
  if (preselectPlanId) sel.value = String(preselectPlanId);
  $('#upgrade-reason').value = '';
  updateUpgradeEstimate();
  $('#upgrade-dialog').showModal();
}

document.addEventListener('DOMContentLoaded', async () => {
  const me = await initMemberPortal('membership', 'Membership Plans');
  if (!me) return;
  await loadMembershipPage();

  $('#open-renew-btn')?.addEventListener('click', () => openRenewModal());
  $('#open-upgrade-btn')?.addEventListener('click', () => openUpgradeModal());
  $('#open-upgrade-btn-2')?.addEventListener('click', () => openUpgradeModal());
  $('#renew-plan-select')?.addEventListener('change', updateRenewSummary);
  $('#upgrade-plan-select')?.addEventListener('change', updateUpgradeEstimate);

  $$('[data-close-dialog]').forEach(btn => {
    btn.addEventListener('click', () => $(`#${btn.dataset.closeDialog}`)?.close());
  });

  $('#renew-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    const planId = $('#renew-plan-select').value;
    const submitBtn = $('#renew-submit-btn');
    submitBtn.disabled = true;
    submitBtn.textContent = 'Creating Razorpay Order...';
    try {
      $('#renew-dialog').close();
      await launchMemberRazorpay({
        purpose: 'RENEWAL',
        plan_id: planId,
        onSuccess: () => loadMembershipPage(),
        onFailure: () => loadMembershipPage()
      });
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = 'Proceed to Razorpay →';
    }
  });

  $('#upgrade-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = Object.fromEntries(new FormData(form).entries());
    const btn = $('#upgrade-submit-btn');
    btn.disabled = true;
    try {
      await memberApi('member/upgrade-requests', formRequest('POST', data));
      $('#upgrade-dialog').close();
      toast('Upgrade request submitted! You will be notified once an admin reviews it.');
      await Promise.all([loadMembershipPage(), loadNotifications()]);
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      btn.disabled = false;
    }
  });

  // Auto-open renew modal if ?action=renew
  const qs = new URLSearchParams(window.location.search);
  if (qs.get('action') === 'renew') openRenewModal();
});
