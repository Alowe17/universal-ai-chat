const userInfoNode = document.getElementById("user-info");
const logoutButton = document.getElementById("logout-button");

async function loadSession() {
    try {
        const response = await fetch("/api/app/session", {
            method: "GET",
            credentials: "include"
        });

        if (!response.ok) {
            throw new Error("Не удалось получить сессию");
        }

        const session = await response.json();
        if (!session.authenticated || !session.user) {
            window.location.href = "/login";
            return;
        }

        renderUser(session.user);
    } catch (error) {
        userInfoNode.innerHTML = "<p>Не удалось загрузить информацию о пользователе.</p>";
    }
}

function renderUser(user) {
    userInfoNode.innerHTML = `
        <div class="info-item">
            <strong>Username</strong>
            <span>${user.username}</span>
        </div>
        <div class="info-item">
            <strong>Email</strong>
            <span>${user.email}</span>
        </div>
        <div class="info-item">
            <strong>Role</strong>
            <span>${user.role}</span>
        </div>
        <div class="info-item">
            <strong>Создан</strong>
            <span>${user.createdAt ?? "недоступно"}</span>
        </div>
    `;
}

logoutButton?.addEventListener("click", async () => {
    await fetch("/api/auth/logout", {
        method: "POST",
        credentials: "include"
    });

    window.location.href = "/login";
});

loadSession();