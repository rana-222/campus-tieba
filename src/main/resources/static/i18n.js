const FONT_SCALES = [0.72, 0.82, 0.92, 1.0, 1.1, 1.2, 1.32];

const I18N = {
    zh: {
        brand: '校园贴吧',
        brandSub: 'Campus Tieba',
        recommend: '推荐帖子',
        browseBoards: '浏览分区',
        search: '搜索',
        profile: '个人主页',
        logout: '退出登录',
        myHome: '我的主页',
        myFavorites: '我的收藏',
        myHistory: '浏览记录',
        settings: '设置',
        follow: '关注',
        likes: '获赞',
        favorites: '收藏',
        today: 'TODAY ON CAMPUS',
        compose: '发布帖子',
        scrollHint: '向下滚动加载更多',
        endHint: '滑到底了喵',
        hotBoards: '热门分区',
        allBoards: '全部',
        notice: '校园公告',
        noticeText: '本周五晚 8 点，图书馆将进行网络维护。',
        noticeTitleLabel: '公告标题',
        noticeContentLabel: '公告内容',
        saveNotice: '保存公告',
        publishTitle: '发布新帖子',
        editTitle: '编辑帖子',
        board: '所属分区',
        title: '标题',
        content: '内容',
        imageFile: '添加图片',
        pasteHint: '可直接粘贴剪贴板中的图片',
        publish: '发布',
        save: '保存',
        settingsTitle: '界面设置',
        notifications: '通知',
        basicSettings: '基本设置',
        adminFunctions: '管理功能',
        receivedLikes: '收到的赞',
        receivedReplies: '收到的回复',
        panelOpacity: '背景不透明度',
        fontSize: '字体大小',
        language: '界面语言',
        searchTitle: '搜索帖子',
        searchPlaceholder: '输入标题或内容关键词',
        searchBtn: '搜索',
        deleteConfirm: '确定删除这条帖子吗？',
        deleted: '帖子已删除',
        edit: '编辑',
        delete: '删除',
        allReplies: '全部回复',
        replyPlaceholder: '写下你的回复',
        submitReply: '发布回复',
        noReplies: '还没有回复，来留下第一条吧。',
        backForum: '返回论坛',
        followBoard: '关注分区',
        unfollowBoard: '取消关注',
        langSaved: '语言偏好已保存'
    },
    en: {
        brand: 'Campus Tieba',
        brandSub: 'Campus Forum',
        recommend: 'Recommended',
        browseBoards: 'Boards',
        search: 'Search',
        profile: 'My Profile',
        logout: 'Log Out',
        myHome: 'My Posts',
        myFavorites: 'My Favorites',
        myHistory: 'History',
        settings: 'Settings',
        follow: 'Following',
        likes: 'Likes',
        favorites: 'Saved',
        today: 'TODAY ON CAMPUS',
        compose: 'New Post',
        scrollHint: 'Scroll down for more',
        endHint: 'You reached the bottom 🐱',
        hotBoards: 'Hot Boards',
        allBoards: 'All',
        notice: 'Campus Notice',
        noticeText: 'Library network maintenance this Friday at 8 PM.',
        noticeTitleLabel: 'Notice Title',
        noticeContentLabel: 'Notice Content',
        saveNotice: 'Save Notice',
        publishTitle: 'Create Post',
        editTitle: 'Edit Post',
        board: 'Board',
        title: 'Title',
        content: 'Content',
        imageFile: 'Add Image',
        pasteHint: 'Paste image from clipboard',
        publish: 'Publish',
        save: 'Save',
        settingsTitle: 'Settings',
        notifications: 'Notifications',
        basicSettings: 'Basic Settings',
        adminFunctions: 'Management',
        receivedLikes: 'Likes Received',
        receivedReplies: 'Replies Received',
        panelOpacity: 'Panel Opacity',
        fontSize: 'Font Size',
        language: 'Language',
        searchTitle: 'Search Posts',
        searchPlaceholder: 'Enter keywords',
        searchBtn: 'Search',
        deleteConfirm: 'Delete this post?',
        deleted: 'Post deleted',
        edit: 'Edit',
        delete: 'Delete',
        allReplies: 'All Replies',
        replyPlaceholder: 'Write a reply',
        submitReply: 'Submit',
        noReplies: 'No replies yet. Be the first!',
        backForum: 'Back to Forum',
        followBoard: 'Follow Board',
        unfollowBoard: 'Unfollow',
        langSaved: 'Language preference saved'
    }
};

function getLang() {
    return localStorage.getItem('lang') || 'zh';
}

function t(key) {
    const lang = getLang();
    return I18N[lang]?.[key] || I18N.zh[key] || key;
}

function applyFontScale(level) {
    const idx = Math.max(0, Math.min(6, Number(level) || 3));
    document.documentElement.style.setProperty('--font-scale', FONT_SCALES[idx]);
    localStorage.setItem('fontLevel', String(idx));
}

function applyLanguage(lang) {
    localStorage.setItem('lang', lang);
    document.documentElement.lang = lang === 'en' ? 'en' : 'zh-CN';
    document.querySelectorAll('[data-i18n]').forEach((el) => {
        const key = el.dataset.i18n;
        const val = t(key);
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
            el.placeholder = val;
        } else {
            el.textContent = val;
        }
    });
    document.querySelectorAll('[data-i18n-html]').forEach((el) => {
        el.innerHTML = t(el.dataset.i18nHtml);
    });
}

function applyPanelAlpha(percent) {
    const value = Math.max(30, Math.min(100, Number(percent) || 90)) / 100;
    document.documentElement.style.setProperty('--panel-alpha', value);
    localStorage.setItem('panelAlpha', String(Math.round(value * 100)));
}

function initSettings() {
    applyFontScale(localStorage.getItem('fontLevel') ?? 3);
    applyPanelAlpha(localStorage.getItem('panelAlpha') ?? 90);
    applyLanguage(getLang());
}

// 全屏图片预览（灯箱）：点击遮罩或 × 关闭，Esc 支持
function openImagePreview(src) {
    const overlay = document.createElement('div');
    overlay.className = 'img-lightbox';
    overlay.innerHTML = `<img src="${src}" alt="图片预览"><button class="img-lightbox-close" type="button" aria-label="关闭">×</button>`;
    const close = () => {
        overlay.remove();
        document.body.style.overflow = '';
        document.removeEventListener('keydown', onKey);
    };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    overlay.onclick = (e) => { if (e.target !== overlay.querySelector('img')) close(); };
    document.addEventListener('keydown', onKey);
    document.body.appendChild(overlay);
    document.body.style.overflow = 'hidden';
}
