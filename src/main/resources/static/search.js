const user = JSON.parse(sessionStorage.getItem('tiebaUser') || 'null');
if (!user) location.href = '/';

function applyBackground(url) {
    document.body.style.setProperty('--custom-background', url ? `url("${url}")` : 'none');
}

applyBackground(user.backgroundUrl);
fetch(`/api/users/${user.id}`)
    .then((response) => response.ok ? response.json() : null)
    .then((profile) => {
        const backgroundUrl = profile?.user?.backgroundUrl;
        if (backgroundUrl) {
            user.backgroundUrl = backgroundUrl;
            sessionStorage.setItem('tiebaUser', JSON.stringify(user));
            applyBackground(backgroundUrl);
        }
    })
    .catch(() => {});
const params = new URLSearchParams(location.search);
const queryInput = document.querySelector('#search-query');
const results = document.querySelector('#search-page-results');
const loadMoreButton = document.querySelector('#search-load-more');
let mode = params.get('mode') === 'users' ? 'users' : 'posts';
let page = 1;
const pageSize = 50;
let items = [];
let total = 0;
queryInput.value = params.get('q') || '';

document.querySelectorAll('.search-mode button').forEach((button) => {
    button.classList.toggle('active', button.dataset.mode === mode);
    button.onclick = () => { mode = button.dataset.mode; document.querySelectorAll('.search-mode button').forEach((item) => item.classList.toggle('active', item === button)); loadResults(); };
});

const avatarMarkup = (avatar, username) => avatar
    ? `<img class="avatar-image" src="${avatar}" alt="${username || '用户'}头像">`
    : (username || '校')[0];

function postMarkup(post) {
    const summary = post.content.length > 160 ? `${post.content.slice(0, 160)}...` : post.content;
    return `<a class="search-result-post" href="/post.html?id=${post.id}"><div><h3>${post.title}</h3><p>${post.username} · ${post.boardName || ''} · ${summary}</p></div></a>`;
}

function userMarkup(person) {
    return `<a class="search-result-user" href="/profile.html?id=${person.id}"><span class="avatar">${avatarMarkup(person.avatar, person.username)}</span><span><strong>${person.username}</strong><small>@${person.account}</small></span></a>`;
}

function renderResults() {
    results.innerHTML = items.length
        ? items.map(mode === 'users' ? userMarkup : postMarkup).join('')
        : '<p class="search-empty">没有找到相关结果</p>';
    loadMoreButton.hidden = items.length >= total;
    loadMoreButton.textContent = `加载更多（已显示 ${items.length}/${total}）`;
}

async function loadResults(reset = true) {
    const query = queryInput.value.trim();
    if (!query) {
        results.innerHTML = '<p class="search-empty">请输入搜索内容</p>';
        loadMoreButton.hidden = true;
        return;
    }
    if (reset) {
        page = 1;
        items = [];
        total = 0;
        results.innerHTML = '<p class="search-empty">正在搜索...</p>';
    }
    const url = mode === 'users'
        ? `/api/users/search?q=${encodeURIComponent(query)}&page=${page}&size=${pageSize}`
        : `/api/posts/search?q=${encodeURIComponent(query)}&page=${page}&size=${pageSize}`;
    const response = await fetch(url);
    const data = response.ok ? await response.json() : { items: [], total: 0, hasMore: false };
    total = data.total || 0;
    items = items.concat(data.items || []);
    renderResults();
}

loadMoreButton.onclick = () => { page += 1; loadResults(false); };

document.querySelector('#search-page-form').onsubmit = (event) => {
    event.preventDefault();
    const query = queryInput.value.trim();
    history.replaceState(null, '', `/search.html?q=${encodeURIComponent(query)}&mode=${mode}`);
    loadResults();
};
if (queryInput.value) loadResults();
