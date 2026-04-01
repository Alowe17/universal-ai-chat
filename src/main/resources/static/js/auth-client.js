(function () {
    const REFRESH_INTERVAL_MS = 10 * 60 * 1000;
    let refreshPromise = null;
    let refreshIntervalId = null;

    async function fetchWithAuth(input, init = {}, options = {}) {
        const requestInit = withCredentials(init);
        const response = await fetch(input, requestInit);

        if (response.status !== 401 || options.skipRefresh) {
            return response;
        }

        const refreshed = await refreshAccessToken({ silent: options.silentRefresh });
        if (!refreshed) {
            return response;
        }

        return fetch(input, withCredentials(init));
    }

    async function ensureSession() {
        let response = await fetch("/api/app/session", withCredentials({ method: "GET" }));
        if (!response.ok) {
            throw new Error("Failed to load session");
        }

        let session = await response.json();
        if (session.authenticated && session.user) {
            return session;
        }

        const refreshed = await refreshAccessToken({ silent: true });
        if (!refreshed) {
            return session;
        }

        response = await fetch("/api/app/session", withCredentials({ method: "GET" }));
        if (!response.ok) {
            throw new Error("Failed to load session");
        }

        session = await response.json();
        return session;
    }

    async function refreshAccessToken({ silent = false } = {}) {
        if (!refreshPromise) {
            refreshPromise = (async () => {
                try {
                    const response = await fetch("/api/auth/refresh", withCredentials({ method: "POST" }));
                    if (!response.ok) {
                        return false;
                    }

                    const payload = await response.json().catch(() => null);
                    document.dispatchEvent(new CustomEvent("app:auth-refreshed", { detail: payload }));
                    if (!silent) {
                        window.AppEvents?.notify("Сессия обновлена автоматически.", "success", 2000);
                    }
                    return true;
                } catch (error) {
                    document.dispatchEvent(new CustomEvent("app:auth-refresh-failed", { detail: { error } }));
                    if (!silent) {
                        window.AppEvents?.notify("Не удалось обновить сессию.", "warning");
                    }
                    return false;
                } finally {
                    refreshPromise = null;
                }
            })();
        }

        return refreshPromise;
    }

    function startSessionRefresh() {
        stopSessionRefresh();
        refreshIntervalId = window.setInterval(() => {
            refreshAccessToken({ silent: true });
        }, REFRESH_INTERVAL_MS);
    }

    function stopSessionRefresh() {
        if (refreshIntervalId) {
            window.clearInterval(refreshIntervalId);
            refreshIntervalId = null;
        }
    }

    function withCredentials(init = {}) {
        return {
            ...init,
            credentials: init.credentials ?? "include"
        };
    }

    window.AuthClient = {
        ensureSession,
        fetchWithAuth,
        refreshAccessToken,
        startSessionRefresh,
        stopSessionRefresh
    };
})();
