const profileId = new URLSearchParams(location.search).get('id');
const $ = (selector) => document.querySelector(selector);
const avatarMarkup = (avatar, username) => avatar
    ? `<img class="avatar-image" src="${avatar}" alt="${username || '用户'}头像">`
    : (username || '校')[0];

if (!profileId) location.href = '/forum-main.html';

function formatTime(value) {
    return value ? value.replace('T', ' ').slice(0, 16) : '';
}

function renderPosts(posts) {
    $('#profile-posts').innerHTML = posts.map((post) => `
        <article class="profile-post" data-id="${post.id}">
            <div class="post-author"><div class="avatar">${avatarMarkup(post.avatar, post.username)}</div>
            <span>${post.username} · ${formatTime(post.createdAt)} · ${post.boardName || ''} · 识别码：${post.id}</span></div>
            <h3>${post.title}</h3>
            <p>${post.content.length > 180 ? `${post.content.slice(0, 180)}……` : post.content}</p>
            <div class="post-meta"><span>♡ ${post.likes || 0}</span><span>☆ ${post.favorites || 0}</span><span>◉ ${post.views || 0}</span></div>
        </article>`).join('') || '<p class="empty-profile">暂时没有发布帖子</p>';
    document.querySelectorAll('.profile-post').forEach((post) => {
        post.onclick = () => { location.href = `/post.html?id=${post.dataset.id}`; };
    });
}

async function loadProfile() {
    try {
        const [profileResponse, postsResponse] = await Promise.all([
            fetch(`/api/users/${encodeURIComponent(profileId)}`),
            fetch(`/api/users/${encodeURIComponent(profileId)}/posts`)
        ]);
        if (!profileResponse.ok || !postsResponse.ok) throw new Error('加载失败');
        const profile = await profileResponse.json();
        const posts = await postsResponse.json();
        const person = profile.user;
        const stats = profile.stats || {};
        $('#profile-header').innerHTML = `
            <div class="profile-avatar avatar">${avatarMarkup(person.avatar, person.username)}</div>
            <div class="profile-identity"><h1>${person.username}</h1><span>@${person.account}</span></div>
            <div class="profile-stats"><span><b>${stats.posts || 0}</b>帖子</span><span><b>${stats.likes || 0}</b>获赞</span><span><b>${stats.favorites || 0}</b>收藏</span><span><b>${stats.follows || 0}</b>关注</span></div>`;
        renderPosts(posts);
    } catch (_) {
        $('#profile-header').innerHTML = '<p>个人资料加载失败</p>';
    }
}

$('#back-button').onclick = () => history.length > 1 ? history.back() : location.href = '/forum-main.html';
loadProfile();
