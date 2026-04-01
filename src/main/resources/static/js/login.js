const loginForm = document.getElementById("login-form");
const messageNode = document.getElementById("login-message");

loginForm?.addEventListener("submit", async event => {
    event.preventDefault();
    messageNode.textContent = "Проверяем данные...";
    messageNode.className = "form-message";

    const payload = {
        login: loginForm.login.value.trim(),
        password: loginForm.password.value
    };

    try {
        const response = await fetch("/api/auth/login", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            credentials: "include",
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || "Не удалось выполнить вход");
        }

        messageNode.textContent = "Успешный вход. Перенаправляем...";
        messageNode.className = "form-message success";
        window.AppEvents?.notify("Вход выполнен успешно.", "success");
        window.location.href = "/";
    } catch (error) {
        messageNode.textContent = error.message || "Ошибка входа";
        messageNode.className = "form-message error";
        window.AppEvents?.notify(error.message || "Ошибка входа.", "error");
    }
});
