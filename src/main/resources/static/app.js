const SESSION_KEY = 'homedriveSession';
const CHUNK_SIZE = 8 * 1024 * 1024;
const RETRYABLE_STATUS_CODES = new Set([408, 425, 429, 502, 503, 504]);

const authScreen = document.querySelector('#auth-screen');
const dashboardScreen = document.querySelector('#dashboard-screen');
const authTabs = document.querySelectorAll('[data-auth-mode]');
const authForms = {
    login: document.querySelector('#login-form'),
    register: document.querySelector('#register-form')
};
const navButtons = document.querySelectorAll('[data-view]');
const views = document.querySelectorAll('.view');
const fileInput = document.querySelector('#file-input');
const uploadTrigger = document.querySelector('#upload-trigger');
const adminConsoleLink = document.querySelector('#admin-console-link');

const state = {
    session: readSession(),
    profile: null,
    currentPath: '/',
    drive: null,
    uploading: false
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
    state.drive = null;
    state.currentPath = '/';
    if (adminConsoleLink) {
        adminConsoleLink.hidden = true;
    }
}

async function api(path, options = {}) {
    const retries = options.retries || 0;

    for (let attempt = 0; attempt <= retries; attempt += 1) {
        const headers = { ...(options.headers || {}) };
        const fetchOptions = {
            method: options.method || 'GET',
            headers
        };

        if (state.session?.accessToken) {
            headers.Authorization = `Bearer ${state.session.accessToken}`;
        }

        if (options.body instanceof FormData) {
            fetchOptions.body = options.body;
        } else if (options.body) {
            headers['Content-Type'] = 'application/json';
            fetchOptions.body = JSON.stringify(options.body);
        }

        try {
            const response = await fetch(path, fetchOptions);

            if (response.status === 401 || response.status === 403) {
                clearSession();
                showAuth();
                throw new Error('Session expired.');
            }

            if (RETRYABLE_STATUS_CODES.has(response.status) && attempt < retries) {
                await sleep(retryDelay(attempt));
                continue;
            }

            if (!response.ok) {
                const requestError = new Error('Request failed.');
                requestError.noRetry = true;
                throw requestError;
            }

            if (response.status === 204) {
                return null;
            }

            return response.json();
        } catch (error) {
            if (error.noRetry || error.message === 'Session expired.' || attempt >= retries) {
                throw error;
            }
            await sleep(retryDelay(attempt));
        }
    }

    throw new Error('Request failed.');
}

function sleep(ms) {
    return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function retryDelay(attempt) {
    return 400 * Math.pow(2, attempt);
}

function setAuthMode(mode) {
    authTabs.forEach((button) => {
        const active = button.dataset.authMode === mode;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', String(active));
    });

    Object.entries(authForms).forEach(([key, form]) => {
        const active = key === mode;
        form.classList.toggle('active', active);
        form.hidden = !active;
        setFormMessage(form, '');
    });
}

function setFormMessage(form, message, error = false) {
    const target = form.querySelector('.form-message');
    target.textContent = message;
    target.classList.toggle('error', error);
}

function showAuth() {
    dashboardScreen.hidden = true;
    authScreen.hidden = false;
    authScreen.classList.add('screen-active');
}

async function showDashboard() {
    authScreen.hidden = true;
    dashboardScreen.hidden = false;
    authScreen.classList.remove('screen-active');
    updateUserSurfaces(state.session?.user);
    setView('home');
    await Promise.allSettled([loadProfile(), loadDrive('/')]);
}

function setView(name) {
    navButtons.forEach((button) => button.classList.toggle('active', button.dataset.view === name));
    views.forEach((view) => view.classList.toggle('active', view.id === `${name}-view`));
}

function updateUserSurfaces(user) {
    if (!user) return;

    const username = user.username || 'user';
    const email = user.email || '';
    const role = user.role || 'USER';
    document.querySelector('#sidebar-user').textContent = username;
    document.querySelector('#home-username').textContent = username;
    document.querySelector('#home-email').textContent = email;
    document.querySelector('#profile-username').textContent = username;
    document.querySelector('#profile-email').textContent = email;
    document.querySelector('#profile-id').textContent = user.id || '-';
    document.querySelector('#profile-initial').textContent = username.slice(0, 1).toUpperCase();
    if (adminConsoleLink) {
        adminConsoleLink.hidden = role !== 'ADMIN';
    }
}

function updateProfile(profile) {
    state.profile = profile;
    updateUserSurfaces(profile);
    document.querySelector('#profile-role').textContent = profile.role || 'USER';
}

async function loadProfile() {
    try {
        const profile = await api('/api/v1/users/me');
        updateProfile(profile);
    } catch (error) {
        console.warn(error.message);
    }
}

function updateStats(stats = {}) {
    document.querySelector('#stat-files').textContent = String(stats.totalFiles || 0);
    document.querySelector('#stat-folders').textContent = String(stats.totalFolders || 0);
    document.querySelector('#stat-bytes').textContent = formatBytes(stats.totalBytes || 0);
}

async function loadDrive(path = state.currentPath) {
    setDriveMessage('Loading...');
    try {
        const data = await api(`/api/v1/files?path=${encodeURIComponent(path)}`);
        state.drive = data;
        state.currentPath = data.path || '/';
        renderDrive(data);
        updateStats(data.stats);
        setDriveMessage('');
    } catch (error) {
        setDriveMessage(error.message, true);
    }
}

function renderDrive(data) {
    renderTree(data.tree);
    renderBreadcrumbs(data.path);
    renderFileList(data.items || []);
}

function renderTree(tree) {
    const root = document.querySelector('#folder-tree');
    root.replaceChildren();
    if (!tree) return;
    root.appendChild(createTreeNode(tree));
}

function createTreeNode(node) {
    const wrapper = document.createElement('div');
    wrapper.className = 'tree-node';

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'tree-button';
    button.classList.toggle('active', node.path === state.currentPath);
    button.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h6l2 2h8v10a2 2 0 0 1-2 2H4Z"></path></svg>';

    const label = document.createElement('span');
    label.textContent = node.name;
    button.appendChild(label);
    button.addEventListener('click', () => loadDrive(node.path));
    wrapper.appendChild(button);

    if (node.children?.length) {
        const children = document.createElement('div');
        children.className = 'tree-children';
        node.children.forEach((child) => children.appendChild(createTreeNode(child)));
        wrapper.appendChild(children);
    }

    return wrapper;
}

function renderBreadcrumbs(path) {
    const target = document.querySelector('#breadcrumbs');
    target.textContent = path || '/';
}

function renderFileList(items) {
    const list = document.querySelector('#file-list');
    list.replaceChildren();

    if (!items.length) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = 'No files';
        list.appendChild(empty);
        return;
    }

    items.forEach((item) => list.appendChild(createFileRow(item)));
}

function createFileRow(item) {
    const row = document.createElement('div');
    row.className = 'file-row';

    const name = document.createElement('button');
    name.type = 'button';
    name.className = 'file-item-name';
    name.innerHTML = item.type === 'folder'
        ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h6l2 2h8v10a2 2 0 0 1-2 2H4Z"></path></svg>'
        : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"></path><path d="M14 2v6h6"></path></svg>';
    const label = document.createElement('span');
    label.textContent = item.name;
    name.appendChild(label);
    name.addEventListener('click', () => {
        if (item.type === 'folder') {
            loadDrive(item.path);
        } else {
            downloadFile(item);
        }
    });

    const size = document.createElement('span');
    size.className = 'file-meta';
    size.textContent = item.type === 'folder' ? '-' : formatBytes(item.size);

    const modified = document.createElement('span');
    modified.className = 'file-meta';
    modified.textContent = formatDate(item.modifiedAt);

    const actions = document.createElement('div');
    actions.className = 'row-actions';

    if (item.type === 'folder') {
        actions.appendChild(rowButton('Open', () => loadDrive(item.path)));
    } else {
        actions.appendChild(rowButton('Download', () => downloadFile(item)));
    }
    actions.appendChild(rowButton('Delete', () => deleteItem(item)));

    row.append(name, size, modified, actions);
    return row;
}

function rowButton(text, handler) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'row-button';
    button.textContent = text;
    button.addEventListener('click', handler);
    return button;
}

async function downloadFile(item) {
    setDriveMessage('Starting download...');
    const params = new URLSearchParams({
        path: item.path,
        token: state.session.accessToken
    });
    const link = document.createElement('a');
    link.href = `/api/v1/files/download?${params.toString()}`;
    link.download = item.name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setDriveMessage('');
}

async function deleteItem(item) {
    if (!confirm(`Delete ${item.name}?`)) {
        return;
    }

    setDriveMessage('Deleting...');
    try {
        await api(`/api/v1/files?path=${encodeURIComponent(item.path)}`, { method: 'DELETE' });
        await loadDrive(state.currentPath);
    } catch (error) {
        setDriveMessage(error.message, true);
    }
}

async function uploadSelectedFiles() {
    if (state.uploading) return;

    const files = [...fileInput.files];
    if (!files.length) return;

    state.uploading = true;
    uploadTrigger.disabled = true;
    try {
        for (let fileIndex = 0; fileIndex < files.length; fileIndex += 1) {
            await uploadFileInChunks(files[fileIndex], fileIndex, files.length);
        }

        fileInput.value = '';
        await loadDrive(state.currentPath);
    } catch (error) {
        setDriveMessage(error.message, true);
    } finally {
        state.uploading = false;
        uploadTrigger.disabled = false;
    }
}

async function uploadFileInChunks(file, fileIndex, fileCount) {
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
    let uploadId = null;

    try {
        const session = await api('/api/v1/files/uploads', {
            method: 'POST',
            body: {
                path: state.currentPath,
                filename: file.name,
                totalSize: file.size,
                totalChunks
            },
            retries: 1
        });
        uploadId = session.uploadId;

        const status = await api(`/api/v1/files/uploads/${uploadId}`, { retries: 3 });
        let uploadedChunks = status.receivedChunks || 0;

        if (totalChunks === 0) {
            setDriveMessage(`Uploading ${fileIndex + 1}/${fileCount}: ${file.name} 100%`);
        }

        for (let chunkIndex = uploadedChunks; chunkIndex < totalChunks; chunkIndex += 1) {
            const start = chunkIndex * CHUNK_SIZE;
            const end = Math.min(start + CHUNK_SIZE, file.size);
            const body = new FormData();
            body.append('chunk', file.slice(start, end), file.name);

            await api(`/api/v1/files/uploads/${uploadId}/chunks?index=${chunkIndex}`, {
                method: 'POST',
                body,
                retries: 5
            });

            uploadedChunks = chunkIndex + 1;
            const percent = Math.round((uploadedChunks / totalChunks) * 100);
            setDriveMessage(`Uploading ${fileIndex + 1}/${fileCount}: ${file.name} ${percent}%`);
        }

        await api(`/api/v1/files/uploads/${uploadId}/complete`, { method: 'POST', retries: 5 });
    } catch (error) {
        throw error;
    }
}

async function cancelUpload(uploadId) {
    try {
        await api(`/api/v1/files/uploads/${uploadId}`, { method: 'DELETE' });
    } catch (error) {
        console.warn(error.message);
    }
}

async function createFolder() {
    const name = prompt('Folder name');
    if (!name) return;

    setDriveMessage('Creating folder...');
    try {
        await api('/api/v1/files/folders', {
            method: 'POST',
            body: { path: state.currentPath, name }
        });
        await loadDrive(state.currentPath);
    } catch (error) {
        setDriveMessage(error.message, true);
    }
}

function setDriveMessage(message, error = false) {
    const target = document.querySelector('#drive-message');
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
    return new Intl.DateTimeFormat('en', {
        year: 'numeric',
        month: 'short',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    }).format(new Date(value));
}

authTabs.forEach((button) => {
    button.addEventListener('click', () => setAuthMode(button.dataset.authMode));
});

document.querySelectorAll('.password-toggle').forEach((button) => {
    button.addEventListener('click', () => {
        const input = button.parentElement.querySelector('input');
        const visible = input.type === 'text';
        input.type = visible ? 'password' : 'text';
        button.classList.toggle('password-visible', !visible);
        button.setAttribute('aria-label', visible ? 'Show password' : 'Hide password');
    });
});

authForms.login.addEventListener('submit', async (event) => {
    event.preventDefault();
    setFormMessage(authForms.login, 'Signing in...');

    try {
        const data = await api('/api/v1/auth/login', {
            method: 'POST',
            body: Object.fromEntries(new FormData(authForms.login))
        });
        writeSession(data);
        authForms.login.reset();
        await showDashboard();
    } catch (error) {
        setFormMessage(authForms.login, 'Sign in failed.', true);
    }
});

authForms.register.addEventListener('submit', async (event) => {
    event.preventDefault();
    setFormMessage(authForms.register, 'Creating account...');

    try {
        const data = await api('/api/v1/auth/register', {
            method: 'POST',
            body: Object.fromEntries(new FormData(authForms.register))
        });
        writeSession(data);
        authForms.register.reset();
        await showDashboard();
    } catch (error) {
        setFormMessage(authForms.register, 'Account creation failed.', true);
    }
});

navButtons.forEach((button) => {
    button.addEventListener('click', () => {
        setView(button.dataset.view);
        if (button.dataset.view === 'drive' && !state.drive) {
            loadDrive(state.currentPath);
        }
    });
});

document.querySelectorAll('[data-go-drive]').forEach((button) => {
    button.addEventListener('click', () => {
        setView('drive');
        if (!state.drive) loadDrive(state.currentPath);
    });
});

adminConsoleLink?.addEventListener('click', () => {
    window.location.href = '/admin.html';
});

document.querySelector('#logout-button').addEventListener('click', async () => {
    try {
        await api('/api/v1/auth/logout', { method: 'POST' });
    } catch (error) {
        console.warn(error.message);
    } finally {
        clearSession();
        showAuth();
        setAuthMode('login');
    }
});

document.querySelector('#refresh-drive').addEventListener('click', () => loadDrive(state.currentPath));
document.querySelector('#new-folder').addEventListener('click', createFolder);
uploadTrigger.addEventListener('click', () => fileInput.click());
fileInput.addEventListener('change', uploadSelectedFiles);

if (state.session?.accessToken && state.session?.user) {
    showDashboard();
} else {
    showAuth();
    setAuthMode('login');
}
