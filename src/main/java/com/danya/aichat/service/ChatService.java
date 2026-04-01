package com.danya.aichat.service;

import com.danya.aichat.model.dto.chat.ChatDetailResponse;
import com.danya.aichat.model.dto.chat.ChatDocumentResponse;
import com.danya.aichat.model.dto.chat.ChatMessageResponse;
import com.danya.aichat.model.dto.chat.ChatSummaryResponse;
import com.danya.aichat.model.entity.Chat;
import com.danya.aichat.model.entity.ChatDocument;
import com.danya.aichat.model.entity.ChatMessage;
import com.danya.aichat.model.entity.User;
import com.danya.aichat.model.enums.ChatMessageRole;
import com.danya.aichat.repository.ChatDocumentRepository;
import com.danya.aichat.repository.ChatMessageRepository;
import com.danya.aichat.repository.ChatRepository;
import com.danya.aichat.repository.UserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    private static final String DEFAULT_CHAT_TITLE = "Новый чат";
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_MESSAGE_LENGTH = 12_000;
    private static final long MAX_PDF_SIZE_BYTES = 15L * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 200;
    private static final int MAX_STORED_PDF_TEXT_LENGTH = 200_000;
    private static final int MAX_HISTORY_MESSAGES = 16;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 8_000;
    private static final int CHUNK_SIZE = 1_800;
    private static final int CHUNK_OVERLAP = 250;
    private static final int MAX_SELECTED_CHUNKS = 6;
    private static final int MAX_TOTAL_DOCUMENT_CONTEXT = 14_000;

    private final ChatRepository chatRepository;
    private final ChatDocumentRepository chatDocumentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    public List<ChatSummaryResponse> getChatsForUser(String username) {
        User user = requireUser(username);
        return chatRepository.findAllByUserIdOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public ChatDetailResponse createChat(String username, String requestedTitle) {
        User user = requireUser(username);

        Chat chat = new Chat();
        chat.setUser(user);
        chat.setTitle(resolveInitialTitle(requestedTitle));

        Chat savedChat = chatRepository.save(chat);
        return ChatDetailResponse.from(savedChat, List.of(), List.of());
    }

    public ChatDetailResponse getChatForUser(String username, Long chatId) {
        Chat chat = requireOwnedChat(username, chatId);
        List<ChatMessage> messages = chatMessageRepository.findAllByChatIdOrderByCreatedAtAscIdAsc(chatId);
        return ChatDetailResponse.from(chat, messages, getDocumentResponses(chatId));
    }

    @Transactional
    public ChatDocumentResponse uploadPdfDocument(String username, Long chatId, MultipartFile file) {
        Chat chat = requireOwnedChat(username, chatId);
        validatePdfFile(file);
        PdfExtractionResult extractionResult = extractPdfText(file);

        ChatDocument document = new ChatDocument();
        document.setChat(chat);
        document.setFileName(resolveFileName(file.getOriginalFilename()));
        document.setPageCount(extractionResult.pageCount());
        document.setTextLength(extractionResult.text().length());
        document.setExtractedText(extractionResult.text());

        chat.touch();
        ChatDocument savedDocument = chatDocumentRepository.save(document);
        chatRepository.save(chat);
        return ChatDocumentResponse.from(savedDocument);
    }

    @Transactional
    public PromptContext registerUserPrompt(String username, Long chatId, String content) {
        Chat chat = requireOwnedChat(username, chatId);
        String normalizedContent = normalizeMessage(content);

        if (DEFAULT_CHAT_TITLE.equals(chat.getTitle())) {
            chat.setTitle(buildTitleFromMessage(normalizedContent));
        }

        ChatMessage userMessage = new ChatMessage();
        userMessage.setChat(chat);
        userMessage.setRole(ChatMessageRole.USER);
        userMessage.setContent(normalizedContent);

        chat.touch();
        ChatMessage savedUserMessage = chatMessageRepository.save(userMessage);
        chatRepository.save(chat);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setChat(chat);
        assistantMessage.setRole(ChatMessageRole.ASSISTANT);
        assistantMessage.setContent("");

        chat.touch();
        ChatMessage savedAssistantMessage = chatMessageRepository.save(assistantMessage);
        chatRepository.save(chat);

        return new PromptContext(
                toSummary(chat),
                ChatMessageResponse.from(savedUserMessage),
                ChatMessageResponse.from(savedAssistantMessage),
                buildOllamaHistory(chat.getId(), normalizedContent)
        );
    }

    @Transactional
    public ChatMessageResponse finalizeAssistantMessage(String username, Long chatId, Long messageId, String content) {
        Chat chat = requireOwnedChat(username, chatId);
        ChatMessage assistantMessage = chatMessageRepository.findByIdAndChatId(messageId, chat.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assistant message not found"));

        assistantMessage.setContent(content == null ? "" : content.strip());
        chat.touch();

        ChatMessage savedAssistantMessage = chatMessageRepository.save(assistantMessage);
        chatRepository.save(chat);
        return ChatMessageResponse.from(savedAssistantMessage);
    }

    public ChatSummaryResponse getChatSummary(String username, Long chatId) {
        return toSummary(requireOwnedChat(username, chatId));
    }

    private List<OllamaClient.OllamaMessage> buildOllamaHistory(Long chatId, String currentPrompt) {
        List<OllamaClient.OllamaMessage> history = new ArrayList<>();
        String documentContext = buildDocumentContext(chatId, currentPrompt);
        if (documentContext != null && !documentContext.isBlank()) {
            history.add(new OllamaClient.OllamaMessage(ChatMessageRole.SYSTEM.getOllamaRole(), documentContext));
        }

        List<ChatMessage> allMessages = chatMessageRepository.findAllByChatIdOrderByCreatedAtAscIdAsc(chatId);
        int fromIndex = Math.max(0, allMessages.size() - MAX_HISTORY_MESSAGES);

        history.addAll(allMessages.subList(fromIndex, allMessages.size())
                .stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> new OllamaClient.OllamaMessage(
                        message.getRole().getOllamaRole(),
                        trimToLength(message.getContent(), MAX_HISTORY_MESSAGE_LENGTH)
                ))
                .toList());

        return history;
    }

    private ChatSummaryResponse toSummary(Chat chat) {
        List<ChatMessage> recentMessages = chatMessageRepository.findTop5ByChatIdOrderByCreatedAtDescIdDesc(chat.getId());
        String preview = recentMessages.stream()
                .map(ChatMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .map(this::buildPreview)
                .orElse("Диалог еще пуст");

        return ChatSummaryResponse.from(chat, preview, chatMessageRepository.countByChatId(chat.getId()));
    }

    private User requireUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private Chat requireOwnedChat(String username, Long chatId) {
        User user = requireUser(username);
        return chatRepository.findByIdAndUserId(chatId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
    }

    private String resolveInitialTitle(String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            return DEFAULT_CHAT_TITLE;
        }

        return truncate(requestedTitle.strip(), MAX_TITLE_LENGTH);
    }

    private String normalizeMessage(String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content must not be blank");
        }

        String normalized = content.strip();
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message content is too long. Maximum length is " + MAX_MESSAGE_LENGTH + " characters"
            );
        }

        return normalized;
    }

    private String buildTitleFromMessage(String content) {
        return truncate(content.replaceAll("\\s+", " "), 56);
    }

    private String buildPreview(String content) {
        return truncate(content.replaceAll("\\s+", " "), 96);
    }

    private List<ChatDocumentResponse> getDocumentResponses(Long chatId) {
        return chatDocumentRepository.findAllByChatIdOrderByCreatedAtAsc(chatId)
                .stream()
                .map(ChatDocumentResponse::from)
                .toList();
    }

    private String buildDocumentContext(Long chatId, String currentPrompt) {
        List<ChatDocument> documents = chatDocumentRepository.findAllByChatIdOrderByCreatedAtAsc(chatId);
        if (documents.isEmpty()) {
            return null;
        }

        List<DocumentChunk> relevantChunks = selectRelevantChunks(documents, currentPrompt);
        if (relevantChunks.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder("""
                The user uploaded PDF documents to this chat.
                Use only the relevant excerpts below whenever they help answer the user's question.
                If the answer cannot be grounded in the attached PDFs, say that clearly.

                """);

        int remainingCharacters = MAX_TOTAL_DOCUMENT_CONTEXT;
        for (DocumentChunk chunk : relevantChunks) {
            if (remainingCharacters <= 0) {
                break;
            }

            String excerpt = trimToLength(chunk.text(), remainingCharacters);
            if (excerpt.isBlank()) {
                continue;
            }

            context.append("PDF excerpt from ")
                    .append(chunk.fileName())
                    .append(", part ")
                    .append(chunk.chunkIndex() + 1)
                    .append("\n")
                    .append(excerpt)
                    .append("\n\n");

            remainingCharacters -= excerpt.length();
        }

        return context.toString().strip();
    }

    private List<DocumentChunk> selectRelevantChunks(List<ChatDocument> documents, String currentPrompt) {
        List<DocumentChunkScore> scoredChunks = new ArrayList<>();
        Set<String> queryTerms = extractSearchTerms(currentPrompt);

        for (ChatDocument document : documents) {
            List<String> chunks = splitIntoChunks(document.getExtractedText(), CHUNK_SIZE, CHUNK_OVERLAP);
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                int score = scoreChunk(chunk, queryTerms);
                scoredChunks.add(new DocumentChunkScore(
                        new DocumentChunk(document.getFileName(), index, chunk),
                        score
                ));
            }
        }

        scoredChunks.sort(Comparator
                .comparingInt(DocumentChunkScore::score).reversed()
                .thenComparing(item -> item.chunk().fileName())
                .thenComparingInt(item -> item.chunk().chunkIndex()));

        List<DocumentChunk> selectedChunks = new ArrayList<>();
        int fallbackIndex = 0;
        while (selectedChunks.size() < MAX_SELECTED_CHUNKS && fallbackIndex < scoredChunks.size()) {
            DocumentChunk candidate = scoredChunks.get(fallbackIndex).chunk();
            if (selectedChunks.stream().noneMatch(existing ->
                    existing.fileName().equals(candidate.fileName()) && existing.chunkIndex() == candidate.chunkIndex()
            )) {
                selectedChunks.add(candidate);
            }
            fallbackIndex++;
        }

        return selectedChunks;
    }

    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            chunks.add(text.substring(start, end).strip());
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }

        return chunks;
    }

    private Set<String> extractSearchTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }

        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (token.length() >= 3) {
                terms.add(token);
            }
        }

        return terms;
    }

    private int scoreChunk(String chunk, Set<String> queryTerms) {
        if (chunk == null || chunk.isBlank()) {
            return Integer.MIN_VALUE;
        }

        if (queryTerms.isEmpty()) {
            return 0;
        }

        String normalizedChunk = chunk.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : queryTerms) {
            if (normalizedChunk.contains(term)) {
                score += 10;
            }
        }

        return score;
    }

    private void validatePdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file must not be empty");
        }

        if (file.getSize() > MAX_PDF_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file is too large. Maximum size is 15 MB");
        }

        String fileName = resolveFileName(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        boolean pdfByExtension = fileName.endsWith(".pdf");
        boolean pdfByContentType = "application/pdf".equalsIgnoreCase(file.getContentType());

        if (!pdfByExtension && !pdfByContentType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported");
        }
    }

    private PdfExtractionResult extractPdfText(MultipartFile file) {
        try (PDDocument pdfDocument = PDDocument.load(file.getBytes())) {
            int pageCount = pdfDocument.getNumberOfPages();
            if (pageCount <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded PDF is empty");
            }

            if (pageCount > MAX_PDF_PAGES) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "PDF is too large. Maximum supported size is " + MAX_PDF_PAGES + " pages"
                );
            }

            PDFTextStripper textStripper = new PDFTextStripper();
            String extractedText = normalizeExtractedText(textStripper.getText(pdfDocument));
            if (extractedText.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No readable text was found in this PDF");
            }

            return new PdfExtractionResult(pageCount, trimToLength(extractedText, MAX_STORED_PDF_TEXT_LENGTH));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read PDF file", exception);
        }
    }

    private String normalizeExtractedText(String value) {
        return value.replace("\u0000", "")
                .replace("\r", "")
                .replaceAll("[\\t\\f\\x0B]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String resolveFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "document.pdf";
        }

        return truncate(originalFileName.strip(), 255);
    }

    private String trimToLength(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength - 1).stripTrailing() + "…";
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength - 1).stripTrailing() + "…";
    }

    public record PromptContext(
            ChatSummaryResponse chat,
            ChatMessageResponse userMessage,
            ChatMessageResponse assistantMessage,
            List<OllamaClient.OllamaMessage> history
    ) {
    }

    private record PdfExtractionResult(
            int pageCount,
            String text
    ) {
    }

    private record DocumentChunk(
            String fileName,
            int chunkIndex,
            String text
    ) {
    }

    private record DocumentChunkScore(
            DocumentChunk chunk,
            int score
    ) {
    }
}
