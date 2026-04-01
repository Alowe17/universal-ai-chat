const userInfoNode = document.getElementById("user-info");
const logoutButton = document.getElementById("logout-button");
const chatListNode = document.getElementById("chat-list");
const messageListNode = document.getElementById("message-list");
const documentListNode = document.getElementById("document-list");
const chatTitleNode = document.getElementById("chat-title");
const chatSubtitleNode = document.getElementById("chat-subtitle");
const connectionStatusNode = document.getElementById("connection-status");
const newChatButton = document.getElementById("new-chat-button");
const uploadPdfButton = document.getElementById("upload-pdf-button");
const pdfUploadInput = document.getElementById("pdf-upload-input");
const composerForm = document.getElementById("composer-form");
const messageInput = document.getElementById("message-input");
const sendButton = document.getElementById("send-button");

const EMPTY_CHAT_PREVIEW = "Диалог еще пуст";
const MODEL_NAME = "deepseek-r1:7b";

const state = {
    session: null,
    chats: [],
    activeChatId: null,
    messagesByChatId: new Map(),
    documentsByChatId: new Map(),
    activeGenerations: new Set(),
    socket: null,
    reconnectTimerId: null,
    manualDisconnect: false,
    uploadingPdf: false,
    reorderingChats: false,
    draggedChatId: null
};

function notify(message, variant = "info", ttl) {
    window.AppEvents?.notify(message, variant, ttl);
}

async function bootstrap() {
    const session = await loadSession();
    if (!session) {
        return;
    }

    state.session = session.user;
    renderUser(session.user);
    window.AuthClient?.startSessionRefresh();
    connectSocket();
    await loadChats();
}

async function loadSession() {
    try {
        const session = await window.AuthClient.ensureSession();
        if (!session.authenticated || !session.user) {
            window.location.href = "/login";
            return null;
        }

        return session;
    } catch (error) {
        userInfoNode.innerHTML = "<p>Не удалось загрузить данные пользователя.</p>";
        notify("Не удалось загрузить сессию пользователя.", "error");
        return null;
    }
}

async function loadChats() {
    try {
        const response = await window.AuthClient.fetchWithAuth("/api/chats", {
            method: "GET"
        });

        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Не удалось загрузить список чатов."));
        }

        state.chats = await response.json();
        sortChats();
        renderChatList();

        if (!state.chats.length) {
            await createChat();
            return;
        }

        const nextChatId = state.chats.some(chat => chat.id === state.activeChatId)
            ? state.activeChatId
            : state.chats[0].id;

        await openChat(nextChatId, { useCache: state.messagesByChatId.has(nextChatId) });
    } catch (error) {
        chatListNode.innerHTML = "<p class=\"sidebar-empty\">Не удалось загрузить список чатов.</p>";
        notify(error.message || "Не удалось загрузить список чатов.", "error");
    }
}

async function createChat() {
    try {
        const response = await window.AuthClient.fetchWithAuth("/api/chats", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({})
        });

        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Не удалось создать чат."));
        }

        const chat = await response.json();
        upsertChatSummary({
            id: chat.id,
            title: chat.title,
            preview: EMPTY_CHAT_PREVIEW,
            sortOrder: chat.sortOrder,
            createdAt: chat.createdAt,
            updatedAt: chat.updatedAt,
            messageCount: chat.messages.length
        });
        state.messagesByChatId.set(chat.id, chat.messages ?? []);
        state.documentsByChatId.set(chat.id, chat.documents ?? []);
        notify("Чат создан.", "success", 2500);
        await openChat(chat.id, { useCache: true });
    } catch (error) {
        setConnectionStatus("Не удалось создать чат", "error");
        notify(error.message || "Не удалось создать чат.", "error");
    }
}

async function openChat(chatId, options = {}) {
    state.activeChatId = chatId;
    renderChatList();

    if (!options.useCache || !state.messagesByChatId.has(chatId)) {
        try {
            const response = await window.AuthClient.fetchWithAuth(`/api/chats/${chatId}`, {
                method: "GET"
            });

            if (!response.ok) {
                throw new Error(await readErrorMessage(response, "Не удалось открыть чат."));
            }

            const chat = await response.json();
            state.messagesByChatId.set(chatId, chat.messages ?? []);
            state.documentsByChatId.set(chatId, chat.documents ?? []);
            upsertChatSummary({
                id: chat.id,
                title: chat.title,
                preview: buildPreview(chat.messages ?? []),
                sortOrder: chat.sortOrder,
                createdAt: chat.createdAt,
                updatedAt: chat.updatedAt,
                messageCount: (chat.messages ?? []).length
            });
        } catch (error) {
            messageListNode.innerHTML = "<div class=\"empty-state\"><p>Не удалось загрузить сообщения чата.</p></div>";
            notify(error.message || "Не удалось открыть выбранный чат.", "error");
            return;
        }
    }

    renderActiveChat();
}

async function connectSocket() {
    const session = await window.AuthClient.ensureSession().catch(() => null);
    if (!session?.authenticated) {
        window.location.href = "/login";
        return;
    }

    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const socketUrl = `${protocol}://${window.location.host}/ws/chat`;

    setConnectionStatus("Подключение к WebSocket...", "pending");
    state.socket = new WebSocket(socketUrl);

    state.socket.addEventListener("open", () => {
        setConnectionStatus("WebSocket подключен", "connected");
        notify("Подключение к чату установлено.", "success", 2500);
        updateComposerState();
    });

    state.socket.addEventListener("message", event => {
        const payload = JSON.parse(event.data);
        handleSocketEvent(payload);
    });

    state.socket.addEventListener("close", async () => {
        state.socket = null;
        state.activeGenerations.clear();
        updateComposerState();

        if (state.manualDisconnect) {
            return;
        }

        await window.AuthClient.refreshAccessToken({ silent: true });
        setConnectionStatus("WebSocket отключен. Переподключение...", "warning");
        notify("Соединение с чатом потеряно. Пробуем переподключиться...", "warning");
        window.clearTimeout(state.reconnectTimerId);
        state.reconnectTimerId = window.setTimeout(() => {
            connectSocket();
        }, 2000);
    });

    state.socket.addEventListener("error", () => {
        setConnectionStatus("Ошибка WebSocket", "error");
        notify("Транспортная ошибка WebSocket.", "error");
    });
}

function handleSocketEvent(event) {
    switch (event.type) {
        case "session.ready":
            setConnectionStatus("WebSocket подключен", "connected");
            break;
        case "chat.updated":
            if (event.chat) {
                upsertChatSummary(event.chat);
                if (event.chat.id === state.activeChatId) {
                    renderActiveChat();
                }
            }
            break;
        case "chat.message.user":
            if (event.chatMessage) {
                upsertMessage(event.chatId, event.chatMessage);
                renderActiveChat();
            }
            break;
        case "chat.message.assistant.started":
            if (event.chatMessage) {
                state.activeGenerations.add(event.chatId);
                upsertMessage(event.chatId, event.chatMessage);
                renderActiveChat();
            }
            break;
        case "chat.message.assistant.delta":
            appendMessageDelta(event.chatId, event.messageId, event.delta ?? "");
            renderActiveChat();
            break;
        case "chat.message.assistant.completed":
            if (event.chatMessage) {
                state.activeGenerations.delete(event.chatId);
                upsertMessage(event.chatId, event.chatMessage);
                renderActiveChat();
            }
            break;
        case "chat.error":
            state.activeGenerations.delete(event.chatId);
            setConnectionStatus(event.message ?? "Ошибка чата", "error");
            notify(event.message ?? "Произошла ошибка при обработке сообщения.", "error");
            updateComposerState();
            renderChatList();
            break;
        default:
            break;
    }
}

function renderUser(user) {
    userInfoNode.innerHTML = `
        <div class="user-card">
            <strong>${escapeHtml(user.username)}</strong>
            <span>${escapeHtml(user.email)}</span>
            <small>${escapeHtml(user.role)}</small>
        </div>
    `;
}

function renderChatList() {
    if (!state.chats.length) {
        chatListNode.innerHTML = "<p class=\"sidebar-empty\">Чаты появятся после создания первого диалога.</p>";
        return;
    }

    chatListNode.innerHTML = state.chats
        .map(chat => {
            const isActive = chat.id === state.activeChatId;
            const isBusy = state.activeGenerations.has(chat.id);

            return `
                <article
                    class="chat-card ${isActive ? "active" : ""}"
                    draggable="${!state.reorderingChats}"
                    data-chat-id="${chat.id}"
                >
                    <button class="chat-item" type="button" data-chat-open-id="${chat.id}">
                        <strong>${escapeHtml(chat.title)}</strong>
                        <span>${escapeHtml(chat.preview ?? EMPTY_CHAT_PREVIEW)}</span>
                        <small>${formatDate(chat.updatedAt)}</small>
                    </button>
                    <div class="chat-item-actions">
                        <button
                            class="chat-action-button"
                            type="button"
                            data-chat-action="rename"
                            data-chat-id="${chat.id}"
                            title="Переименовать чат"
                            aria-label="Переименовать чат"
                        >✎</button>
                        <button
                            class="chat-action-button danger"
                            type="button"
                            data-chat-action="delete"
                            data-chat-id="${chat.id}"
                            title="Удалить чат"
                            aria-label="Удалить чат"
                            ${isBusy ? "disabled" : ""}
                        >×</button>
                        <span class="chat-drag-handle" title="Перетащите чат, чтобы изменить порядок">⋮⋮</span>
                    </div>
                </article>
            `;
        })
        .join("");

    document.querySelectorAll("[data-chat-open-id]").forEach(button => {
        button.addEventListener("click", async () => {
            await openChat(Number(button.dataset.chatOpenId), { useCache: true });
        });
    });

    document.querySelectorAll("[data-chat-action]").forEach(button => {
        button.addEventListener("click", async event => {
            event.stopPropagation();
            const chatId = Number(button.dataset.chatId);

            if (button.dataset.chatAction === "rename") {
                await renameChat(chatId);
                return;
            }

            if (button.dataset.chatAction === "delete") {
                await deleteChat(chatId);
            }
        });
    });

    document.querySelectorAll(".chat-card").forEach(card => {
        card.addEventListener("dragstart", handleChatDragStart);
        card.addEventListener("dragover", handleChatDragOver);
        card.addEventListener("dragleave", handleChatDragLeave);
        card.addEventListener("drop", handleChatDrop);
        card.addEventListener("dragend", handleChatDragEnd);
    });
}

function renderActiveChat() {
    const activeChat = state.chats.find(chat => chat.id === state.activeChatId);
    const messages = state.messagesByChatId.get(state.activeChatId) ?? [];
    const documents = state.documentsByChatId.get(state.activeChatId) ?? [];

    if (!activeChat) {
        chatTitleNode.textContent = "Выберите чат";
        chatSubtitleNode.textContent = "Создайте диалог и начните переписку.";
        documentListNode.innerHTML = "<p class=\"document-empty\">Сначала выберите чат.</p>";
        messageListNode.innerHTML = "<div class=\"empty-state\"><p>Чат еще не выбран.</p></div>";
        updateComposerState();
        return;
    }

    chatTitleNode.textContent = activeChat.title;
    chatSubtitleNode.textContent = `${messages.length} сообщений • ${documents.length} PDF • обновлен ${formatDate(activeChat.updatedAt)}`;

    renderDocumentList(documents);

    if (!messages.length) {
        messageListNode.innerHTML = `
            <div class="empty-state">
                <p>Чат готов. Напишите первое сообщение или добавьте PDF для работы с документом.</p>
            </div>
        `;
    } else {
        messageListNode.innerHTML = messages
            .map(message => `
                <article class="message-row ${message.role === "USER" ? "user" : "assistant"}">
                    <div class="message-meta">
                        <span>${message.role === "USER" ? "Вы" : "Assistant"}</span>
                        <time>${formatDate(message.createdAt)}</time>
                    </div>
                    <div class="message-bubble">${formatMultiline(message.content)}</div>
                </article>
            `)
            .join("");
    }

    messageListNode.scrollTop = messageListNode.scrollHeight;
    updateComposerState();
}

function renderDocumentList(documents) {
    if (!documents.length) {
        documentListNode.innerHTML = "<p class=\"document-empty\">У этого чата пока нет PDF-файлов.</p>";
        return;
    }

    documentListNode.innerHTML = documents
        .map(document => `
            <article class="document-card">
                <strong>${escapeHtml(document.fileName)}</strong>
                <span>${document.pageCount} стр. • ${document.textLength} символов</span>
                <small>${formatDate(document.createdAt)}</small>
            </article>
        `)
        .join("");
}

function updateComposerState() {
    const canSend = Boolean(state.activeChatId)
        && Boolean(state.socket)
        && state.socket.readyState === WebSocket.OPEN
        && !state.activeGenerations.has(state.activeChatId);

    sendButton.disabled = !canSend;
    messageInput.disabled = !canSend;
    uploadPdfButton.disabled = !state.activeChatId || state.uploadingPdf;
    messageInput.placeholder = canSend
        ? `Введите сообщение для ${MODEL_NAME}...`
        : "Дождитесь подключения WebSocket и завершения генерации";
}

function upsertChatSummary(chatSummary) {
    const existingIndex = state.chats.findIndex(chat => chat.id === chatSummary.id);
    if (existingIndex >= 0) {
        state.chats[existingIndex] = {
            ...state.chats[existingIndex],
            ...chatSummary
        };
    } else {
        state.chats.push(chatSummary);
    }

    sortChats();
    renderChatList();
}

function removeChatFromState(chatId) {
    state.chats = state.chats.filter(chat => chat.id !== chatId);
    state.messagesByChatId.delete(chatId);
    state.documentsByChatId.delete(chatId);
    state.activeGenerations.delete(chatId);

    if (state.activeChatId === chatId) {
        state.activeChatId = null;
    }
}

function sortChats() {
    state.chats.sort((left, right) => {
        const sortOrderDifference = (Number(right.sortOrder) || 0) - (Number(left.sortOrder) || 0);
        if (sortOrderDifference !== 0) {
            return sortOrderDifference;
        }

        return new Date(right.updatedAt) - new Date(left.updatedAt);
    });
}

function upsertMessage(chatId, message) {
    const messages = [...(state.messagesByChatId.get(chatId) ?? [])];
    const existingIndex = messages.findIndex(item => item.id === message.id);

    if (existingIndex >= 0) {
        messages[existingIndex] = message;
    } else {
        messages.push(message);
    }

    state.messagesByChatId.set(chatId, messages);
}

function appendMessageDelta(chatId, messageId, delta) {
    const messages = [...(state.messagesByChatId.get(chatId) ?? [])];
    const targetIndex = messages.findIndex(message => message.id === messageId);
    if (targetIndex < 0) {
        return;
    }

    messages[targetIndex] = {
        ...messages[targetIndex],
        content: `${messages[targetIndex].content ?? ""}${delta}`
    };
    state.messagesByChatId.set(chatId, messages);
}

function buildPreview(messages) {
    const lastMessage = [...messages].reverse().find(message => message.content && message.content.trim().length > 0);
    return lastMessage ? shrinkText(lastMessage.content, 96) : EMPTY_CHAT_PREVIEW;
}

function shrinkText(value, maxLength) {
    const cleanValue = value.replace(/\s+/g, " ").trim();
    if (cleanValue.length <= maxLength) {
        return cleanValue;
    }

    return `${cleanValue.slice(0, maxLength - 1).trimEnd()}…`;
}

function setConnectionStatus(text, stateName) {
    connectionStatusNode.textContent = text;
    connectionStatusNode.dataset.state = stateName;
}

function formatDate(value) {
    if (!value) {
        return "сейчас";
    }

    return new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    }).format(new Date(value));
}

function formatMultiline(text) {
    return escapeHtml(text ?? "").replace(/\n/g, "<br>");
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

async function uploadPdf(file) {
    if (!file || !state.activeChatId) {
        return;
    }

    state.uploadingPdf = true;
    updateComposerState();
    setConnectionStatus(`Загружаем PDF: ${file.name}`, "pending");

    try {
        const formData = new FormData();
        formData.append("file", file);

        const response = await window.AuthClient.fetchWithAuth(`/api/chats/${state.activeChatId}/documents/pdf`, {
            method: "POST",
            body: formData
        });

        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Не удалось загрузить PDF."));
        }

        const uploadedDocument = await response.json();
        const documents = [...(state.documentsByChatId.get(state.activeChatId) ?? []), uploadedDocument];
        state.documentsByChatId.set(state.activeChatId, documents);
        renderDocumentList(documents);
        setConnectionStatus(`PDF загружен: ${file.name}`, "connected");
        notify(`PDF загружен: ${file.name}`, "success");
        await openChat(state.activeChatId, { useCache: false });
    } catch (error) {
        setConnectionStatus("Не удалось загрузить PDF", "error");
        notify(error.message || "Не удалось загрузить PDF.", "error");
    } finally {
        state.uploadingPdf = false;
        pdfUploadInput.value = "";
        updateComposerState();
    }
}

async function renameChat(chatId) {
    const chat = state.chats.find(item => item.id === chatId);
    if (!chat) {
        return;
    }

    const nextTitle = window.prompt("Введите новое название чата", chat.title);
    if (nextTitle === null) {
        return;
    }

    const normalizedTitle = nextTitle.trim();
    if (!normalizedTitle) {
        notify("Название чата не может быть пустым.", "warning");
        return;
    }

    if (normalizedTitle === chat.title) {
        return;
    }

    try {
        const response = await window.AuthClient.fetchWithAuth(`/api/chats/${chatId}`, {
            method: "PATCH",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ title: normalizedTitle })
        });

        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Не удалось переименовать чат."));
        }

        const updatedChat = await response.json();
        upsertChatSummary(updatedChat);
        if (chatId === state.activeChatId) {
            renderActiveChat();
        }
        notify("Чат переименован.", "success", 2500);
    } catch (error) {
        notify(error.message || "Не удалось переименовать чат.", "error");
    }
}

async function deleteChat(chatId) {
    const chat = state.chats.find(item => item.id === chatId);
    if (!chat) {
        return;
    }

    if (!window.confirm(`Удалить чат "${chat.title}"? Это действие нельзя отменить.`)) {
        return;
    }

    try {
        const response = await window.AuthClient.fetchWithAuth(`/api/chats/${chatId}`, {
            method: "DELETE"
        });

        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Не удалось удалить чат."));
        }

        removeChatFromState(chatId);
        renderChatList();
        renderActiveChat();
        notify("Чат удален.", "success", 2500);

        if (!state.chats.length) {
            await createChat();
            return;
        }

        if (!state.activeChatId) {
            await openChat(state.chats[0].id, { useCache: true });
        }
    } catch (error) {
        notify(error.message || "Не удалось удалить чат.", "error");
    }
}

function handleChatDragStart(event) {
    if (state.reorderingChats) {
        event.preventDefault();
        return;
    }

    const card = event.currentTarget;
    state.draggedChatId = Number(card.dataset.chatId);
    card.classList.add("dragging");
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", String(state.draggedChatId));
}

function handleChatDragOver(event) {
    if (state.draggedChatId == null) {
        return;
    }

    event.preventDefault();
    event.currentTarget.classList.add("drop-target");
}

function handleChatDragLeave(event) {
    event.currentTarget.classList.remove("drop-target");
}

async function handleChatDrop(event) {
    event.preventDefault();
    const targetCard = event.currentTarget;
    targetCard.classList.remove("drop-target");

    const draggedChatId = state.draggedChatId;
    const targetChatId = Number(targetCard.dataset.chatId);
    if (!draggedChatId || draggedChatId === targetChatId) {
        return;
    }

    const targetRect = targetCard.getBoundingClientRect();
    const placeAfter = event.clientY > targetRect.top + targetRect.height / 2;
    const snapshot = state.chats.map(chat => ({ ...chat }));

    reorderChatsLocally(draggedChatId, targetChatId, placeAfter);
    renderChatList();

    try {
        await persistChatOrder();
    } catch (error) {
        state.chats = snapshot;
        sortChats();
        renderChatList();
        notify(error.message || "Не удалось сохранить порядок чатов.", "error");
    }
}

function handleChatDragEnd(event) {
    state.draggedChatId = null;
    event.currentTarget.classList.remove("dragging");
    document.querySelectorAll(".chat-card.drop-target").forEach(node => {
        node.classList.remove("drop-target");
    });
}

function reorderChatsLocally(draggedChatId, targetChatId, placeAfter) {
    const chats = [...state.chats];
    const fromIndex = chats.findIndex(chat => chat.id === draggedChatId);
    const targetIndex = chats.findIndex(chat => chat.id === targetChatId);

    if (fromIndex < 0 || targetIndex < 0) {
        return;
    }

    const [draggedChat] = chats.splice(fromIndex, 1);
    let nextIndex = targetIndex;

    if (fromIndex < targetIndex) {
        nextIndex -= 1;
    }

    if (placeAfter) {
        nextIndex += 1;
    }

    chats.splice(nextIndex, 0, draggedChat);
    state.chats = chats.map((chat, index) => ({
        ...chat,
        sortOrder: chats.length - index
    }));
}

async function persistChatOrder() {
    state.reorderingChats = true;

    try {
        const response = await window.AuthClient.fetchWithAuth("/api/chats/reorder", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                chatIds: state.chats.map(chat => chat.id)
            })
        });

        if (!response.ok) {
            throw new Error(await readErrorMessage(response, "Не удалось сохранить порядок чатов."));
        }

        state.chats = await response.json();
        sortChats();
        renderChatList();
        notify("Порядок чатов обновлен.", "success", 2000);
    } finally {
        state.reorderingChats = false;
    }
}

async function readErrorMessage(response, fallbackMessage) {
    try {
        const text = (await response.text()).trim();
        if (!text) {
            return fallbackMessage;
        }

        return text.length > 240 ? fallbackMessage : text;
    } catch (error) {
        return fallbackMessage;
    }
}

composerForm?.addEventListener("submit", event => {
    event.preventDefault();

    const content = messageInput.value.trim();
    if (!content || !state.activeChatId || !state.socket || state.socket.readyState !== WebSocket.OPEN) {
        return;
    }

    state.socket.send(JSON.stringify({
        type: "chat.send",
        chatId: state.activeChatId,
        content
    }));

    messageInput.value = "";
    state.activeGenerations.add(state.activeChatId);
    updateComposerState();
    renderChatList();
});

messageInput?.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        composerForm?.requestSubmit();
    }
});

newChatButton?.addEventListener("click", async () => {
    await createChat();
});

uploadPdfButton?.addEventListener("click", () => {
    if (!state.activeChatId || state.uploadingPdf) {
        return;
    }

    pdfUploadInput.click();
});

pdfUploadInput?.addEventListener("change", async event => {
    const [file] = event.target.files ?? [];
    await uploadPdf(file);
});

logoutButton?.addEventListener("click", async () => {
    state.manualDisconnect = true;
    window.AuthClient?.stopSessionRefresh();

    if (state.socket) {
        state.socket.close();
    }

    await fetch("/api/auth/logout", {
        method: "POST",
        credentials: "include"
    });

    notify("Вы вышли из системы.", "info", 2500);
    window.location.href = "/login";
});

document.addEventListener("app:auth-refresh-failed", () => {
    notify("Сессию не удалось обновить. Потребуется повторный вход.", "warning");
});

bootstrap();
