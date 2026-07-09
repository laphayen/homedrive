const SESSION_KEY = 'homedriveSession';

const authScreen = document.querySelector('#admin-auth-screen');
const dashboardScreen = document.querySelector('#admin-dashboard-screen');
const loginForm = document.querySelector('#admin-login-form');
const adminNavButtons = document.querySelectorAll('[data-admin-view]');
const adminViews = document.querySelectorAll('[id^="admin-"][id$="-view"]');

const state = {
    session: readSession(),
    profile: null,
    users: [],
    system: null
};

function readSession() {
    try {
        return JSON.parse(localStorage.getItem(SESSION_KEY) || 'null');
    } catch {
        localStorage.removeItem(SESSION_KEY);
        return null;
    }
}

function writeSession(session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    state.session = session;
}

function clearSession() {
    localStorage.removeItem(SESSION_KEY);
    state.session = null;
    state.profile = null;
    state.users = [];
    state.system = null;
}

async function api(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    const fetchOptions = {
        method: options.method || 'GET',
        headers
    };

    if (state.session?.accessToken) {
        headers.Authorization = `Bearer ${state.session.accessToken}`;
    }

    if (options.body) {
        headers['Content-Type'] = 'application/json';
        fetchOptions.body = JSON.stringify(options.body);
    }

    const response = await fetch(path, fetchOptions);

    if (response.status === 401) {
        clearSession();
        showAuth();
        throw new Error('Session expired.');
    }

    if (response.status === 403) {
        showAuth('관리자 권한이 필요합니다.', true);
        throw new Error('Admin access is required.');
    }

    if (!response.ok) {
        const message = await response.text();
        throw new Error(message || 'Request failed.');
    }

    if (response.status === 204) {
        return null;
    }

    return response.json();
}

function setFormMessage(message, error = false) {
    const target = loginForm.querySelector('.form-message');
    target.textContent = message;
    target.classList.toggle('error', error);
}

function showAuth(message = '', error = false) {
    dashboardScreen.hidden = true;
    authScreen.hidden = false;
    authScreen.classList.add('screen-active');
    setFormMessage(message, error);
}

function showDashboard() {
    authScreen.hidden = true;
    authScreen.classList.remove('screen-active');
    dashboardScreen.hidden = false;
}

function setAdminView(name) {
    adminNavButtons.forEach((button) => {
        button.classList.toggle('active', button.dataset.adminView === name);
    });
    adminViews.forEach((view) => {
        view.classList.toggle('active', view.id === `admin-${name}-view`);
    });
}

async function loadAdmin() {
    try {
        const profile = await api('/api/v1/users/me');
        if (profile.role !== 'ADMIN') {
            showAuth('관리자 권한이 필요합니다.', true);
            return;
        }

        state.profile = profile;
        document.querySelector('#admin-sidebar-user').textContent = profile.username;
        showDashboard();
        setAdminView('users');
        await refreshAdminData();
    } catch (error) {
        if (state.session?.accessToken) {
            setFormMessage(error.message, true);
        }
    }
}

async function refreshAdminData() {
    setUsersMessage('Loading...');
    setSystemMessage('Loading...');

    try {
        const [system, users] = await Promise.all([
            api('/api/v1/admin/system'),
            api('/api/v1/admin/users')
        ]);
        state.system = system;
        state.users = users;
        renderSystem(system);
        renderUsers(users);
        setUsersMessage('');
        setSystemMessage('Ready');
    } catch (error) {
        setUsersMessage(error.message, true);
        setSystemMessage(error.message, true);
    }
}

function renderSystem(system) {
    document.querySelector('#admin-stat-users').textContent = String(system.totalUsers || 0);
    document.querySelector('#admin-stat-admins').textContent = String(system.adminUsers || 0);
    document.querySelector('#admin-stat-storage').textContent = formatBytes(system.totalBytes || 0);
    document.querySelector('#admin-stat-files').textContent = String(system.totalFiles || 0);
    document.querySelector('#admin-stat-folders').textContent = String(system.totalFolders || 0);
    document.querySelector('#admin-stat-uploads').textContent = String(system.activeUploadSessions || 0);
}

function renderUsers(users) {
    const list = document.querySelector('#admin-user-list');
    list.replaceChildren();

    if (!users.length) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = 'No users';
        list.appendChild(empty);
        return;
    }

    users.forEach((user) => list.appendChild(createUserRow(user)));
}

function createUserRow(user) {
    const row = document.createElement('div');
    row.className = 'admin-user-row';

    const identity = document.createElement('div');
    identity.className = 'admin-user-identity';

    const avatar = document.createElement('span');
    avatar.className = 'admin-user-avatar';
    avatar.textContent = (user.username || 'U').slice(0, 1).toUpperCase();

    const nameBlock = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = user.username;
    const email = document.createElement('span');
    email.textContent = user.email;
    nameBlock.append(name, email);
    identity.append(avatar, nameBlock);

    const joined = document.createElement('span');
    joined.className = 'file-meta';
    joined.textContent = formatDate(user.createdAt);

    const storage = document.createElement('span');
    storage.className = 'file-meta';
    storage.textContent = formatBytes(user.storage?.totalBytes || 0);

    const role = document.createElement('select');
    role.className = 'admin-role-select';
    role.innerHTML = '<option value="USER">USER</option><option value="ADMIN">ADMIN</option>';
    role.value = user.role || 'USER';
    role.disabled = user.id === state.profile?.id;
    role.addEventListener('change', () => updateRole(user, role.value));

    const actions = document.createElement('div');
    actions.className = 'row-actions';
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'row-button danger-row-button';
    remove.textContent = 'Delete';
    remove.disabled = user.id === state.profile?.id;
    remove.addEventListener('click', () => deleteUser(user));
    actions.appendChild(remove);

    row.append(identity, joined, storage, role, actions);
    return row;
}

async function updateRole(user, role) {
    setUsersMessage('Updating...');
    try {
        await api(`/api/v1/admin/users/${user.id}/role`, {
            method: 'PATCH',
            body: { role }
        });
        await refreshAdminData();
    } catch (error) {
        setUsersMessage(error.message, true);
        renderUsers(state.users);
    }
}

async function deleteUser(user) {
    if (!confirm(`${user.username} 계정을 삭제할까요?`)) {
        return;
    }

    setUsersMessage('Deleting...');
    try {
        await api(`/api/v1/admin/users/${user.id}`, { method: 'DELETE' });
        await refreshAdminData();
    } catch (error) {
        setUsersMessage(error.message, true);
    }
}

async function clearUploadSessions() {
    if (!confirm('진행 중인 업로드 세션을 정리할까요?')) {
        return;
    }

    setSystemMessage('Clearing...');
    try {
        await api('/api/v1/admin/system/uploads', { method: 'DELETE' });
        await refreshAdminData();
    } catch (error) {
        setSystemMessage(error.message, true);
    }
}

function setUsersMessage(message, error = false) {
    const target = document.querySelector('#admin-users-message');
    target.textContent = message;
    target.classList.toggle('error', error);
}

function setSystemMessage(message, error = false) {
    const target = document.querySelector('#admin-system-message');
    target.textContent = message;
    target.classList.toggle('error', error);
}

function formatBytes(bytes) {
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    const value = bytes / Math.pow(1024, index);
    return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

function formatDate(value) {
    if (!value) return '-';
    return new Intl.DateTimeFormat('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    }).format(new Date(value));
}

document.querySelectorAll('.password-toggle').forEach((button) => {
    button.addEventListener('click', () => {
        const input = button.parentElement.querySelector('input');
        const visible = input.type === 'text';
        input.type = visible ? 'password' : 'text';
        button.classList.toggle('password-visible', !visible);
        button.setAttribute('aria-label', visible ? 'Show password' : 'Hide password');
    });
});

loginForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    setFormMessage('Signing in...');

    try {
        const data = await api('/api/v1/auth/login', {
            method: 'POST',
            body: Object.fromEntries(new FormData(loginForm))
        });
        writeSession(data);
        loginForm.reset();
        await loadAdmin();
    } catch (error) {
        setFormMessage('로그인에 실패했습니다.', true);
    }
});

adminNavButtons.forEach((button) => {
    button.addEventListener('click', () => setAdminView(button.dataset.adminView));
});

document.querySelector('#admin-refresh-users').addEventListener('click', refreshAdminData);
document.querySelector('#admin-refresh-system').addEventListener('click', refreshAdminData);
document.querySelector('#admin-clear-uploads').addEventListener('click', clearUploadSessions);

document.querySelector('#admin-open-app').addEventListener('click', () => {
    window.location.href = '/';
});

document.querySelector('#admin-logout-button').addEventListener('click', async () => {
    try {
        await api('/api/v1/auth/logout', { method: 'POST' });
    } catch (error) {
        console.warn(error.message);
    } finally {
        clearSession();
        showAuth();
    }
});

if (state.session?.accessToken) {
    loadAdmin();
} else {
    showAuth();
}
