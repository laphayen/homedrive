const tabs = document.querySelectorAll('.tab');
const forms = document.querySelectorAll('.auth-form');
const sessionPanel = document.querySelector('#logged-in');

function switchTab(name) {
    tabs.forEach((tab) => {
        const selected = tab.id === `${name}-tab`;
        tab.classList.toggle('active', selected);
        tab.setAttribute('aria-selected', String(selected));
    });
    forms.forEach((form) => {
        const selected = form.id === `${name}-panel`;
        form.classList.toggle('active', selected);
        form.hidden = !selected;
        form.querySelector('.form-message').textContent = '';
    });
}

tabs.forEach((tab) => tab.addEventListener('click', () => switchTab(tab.id.replace('-tab', ''))));

document.querySelectorAll('.reveal').forEach((button) => {
    button.addEventListener('click', () => {
        const input = button.parentElement.querySelector('input');
        const visible = input.type === 'text';
        input.type = visible ? 'password' : 'text';
        button.textContent = visible ? 'SHOW' : 'HIDE';
    });
});

async function request(path, body, token) {
    const response = await fetch(path, {
        method: 'POST',
        headers: {
            ...(body ? { 'Content-Type': 'application/json' } : {}),
            ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: body ? JSON.stringify(body) : undefined
    });
    if (!response.ok) {
        const message = await response.text();
        throw new Error(message || '요청을 처리하지 못했습니다.');
    }
    return response.status === 204 ? null : response.json();
}

function saveSession(data) {
    localStorage.setItem('homedriveSession', JSON.stringify(data));
    showSession(data);
}

function showSession(data) {
    document.querySelector('.tabs').hidden = true;
    forms.forEach((form) => { form.hidden = true; form.classList.remove('active'); });
    sessionPanel.hidden = false;
    document.querySelector('#session-name').textContent = data.user.username;
    document.querySelector('#session-email').textContent = data.user.email;
}

forms.forEach((form) => {
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const button = form.querySelector('.primary-button');
        const message = form.querySelector('.form-message');
        const body = Object.fromEntries(new FormData(form));
        button.disabled = true;
        message.className = 'form-message';
        message.textContent = 'CONNECTING...';
        try {
            const action = form.id === 'login-panel' ? 'login' : 'register';
            const data = await request(`/api/v1/auth/${action}`, body);
            saveSession(data);
            form.reset();
        } catch (error) {
            message.classList.add('error');
            message.textContent = error.message;
        } finally {
            button.disabled = false;
        }
    });
});

document.querySelector('#logout-button').addEventListener('click', async () => {
    const session = JSON.parse(localStorage.getItem('homedriveSession') || 'null');
    if (!session) return;
    const button = document.querySelector('#logout-button');
    button.disabled = true;
    try {
        await request('/api/v1/auth/logout', null, session.accessToken);
    } catch (error) {
        console.warn(error.message);
    } finally {
        localStorage.removeItem('homedriveSession');
        sessionPanel.hidden = true;
        document.querySelector('.tabs').hidden = false;
        switchTab('login');
        button.disabled = false;
    }
});

try {
    const saved = JSON.parse(localStorage.getItem('homedriveSession') || 'null');
    if (saved?.accessToken && saved?.user) showSession(saved);
} catch {
    localStorage.removeItem('homedriveSession');
}
