package com.ocr.v2.pipeline;

import com.ocr.v2.config.AiProperties;
import com.ocr.v2.pdf.PreparedDocument;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import com.ocr.v2.config.V2Component;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Assembles the messages for one extraction.
 *
 * <p>The ordering is the whole design. The model sees, in this order: who it is and the
 * non-negotiable grounding rules; the retrieved knowledge, fenced and explicitly labelled as hints
 * that carry no values; the document's own text layer, labelled authoritative; and finally the page
 * images.
 *
 * <p>Putting the grounding rules before the hints and the hints before the document means that by
 * the time the model reads a hint it already knows a hint cannot be a source of values, and by the
 * time it reads the document it knows the document overrides everything above it.
 */
@V2Component
public class PromptAssembler {

    private final AiProperties properties;

    public PromptAssembler(AiProperties properties) {
        this.properties = properties;
    }

    /** Small, cheap prompt for the scan fallback: identify the template, extract nothing. */
    public static final String SIGNATURE_PROMPT = """
            Look at this single page and describe only its STRUCTURE, so a filing system can
            recognise the template. Reply in at most 8 short lines:

            - Document type as printed in the title.
            - The company name in the letterhead.
            - The company name in the addressee block, if there is one.
            - The column headings of the main table, in order.
            - Any currency symbol or code shown in a column heading.

            Do NOT report any invoice number, date, quantity, price or amount.
            Reply as plain text lines, no JSON, no commentary.
            """;

    public List<Message> assemble(PreparedDocument document, RetrievedKnowledge knowledge, boolean includeTextLayer) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(knowledge)));
        messages.add(userMessage(document, includeTextLayer));
        return messages;
    }

    // ------------------------------------------------------------------ system

    private String systemPrompt(RetrievedKnowledge knowledge) {
        StringBuilder prompt = new StringBuilder("""
                You are a senior trade finance officer and document analyst extracting structured
                data from Proforma Invoices for a bank. The output is used to initiate payments, so
                a wrong value is far more costly than a missing one.

                NON-NEGOTIABLE RULES
                1. Every value you output must be readable in the attached document. If you cannot
                   read it, output null. Never guess, never estimate, never infer from convention.
                2. The REFERENCE KNOWLEDGE below tells you HOW and WHERE to read. It is NOT a source
                   of values. If it disagrees with the document, the document wins.
                3. Values appearing in reference knowledge - including any example - are masked
                   placeholders belonging to other documents. Copying one into your answer is always
                   an error.
                4. Do not repair the document. If a printed total disagrees with the line items,
                   report both exactly as printed; an automated check afterwards will flag it.
                5. Return only the JSON object described by the target schema. No markdown, no prose.
                """);

        if (!knowledge.isEmpty()) {
            prompt.append("\n===== REFERENCE KNOWLEDGE (guidance only - contains no values for this document) =====\n");
            for (RetrievedKnowledge.Chunk chunk : knowledge.chunks()) {
                prompt.append("\n--- [").append(chunk.type()).append("] ").append(chunk.title());
                if (chunk.score() != null) {
                    prompt.append(String.format(" (match %.2f)", chunk.score()));
                }
                prompt.append(" ---\n").append(chunk.body().strip()).append('\n');
            }
            prompt.append("\n===== END REFERENCE KNOWLEDGE =====\n");
        }

        return prompt.toString();
    }

    // ------------------------------------------------------------------ user

    private UserMessage userMessage(PreparedDocument document, boolean includeTextLayer) {
        StringBuilder text = new StringBuilder();
        text.append("Extract the Proforma Invoice data from the attached document.\n")
                .append("The attached images are the full document in page order. Page count: ")
                .append(document.pageCount()).append(".\n");

        boolean withText = includeTextLayer
                && properties.extraction().includeTextLayer()
                && document.textLayerUsable();

        if (withText) {
            text.append("""

                    Below is the text layer embedded in this same PDF - the exact characters the
                    document declares. It is authoritative for spelling, digits and codes: where the
                    rendered image is ambiguous, trust this text. It carries no layout, so use the
                    images to decide which value belongs to which field and which table row.

                    ===== DOCUMENT TEXT LAYER =====
                    """)
                    .append(truncate(document.textLayer(), properties.extraction().maxTextLayerChars()))
                    .append("\n===== END DOCUMENT TEXT LAYER =====\n");
        } else if (document.pdf()) {
            text.append("\nThis PDF has no usable text layer (it is a scan). Read every value from the images.\n");
        }

        text.append("\nReturn only the JSON object.");

        List<Media> media = document.base64Images().stream()
                .map(PromptAssembler::toMedia)
                .toList();

        return UserMessage.builder()
                .text(text.toString())
                .media(media)
                .build();
    }

    private static Media toMedia(String base64Png) {
        return Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(Base64.getDecoder().decode(base64Png))
                .build();
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n[text layer truncated at " + max + " characters]";
    }
}
