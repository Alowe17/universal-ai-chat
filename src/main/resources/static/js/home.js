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
    uploadingPdf: false
};

async function bootstrap() {
    const session = await loadSession();
    if (!session) {
        return;
    }

    state.session = session.user;
    renderUser(session.user);
    connectSocket();
    await loadChats();
}

async function loadSession() {
    try {
        const response = await fetch("/api/app/session", {
            method: "GET",
            credentials: "include"
        });

        if (!response.ok) {
            throw new Error("Failed to load session");
        }

        const session = await response.json();
        if (!session.authenticated || !session.user) {
            window.location.href = "/login";
            return null;
        }

        return session;
    } catch (error) {
        userInfoNode.innerHTML = "<p>Не удалось загрузить данные пользователя.</p>";
        return null;
    }
}

async function loadChats() {
    try {
        const response = await fetch("/api/chats", {
            method: "GET",
            credentials: "include"
        });

        if (!response.ok) {
            throw new Error("Failed to load chats");
        }

        state.chats = await response.json();
        renderChatList();

        if (state.chats.length === 0) {
            await createChat();
            return;
        }

        await openChat(state.chats[0].id);
    } catch (error) {
        chatListNode.innerHTML = "<p class=\"sidebar-empty\">Не удалось загрузить список чатов.</p>";
    }
}

async function createChat() {
    try {
        const response = await fetch("/api/chats", {
            method: "POST",
            credentials: "include",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({})
        });

        if (!response.ok) {
            throw new Error("Failed to create chat");
        }

        const chat = await response.json();
        upsertChatSummary({
            id: chat.id,
            title: chat.title,
            preview: "Диалог еще пуст",
            createdAt: chat.createdAt,
            updatedAt: chat.updatedAt,
            messageCount: chat.messages.length
        });
        state.messagesByChatId.set(chat.id, chat.messages);
        state.documentsByChatId.set(chat.id, chat.documents ?? []);
        await openChat(chat.id, { useCache: true });
    } catch (error) {
        setConnectionStatus("Не удалось создать чат", "error");
    }
}

async function openChat(chatId, options = {}) {
    state.activeChatId = chatId;
    renderChatList();

    if (!options.useCache || !state.messagesByChatId.has(chatId)) {
        try {
            const response = await fetch(`/api/chats/${chatId}`, {
                method: "GET",
                credentials: "include"
            });

            if (!response.ok) {
                throw new Error("Failed to load chat");
            }

            const chat = await response.json();
            state.messagesByChatId.set(chatId, chat.messages);
            state.documentsByChatId.set(chatId, chat.documents ?? []);
            upsertChatSummary({
                id: chat.id,
                title: chat.title,
                preview: buildPreview(chat.messages),
                createdAt: chat.createdAt,
                updatedAt: chat.updatedAt,
                messageCount: chat.messages.length
            });
        } catch (error) {
            messageListNode.innerHTML = "<div class=\"empty-state\"><p>Не удалось загрузить сообщения чата.</p></div>";
            return;
        }
    }

    renderActiveChat();
}

function connectSocket() {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const socketUrl = `${protocol}://${window.location.host}/ws/chat`;

    setConnectionStatus("Подключение к WebSocket...", "pending");
    state.socket = new WebSocket(socketUrl);

    state.socket.addEventListener("open", () => {
        setConnectionStatus("WebSocket подключен", "connected");
        updateComposerState();
    });

    state.socket.addEventListener("message", event => {
        const payload = JSON.parse(event.data);
        handleSocketEvent(payload);
    });

    state.socket.addEventListener("close", () => {
        state.socket = null;
        state.activeGenerations.clear();
        updateComposerState();

        if (state.manualDisconnect) {
            return;
        }

        setConnectionStatus("WebSocket отключен. Переподключение...", "warning");
        window.clearTimeout(state.reconnectTimerId);
        state.reconnectTimerId = window.setTimeout(connectSocket, 2000);
    });

    state.socket.addEventListener("error", () => {
        setConnectionStatus("Ошибка WebSocket", "error");
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
            updateComposerState();
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
    if (state.chats.length === 0) {
        chatListNode.innerHTML = "<p class=\"sidebar-empty\">Чаты появятся после создания первого диалога.</p>";
        return;
    }

    chatListNode.innerHTML = state.chats
        .map(chat => `
            <button
                class="chat-item ${chat.id === state.activeChatId ? "active" : ""}"
                type="button"
                data-chat-id="${chat.id}"
            >
                <strong>${escapeHtml(chat.title)}</strong>
                <span>${escapeHtml(chat.preview ?? "Диалог еще пуст")}</span>
                <small>${formatDate(chat.updatedAt)}</small>
            </button>
        `)
        .join("");

    document.querySelectorAll("[data-chat-id]").forEach(button => {
        button.addEventListener("click", async () => {
            await openChat(Number(button.dataset.chatId));
        });
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

    if (messages.length === 0) {
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
        ? "Введите сообщение для deepseek-r1:7b..."
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

    state.chats.sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt));
    renderChatList();
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
    return lastMessage ? shrinkText(lastMessage.content, 96) : "Диалог еще пуст";
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

        const response = await fetch(`/api/chats/${state.activeChatId}/documents/pdf`, {
            method: "POST",
            credentials: "include",
            body: formData
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || "PDF upload failed");
        }

        const uploadedDocument = await response.json();
        const documents = [...(state.documentsByChatId.get(state.activeChatId) ?? []), uploadedDocument];
        state.documentsByChatId.set(state.activeChatId, documents);
        renderDocumentList(documents);
        setConnectionStatus(`PDF загружен: ${file.name}`, "connected");
        await openChat(state.activeChatId, { useCache: false });
    } catch (error) {
        setConnectionStatus("Не удалось загрузить PDF", "error");
    } finally {
        state.uploadingPdf = false;
        pdfUploadInput.value = "";
        updateComposerState();
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

    if (state.socket) {
        state.socket.close();
    }

    await fetch("/api/auth/logout", {
        method: "POST",
        credentials: "include"
    });

    window.location.href = "/login";
});

bootstrap();
