const postId = new URLSearchParams(location.search).get('id');
if (!postId) location.href = '/forum-main.html';

const user = JSON.parse(sessionStorage.getItem('tiebaUser') || 'null');
const avatarMarkup = (avatar, username) => avatar
    ? `<img class="avatar-image" src="${avatar}" alt="${username || '用户'}头像">`
    : (username || '校')[0];
if (!user) { location.href = '/'; throw new Error('请先登录'); }

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

const $ = (selector) => document.querySelector(selector);
let currentPost = null;
let followingBoard = false;
let liked = false;
let favorited = false;
let canManage = false;
let replyTargetId = null;

initSettings();

async function recordHistory() {
    await fetch('/api/history', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: user.id, postId: Number(postId) })
    }).catch(() => {});
}

async function loadStatus() {
    const res = await fetch(`/api/posts/${postId}/status?userId=${user.id}`);
    if (!res.ok) return;
    const status = await res.json();
    liked = status.liked;
    favorited = status.favorited;
    canManage = Boolean(status.canManage);
}

async function loadBoardStatus(boardName) {
    const res = await fetch(`/api/boards/status?userId=${user.id}&board=${encodeURIComponent(boardName)}`);
    if (!res.ok) return;
    const data = await res.json();
    followingBoard = data.following;
}

function renderPost(post) {
    currentPost = post;
    const isOwner = Number(post.userId) === Number(user.id);
    const img = post.imageUrl && post.imageUrl.trim();
    $('#post-breadcrumb').innerHTML = `<a class="breadcrumb-chip breadcrumb-home" href="/forum-main.html">论坛主页</a><span class="separator">/</span><a class="breadcrumb-chip breadcrumb-board" href="/forum-main.html?board=${encodeURIComponent(post.boardName || '')}">${post.boardName || '当前版面'}</a><span class="separator">/</span><span class="breadcrumb-chip breadcrumb-post">${post.title}</span>`;
    $('#detail').innerHTML = `
            <a class="post-author profile-author" href="/profile.html?id=${post.userId}" title="查看发布者个人主页"><span class="avatar">${avatarMarkup(post.avatar, post.username)}</span>
        <span>${post.username} · ${post.boardName || ''} · ${(post.createdAt || '').replace('T', ' ').slice(0, 16)} · 识别码：${post.id}</span></a>
        <div class="detail-board-bar">
            <span class="board-badge">${post.boardName}</span>
            <button id="follow-board-btn" class="follow-board-btn">${followingBoard ? t('unfollowBoard') : t('followBoard')}</button>
        </div>
        <h1>${post.title}</h1>
        <div class="detail-content">${post.content}</div>
        ${img ? `<img class="post-media" src="${img}" alt="帖子图片" title="点击查看大图">` : ''}
        <div class="post-meta detail-actions">
            <button id="like-btn">${liked ? '♥' : '♡'} ${post.likes || 0}</button>
            <button id="fav-btn">${favorited ? '★' : '☆'} ${post.favorites || 0}</button>
            <span>◉ ${post.views || 0}</span>
            ${isOwner ? `<button id="delete-btn">${t('delete')}</button>` : ''}
            ${!isOwner && canManage ? '<button id="admin-delete-btn">删除帖子</button>' : ''}
        </div>`;

    const detailImg = document.querySelector('#detail img.post-media');
    if (detailImg) detailImg.onclick = () => openImagePreview(detailImg.src);

    $('#follow-board-btn').onclick = async () => {
        const res = await fetch('/api/boards/follow', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: user.id, board: post.boardName, follow: !followingBoard })
        });
        const data = await res.json();
        followingBoard = data.following;
        $('#follow-board-btn').textContent = followingBoard ? t('unfollowBoard') : t('followBoard');
    };

    $('#like-btn').onclick = async () => {
        const res = await fetch(`/api/posts/${postId}/like`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: user.id })
        });
        const data = await res.json();
        liked = data.liked;
        currentPost.likes = data.likes;
        $('#like-btn').textContent = `${liked ? '♥' : '♡'} ${data.likes}`;
    };

    $('#fav-btn').onclick = async () => {
        const enabling = !favorited;
        const res = await fetch('/api/favorites', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: user.id, postId: Number(postId), favorite: enabling })
        });
        const data = await res.json();
        favorited = data.favorited;
        currentPost.favorites = data.favorites;
        $('#fav-btn').textContent = `${favorited ? '★' : '☆'} ${data.favorites}`;
    };

    if (isOwner) {
        $('#delete-btn').onclick = async () => {
            if (!confirm(t('deleteConfirm'))) return;
            await fetch(`/api/posts/${postId}?userId=${user.id}`, { method: 'DELETE' });
            alert(t('deleted'));
            location.href = '/forum-main.html';
        };
    }
    if (!isOwner && canManage) {
        $('#admin-delete-btn').onclick = async () => {
            if (!confirm(`确认删除识别码为 ${post.id} 的帖子吗？`)) return;
            const response = await fetch(`/api/posts/${post.id}?userId=${user.id}`, { method: 'DELETE' });
            const result = await response.json();
            if (!response.ok) { alert(result.message || '删除失败'); return; }
            alert(result.message); location.href = '/forum-main.html';
        };
    }
}

async function loadPost() {
    try {
        const response = await fetch('/api/posts/' + encodeURIComponent(postId));
        if (!response.ok) throw new Error('帖子不存在');
        const post = await response.json();
        await loadStatus();
        await loadBoardStatus(post.boardName);
        renderPost(post);
        recordHistory();
    } catch (error) {
        $('#detail').innerHTML = '<p>帖子加载失败</p>';
    }
    loadReplies();
}

async function loadReplies() {
    try {
        const response = await fetch(`/api/posts/${encodeURIComponent(postId)}/replies?userId=${user.id}`);
        const replies = await response.json();
        const roots = replies.filter((reply) => !reply.parentId);
        const children = replies.filter((reply) => reply.parentId);
        const renderReply = (reply, nested = false) => {
            const isReplyOwner = Number(reply.userId) === Number(user.id);
            const canDeleteReply = isReplyOwner || canManage;
            return `<article class="reply ${nested ? 'reply-nested' : ''}"><a class="reply-author profile-author" href="/profile.html?id=${reply.userId}" title="查看评论者个人主页"><span class="reply-avatar">${avatarMarkup(reply.avatar, reply.username)}</span><span class="reply-user">${reply.username}</span></a><p class="reply-content">${reply.content}</p><div class="reply-actions"><button data-reply-action="like" data-reply-id="${reply.id}">${reply.liked ? '♥' : '♡'} ${reply.likes || 0}</button><button data-reply-action="reply" data-reply-id="${reply.id}">回复</button>${canDeleteReply ? `<button data-reply-action="delete" data-reply-id="${reply.id}">${t('delete')}</button>` : ''}</div></article>`;
        };
        $('#replies').innerHTML = roots.map((root) => `${renderReply(root)}${children.filter((child) => Number(child.parentId) === Number(root.id)).map((child) => renderReply(child, true)).join('')}`).join('') || `<p>${t('noReplies')}</p>`;
        $('#replies').querySelectorAll('[data-reply-action="like"]').forEach((button) => button.onclick = async () => {
            const response = await fetch(`/api/posts/${postId}/replies/${button.dataset.replyId}/like`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: user.id }) });
            const result = await response.json(); if (response.ok) button.textContent = `${result.liked ? '♥' : '♡'} ${result.likes}`;
        });
        $('#replies').querySelectorAll('[data-reply-action="reply"]').forEach((button) => button.onclick = () => {
            const target = replies.find((reply) => Number(reply.id) === Number(button.dataset.replyId));
            replyTargetId = Number(button.dataset.replyId); $('#reply-target').textContent = `回复：@${target?.username || '用户'}（二级评论）　×`;
            $('#reply-target').classList.remove('hidden'); $('#reply-content').focus(); $('#reply-content').placeholder = '写下你的回复';
        });
        $('#replies').querySelectorAll('[data-reply-action="delete"]').forEach((button) => button.onclick = async () => {
            if (!confirm(t('deleteConfirm'))) return;
            const response = await fetch(`/api/posts/${postId}/replies/${button.dataset.replyId}?userId=${user.id}`, { method: 'DELETE' });
            const result = await response.json();
            if (!response.ok) { alert(result.message || '删除失败'); return; }
            loadReplies();
            loadPost();
        });
    } catch (error) {
        $('#replies').innerHTML = `<p>${t('noReplies')}</p>`;
    }
}

$('#back-button').onclick = () => { location.href = '/forum-main.html'; };
$('#reply-target').onclick = () => { replyTargetId = null; $('#reply-target').classList.add('hidden'); };
const replyTextarea = $('#reply-content');
function autoResizeTextarea() {
    replyTextarea.style.height = 'auto';
    replyTextarea.style.height = Math.min(replyTextarea.scrollHeight, 160) + 'px';
}
replyTextarea.addEventListener('input', () => {
    autoResizeTextarea();
    if (!replyTextarea.value.trim()) replyTargetId = null;
});
replyTextarea.addEventListener('focus', autoResizeTextarea);

$('#reply-form').onsubmit = async (event) => {
    event.preventDefault();
    const content = new FormData(event.target).get('content');
    const res = await fetch('/api/posts/' + encodeURIComponent(postId) + '/replies', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: user.id, parentId: replyTargetId, content })
    });
    if (res.ok) {
        event.target.reset();
        replyTextarea.style.height = '';
        replyTargetId = null;
        $('#reply-target').classList.add('hidden');
        loadReplies();
        loadPost();
    } else {
        alert('回复失败，请重试');
    }
};

loadPost();
