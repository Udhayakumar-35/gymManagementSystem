async function loadProfile() {
  try {
    const p = await memberApi('member/profile');
    $('#pf-name').value = p.full_name || '';
    $('#pf-age').value = p.age || '';
    $('#pf-gender').value = p.gender || 'Prefer not to say';
    $('#pf-phone').value = p.phone || '';
    $('#pf-email').value = p.email || '';
    $('#pf-emergency').value = p.emergency_contact || '';
    $('#pf-address').value = p.address || '';

    const m = p.currentMembership;
    $('#readonly-account-info').innerHTML = `
      <div><small>Member ID</small><b>#${esc(p.member_id)}</b></div>
      <div><small>Username</small><b>@${esc(p.username)}</b></div>
      <div><small>Account Role</small><b>${esc(p.role || 'MEMBER')}</b></div>
      <div><small>Joined Date</small><b>${fmtDate(p.joining_date)}</b></div>
      <div><small>Current Plan</small><b>${m ? esc(m.plan_name) : 'No active plan'}</b></div>
      <div><small>Membership Status</small><div>${m ? statusBadge(m.status) : statusBadge('EXPIRED', 'Inactive')}</div></div>
      ${m?.end_date ? `<div style="grid-column:1/-1"><small>Validity Window</small><b>${fmtDate(m.start_date)} — ${fmtDate(m.end_date)} (${m.days_remaining} day(s) remaining)</b></div>` : ''}
    `;
  } catch (err) {
    toast(err.message, 'error');
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  const me = await initMemberPortal('profile', 'My Profile');
  if (!me) return;
  await loadProfile();

  $('#profile-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = Object.fromEntries(new FormData(form).entries());
    const btn = $('#save-profile-btn');
    btn.disabled = true;
    btn.textContent = 'Saving...';
    try {
      const res = await memberApi('member/profile', formRequest('PUT', data));
      toast(res.message || 'Profile updated.');
      await loadProfile();
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = 'Save Profile Changes';
    }
  });

  $('#password-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = Object.fromEntries(new FormData(form).entries());
    if (data.new_password !== data.confirm_password) {
      toast('New password and confirmation do not match.', 'error');
      return;
    }
    const btn = $('#save-pw-btn');
    btn.disabled = true;
    btn.textContent = 'Updating...';
    try {
      const res = await memberApi('member/password', formRequest('PUT', {
        current_password: data.current_password,
        new_password: data.new_password
      }));
      form.reset();
      toast(res.message || 'Password updated.');
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = 'Update Password';
    }
  });
});
