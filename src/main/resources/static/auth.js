/* 登录 / 注册页交互（index.html） */

let admin = false;

const $ = (selector) => document.querySelector(selector);

/* 用户登录 / 管理员登录入口切换 */
document.querySelectorAll('.mode-switch button').forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll('.mode-switch button').forEach((x) => x.classList.remove('active'));
    btn.classList.add('active');
    admin = btn.dataset.mode === 'admin';
  };
});

/* 显示错误提示并触发抖动动效（强制重排以重启动画） */
function showError(el, message) {
  el.textContent = message;
  el.classList.remove('shake');
  void el.offsetWidth;
  el.classList.add('shake');
}

/* 登录 */
$('#login-form').onsubmit = async (e) => {
  e.preventDefault();
  $('#auth-error').textContent = '';
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        account: $('#account').value,
        password: $('#password').value,
        admin
      })
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.message);
    sessionStorage.setItem('tiebaUser', JSON.stringify(data.user));
    location.href = '/forum-main.html';
  } catch (err) {
    if (location.protocol === 'file:') {
      /* 本地直接打开页面时的演示兜底 */
      sessionStorage.setItem('tiebaUser', JSON.stringify({ id: 20240001, username: $('#account').value || '校园同学' }));
      location.href = '/forum-main.html';
    } else {
      showError($('#auth-error'), err.message || '登录失败，请检查账号信息');
    }
  }
};

/* 登录 / 注册表单切换 */
$('#register-toggle').onclick = () => {
  $('#login-form').hidden = !$('#login-form').hidden;
  $('#register-form').hidden = !$('#register-form').hidden;
  $('#register-toggle').textContent = $('#register-form').hidden ? '新同学？注册账号' : '返回登录';
};

/* 注册 */
$('#register-form').onsubmit = async (e) => {
  e.preventDefault();
  $('#register-error').textContent = '';
  try {
    const response = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        account: $('#reg-account').value,
        password: $('#reg-password').value,
        username: $('#reg-name').value
      })
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.message);
    alert('注册成功，请登录');
    $('#register-toggle').click();
  } catch (err) {
    showError($('#register-error'), err.message);
  }
};
