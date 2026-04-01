const registerForm = document.getElementById("register-form");
const registerMessageNode = document.getElementById("register-message");

registerForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    registerMessageNode.textContent = "Создаем аккаунт...";
    registerMessageNode.className = "form-message";

    const payload = {
        username: registerForm.username.value.trim(),
        email: registerForm.email.value.trim(),
        password: registerForm.password.value
    };

    try {
        const response = await fetch("/api/auth/register", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            credentials: "include",
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || "Не удалось зарегистрироваться");
        }

        registerMessageNode.textContent = "Аккаунт создан. Перенаправляем...";
        registerMessageNode.className = "form-message success";
        window.location.href = "/";
    } catch (error) {
        registerMessageNode.textContent = error.message || "Ошибка регистрации";
        registerMessageNode.className = "form-message error";
    }
});
