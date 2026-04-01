(function () {
    const EVENT_PREFIX = "app:";
    const TOAST_ROOT_ID = "app-toast-root";
    const DEFAULT_TTL = 5000;

    function ensureToastRoot() {
        let root = document.getElementById(TOAST_ROOT_ID);
        if (root) {
            return root;
        }

        root = document.createElement("section");
        root.id = TOAST_ROOT_ID;
        root.className = "app-toast-root";
        root.setAttribute("aria-live", "polite");
        root.setAttribute("aria-atomic", "true");
        document.body.appendChild(root);
        return root;
    }

    function dispatch(name, detail = {}) {
        document.dispatchEvent(new CustomEvent(`${EVENT_PREFIX}${name}`, { detail }));
    }

    function notify(message, variant = "info", ttl = DEFAULT_TTL) {
        if (!message) {
            return;
        }

        dispatch("notify", { message, variant, ttl });
    }

    function showToast(detail) {
        const root = ensureToastRoot();
        const toast = document.createElement("article");
        toast.className = `app-toast ${detail.variant ?? "info"}`;
        toast.innerHTML = `
            <strong>${escapeHtml(resolveTitle(detail.variant))}</strong>
            <p>${escapeHtml(detail.message ?? "")}</p>
        `;

        root.appendChild(toast);

        window.setTimeout(() => {
            toast.classList.add("visible");
        }, 10);

        window.setTimeout(() => {
            toast.classList.remove("visible");
            window.setTimeout(() => toast.remove(), 220);
        }, detail.ttl ?? DEFAULT_TTL);
    }

    function resolveTitle(variant) {
        switch (variant) {
            case "success":
                return "Success";
            case "warning":
                return "Warning";
            case "error":
                return "Error";
            default:
                return "Event";
        }
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#39;");
    }

    document.addEventListener(`${EVENT_PREFIX}notify`, event => {
        showToast(event.detail ?? {});
    });

    window.addEventListener("error", event => {
        if (!event.message) {
            return;
        }

        notify("Unexpected interface error. Please retry the action.", "error");
    });

    window.addEventListener("unhandledrejection", () => {
        notify("Unhandled async error occurred in the page.", "error");
    });

    window.AppEvents = {
        dispatch,
        notify
    };
})();
