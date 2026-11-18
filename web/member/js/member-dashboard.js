async function loadMemberDashboard() {
  try {
    const d = await memberApi('member/dashboard');
    updateNotifBadge(d.unreadNotifications);

    const firstName = (d.member?.full_name || 'Member').split(/\s+/)[0];
    $('#welcome-heading').textContent = `Welcome back, ${firstName}.`;
    $('#welcome-sub').textContent = `Member #${d.member.member_id} · Joined ${fmtDate(d.member.joining_date)}`;

    // 1. Dynamic Renewal Reminder Banner
    const rem = d.renewalReminder;
    const bannerEl = $('#renewal-banner');
    if (rem && ['EXPIRED', 'URGENT', 'UPCOMING', 'PENDING', 'NONE'].includes(rem.level)) {
      const cls = rem.level === 'EXPIRED' ? 'banner-danger'
        : (rem.level === 'URGENT' || rem.level === 'PENDING' ? 'banner-warn' : 'banner-info');
      const ctaHref = rem.level === 'PENDING' ? '/member/payments.html' : '/member/membership.html?action=renew';
      const ctaText = rem.level === 'PENDING' ? 'Complete Payment →' : (rem.level === 'NONE' ? 'Browse Plans →' : 'Renew Now →');
      bannerEl.innerHTML = `
        <div class="banner ${cls}">
          <div>
            <h3>${esc(rem.title)}</h3>
            <p>${esc(rem.message)}</p>
          </div>
          <a class="btn btn-lime" href="${ctaHref}">${ctaText}</a>
        </div>`;
    } else {
      bannerEl.innerHTML = '';
    }

    // 2. Active Upgrade Request Banner
    const upg = d.activeUpgradeRequest;
    const upgEl = $('#upgrade-banner');
    if (upg) {
      if (upg.status === 'APPROVED') {
        upgEl.innerHTML = `
          <div class="banner banner-success">
            <div>
              <h3>Upgrade Approved: ${esc(upg.requested_plan_name)}</h3>
              <p>Admin approved your upgrade request! Complete payment of <b>${money(upg.price_difference)}</b> via Razorpay to activate your upgraded plan.${upg.admin_notes ? ` Note: ${esc(upg.admin_notes)}` : ''}</p>
            </div>
            <button type="button" class="btn btn-lime" id="dash-pay-upgrade" data-req-id="${upg.request_id}">Pay ${money(upg.price_difference)} &amp; Upgrade</button>
          </div>`;
        $('#dash-pay-upgrade')?.addEventListener('click', async btn => {
          btn.currentTarget.disabled = true;
          try {
            await launchMemberRazorpay({
              purpose: 'UPGRADE',
              upgrade_request_id: upg.request_id,
              onSuccess: () => loadMemberDashboard(),
              onFailure: () => loadMemberDashboard()
            });
          } catch (e) { toast(e.message, 'error'); }
          finally { btn.currentTarget.disabled = false; }
        });
      } else {
        upgEl.innerHTML = `
          <div class="banner banner-info">
            <div>
              <h3>Upgrade Request Pending Approval</h3>
              <p>Your request to upgrade to <b>${esc(upg.requested_plan_name)}</b> (${money(upg.price_difference)}) was submitted on ${fmtDate(upg.created_at)} and is awaiting admin review.</p>
            </div>
            <a class="btn btn-ghost btn-sm" href="/member/membership.html#upgrades">View Status</a>
          </div>`;
      }
    } else {
      upgEl.innerHTML = '';
    }

    // 3. Top Stat Cards
    const m = d.currentMembership;
    const att = d.attendance;
    const pay = d.paymentOverview;

    const planName = m ? m.plan_name : 'No Plan';
    const memStatus = m ? m.status : 'EXPIRED';
    const daysRem = m && m.days_remaining !== undefined ? `${m.days_remaining} days` : '—';
    const lastPayVal = pay.lastPaymentAmount !== null ? money(pay.lastPaymentAmount) : '—';

    $('#dashboard-stats').innerHTML = `
      <article class="m-card m-card-hero">
        <div class="m-stat-top"><span class="m-icon lime">◇</span>${statusBadge(memStatus)}</div>
        <p class="m-card-label">Current Membership</p>
        <strong class="m-card-value">${esc(planName)}</strong>
        <span class="m-card-meta">${m?.end_date ? `Expires ${fmtDate(m.end_date)}` : 'Choose a plan to start'}</span>
      </article>
      <article class="m-card">
        <div class="m-stat-top"><span class="m-icon emerald">◷</span><span class="badge">${esc(m?.renewal_status || 'NONE')}</span></div>
        <p class="m-card-label">Days Remaining</p>
        <strong class="m-card-value">${esc(daysRem)}</strong>
        <span class="m-card-meta">${m?.start_date ? `Started ${fmtDate(m.start_date)}` : 'No active validity'}</span>
      </article>
      <article class="m-card">
        <div class="m-stat-top"><span class="m-icon sky">⚡</span><span class="badge">THIS MONTH</span></div>
        <p class="m-card-label">Monthly Check-ins</p>
        <strong class="m-card-value">${att.currentMonthCount ?? 0}</strong>
        <span class="m-card-meta">${att.totalCount ?? 0} total lifetime visits</span>
      </article>
      <article class="m-card">
        <div class="m-stat-top"><span class="m-icon ${Number(pay.pendingCount) > 0 ? 'amber' : 'lime'}">₹</span>${pay.lastPaymentStatus ? statusBadge(pay.lastPaymentStatus) : '<span class="badge">NONE</span>'}</div>
        <p class="m-card-label">Last Payment</p>
        <strong class="m-card-value">${lastPayVal}</strong>
        <span class="m-card-meta">${Number(pay.pendingCount) > 0 ? `${pay.pendingCount} pending (${money(pay.pendingTotal)})` : `Total paid: ${money(pay.totalPaid)}`}</span>
      </article>
    `;

    // 4. Membership Overview Detail
    if (m && m.start_date) {
      const totalDays = Math.max(1, Number(m.total_days || 30));
      const remDays = Math.max(0, Number(m.days_remaining || 0));
      const pct = Math.min(100, Math.max(0, Math.round((remDays / totalDays) * 100)));
      $('#membership-overview').innerHTML = `
        <div class="info-kv">
          <div><small>Plan Name</small><b>${esc(m.plan_name)} (${esc(m.membership_type || 'Standard')})</b></div>
          <div><small>Status</small><div>${statusBadge(m.status)}</div></div>
          <div><small>Start Date</small><b>${fmtDate(m.start_date)}</b></div>
          <div><small>Expiry Date</small><b>${fmtDate(m.end_date)}</b></div>
          <div><small>Days Remaining</small><b>${remDays} day(s)</b></div>
          <div><small>Renewal Status</small><div>${statusBadge(m.renewal_status)}</div></div>
        </div>
        <div style="margin-top:16px">
          <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--text-soft)">
            <span>Validity Remaining</span><span>${pct}%</span>
          </div>
          <div class="progress-track"><div class="progress-fill" style="width:${pct}%"></div></div>
        </div>
      `;
    } else {
      $('#membership-overview').innerHTML = `
        <div style="padding:20px 0;color:var(--text-soft)">
          <p style="margin:0 0 14px">You do not have an active gym membership yet.</p>
          <a class="btn btn-lime" href="/member/membership.html">Explore Plans &amp; Join</a>
        </div>`;
    }

    // 5. Attendance Overview + Chart
    $('#last-checkin-pill').textContent = att.lastCheckInDate
      ? `Last visit: ${fmtDate(att.lastCheckInDate)}`
      : 'No visits recorded yet';

    const trend = att.monthlyTrend || [];
    const maxV = Math.max(1, ...trend.map(r => Number(r.visits || 0)));
    const chartHtml = trend.length
      ? `<div class="chart-bars">${trend.map(r => {
          const [y, mo] = String(r.month).split('-');
          const label = new Date(Number(y), Number(mo) - 1).toLocaleDateString('en-IN', { month: 'short' });
          const h = Math.max(10, Math.round((Number(r.visits) / maxV) * 125));
          return `<div class="chart-col"><div class="chart-bar" style="height:${h}px"><span>${r.visits}</span></div><small>${esc(label)}</small></div>`;
        }).join('')}</div>`
      : `<div style="padding:36px 0;text-align:center;color:var(--text-muted)">Check in at the gym desk to see your monthly attendance chart here.</div>`;

    $('#attendance-overview').innerHTML = `
      <div class="info-kv" style="margin-bottom:14px">
        <div><small>This Month</small><b>${att.currentMonthCount ?? 0} check-ins</b></div>
        <div><small>Last Check-in Date</small><b>${fmtDate(att.lastCheckInDate)}</b></div>
      </div>
      ${chartHtml}
    `;

    // 6. Payment Overview
    $('#payment-overview').innerHTML = `
      <div class="info-kv">
        <div><small>Last Payment Amount</small><b>${pay.lastPaymentAmount !== null ? money(pay.lastPaymentAmount) : 'No payments yet'}</b></div>
        <div><small>Last Payment Date</small><b>${fmtDateTime(pay.lastPaymentDate)}</b></div>
        <div><small>Last Payment Status</small><div>${pay.lastPaymentStatus ? statusBadge(pay.lastPaymentStatus) : '—'}</div></div>
        <div><small>Pending Membership Fees</small><b>${Number(pay.pendingCount) > 0 ? `${money(pay.pendingTotal)} (${pay.pendingCount} pending)` : 'None — All clear'}</b></div>
      </div>
      ${Number(pay.pendingCount) > 0 ? `<div style="margin-top:16px"><a class="btn btn-lime btn-sm" href="/member/payments.html">Pay Pending Fees Now →</a></div>` : ''}
    `;
  } catch (err) {
    toast(err.message, 'error');
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  const me = await initMemberPortal('dashboard', 'Dashboard');
  if (me) await loadMemberDashboard();
});
