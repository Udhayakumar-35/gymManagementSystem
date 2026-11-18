const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const state = { page: 'dashboard', members: [], plans: [], memberships: [], editing: null, authMode: 'login' };
const pageNames = { dashboard: 'Overview', members: 'Members', plans: 'Plans', memberships: 'Memberships', upgrades: 'Upgrade Requests', payments: 'Payments', attendance: 'Attendance', reports: 'Reports' };
const fields = {
  member: { title: 'Add member', endpoint: 'members', inputs: [
    ['full_name', 'Full name', 'text', true], ['age', 'Age', 'number', true], ['gender', 'Gender', 'select', true, ['Female','Male','Non-binary','Prefer not to say']], ['phone', 'Phone number', 'tel', true], ['email', 'Email address', 'email'], ['joining_date', 'Joining date', 'date', true], ['address', 'Address', 'textarea', true, null, true], ['emergency_contact', 'Emergency contact', 'tel', true]
  ] },
  plan: { title: 'Create membership plan', endpoint: 'plans', inputs: [
    ['plan_name', 'Plan name', 'text', true], ['duration_months', 'Duration', 'select', true, [['1','Monthly'],['3','Quarterly'],['6','Half-yearly'],['12','Yearly']]], ['price', 'Price (₹)', 'number', true], ['membership_type', 'Membership type', 'select', false, ['Basic','Standard','Premium','Elite']], ['description', 'Description', 'textarea', false, null, true], ['benefits', 'Benefits / Facilities (comma-separated)', 'textarea', false, null, true]
  ] },
  membership: { title: 'Assign a plan', endpoint: 'memberships', inputs: [
    ['member_id', 'Member', 'select', true, 'members'], ['plan_id', 'Plan', 'select', true, 'plans'], ['start_date', 'Start date', 'date', true]
  ] },
  payment: { title: 'Record payment', endpoint: 'payments', inputs: [
    ['member_id', 'Member', 'select', true, 'members'], ['membership_id', 'Membership (optional)', 'select', false, 'memberships'], ['payer_email', 'Payer email (for UPI prefill)', 'email', false], ['amount', 'Amount (₹)', 'number', true], ['payment_method', 'Payment method', 'select', true, ['CASH','UPI','CARD',['RAZORPAY','Razorpay Checkout (UPI)']]], ['payment_date', 'Payment date', 'date', true], ['status', 'Payment status', 'select', true, ['PAID','PENDING']], ['notes', 'Notes', 'textarea', false, null, true]
  ] },
  checkin: { title: 'Check in member', endpoint: 'attendance', inputs: [['member_id', 'Member', 'select', true, 'members']] },
  announcement: { title: 'Broadcast Gym Announcement', endpoint: 'announcements', inputs: [
    ['title', 'Announcement title', 'text', true, null, true], ['message', 'Message for all members', 'textarea', true, null, true]
  ] }
};

async function api(path, options = {}) {
  const response = await fetch(`/api/${path}`, { credentials: 'same-origin', ...options });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) { if (response.status === 401) showAuth(); throw new Error(data.error || 'Something went wrong.'); }
  return data;
}
function formBody(values) { return new URLSearchParams(Object.entries(values).filter(([,v]) => v !== undefined && v !== null)).toString(); }
function request(method, values = {}) { return { method, headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }, body: formBody(values) }; }
function esc(value) { return String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[c]); }
function money(value) { return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(Number(value || 0)); }
function date(value) { if (!value) return '—'; const d = new Date(`${String(value).slice(0,10)}T00:00:00`); return Number.isNaN(+d) ? value : d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }); }
function dateTime(value) { if (!value) return '—'; const d = new Date(String(value).replace(' ', 'T')); return Number.isNaN(+d) ? value : d.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }); }
function paymentMethodName(value) { return value === 'RAZORPAY' ? 'Razorpay (UPI)' : value; }
function badge(value) { const text = String(value ?? '—'); return `<span class="badge ${esc(text.toLowerCase().replaceAll(' ', '-'))}">${esc(text)}</span>`; }
function emptyRow(colspan, text = 'Nothing to show yet.') { return `<table><tbody><tr><td class="empty-state" colspan="${colspan}">${esc(text)}</td></tr></tbody></table>`; }
function table(headers, rows, render) {
  if (!rows?.length) return emptyRow(headers.length);
  return `<table><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.map(render).join('')}</tbody></table>`;
}
function toast(message, error = false) { const el = $('#toast'); el.textContent = message; el.classList.toggle('error', error); el.classList.add('show'); clearTimeout(toast.timer); toast.timer = setTimeout(()=>el.classList.remove('show'), 3500); }
function showAuth() { $('#app').classList.add('hidden'); $('#auth-screen').classList.remove('hidden'); }
function showApp(userInfo = null) {
  $('#auth-screen').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#today-label').textContent = new Date().toLocaleDateString('en-IN', { weekday:'short', day:'numeric', month:'short', year:'numeric' });
  if (userInfo?.username && $('#admin-avatar')) {
    $('#admin-avatar').textContent = userInfo.username.slice(0, 1).toUpperCase();
  }
  navigate(state.page);
}

function setAuthMode(mode) {
  state.authMode = mode;
  const tabs = $('#auth-tabs');
  const memberBox = $('#member-signup-fields');
  const card = $('#auth-form');
  const memberInputs = $$('input, select, textarea', memberBox);

  $$('.auth-tab', tabs).forEach(t => t.classList.toggle('active', t.dataset.authMode === mode));

  if (mode === 'setup') {
    if (tabs) tabs.classList.add('hidden');
    memberBox.classList.add('hidden');
    card.classList.remove('wide');
    memberInputs.forEach(el => { el.required = false; el.disabled = true; });
    $('#auth-eyebrow').textContent = 'FIRST-TIME SETUP';
    $('#auth-title').textContent = 'Create your admin';
    $('#auth-description').textContent = 'Set up the administrator account for this local workspace.';
    $('#username-label').textContent = 'Admin username';
    $('#auth-submit').innerHTML = 'Create admin <span>→</span>';
    $('input[name=password]').autocomplete = 'new-password';
  } else if (mode === 'member_signup') {
    if (tabs) tabs.classList.remove('hidden');
    memberBox.classList.remove('hidden');
    card.classList.add('wide');
    memberInputs.forEach(el => { el.disabled = false; el.required = el.name !== 'email'; });
    $('#auth-eyebrow').textContent = 'MEMBER PORTAL REGISTRATION';
    $('#auth-title').textContent = 'Join Forge Gym';
    $('#auth-description').textContent = 'Create your personal Member Portal account to manage plans, renewals, and payments.';
    $('#username-label').textContent = 'Choose a username';
    $('#auth-submit').innerHTML = 'Create Member Account <span>→</span>';
    $('input[name=password]').autocomplete = 'new-password';
  } else if (mode === 'signup') {
    if (tabs) tabs.classList.remove('hidden');
    memberBox.classList.add('hidden');
    card.classList.remove('wide');
    memberInputs.forEach(el => { el.required = false; el.disabled = true; });
    $('#auth-eyebrow').textContent = 'ADMIN REGISTRATION';
    $('#auth-title').textContent = 'Create manager account';
    $('#auth-description').textContent = 'Register a new administrator account for the Admin Portal.';
    $('#username-label').textContent = 'Username';
    $('#auth-submit').innerHTML = 'Create Admin Account <span>→</span>';
    $('input[name=password]').autocomplete = 'new-password';
  } else {
    if (tabs) tabs.classList.remove('hidden');
    memberBox.classList.add('hidden');
    card.classList.remove('wide');
    memberInputs.forEach(el => { el.required = false; el.disabled = true; });
    $('#auth-eyebrow').textContent = 'FORGE PORTAL';
    $('#auth-title').textContent = 'Welcome back';
    $('#auth-description').textContent = 'Sign in with your Admin or Member account.';
    $('#username-label').textContent = 'Username (or Member Email / Phone)';
    $('#auth-submit').innerHTML = 'Sign in <span>→</span>';
    $('input[name=password]').autocomplete = 'current-password';
  }
}

async function initialize() {
  try {
    const status = await api('setup/status');
    if (status.setupRequired) {
      setAuthMode('setup');
    } else {
      setAuthMode('login');
      try {
        const me = await api('me');
        if (me.role === 'MEMBER') {
          window.location.href = '/member/member-dashboard.html';
          return;
        }
        showApp(me);
      } catch (e) { showAuth(); }
    }
  } catch (e) { showAuth(); toast(e.message, true); }
}

function navigate(page) {
  if (!pageNames[page]) return;
  state.page = page; $$('.page').forEach(el=>el.classList.toggle('active', el.id === `page-${page}`));
  $$('.nav-link').forEach(el=>el.classList.toggle('active', el.dataset.page === page)); $('#crumb').textContent = pageNames[page];
  $('.sidebar').classList.remove('open'); loadPage(page).catch(e=>toast(e.message,true));
}
async function loadPage(page) {
  if (page === 'dashboard') return loadDashboard();
  if (page === 'members') return loadMembers();
  if (page === 'plans') return loadPlans();
  if (page === 'memberships') return loadMemberships();
  if (page === 'upgrades') return loadUpgrades();
  if (page === 'payments') return loadPayments();
  if (page === 'attendance') return loadAttendance();
  if (page === 'reports') return loadReports();
}

function updateUpgradesBadge(count) {
  const el = $('#upgrades-badge');
  if (!el) return;
  const n = Number(count || 0);
  if (n > 0) { el.textContent = String(n); el.classList.remove('hidden'); }
  else { el.classList.add('hidden'); }
}

async function loadDashboard() {
  const d = await api('dashboard'); $('#stat-members').textContent = d.members; $('#stat-active').textContent = d.activeMemberships;
  $('#stat-expired').textContent = d.expiredMemberships; $('#stat-revenue').textContent = money(d.revenue); $('#stat-attendance').textContent = d.todayAttendance;
  updateUpgradesBadge(d.pendingUpgradeRequests);
  $('#recent-members').innerHTML = table(['Member','Phone','Joined'],d.recentMembers,r=>`<tr><td><b>${esc(r.full_name)}</b></td><td>${esc(r.phone)}</td><td>${date(r.joining_date)}</td></tr>`);
  $('#recent-payments').innerHTML = table(['Member','Amount','Method','Date'],d.recentPayments,r=>`<tr><td><b>${esc(r.full_name)}</b></td><td>${money(r.amount)}</td><td>${esc(paymentMethodName(r.payment_method))}</td><td>${dateTime(r.payment_date)}</td></tr>`);
}
async function loadMembers() {
  const rows = await api(`members?search=${encodeURIComponent($('#members-search').value)}`); state.members = rows; $('#members-count').textContent = `${rows.length} ${rows.length===1?'member':'members'}`;
  $('#members-table').innerHTML = table(['ID','Member','Phone','Email','Joined','Actions'],rows,r=>`<tr><td>#${r.member_id}</td><td><b>${esc(r.full_name)}</b><br><small>${esc(r.gender)}, ${r.age} years${r.portal_username?` · @${esc(r.portal_username)}`:''}</small></td><td>${esc(r.phone)}</td><td>${esc(r.email||'—')}</td><td>${date(r.joining_date)}</td><td><button class="action-button" data-view-member="${r.member_id}">Details</button> <button class="action-button" data-edit="member" data-id="${r.member_id}">Edit</button> <button class="action-button danger" data-delete-member="${r.member_id}">Delete</button></td></tr>`);
}
async function loadPlans() {
  state.plans = await api('plans'); const rows=state.plans;
  $('#plans-list').innerHTML=rows.length?rows.map(r=>`<article class="plan-card"><span class="duration">${r.duration_months} ${r.duration_months===1?'MONTH':'MONTHS'} · ${esc(r.membership_type||'Standard').toUpperCase()}</span><h3>${esc(r.plan_name)}</h3><div class="price">${money(r.price)} <small>/ plan</small></div><p>${esc(r.description||'Gym membership access')}</p>${badge(r.active?'ACTIVE':'INACTIVE')}<div class="plan-actions"><button class="action-button" data-edit="plan" data-id="${r.plan_id}">Edit plan</button><button class="action-button danger" data-disable-plan="${r.plan_id}">Archive</button></div></article>`).join(''):emptyRow(1);
}
async function loadMemberships() {
  state.memberships=await api('memberships'); $('#memberships-table').innerHTML=table(['Member','Plan','Start date','End date','Status'],state.memberships,r=>`<tr><td><b>${esc(r.full_name)}</b><br><small>#${r.member_id}</small></td><td>${esc(r.plan_name)}</td><td>${date(r.start_date)}</td><td>${date(r.end_date)}</td><td>${badge(r.status)}</td></tr>`);
}
async function loadUpgrades() {
  const status = $('#upgrades-filter')?.value || 'ALL';
  const rows = await api(`upgrade-requests?status=${encodeURIComponent(status)}`);
  const pendingCount = status === 'ALL' ? rows.filter(r => r.status === 'PENDING').length : null;
  if (pendingCount !== null) updateUpgradesBadge(pendingCount);
  $('#upgrades-table').innerHTML = table(
    ['ID', 'Member', 'Current Plan', 'Requested Plan', 'Payable', 'Reason / Notes', 'Status', 'Submitted', 'Actions'],
    rows,
    r => {
      const actions = r.status === 'PENDING'
        ? `<button class="action-button approve" data-review-upgrade="${r.request_id}" data-decision="APPROVED">Approve</button> <button class="action-button danger" data-review-upgrade="${r.request_id}" data-decision="REJECTED">Reject</button>`
        : (r.reviewed_by ? `<small>By ${esc(r.reviewed_by)}</small>` : '—');
      return `<tr>
        <td>#UPG-${r.request_id}</td>
        <td><b>${esc(r.full_name)}</b><br><small>#${r.member_id} · ${esc(r.phone)}</small></td>
        <td>${esc(r.current_plan_name || 'None')}</td>
        <td><b>${esc(r.requested_plan_name)}</b> (${r.requested_duration_months} mo)</td>
        <td><b>${money(r.price_difference)}</b></td>
        <td>${esc(r.admin_notes || r.reason || '—')}</td>
        <td>${badge(r.status)}</td>
        <td>${date(r.created_at)}</td>
        <td>${actions}</td>
      </tr>`;
    }
  );
}
async function loadPayments() {
  const params=new URLSearchParams({search:$('#payments-search').value,from:$('#payment-from').value,to:$('#payment-to').value});const rows=await api(`payments?${params}`);
  $('#payments-table').innerHTML=table(['Member','Amount','Method','Date','Status','Action'],rows,r=>`<tr><td><b>${esc(r.full_name)}</b><br><small>#${r.member_id}${r.gateway_order_id?` · ${esc(r.gateway_order_id)}`:''}</small></td><td>${money(r.amount)}</td><td>${esc(paymentMethodName(r.payment_method))}</td><td>${dateTime(r.payment_date)}</td><td>${badge(r.status)}</td><td>${r.status==='PENDING'&&r.payment_method!=='RAZORPAY'?`<button class="action-button" data-mark-paid="${r.payment_id}">Mark paid</button>`:'—'}</td></tr>`);
}
async function loadAttendance() {
  const p=new URLSearchParams({search:$('#attendance-search').value,date:$('#attendance-date').value});const rows=await api(`attendance?${p}`);
  $('#attendance-table').innerHTML=table(['Member','Member ID','Check-in date','Check-in time'],rows,r=>`<tr><td><b>${esc(r.full_name)}</b></td><td>#${r.member_id}</td><td>${date(r.attendance_date)}</td><td>${new Date(String(r.check_in_at).replace(' ','T')).toLocaleTimeString('en-IN',{hour:'2-digit',minute:'2-digit'})}</td></tr>`);
}
async function loadReports() {
  const month=$('#report-month').value;const d=await api(`reports?month=${encodeURIComponent(month)}`);const s=d.summary;
  $('#report-summary').innerHTML=[['Total members',s.members],['Active memberships',s.activeMemberships],['Expired memberships',s.expiredMemberships],['Revenue',money(s.revenue)],['Today’s attendance',d.attendanceToday]].map(([l,v])=>`<article class="stat-card"><p>${l}</p><strong>${v}</strong><small>Current club snapshot</small></article>`).join('');
  const max=Math.max(1,...d.monthlyRevenue.map(r=>Number(r.revenue)));$('#revenue-chart').innerHTML=d.monthlyRevenue.length?d.monthlyRevenue.map(r=>{const [y,m]=r.month.split('-');return `<div class="chart-column"><div class="bar" style="height:${Math.max(5,Number(r.revenue)/max*135)}px"><b>${money(r.revenue)}</b></div><span>${new Date(Number(y),Number(m)-1).toLocaleDateString('en-IN',{month:'short'})}</span></div>`}).join(''):'<p class="muted">No revenue recorded yet.</p>';
  $('#renewals-table').innerHTML=table(['Member','Plan','Start date','End date','Status'],d.renewals,r=>`<tr><td><b>${esc(r.full_name)}</b></td><td>${esc(r.plan_name)}</td><td>${date(r.start_date)}</td><td>${date(r.end_date)}</td><td>${badge(r.renewal_status)}</td></tr>`);
  $('#attendance-report-table').innerHTML=table(['Date','Check-ins'],d.attendanceByDay,r=>`<tr><td><b>${date(r.visit_date)}</b></td><td>${r.visits}</td></tr>`);
  $('#report-payments').innerHTML=table(['Member','Amount','Method','Date','Status'],d.payments,r=>`<tr><td><b>${esc(r.full_name)}</b></td><td>${money(r.amount)}</td><td>${esc(paymentMethodName(r.payment_method))}</td><td>${dateTime(r.payment_date)}</td><td>${badge(r.status)}</td></tr>`);
  $('#report-month-label').textContent=month?new Date(`${month}-01T00:00:00`).toLocaleDateString('en-IN',{month:'long',year:'numeric'}):'';
}

function optionsFor(source) {
  if (source==='members') return state.members.map(m=>[String(m.member_id),`${m.full_name} · #${m.member_id}`]);
  if (source==='plans') return state.plans.filter(p=>p.active).map(p=>[String(p.plan_id),`${p.plan_name} · ${money(p.price)}`]);
  if (source==='memberships') return state.memberships.filter(m=>m.status==='ACTIVE'||m.status==='UPCOMING').map(m=>[String(m.membership_id),`${m.full_name} · ${m.plan_name} · #${m.membership_id}`]);
  return source.map(x=>Array.isArray(x)?x:[x,x]);
}
async function openForm(type, record = null) {
  const config=fields[type];if(!config)return;state.editing=record?{type,record}:null;
  $('#form-fields').style.display='grid';
  $('#save-form').classList.remove('hidden');$('#cancel-form').textContent='Cancel';
  $('#save-form').textContent = type === 'announcement' ? 'Send to All Members' : 'Save changes';
  if(['membership','payment','checkin'].includes(type)) {state.members=await api('members');state.plans=await api('plans');state.memberships=await api('memberships');}
  const specs=config.inputs;$('#dialog-title').textContent=record?`Edit ${type}`:config.title;$('#dialog-eyebrow').textContent=record?'UPDATE RECORD':'NEW RECORD';$('#form-error').textContent='';
  $('#form-fields').innerHTML=specs.map(([name,label,kind,required,source,wide])=>{
    let control;if(kind==='select'){const opts=optionsFor(source);control=`<select name="${name}" ${required?'required':''}><option value="">Choose…</option>${opts.map(([v,t])=>`<option value="${esc(v)}">${esc(t)}</option>`).join('')}</select>`;}
    else if(kind==='textarea')control=`<textarea name="${name}" ${required?'required':''}></textarea>`;
    else control=`<input name="${name}" type="${kind}" ${required?'required':''} ${kind==='number'?'min="0" step="'+(name==='age'?'1':'0.01')+'"':''}>`;
    return `<label class="${wide?'wide':''}">${esc(label)}${control}</label>`;
  }).join('');
  const form=$('#data-form');form.dataset.type=type;
  if(record) for(const [name] of specs){const input=form.elements.namedItem(name);if(input)input.value=record[name]??'';}
  if(type==='member'&&!record)form.elements.joining_date.value=new Date().toISOString().slice(0,10);
  if(type==='membership')form.elements.start_date.value=new Date().toISOString().slice(0,10);
  if(type==='payment')form.elements.payment_date.value=new Date().toISOString().slice(0,10);
  if(type==='plan'&&!record&&form.elements.membership_type)form.elements.membership_type.value='Standard';
  $('#form-dialog').showModal();
}
async function submitForm(event) {
  event.preventDefault();
  const form=event.currentTarget,type=form.dataset.type,config=fields[type];
  const data=Object.fromEntries(new FormData(form).entries());
  if(type==='payment'&&!data.membership_id)delete data.membership_id;
  const edit=state.editing,method=edit?'PUT':'POST';
  if(edit)data.id=edit.record.member_id??edit.record.plan_id;
  $('#save-form').disabled=true;$('#save-form').textContent=type==='payment'&&data.payment_method==='RAZORPAY'?'Opening Razorpay...':'Saving...';$('#form-error').textContent='';
  try {
    if(type==='payment'&&data.payment_method==='RAZORPAY') {
      if(typeof window.Razorpay!=='function')throw new Error('Razorpay Checkout could not load. Check your internet connection and reload the page.');
      const order=await api('razorpay/order',request('POST',{member_id:data.member_id,membership_id:data.membership_id,amount:data.amount,payer_email:data.payer_email}));
      $('#form-dialog').close();
      const checkout=new window.Razorpay({
        key:order.keyId,order_id:order.orderId,amount:String(order.amount),currency:order.currency,
        name:'Forge Fitness Club',description:'Gym membership payment',method:'upi',
        prefill:{name:order.name,email:order.email,contact:order.contact},theme:{color:'#111b2d'},
        handler:async response=>{
          try{const result=await api('razorpay/verify',request('POST',response));toast(result.message);await Promise.all([loadPayments(),loadDashboard()]);}
          catch(error){toast(error.message,true);}
        },
        modal:{ondismiss:()=>toast('Checkout closed. The payment remains pending until verified.')}
      });
      checkout.on('payment.failed',response=>toast(response.error?.description||'Razorpay could not complete the payment.',true));
      checkout.open();
      return;
    }
    const res = await api(config.endpoint,request(method,data));
    $('#form-dialog').close();
    if (type === 'announcement') toast(`Announcement sent to ${res.notifiedMembers ?? 0} member(s).`);
    else toast(edit?'Changes saved.':'Record added.');
    await Promise.all([loadPage(state.page),loadDashboard().catch(()=>{})]);
  } catch(e) {
    if(type==='payment'&&data.payment_method==='RAZORPAY'&&!$('#form-dialog').open)toast(e.message,true);
    else $('#form-error').textContent=e.message;
  } finally {
    $('#save-form').disabled=false;
    const isRazorpay=type==='payment'&&form.elements.payment_method?.value==='RAZORPAY';
    $('#save-form').textContent=isRazorpay?'Continue to Razorpay':(type==='announcement'?'Send to All Members':'Save changes');
  }
}
document.addEventListener('click',async event=>{
  const authTab = event.target.closest('[data-auth-mode]');
  if (authTab) { setAuthMode(authTab.dataset.authMode); return; }

  const nav=event.target.closest('[data-page],[data-nav]');if(nav){navigate(nav.dataset.page||nav.dataset.nav);return;}
  const open=event.target.closest('[data-open]');if(open){try{await openForm(open.dataset.open);}catch(e){toast(e.message,true);}return;}
  const edit=event.target.closest('[data-edit]');if(edit){const list=edit.dataset.edit==='member'?state.members:state.plans;const record=list.find(x=>String(x.member_id??x.plan_id)===edit.dataset.id);if(record)openForm(edit.dataset.edit,record);return;}
  const view=event.target.closest('[data-view-member]');if(view){try{const m=await api(`members?id=${view.dataset.viewMember}`);$('#dialog-title').textContent=m.full_name;$('#dialog-eyebrow').textContent=`MEMBER #${m.member_id}`;$('#form-fields').style.display='block';$('#form-fields').innerHTML=`<div class="detail-grid" style="display:grid;grid-template-columns:1fr 1fr;gap:14px;width:100%"><div><small>PHONE</small><b>${esc(m.phone)}</b></div><div><small>EMAIL</small><b>${esc(m.email||'—')}</b></div><div><small>AGE / GENDER</small><b>${m.age} · ${esc(m.gender)}</b></div><div><small>JOINED</small><b>${date(m.joining_date)}</b></div><div><small>EMERGENCY CONTACT</small><b>${esc(m.emergency_contact)}</b></div><div><small>VISITS</small><b>${m.attendanceCount}</b></div><div><small>PORTAL ACCOUNT</small><b>${m.portal_username?`@${esc(m.portal_username)}`:'Not registered'}</b></div><div style="grid-column:1/-1"><small>ADDRESS</small><b>${esc(m.address)}</b></div><div style="grid-column:1/-1"><small>MEMBERSHIPS</small><b>${m.memberships.length?m.memberships.map(x=>`${esc(x.plan_name)} · ${date(x.start_date)} to ${date(x.end_date)} (${esc(x.status)})`).join('<br>'):'No memberships yet'}</b></div><div style="grid-column:1/-1"><small>RECENT PAYMENTS</small><b>${m.payments.length?m.payments.map(x=>`${money(x.amount)} · ${esc(x.payment_method)} · ${dateTime(x.payment_date)} (${esc(x.status)})`).join('<br>'):'No payments yet'}</b></div></div>`;$('#save-form').classList.add('hidden');$('#cancel-form').textContent='Close';$('#form-dialog').showModal();}catch(e){toast(e.message,true);}return;}
  const remove=event.target.closest('[data-delete-member]');if(remove){if(!confirm('Delete this member? This is only possible when they have no membership, payment, or attendance history.'))return;try{await api(`members?id=${remove.dataset.deleteMember}`,{method:'DELETE'});toast('Member deleted.');await loadMembers();await loadDashboard();}catch(e){toast(e.message,true);}return;}
  const archive=event.target.closest('[data-disable-plan]');if(archive){if(!confirm('Archive this plan? Existing memberships will remain in your records.'))return;try{await api(`plans?id=${archive.dataset.disablePlan}`,{method:'DELETE'});toast('Plan archived.');await loadPlans();}catch(e){toast(e.message,true);}return;}
  const paid=event.target.closest('[data-mark-paid]');if(paid){try{await api(`payments?id=${paid.dataset.markPaid}`,request('PUT',{status:'PAID'}));toast('Payment marked as paid.');await loadPayments();await loadDashboard();}catch(e){toast(e.message,true);}return;}
  const review=event.target.closest('[data-review-upgrade]');
  if(review){
    const reqId = review.dataset.reviewUpgrade;
    const decision = review.dataset.decision;
    const note = prompt(decision === 'APPROVED' ? 'Optional note for member (or leave blank to approve):' : 'Optional reason for declining this request:');
    if (note === null) return;
    try {
      await api(`upgrade-requests?id=${reqId}`, request('PUT', { status: decision, admin_notes: note }));
      toast(decision === 'APPROVED' ? 'Upgrade request approved and member notified.' : 'Upgrade request rejected and member notified.');
      await Promise.all([loadUpgrades(), loadDashboard()]);
    } catch (e) { toast(e.message, true); }
  }
});

$('#auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = Object.fromEntries(new FormData(form).entries());
  const mode = state.authMode;
  $('#auth-submit').disabled = true;
  try {
    const endpoint = mode === 'setup' ? 'setup'
      : (mode === 'member_signup' ? 'member/signup'
      : (mode === 'signup' ? 'signup' : 'login'));
    const res = await api(endpoint, request('POST', data));
    form.reset();
    if (res.role === 'MEMBER' || res.redirect === '/member/member-dashboard.html') {
      toast(mode === 'member_signup' ? 'Welcome to Forge Gym! Opening Member Portal...' : 'Welcome back! Opening Member Portal...');
      window.location.href = '/member/member-dashboard.html';
      return;
    }
    toast(mode === 'setup' ? 'Administrator account created.' : (mode === 'signup' ? 'Admin account created. Welcome!' : 'Welcome back.'));
    showApp(res);
  } catch (e) {
    toast(e.message, true);
  } finally {
    $('#auth-submit').disabled = false;
  }
});

$('#data-form').addEventListener('change',event=>{
  const form=event.currentTarget;
  if(event.target.name==='payment_method')$('#save-form').textContent=event.target.value==='RAZORPAY'?'Continue to Razorpay':'Save changes';
  if(event.target.name==='member_id'&&form.dataset.type==='payment'){
    const member=state.members.find(x=>String(x.member_id)===event.target.value);
    if(member?.email)form.elements.payer_email.value=member.email;
  }
});$('#data-form').addEventListener('submit',submitForm);$('#dialog-close').addEventListener('click',()=>$('#form-dialog').close());$('#cancel-form').addEventListener('click',()=>$('#form-dialog').close());
$('#logout').addEventListener('click',async()=>{try{await api('logout',request('POST'));}finally{showAuth();toast('You have signed out.');}});
$('#menu-toggle').addEventListener('click',()=>$('.sidebar').classList.toggle('open'));
let searchTimer;for(const [selector,page] of [['#members-search','members'],['#payments-search','payments'],['#attendance-search','attendance']])$(selector).addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>loadPage(page).catch(e=>toast(e.message,true)),250);});
for(const selector of ['#payment-from','#payment-to'])$(selector).addEventListener('change',()=>loadPayments().catch(e=>toast(e.message,true)));
$('#attendance-date').addEventListener('change',()=>loadAttendance().catch(e=>toast(e.message,true)));
$('#upgrades-filter')?.addEventListener('change',()=>loadUpgrades().catch(e=>toast(e.message,true)));
$('#report-month').value=new Date().toISOString().slice(0,7);$('#report-month').addEventListener('change',()=>loadReports().catch(e=>toast(e.message,true)));
initialize();
