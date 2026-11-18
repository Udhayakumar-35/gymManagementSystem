const payState = {
  filter: 'ALL',
  allRows: [],
  filteredRows: []
};

async function loadPaymentsPage() {
  try {
    const [allRows, filteredRows] = await Promise.all([
      memberApi('member/payments?status=ALL'),
      payState.filter === 'ALL'
        ? Promise.resolve(null)
        : memberApi(`member/payments?status=${encodeURIComponent(payState.filter)}`)
    ]);
    payState.allRows = allRows || [];
    payState.filteredRows = filteredRows === null ? payState.allRows : (filteredRows || []);

    renderSummaryCards();
    renderPendingPayments();
    renderTransactionsTable();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function renderSummaryCards() {
  const paidRows = payState.allRows.filter(r => r.status === 'PAID');
  const pendingRows = payState.allRows.filter(r => r.status === 'PENDING');
  const failedRows = payState.allRows.filter(r => r.status === 'FAILED');

  const totalPaid = paidRows.reduce((acc, r) => acc + Number(r.amount || 0), 0);
  const totalPending = pendingRows.reduce((acc, r) => acc + Number(r.amount || 0), 0);

  $('#payment-summary-cards').innerHTML = `
    <article class="m-card">
      <div class="m-stat-top"><span class="m-icon emerald">✓</span><span class="badge success">${paidRows.length} PAID</span></div>
      <p class="m-card-label">Total Successful Payments</p>
      <strong class="m-card-value">${money(totalPaid)}</strong>
      <span class="m-card-meta">Receipts available for all successful payments</span>
    </article>
    <article class="m-card">
      <div class="m-stat-top"><span class="m-icon amber">◷</span><span class="badge pending">${pendingRows.length} PENDING</span></div>
      <p class="m-card-label">Pending Membership Fees</p>
      <strong class="m-card-value">${money(totalPending)}</strong>
      <span class="m-card-meta">${pendingRows.length ? 'Pay online safely in Razorpay Test Mode' : 'No pending dues'}</span>
    </article>
    <article class="m-card">
      <div class="m-stat-top"><span class="m-icon rose">!</span><span class="badge failed">${failedRows.length} FAILED</span></div>
      <p class="m-card-label">Total Transactions</p>
      <strong class="m-card-value">${payState.allRows.length}</strong>
      <span class="m-card-meta">${failedRows.length} failed attempt(s) logged</span>
    </article>
  `;
}

function renderPendingPayments() {
  const section = $('#pending-payments-section');
  const pending = payState.allRows.filter(r => r.status === 'PENDING');
  if (!pending.length) {
    section.classList.add('hidden');
    section.innerHTML = '';
    return;
  }
  section.classList.remove('hidden');
  section.innerHTML = pending.map(p => `
    <div class="banner banner-warn">
      <div>
        <h3>Pending Payment #${p.payment_id} — ${esc(p.plan_name || 'Gym Membership')} (${money(p.amount)})</h3>
        <p>${esc(p.notes || 'Pending membership fee')} · Created ${fmtDateTime(p.payment_date)}${p.razorpay_order_id ? ` · Order ${esc(p.razorpay_order_id)}` : ''}</p>
      </div>
      <button type="button" class="btn btn-lime" data-pay-pending="${p.payment_id}">Pay Now (${money(p.amount)})</button>
    </div>
  `).join('');

  $$('[data-pay-pending]', section).forEach(btn => {
    btn.addEventListener('click', () => handlePayPending(btn));
  });
}

async function handlePayPending(btn) {
  const paymentId = btn.dataset.payPending;
  btn.disabled = true;
  try {
    await launchMemberRazorpay({
      purpose: 'PENDING_PAYMENT',
      payment_id: paymentId,
      onSuccess: () => loadPaymentsPage(),
      onFailure: () => loadPaymentsPage()
    });
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    btn.disabled = false;
  }
}

function renderTransactionsTable() {
  const wrap = $('#payments-table-wrap');
  const rows = payState.filteredRows;
  if (!rows.length) {
    wrap.innerHTML = `<table><tbody><tr><td class="empty-cell">No transactions match this filter.</td></tr></tbody></table>`;
    return;
  }
  wrap.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Transaction ID</th>
          <th>Membership Plan</th>
          <th>Amount</th>
          <th>Payment Date</th>
          <th>Method</th>
          <th>Status</th>
          <th>Razorpay Payment ID</th>
          <th>Action</th>
        </tr>
      </thead>
      <tbody>
        ${rows.map(r => {
          const statusLabel = r.status === 'PAID' ? 'Success' : (r.status === 'FAILED' ? 'Failed' : (r.status === 'PENDING' ? 'Pending' : r.status));
          let action = '—';
          if (r.status === 'PAID') {
            action = `<button type="button" class="btn btn-emerald btn-sm" data-download-receipt="${r.payment_id}">⬇ Download Receipt</button>`;
          } else if (r.status === 'PENDING') {
            action = `<button type="button" class="btn btn-lime btn-sm" data-pay-pending="${r.payment_id}">Pay Now</button>`;
          }
          const gwId = r.razorpay_payment_id
            ? `<code>${esc(r.razorpay_payment_id)}</code>`
            : (r.razorpay_order_id ? `<small style="color:var(--text-muted)">${esc(r.razorpay_order_id)}</small>` : '—');
          return `
            <tr>
              <td><b>#TXN-${r.payment_id}</b></td>
              <td>${esc(r.plan_name || 'Gym Membership')}${r.failure_reason ? `<br><small style="color:var(--rose)">${esc(r.failure_reason)}</small>` : ''}</td>
              <td><b>${money(r.amount)}</b></td>
              <td>${fmtDateTime(r.payment_date)}</td>
              <td>${esc(r.payment_method === 'RAZORPAY' ? 'Razorpay (UPI/Test)' : r.payment_method)}</td>
              <td>${statusBadge(r.status, statusLabel)}</td>
              <td>${gwId}</td>
              <td>${action}</td>
            </tr>`;
        }).join('')}
      </tbody>
    </table>`;

  $$('[data-download-receipt]', wrap).forEach(btn => {
    btn.addEventListener('click', () => downloadReceipt(btn.dataset.downloadReceipt));
  });
  $$('[data-pay-pending]', wrap).forEach(btn => {
    btn.addEventListener('click', () => handlePayPending(btn));
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  const me = await initMemberPortal('payments', 'Payments & Receipts');
  if (!me) return;
  await loadPaymentsPage();

  $$('#status-filters .pill').forEach(pill => {
    pill.addEventListener('click', async () => {
      $$('#status-filters .pill').forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      payState.filter = pill.dataset.filter;
      await loadPaymentsPage();
    });
  });
});
