const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);
const avatarMarkup = (avatar, username) => avatar
    ? `<img class="avatar-image" src="${avatar}" alt="${username || '用户'}头像">`
    : (username || '校')[0];

if (!sessionStorage.getItem('tiebaUser')) {
    location.href = '/';
}

const user = JSON.parse(sessionStorage.getItem('tiebaUser'));
let selectedBoard = new URLSearchParams(location.search).get('board') || '';
let boardRecommended = false;
let partitionBoard = '';
let currentView = 'feed';
let allPosts = [];
let visiblePosts = [];
let nextIndex = 0;
let likedPosts = new Set();
let favPosts = new Set();
let BOARDS = ['学习交流', '校园生活', '社团活动', '失物招领'];
let BOARD_DATA = [];
let siteNotice = null;

function boardIconMarkup(board, className = 'avatar-image') {
    const icon = board.icon || '';
    return /^\/uploads\//.test(icon) || /^https?:\/\//.test(icon)
        ? `<img class="${className}" src="${icon}" alt="${board.name}图标">`
        : (icon || board.name?.[0] || '吧');
}

function boardPickerMarkup(prefix, mainValue = '', childValue = '') {
    return `<div class="board-picker" data-picker="${prefix}">
        <div class="board-picker-column"><label>主版面</label><input class="board-picker-input" data-role="main" value="${mainValue}" placeholder="输入主版面关键字"><div class="board-picker-results" data-role="main-results"></div></div>
        <div class="board-picker-column"><label>子版面</label><input class="board-picker-input" data-role="child" value="${childValue}" placeholder="输入子版面关键字"><div class="board-picker-results" data-role="child-results"></div></div>
    </div>`;
}

function setupBoardPicker(root, onChange) {
    const mainInput = root.querySelector('[data-role="main"]');
    const childInput = root.querySelector('[data-role="child"]');
    const mainResults = root.querySelector('[data-role="main-results"]');
    const childResults = root.querySelector('[data-role="child-results"]');
    const parents = BOARD_DATA.filter((board) => !board.parentId);
    const children = BOARD_DATA.filter((board) => board.parentId);
    const draw = () => {
        const mainQuery = mainInput.value.trim().toLowerCase();
        const selectedMain = BOARD_DATA.find((board) => board.name === mainInput.value.trim());
        const childQuery = childInput.value.trim().toLowerCase();
        const visibleParents = parents.filter((board) => board.name.toLowerCase().includes(mainQuery));
        const visibleChildren = children.filter((board) => (!selectedMain || Number(board.parentId) === Number(selectedMain.id)) && board.name.toLowerCase().includes(childQuery));
        mainResults.innerHTML = visibleParents.map((board) => `<button type="button" data-value="${board.name}">${board.name}</button>`).join('') || '<span>无匹配主版面</span>';
        childResults.innerHTML = visibleChildren.map((board) => `<button type="button" data-value="${board.name}">${board.name}</button>`).join('') || '<span>无匹配子版面</span>';
        mainResults.querySelectorAll('button').forEach((button) => button.onclick = () => { mainInput.value = button.dataset.value; childInput.value = ''; draw(); onChange?.(); });
        childResults.querySelectorAll('button').forEach((button) => button.onclick = () => { childInput.value = button.dataset.value; onChange?.(); });
    };
    mainInput.oninput = draw;
    childInput.oninput = draw;
    draw();
    const readSelection = () => ({ main: mainInput.value.trim(), child: childInput.value.trim() });
    root._readSelection = readSelection;
    return readSelection;
}

initSettings();

$('#profile-name').textContent = user.username || '校园同学';
$('#profile-id').textContent = `ID: ${String(user.id || 0).padStart(8, '0')}`;
$('#sidebar-avatar').innerHTML = avatarMarkup(user.avatar, user.username);

// 校园公告：从后端加载管理员可编辑的标题与内容；加载成功后移除 data-i18n，
// 避免切换界面语言时覆盖管理员设置的公告文案（接口异常时保留 i18n 默认文案）。
function renderSiteNotice() {
    if (!siteNotice) return;
    const titleEl = $('#notice-title');
    const contentEl = $('#notice-content');
    if (titleEl) { delete titleEl.dataset.i18n; titleEl.textContent = siteNotice.title; }
    if (contentEl) { delete contentEl.dataset.i18n; contentEl.textContent = siteNotice.content; }
}

async function loadSiteNotice() {
    try {
        const response = await fetch('/api/notice');
        if (!response.ok) return;
        siteNotice = await response.json();
        renderSiteNotice();
    } catch (_) {}
}

async function loadUserStats() {
    try {
        const res = await fetch(`/api/users/${user.id}/stats`);
        if (!res.ok) return;
        const stats = await res.json();
        $('#follow-count').textContent = stats.follows ?? 0;
        $('#like-count').textContent = stats.likes ?? 0;
        $('#favorite-count').textContent = stats.favorites ?? 0;
    } catch (_) {}
}

async function loadTrending() {
    try {
        const res = await fetch('/api/boards');
        const boards = await res.json();
        BOARD_DATA = boards;
        BOARDS = boards.map((board) => board.name);
        if (selectedBoard !== 'all' && !BOARDS.includes(selectedBoard)) selectedBoard = '';
        setTopBoardActive(selectedBoard);
        $('#feed-title').textContent = selectedBoard === 'all' ? t('browseBoards') : (selectedBoard || t('recommend'));
        const mainBoards = boards.filter((board) => !board.parentId);
        $('#trending-list').innerHTML = mainBoards.map((b) =>
            `<button class="trend" data-board="${b.name}" type="button"><div class="avatar">${boardIconMarkup(b)}</div><div><strong>${b.name}</strong><span>${b.hot_score} 热度</span></div></button>`
        ).join('');
        $('#trending-list').querySelectorAll('[data-board]').forEach((button) => button.onclick = () => {
            selectedBoard = button.dataset.board; boardRecommended = false; currentView = 'feed'; partitionBoard = '';
            setTopBoardActive(selectedBoard); setSidebarActive(''); $('#feed-title').textContent = selectedBoard; loadFeed();
        });
    } catch (_) {}
}

async function fetchPosts() {
    let url = '/api/posts';
    if (currentView === 'myhome') {
        url = `/api/users/${user.id}/posts`;
    } else if (currentView === 'favorites') {
        url = `/api/users/${user.id}/favorites`;
    } else if (currentView === 'history') {
        url = `/api/users/${user.id}/history`;
    } else if (selectedBoard === 'all') {
        url = partitionBoard ? `/api/posts?board=${encodeURIComponent(partitionBoard)}&recommended=${boardRecommended}` : `/api/posts?recommended=${boardRecommended}`;
    } else if (selectedBoard) {
        url = `/api/posts?board=${encodeURIComponent(selectedBoard)}&recommended=${boardRecommended}`;
    }
    const response = await fetch(url);
    if (!response.ok) throw new Error('加载失败');
    return await response.json();
}

function renderBoardOverview() {
    const overview = $('#board-overview');
    const query = (overview.querySelector('.board-search')?.value || '').trim().toLowerCase();
    const boards = BOARD_DATA.filter((board) => board.name.toLowerCase().includes(query));
    if (!overview.querySelector('.board-search')) {
        overview.innerHTML = '<div class="board-search-shell"><span>⌕</span><input class="board-search" type="search" placeholder="搜索版面名称"></div><div class="board-grid"></div>';
        overview.querySelector('.board-search').oninput = () => renderBoardOverview();
    }
    overview.querySelector('.board-grid').innerHTML = boards.map((board) => `<button class="board-overview-card" type="button" data-board="${board.name}"><div class="avatar">${boardIconMarkup(board)}</div><div><strong>${board.name}</strong><span>${board.hot_score || 0} 热度</span></div></button>`).join('') || '<p>没有找到匹配的版面</p>';
    overview.querySelectorAll('[data-board]').forEach((button) => button.onclick = () => {
        selectedBoard = button.dataset.board; boardRecommended = false; currentView = 'feed'; partitionBoard = '';
        setTopBoardActive(selectedBoard); $('#feed-title').textContent = selectedBoard; loadFeed();
    });
}

function renderBoardBreadcrumb() {
    const breadcrumb = $('#board-breadcrumb');
    if (!selectedBoard) { breadcrumb.innerHTML = ''; return; }
    if (selectedBoard === 'all') {
        breadcrumb.innerHTML = '<a href="/forum-main.html">论坛主页</a><span class="separator">/</span><span>浏览分区</span>';
        return;
    }
    const board = BOARD_DATA.find((item) => item.name === selectedBoard);
    const parent = board?.parentId ? BOARD_DATA.find((item) => Number(item.id) === Number(board.parentId)) : null;
    breadcrumb.innerHTML = `<a href="/forum-main.html">论坛主页</a><span class="separator">/</span>${parent ? `<a href="/forum-main.html?board=${encodeURIComponent(parent.name)}">${parent.name}</a><span class="separator">/</span>` : ''}<span>${selectedBoard}</span>`;
}

async function renderChildren(boardName, container) {
    const response = await fetch(`/api/boards/children?parent=${encodeURIComponent(boardName)}`);
    const children = response.ok ? await response.json() : [];
    container.innerHTML = `<div class="subboard-heading"><strong>子版面</strong><input class="subboard-search" type="search" placeholder="搜索子版面"><span>${children.length} 个</span></div><div class="subboard-grid"></div>`;
    const grid = container.querySelector('.subboard-grid');
    const draw = () => {
        const query = container.querySelector('.subboard-search').value.trim().toLowerCase();
        grid.innerHTML = children.filter((child) => child.name.toLowerCase().includes(query)).slice(0, 6).map((child) => `<button class="board-overview-card" type="button" data-board="${child.name}"><div class="avatar">${boardIconMarkup(child)}</div><div><strong>${child.name}</strong><span>${child.hot_score || 0} 热度</span></div></button>`).join('') || '<span class="subboard-empty">暂无匹配子版面</span>';
        grid.querySelectorAll('[data-board]').forEach((button) => button.onclick = () => { selectedBoard = button.dataset.board; boardRecommended = false; currentView = 'feed'; loadFeed(); });
    };
    container.querySelector('.subboard-search').oninput = draw;
    draw();
}

function formatTime(ts) {
    if (!ts) return '';
    return ts.replace('T', ' ').slice(0, 16);
}

function renderCard(post) {
    const summary = post.content.length > 180 ? `${post.content.slice(0, 180)}……` : post.content;
    const isOwner = Number(post.userId) === Number(user.id);
    const liked = likedPosts.has(Number(post.id));
    const faved = favPosts.has(Number(post.id));
    const img = post.imageUrl && post.imageUrl.trim();
    return `<article class="post" data-id="${post.id}">
        <div class="post-author"><a class="avatar profile-link" href="/profile.html?id=${post.userId}" title="查看个人主页">${avatarMarkup(post.avatar, post.username)}</a>
        <span>${post.username} · ${formatTime(post.createdAt)} · ${post.boardName || ''} · 识别码：${post.id}</span></div>
        <h3>${post.title}</h3><div class="post-content">${summary}</div>
        ${img ? `<img class="post-media" src="${img}" alt="帖子图片" title="点击查看大图">` : ''}
        <div class="post-meta">
            <button data-action="like" data-id="${post.id}">${liked ? '♥' : '♡'} ${post.likes || 0}</button>
            <button data-action="favorite" data-id="${post.id}">${faved ? '★' : '☆'} ${post.favorites || 0}</button>
            <span>◉ ${post.views || 0}</span>
            ${isOwner ? `<button class="post-action-edit" data-action="edit" data-id="${post.id}">${t('edit')}</button>
            <button class="post-action-delete" data-action="delete" data-id="${post.id}">${t('delete')}</button>` : ''}
        </div>
    </article>`;
}

function renderFeed() {
    const feed = $('#feed');
    const sortSwitch = $('#board-sort-switch');
    sortSwitch.classList.toggle('hidden', !selectedBoard || selectedBoard === 'all' || currentView !== 'feed');
    sortSwitch.querySelectorAll('button').forEach((button) => button.classList.toggle('active', (button.dataset.sort === 'recommended') === boardRecommended));
    if (selectedBoard === 'all') {
        feed.classList.add('hidden');
        $('#loading').classList.add('hidden');
        $('#board-overview').classList.remove('hidden');
        $('#subboard-preview').classList.add('hidden');
        renderBoardBreadcrumb();
        renderBoardOverview();
        return;
    }
    $('#board-overview').classList.add('hidden');
    feed.classList.remove('hidden');
    const subboardPreview = $('#subboard-preview');
    const selectedBoardData = BOARD_DATA.find((item) => item.name === selectedBoard);
    if (selectedBoard && !selectedBoardData?.parentId) {
        subboardPreview.classList.remove('hidden');
        renderChildren(selectedBoard, subboardPreview);
    } else {
        subboardPreview.classList.add('hidden');
    }
    renderBoardBreadcrumb();
    feed.innerHTML = visiblePosts.map(renderCard).join('');
    updateLoading(false);
    feed.querySelectorAll('.post').forEach((post) => {
        post.onclick = (e) => {
            if (e.target.closest('[data-action]')) return;
            location.href = `/post.html?id=${post.dataset.id}`;
        };
    });
    feed.querySelectorAll('[data-action]').forEach((button) => {
        button.onclick = (event) => { event.stopPropagation(); handleAction(button); };
    });
    feed.querySelectorAll('img.post-media').forEach((img) => {
        img.onclick = (event) => { event.stopPropagation(); openImagePreview(img.src); };
    });
    sortSwitch.querySelectorAll('button').forEach((button) => button.onclick = () => {
        boardRecommended = button.dataset.sort === 'recommended';
        loadFeed();
    });
}

function renderNotifications(data) {
    $('#board-sort-switch').classList.add('hidden');
    $('#board-overview').classList.add('hidden');
    $('#subboard-preview').classList.add('hidden');
    $('#board-breadcrumb').innerHTML = '';
    $('#loading').classList.add('hidden');
    const renderItem = (item) => `<article class="notification-item"><div class="notification-source"><span class="avatar">${avatarMarkup(item.sourceAvatar, item.sourceName)}</span><strong>${item.sourceName}</strong><time>${formatTime(String(item.createdAt || ''))}</time></div><p>${item.type === 'reply' ? item.content : item.content}</p><a href="/post.html?id=${item.postId}" class="notification-post">${item.postTitle || '查看对应帖子'} <span>→</span></a></article>`;
    const likes = data.likes || [];
    const replies = data.replies || [];
    const feed = $('#feed');
    feed.classList.remove('hidden');
    feed.innerHTML = `<div class="settings-tabs"><button type="button" class="settings-tab active" data-notify-tab="likes">${t('receivedLikes')} <span>${likes.length}</span></button><button type="button" class="settings-tab" data-notify-tab="replies">${t('receivedReplies')} <span>${replies.length}</span></button></div><section class="notification-group" data-notify-panel="likes">${likes.map(renderItem).join('') || '<p class="notification-empty">暂时没有收到的赞</p>'}</section><section class="notification-group hidden" data-notify-panel="replies">${replies.map(renderItem).join('') || '<p class="notification-empty">暂时没有收到的回复</p>'}</section>`;
    feed.querySelectorAll('.settings-tab').forEach((tab) => tab.onclick = () => {
        feed.querySelectorAll('.settings-tab').forEach((x) => x.classList.toggle('active', x === tab));
        feed.querySelectorAll('[data-notify-panel]').forEach((panel) => panel.classList.toggle('hidden', panel.dataset.notifyPanel !== tab.dataset.notifyTab));
    });
}

// 通知红点：对比「最新一条通知时间」与「上次已读标记」（localStorage，按账号隔离）。
// 同一接口返回的 createdAt 字符串格式一致，直接字符串比较即可。
const NOTIF_SEEN_KEY = `lastNotifSeen:${user.id}`;

function latestNotificationTime(data) {
    return [...(data.likes || []), ...(data.replies || [])]
        .map((item) => String(item.createdAt || ''))
        .sort()
        .pop() || '';
}

function setNotificationDot(hasUnread) {
    $$('[data-view="notifications"]')
        .forEach((button) => button.classList.toggle('has-unread', hasUnread));
}

async function refreshNotificationDot() {
    try {
        const response = await fetch(`/api/users/${user.id}/notifications`);
        if (!response.ok) return;
        const data = await response.json();
        const latest = latestNotificationTime(data);
        if (!latest) return;
        const seen = localStorage.getItem(NOTIF_SEEN_KEY) || '';
        setNotificationDot(!seen || latest > seen);
    } catch (_) {}
}

async function loadNotifications() {
    try {
        const response = await fetch(`/api/users/${user.id}/notifications`);
        const data = response.ok ? await response.json() : { likes: [], replies: [] };
        renderNotifications(data);
        const latest = latestNotificationTime(data);
        if (latest) localStorage.setItem(NOTIF_SEEN_KEY, latest);
    } catch (_) {
        renderNotifications({ likes: [], replies: [] });
    }
    setNotificationDot(false);
}

function updateLoading(atBottom) {
    const loading = $('#loading');
    const hasMore = nextIndex < allPosts.length;
    loading.textContent = hasMore ? t('scrollHint') : t('endHint');
    loading.classList.toggle('hidden', !atBottom || hasMore);
}

function preload() {
    while (nextIndex < allPosts.length) {
        visiblePosts.push(allPosts[nextIndex++]);
    }
}

async function loadPostStatus(posts) {
    likedPosts.clear();
    favPosts.clear();
    await Promise.all(posts.map(async (post) => {
        try {
            const res = await fetch(`/api/posts/${post.id}/status?userId=${user.id}`);
            if (!res.ok) return;
            const status = await res.json();
            if (status.liked) likedPosts.add(Number(post.id));
            if (status.favorited) favPosts.add(Number(post.id));
        } catch (_) {}
    }));
}

function renderBoardTabs() {
    const tabs = $('#board-tabs');
    tabs.classList.add('hidden');
}

async function loadFeed() {
    if (selectedBoard === 'all') {
        allPosts = [];
    } else {
        try {
            allPosts = await fetchPosts();
            await loadPostStatus(allPosts);
        } catch (_) {
            allPosts = [];
        }
    }
    visiblePosts = [];
    nextIndex = 0;
    preload();
    renderFeed();
    renderBoardTabs();
}

function setSidebarActive(view) {
    $$('.profile-menu button, #notifications-button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
}

function setTopBoardActive(board) {
    $$('#boards .board').forEach((b) => b.classList.toggle('active', b.dataset.board === board));
    $$('#drawer-nav [data-board]').forEach((b) => b.classList.toggle('active', b.dataset.board === board));
}

function setMobileViewActive(view) {
    $$('#drawer-nav [data-view]').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
}

function openDrawer() { $('#mobile-drawer').classList.add('open'); }
function closeDrawer() { $('#mobile-drawer').classList.remove('open'); }

function handleAction(button) {
    const postId = Number(button.dataset.id);
    const post = allPosts.find((item) => Number(item.id) === postId);
    if (!post) return;

    if (button.dataset.action === 'like') {
        fetch(`/api/posts/${postId}/like`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: user.id })
        }).then((r) => r.json()).then((data) => {
            post.likes = data.likes;
            if (data.liked) likedPosts.add(postId); else likedPosts.delete(postId);
            renderFeed();
        });
    }
    if (button.dataset.action === 'favorite') {
        const enabling = !favPosts.has(postId);
        fetch('/api/favorites', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: user.id, postId, favorite: enabling })
        }).then((r) => r.json()).then((data) => {
            post.favorites = data.favorites;
            if (data.favorited) favPosts.add(postId); else favPosts.delete(postId);
            renderFeed();
            loadUserStats();
        });
    }
    if (button.dataset.action === 'edit') {
        openComposeModal(post);
    }
    if (button.dataset.action === 'delete') {
        if (!confirm(t('deleteConfirm'))) return;
        fetch(`/api/posts/${postId}?userId=${user.id}`, { method: 'DELETE' })
            .then((r) => r.json())
            .then(() => { alert(t('deleted')); loadFeed(); loadUserStats(); });
    }
}

$('#feed').addEventListener('scroll', () => {
    const feed = $('#feed');
    const atBottom = feed.scrollTop + feed.clientHeight >= feed.scrollHeight - 4;
    if (feed.scrollTop + feed.clientHeight > feed.scrollHeight - 240) {
        const before = nextIndex;
        preload();
        if (nextIndex > before) renderFeed();
    }
    updateLoading(atBottom && nextIndex >= allPosts.length);
});

function handleBoardSwitch(button) {
    setTopBoardActive(button.dataset.board);
    selectedBoard = button.dataset.board;
    boardRecommended = false;
    currentView = 'feed';
    partitionBoard = '';
    setSidebarActive('');
    setMobileViewActive('');
    closeDrawer();
    const titles = { '': t('recommend'), all: t('browseBoards') };
    $('#feed-title').textContent = titles[selectedBoard] || button.textContent;
    window.scrollTo({ top: 0, behavior: 'smooth' });
    loadFeed();
}

function handleViewSwitch(button) {
    const view = button.dataset.view;
    if (view === 'settings') { closeDrawer(); $('#settings-button').click(); return; }
    if (view === 'search') { closeDrawer(); location.href = '/search.html'; return; }
    currentView = view;
    selectedBoard = '';
    boardRecommended = false;
    partitionBoard = '';
    setSidebarActive(currentView);
    setTopBoardActive('');
    $$('#boards .board').forEach((b) => b.classList.remove('active'));
    setMobileViewActive(currentView);
    closeDrawer();
    const titleMap = { home: t('myHome'), favorites: t('myFavorites'), history: t('myHistory') };
    if (currentView === 'notifications') {
        $('#feed-title').textContent = t('notifications');
        $('#main-search-form').classList.remove('hidden');
        loadNotifications();
        return;
    }
    $('#feed-title').textContent = titleMap[currentView];
    if (currentView === 'home') currentView = 'myhome';
    window.scrollTo({ top: 0, behavior: 'smooth' });
    loadFeed();
}

$$('#boards .board').forEach((button) => { button.onclick = () => handleBoardSwitch(button); });
$$('#drawer-nav [data-board]').forEach((button) => { button.onclick = () => handleBoardSwitch(button); });

$$('.profile-menu [data-view]').forEach((button) => { button.onclick = () => handleViewSwitch(button); });
$$('#drawer-nav [data-view]').forEach((button) => { button.onclick = () => handleViewSwitch(button); });
$('#notifications-button').onclick = () => handleViewSwitch($('#notifications-button'));

$('#topbar-menu-toggle').onclick = openDrawer;
$('#drawer-backdrop').onclick = closeDrawer;
$('#drawer-close').onclick = closeDrawer;

$('#mobile-search-form').onsubmit = (event) => {
    event.preventDefault();
    const query = new FormData(event.target).get('q')?.toString().trim();
    if (query) location.href = `/search.html?q=${encodeURIComponent(query)}`;
};

$('#mobile-logout-button').onclick = () => {
    sessionStorage.removeItem('tiebaUser');
    location.href = '/';
};

function showModal(title, body) {
    $('#modal-root').innerHTML = `<div class="modal-backdrop"><section class="modal"><button class="modal-close">×</button><h3>${title}</h3>${body}</section></div>`;
    $('.modal-close').onclick = () => { $('#modal-root').innerHTML = ''; };
    $('.modal-backdrop').onclick = (e) => { if (e.target.classList.contains('modal-backdrop')) $('#modal-root').innerHTML = ''; };
}

async function uploadFile(file) {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/api/upload', { method: 'POST', body: form });
    if (!res.ok) throw new Error('upload failed');
    return await res.json();
}

function composeFormHtml(post) {
    const isEdit = !!post;
    const selectedBoard = BOARD_DATA.find((board) => board.name === post?.boardName);
    const parent = selectedBoard?.parentId ? BOARD_DATA.find((board) => Number(board.id) === Number(selectedBoard.parentId)) : selectedBoard;
    return `<form id="compose-form">
        <input type="hidden" name="postId" value="${post ? post.id : ''}">
        <fieldset class="board-picker-fieldset"><legend>${t('board')}</legend>${boardPickerMarkup('compose', parent?.name || '', selectedBoard?.parentId ? selectedBoard.name : '')}</fieldset>
        <label>${t('title')}<input name="title" required maxlength="150" value="${post ? post.title.replace(/"/g, '&quot;') : ''}"></label>
        <label>${t('content')}<textarea name="content" required rows="6">${post ? post.content : ''}</textarea></label>
        <label>${t('imageFile')}<input name="imageFile" type="file" accept="image/*"></label>
        <button type="button" class="file-clear hidden" data-clear-file="imageFile">删除已选图片</button>
        <p class="paste-hint">${t('pasteHint')}</p>
        <div id="paste-preview" class="paste-preview"></div>
        <input type="hidden" name="imageUrl" value="${post && post.imageUrl ? post.imageUrl : ''}">
        <button class="primary" type="submit">${isEdit ? t('save') : t('publish')}</button>
    </form>`;
}

function openComposeModal(post) {
    showModal(post ? t('editTitle') : t('publishTitle'), composeFormHtml(post));
    const form = $('#compose-form');
    const readBoard = setupBoardPicker(form.querySelector('[data-picker="compose"]'));
    const preview = $('#paste-preview');
    let pastedImage = null;

    form.querySelector('[name=imageFile]').onchange = (e) => {
        pastedImage = e.target.files[0] || null;
        preview.innerHTML = pastedImage ? `<img src="${URL.createObjectURL(pastedImage)}" alt="">` : '';
        form.querySelector('[data-clear-file=imageFile]').classList.toggle('hidden', !pastedImage);
    };
    form.querySelectorAll('[data-clear-file]').forEach((button) => button.onclick = () => {
        const name = button.dataset.clearFile;
        form.querySelector(`[name=${name}]`).value = '';
        if (name === 'imageFile') pastedImage = null;
        preview.innerHTML = '';
        button.classList.add('hidden');
    });

    form.onpaste = async (e) => {
        const items = e.clipboardData?.items;
        if (!items) return;
        for (const item of items) {
            if (item.type.startsWith('image/')) {
                e.preventDefault();
                pastedImage = item.getAsFile();
                preview.innerHTML = `<img src="${URL.createObjectURL(pastedImage)}" alt="">`;
                form.querySelector('[data-clear-file=imageFile]').classList.remove('hidden');
                break;
            }
        }
    };

    form.onsubmit = async (e) => {
        e.preventDefault();
        const data = Object.fromEntries(new FormData(form));
        let imageUrl = data.imageUrl || '';
        const imageFile = form.querySelector('[name=imageFile]').files[0] || (pastedImage && pastedImage.type?.startsWith('image/') ? pastedImage : null);
        try {
            if (imageFile) { const up = await uploadFile(imageFile); imageUrl = up.url; }
        } catch (_) { alert('文件上传失败'); return; }

        const boardSelection = readBoard();
        const board = boardSelection.child || boardSelection.main;
        if (!board) { alert('请选择主版面或子版面'); return; }
        // 校验所选版面真实存在；若填了子版面，须属于所选主版面
        const mainName = boardSelection.main;
        const target = BOARD_DATA.find((b) => b.name === board);
        if (!target) {
            alert(`版面「${board}」不存在，请从下拉候选中选择`);
            return;
        }
        if (target.parentId) {
            const parent = BOARD_DATA.find((b) => Number(b.id) === Number(target.parentId));
            if (mainName && parent && parent.name !== mainName) {
                alert(`子版面「${board}」不属于主版面「${mainName}」，请从下拉候选中选择`);
                return;
            }
        } else if (boardSelection.child) {
            // 用户填了子版面输入但值不存在（target 是主板块而非子板块）
            alert(`子版面「${boardSelection.child}」不存在，请从下拉候选中选择`);
            return;
        }
        const payload = { userId: user.id, board, title: data.title, content: data.content, imageUrl, videoUrl: '' };
        const postId = data.postId;
        const url = postId ? `/api/posts/${postId}` : '/api/posts';
        const method = postId ? 'PUT' : 'POST';
        await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
        $('#modal-root').innerHTML = '';
        loadFeed();
        loadUserStats();
    };
}

$('#compose-button').onclick = () => openComposeModal(null);

$('#settings-button').onclick = () => {
    const level = localStorage.getItem('fontLevel') ?? '3';
        const lang = getLang();
        const alpha = localStorage.getItem('panelAlpha') ?? '90';
        const isManager = user.userType === 'ADMIN' || user.userType === 'MODERATOR';
        showModal(t('settingsTitle'), `
        ${isManager ? `<div class="settings-tabs"><button type="button" class="settings-tab active" data-settings-tab="basic">${t('basicSettings')}</button><button type="button" class="settings-tab" data-settings-tab="admin">${t('adminFunctions')}</button></div>` : ''}
        <div class="settings-panel" data-settings-panel="basic">
        <form id="profile-form">
            <label>登录用户名<input name="account" required maxlength="50" value="${user.account || ''}"></label>
            <label>发帖昵称<input name="username" required maxlength="50" value="${user.username || ''}"></label>
            <label>新密码（留空则不修改）<input name="password" type="password" minlength="8" maxlength="32"></label>
            <button class="primary" type="submit">保存个人信息</button>
        </form>
        <form id="avatar-form">
            <label>自定义头像<input type="file" id="avatar-file" accept="image/jpeg,image/png,image/gif,image/webp" required></label>
            <div id="avatar-preview" class="avatar-preview">${avatarMarkup(user.avatar, user.username)}</div>
            <button class="primary" type="submit">上传头像</button>
        </form>
        <form id="background-form">
            <label>网页背景图片（自动适应窗口）<input type="file" id="background-file" accept="image/jpeg,image/png,image/webp" required></label>
            <button class="primary" type="submit">应用背景图片</button>
        </form>
        <label>${t('panelOpacity')}
            <input type="range" id="panel-alpha-setting" min="30" max="100" step="5" value="${alpha}">
            <span id="panel-alpha-preview">${alpha}%</span>
        </label>
        <label>${t('fontSize')}
            <input type="range" id="font-setting" min="0" max="6" step="1" value="${level}">
            <span id="font-preview">${FONT_SCALES[level]}×</span>
        </label>
        <label>${t('language')}
            <select id="lang-setting"><option value="zh" ${lang === 'zh' ? 'selected' : ''}>简体中文</option>
            <option value="en" ${lang === 'en' ? 'selected' : ''}>English</option></select>
        </label>
        </div>
        ${isManager ? '<div class="settings-panel hidden" data-settings-panel="admin">' : ''}
        ${user.userType === 'ADMIN' ? `<form id="board-form"><div class="board-create-columns"><div class="board-create-column"><label>所属主版面</label><input name="parentName" class="board-picker-input" placeholder="输入主版面关键字"><div class="board-picker-results" data-role="create-parent-results"></div></div><div class="board-create-column"><label>新建版面名称</label><input name="name" required maxlength="50" placeholder="输入版面关键字"><div class="board-picker-results" data-role="create-name-results"></div></div></div><label>版面图标<input name="iconFile" type="file" accept="image/jpeg,image/png,image/gif,image/webp"></label><button class="primary" type="submit">新建版面</button></form><form id="admin-form"><label>删除版面<input name="board" class="board-picker-input" placeholder="输入版面关键字" autocomplete="off"><div class="board-picker-results" data-role="delete-board-results"></div></label><button class="primary" type="submit" formnovalidate>删除版面</button><label>封禁/解封用户 ID<input name="targetId" type="number" required></label><label><input name="banned" type="checkbox"> 封禁账号</label><button class="primary" type="submit">更新账号状态</button></form><form id="moderator-form"><label>版主用户 ID<input name="targetUserId" type="number" min="1" required></label><label>负责主版面<select name="boardName">${BOARD_DATA.filter((board) => !board.parentId).map((board) => `<option>${board.name}</option>`).join('')}</select></label><label><input name="enabled" type="checkbox" checked> 设置为版主</label><button class="primary" type="submit">更新版主权限</button></form><form id="admin-post-delete-form"><label>按帖子识别码删除<input name="postId" type="number" min="1" required placeholder="例如 42"></label><button class="primary" type="submit">删除帖子</button></form><form id="admin-user-delete-form"><p class="paste-hint">删除用户将级联清理其发布的帖子、回复、点赞、收藏、关注、浏览记录与版主身份，不可恢复。</p><label>删除用户 ID<input name="targetId" type="number" min="1" required placeholder="例如 5"></label><button class="primary" type="submit">删除用户</button></form><form id="notice-form"><label>${t('noticeTitleLabel')}<input name="title" required maxlength="100" value="${(siteNotice?.title || '').replace(/"/g, '&quot;')}"></label><label>${t('noticeContentLabel')}<textarea name="content" required rows="3" maxlength="500">${siteNotice?.content || ''}</textarea></label><button class="primary" type="submit">${t('saveNotice')}</button></form>` : ''}
        ${user.userType === 'MODERATOR' ? `<form id="mod-board-form"><p class="paste-hint">您是版主，仅可在自己负责的主版面下新建子版面。</p><div class="board-create-columns"><div class="board-create-column"><label>所属主版面</label><input name="parentName" class="board-picker-input" placeholder="输入主版面关键字" autocomplete="off"><div class="board-picker-results" data-role="mod-parent-results"></div></div><div class="board-create-column"><label>新建子版面名称</label><input name="name" required maxlength="50" placeholder="输入子版面名称" autocomplete="off"><div class="board-picker-results" data-role="mod-name-results"></div></div></div><button class="primary" type="submit">新建子版面</button></form><form id="mod-board-delete-form"><label>删除子版面<input name="board" class="board-picker-input" placeholder="输入子版面关键字" autocomplete="off"><div class="board-picker-results" data-role="mod-delete-results"></div></label><button class="primary" type="submit">删除子版面</button></form><form id="mod-post-delete-form"><p class="paste-hint">您是版主，仅能删除您负责主版面（含其子版面）下的帖子。</p><label>按帖子识别码删除<input name="postId" type="number" min="1" required placeholder="例如 42"></label><button class="primary" type="submit">删除帖子</button></form>` : ''}
        ${isManager ? '</div>' : ''}`);
        const modalRoot = $('#modal-root');
        modalRoot.querySelectorAll('.settings-tab').forEach((tab) => tab.onclick = () => {
            modalRoot.querySelectorAll('.settings-tab').forEach((x) => x.classList.toggle('active', x === tab));
            modalRoot.querySelectorAll('.settings-panel').forEach((panel) => panel.classList.toggle('hidden', panel.dataset.settingsPanel !== tab.dataset.settingsTab));
        });
        $('#panel-alpha-setting').oninput = (e) => {
            applyPanelAlpha(e.target.value);
            $('#panel-alpha-preview').textContent = `${e.target.value}%`;
        };
        const createForm = $('#board-form');
        if (createForm) {
            const parentInput = createForm.querySelector('[name=parentName]');
            const nameInput = createForm.querySelector('[name=name]');
            const parentResults = createForm.querySelector('[data-role="create-parent-results"]');
            const nameResults = createForm.querySelector('[data-role="create-name-results"]');
            const drawCreateCandidates = () => {
                const parentQuery = parentInput.value.trim().toLowerCase();
                const nameQuery = nameInput.value.trim().toLowerCase();
                parentResults.innerHTML = BOARD_DATA.filter((board) => !board.parentId && board.name.toLowerCase().includes(parentQuery)).map((board) => `<button type="button" data-value="${board.name}">${board.name}</button>`).join('') || '<span>无匹配主版面</span>';
                nameResults.innerHTML = BOARD_DATA.filter((board) => board.name.toLowerCase().includes(nameQuery)).slice(0, 8).map((board) => `<button type="button" data-value="${board.name}">${board.name}</button>`).join('') || '<span>可手动输入新名称</span>';
                parentResults.querySelectorAll('button').forEach((button) => button.onclick = () => { parentInput.value = button.dataset.value; });
                nameResults.querySelectorAll('button').forEach((button) => button.onclick = () => { nameInput.value = button.dataset.value; });
            };
            parentInput.oninput = drawCreateCandidates;
            nameInput.oninput = drawCreateCandidates;
            drawCreateCandidates();
        }
        $('#avatar-file').onchange = (e) => {
            const file = e.target.files[0];
            if (file) $('#avatar-preview').innerHTML = `<img class="avatar-image" src="${URL.createObjectURL(file)}" alt="头像预览">`;
        };
        $('#avatar-form').onsubmit = async (e) => {
            e.preventDefault();
            const formData = new FormData();
            formData.append('userId', user.id);
            formData.append('file', $('#avatar-file').files[0]);
            const response = await fetch('/api/upload/avatar', { method: 'POST', body: formData });
            const result = await response.json();
            if (!response.ok) { alert(result.message || '头像上传失败'); return; }
            user.avatar = result.url;
            sessionStorage.setItem('tiebaUser', JSON.stringify(user));
            $('#sidebar-avatar').innerHTML = avatarMarkup(user.avatar, user.username);
            $('#modal-root').innerHTML = '';
            loadFeed();
        };
    $('#profile-form').onsubmit = async (e) => {
        e.preventDefault();
        const data = Object.fromEntries(new FormData(e.target));
        const response = await fetch(`/api/users/${user.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: user.id, ...data, backgroundUrl: user.backgroundUrl || null }) });
        const result = await response.json();
        if (!response.ok) { alert(result.message || '保存失败'); return; }
        Object.assign(user, result.user); sessionStorage.setItem('tiebaUser', JSON.stringify(user));
        $('#profile-name').textContent = user.username; alert('个人信息已保存');
    };
    $('#background-form').onsubmit = async (e) => {
        e.preventDefault(); const formData = new FormData(); formData.append('userId', user.id); formData.append('file', $('#background-file').files[0]);
        const response = await fetch('/api/upload/background', { method: 'POST', body: formData }); const result = await response.json();
        if (!response.ok) { alert(result.message || '背景图上传失败'); return; }
        user.backgroundUrl = result.url; sessionStorage.setItem('tiebaUser', JSON.stringify(user)); applyBackground(result.url); alert('背景图片已应用');
    };
    $('#board-form')?.addEventListener('submit', async (e) => {
        e.preventDefault(); const data = Object.fromEntries(new FormData(e.target));
        const file = e.target.querySelector('[name=iconFile]').files[0];
        let icon = '';
        if (file) {
            const upload = new FormData(); upload.append('userId', user.id); upload.append('file', file);
            const iconResponse = await fetch('/api/upload/board-icon', { method: 'POST', body: upload });
            const iconResult = await iconResponse.json(); if (!iconResponse.ok) { alert(iconResult.message || '图标上传失败'); return; }
            icon = iconResult.url;
        }
        const response = await fetch('/api/boards', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: user.id, name: data.name, parentName: data.parentName, icon }) });
        const result = await response.json(); if (!response.ok) { alert(result.message); return; }
        $('#modal-root').innerHTML = ''; await loadTrending(); renderBoardTabs(); alert(result.message);
    });
    $('#admin-form')?.addEventListener('submit', async (e) => {
        e.preventDefault(); const data = Object.fromEntries(new FormData(e.target));
        if (e.submitter?.textContent.includes('删除')) {
            if (!data.board?.trim()) { alert('请输入要删除的版面名称'); return; }
            const response = await fetch(`/api/boards/${encodeURIComponent(data.board.trim())}?userId=${user.id}`, { method: 'DELETE' });
            const result = await response.json(); if (!response.ok) { alert(result.message || '删除失败'); return; }
            $('#modal-root').innerHTML = ''; await loadTrending(); renderBoardTabs(); alert(result.message); return;
        }
        const response = await fetch(`/api/users/${data.targetId}/ban`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: user.id, banned: Boolean(data.banned) }) });
        const result = await response.json(); alert(result.message || '操作失败');
    });
    $('#moderator-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = Object.fromEntries(new FormData(e.target));
        const response = await fetch('/api/boards/moderators', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: user.id, targetUserId: Number(data.targetUserId), boardName: data.boardName, enabled: Boolean(data.enabled) }) });
        const result = await response.json(); alert(result.message || '操作失败');
    });
    $('#admin-post-delete-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const postId = new FormData(e.target).get('postId');
        if (!confirm(`确认删除识别码为 ${postId} 的帖子吗？`)) return;
        const response = await fetch(`/api/posts/${encodeURIComponent(postId)}?userId=${user.id}`, { method: 'DELETE' });
        const result = await response.json();
        if (!response.ok) { alert(result.message || '删除失败'); return; }
        e.target.reset(); loadFeed(); alert(result.message);
    });
    $('#admin-user-delete-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const targetId = new FormData(e.target).get('targetId');
        if (!confirm(`确认删除用户 ID ${targetId}？该用户发布的所有帖子、回复、点赞、收藏、关注、浏览记录及版主身份将一并删除，且无法恢复。`)) return;
        const response = await fetch(`/api/users/${encodeURIComponent(targetId)}?userId=${user.id}`, { method: 'DELETE' });
        const result = await response.json();
        if (!response.ok) { alert(result.message || '删除失败'); return; }
        e.target.reset(); loadFeed(); loadUserStats(); alert(result.message);
    });
    $('#notice-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = Object.fromEntries(new FormData(e.target));
        const response = await fetch('/api/notice', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: user.id, title: data.title, content: data.content }) });
        const result = await response.json();
        if (!response.ok) { alert(result.message || '保存失败'); return; }
        siteNotice = { title: result.title, content: result.content };
        renderSiteNotice();
        alert(result.message);
    });
    $('#mod-post-delete-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const postId = new FormData(e.target).get('postId');
        if (!confirm(`确认删除识别码为 ${postId} 的帖子吗？`)) return;
        const response = await fetch(`/api/posts/${encodeURIComponent(postId)}?userId=${user.id}`, { method: 'DELETE' });
        const result = await response.json();
        if (!response.ok) { alert(result.message || '无权删除或帖子不存在'); return; }
        e.target.reset(); loadFeed(); alert(result.message);
    });
    // 管理员删除版面：输入搜索候选（替代原 select）
    const adminBoardInput = $('#admin-form [name=board]');
    const adminBoardResults = $('#admin-form [data-role="delete-board-results"]');
    if (adminBoardInput && adminBoardResults) {
        const drawAdminBoard = () => {
            const q = adminBoardInput.value.trim().toLowerCase();
            adminBoardResults.innerHTML = BOARD_DATA.filter((b) => b.name.toLowerCase().includes(q)).slice(0, 8)
                .map((b) => `<button type="button" data-value="${b.name}">${b.name}</button>`).join('') || '<span>无匹配版面</span>';
            adminBoardResults.querySelectorAll('button').forEach((btn) => btn.onclick = () => { adminBoardInput.value = btn.dataset.value; });
        };
        adminBoardInput.oninput = drawAdminBoard;
        drawAdminBoard();
    }
    // 版主新建子版面
    const modBoardForm = $('#mod-board-form');
    if (modBoardForm) {
        const parentInput = modBoardForm.querySelector('[name=parentName]');
        const nameInput = modBoardForm.querySelector('[name=name]');
        const parentResults = modBoardForm.querySelector('[data-role="mod-parent-results"]');
        const nameResults = modBoardForm.querySelector('[data-role="mod-name-results"]');
        const drawModCandidates = () => {
            const pq = parentInput.value.trim().toLowerCase();
            const nq = nameInput.value.trim().toLowerCase();
            parentResults.innerHTML = BOARD_DATA.filter((b) => !b.parentId && b.name.toLowerCase().includes(pq))
                .map((b) => `<button type="button" data-value="${b.name}">${b.name}</button>`).join('') || '<span>无匹配主版面</span>';
            nameResults.innerHTML = BOARD_DATA.filter((b) => b.name.toLowerCase().includes(nq)).slice(0, 8)
                .map((b) => `<button type="button" data-value="${b.name}">${b.name}</button>`).join('') || '<span>可手动输入新名称</span>';
            parentResults.querySelectorAll('button').forEach((btn) => btn.onclick = () => { parentInput.value = btn.dataset.value; });
            nameResults.querySelectorAll('button').forEach((btn) => btn.onclick = () => { nameInput.value = btn.dataset.value; });
        };
        parentInput.oninput = drawModCandidates;
        nameInput.oninput = drawModCandidates;
        drawModCandidates();
        modBoardForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const data = Object.fromEntries(new FormData(e.target));
            if (!data.parentName?.trim() || !data.name?.trim()) { alert('请填写所属主版面和子版面名称'); return; }
            const response = await fetch('/api/boards', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId: user.id, name: data.name.trim(), parentName: data.parentName.trim(), icon: '' })
            });
            const result = await response.json();
            if (!response.ok) { alert(result.message || '新建失败'); return; }
            $('#modal-root').innerHTML = ''; await loadTrending(); renderBoardTabs(); alert(result.message);
        });
    }
    // 版主删除子版面：候选只列子版面，后端再做权限校验
    const modDeleteInput = $('#mod-board-delete-form [name=board]');
    const modDeleteResults = $('#mod-board-delete-form [data-role="mod-delete-results"]');
    if (modDeleteInput && modDeleteResults) {
        const drawModDelete = () => {
            const q = modDeleteInput.value.trim().toLowerCase();
            modDeleteResults.innerHTML = BOARD_DATA.filter((b) => b.parentId && b.name.toLowerCase().includes(q)).slice(0, 8)
                .map((b) => `<button type="button" data-value="${b.name}">${b.name}</button>`).join('') || '<span>无匹配子版面</span>';
            modDeleteResults.querySelectorAll('button').forEach((btn) => btn.onclick = () => { modDeleteInput.value = btn.dataset.value; });
        };
        modDeleteInput.oninput = drawModDelete;
        drawModDelete();
    }
    $('#mod-board-delete-form')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const data = Object.fromEntries(new FormData(e.target));
        if (!data.board?.trim()) { alert('请输入要删除的子版面名称'); return; }
        if (!confirm(`确认删除子版面「${data.board}」吗？该版面下的帖子将一并删除。`)) return;
        const response = await fetch(`/api/boards/${encodeURIComponent(data.board)}?userId=${user.id}`, { method: 'DELETE' });
        const result = await response.json();
        if (!response.ok) { alert(result.message || '删除失败'); return; }
        $('#modal-root').innerHTML = ''; await loadTrending(); renderBoardTabs(); alert(result.message);
    });
    $('#font-setting').oninput = (e) => {
        applyFontScale(e.target.value);
        $('#font-preview').textContent = `${FONT_SCALES[e.target.value]}×`;
    };
    $('#lang-setting').onchange = (e) => {
        applyLanguage(e.target.value);
        alert(t('langSaved'));
        $('#modal-root').innerHTML = '';
        location.reload();
    };
};

function applyBackground(url) {
    document.body.style.setProperty('--custom-background', url ? `url("${url}")` : 'none');
    document.body.classList.toggle('has-custom-background', Boolean(url));
}

$('#main-search-form').onsubmit = (event) => {
    event.preventDefault();
    const query = new FormData(event.target).get('q')?.toString().trim();
    if (query) location.href = `/search.html?q=${encodeURIComponent(query)}`;
};

function searchScore(post, query) {
    const text = `${post.title} ${post.content} ${post.username}`.toLowerCase();
    const normalized = query.replace(/\s/g, '').toLowerCase();
    if (!normalized) return 0;
    let score = text.includes(normalized) ? 1000 : 0;
    for (let index = 0; index < normalized.length - 1; index += 1) {
        if (text.includes(normalized.slice(index, index + 2))) score += 120;
    }
    if ([...normalized].every((char) => text.includes(char))) score += 30;
    let cursor = 0;
    for (const char of normalized) {
        const found = text.indexOf(char, cursor);
        if (found < 0) break;
        score += 12;
        cursor = found + 1;
    }
    return score - Math.min(text.indexOf(normalized), 100);
}

$('#logout-button').onclick = () => {
    sessionStorage.removeItem('tiebaUser');
    location.href = '/';
};

$('#all-boards').onclick = () => {
    setTopBoardActive('all');
    selectedBoard = 'all';
    boardRecommended = false;
    currentView = 'feed';
    partitionBoard = '';
    setSidebarActive('');
    $('#feed-title').textContent = t('browseBoards');
    loadFeed();
};

loadUserStats();
applyBackground(user.backgroundUrl);
refreshNotificationDot();
loadSiteNotice();
loadTrending().then(() => loadFeed());
