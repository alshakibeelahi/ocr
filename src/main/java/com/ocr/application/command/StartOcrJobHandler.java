package com.ocr.application.command;

import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.dto.StreamEvent;
import com.ocr.application.port.OllamaVisionPort;
import com.ocr.application.port.StreamEventPublisher;
import com.ocr.domain.model.JobId;
import com.ocr.domain.model.OcrJob;
import com.ocr.domain.model.OcrPage;
import com.ocr.domain.model.PageMetadata;
import com.ocr.domain.repository.OcrJobRepository;
import com.ocr.domain.service.ImagePreprocessor;
import com.ocr.domain.service.PdfPageExtractor;
import com.ocr.interfaces.config.OcrProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.Executor;

@Service
public class StartOcrJobHandler {

    private final OcrJobRepository repository;
    private final PdfPageExtractor pdfPageExtractor;
    private final ImagePreprocessor imagePreprocessor;
    private final OllamaVisionPort ollamaVisionPort;
    private final StreamEventPublisher eventPublisher;
    private final OcrProperties ocrProperties;
    private final Executor ocrTaskExecutor;

    public StartOcrJobHandler(
            OcrJobRepository repository,
            PdfPageExtractor pdfPageExtractor,
            ImagePreprocessor imagePreprocessor,
            OllamaVisionPort ollamaVisionPort,
            StreamEventPublisher eventPublisher,
            OcrProperties ocrProperties,
            @Qualifier("ocrTaskExecutor") Executor ocrTaskExecutor
    ) {
        this.repository = repository;
        this.pdfPageExtractor = pdfPageExtractor;
        this.imagePreprocessor = imagePreprocessor;
        this.ollamaVisionPort = ollamaVisionPort;
        this.eventPublisher = eventPublisher;
        this.ocrProperties = ocrProperties;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    public JobId handle(StartOcrJobCommand command) throws IOException {
        JobId jobId = JobId.generate();
        OcrJob job = new OcrJob(jobId, command.fileName());
        int totalPages = pdfPageExtractor.countPages(command.pdfBytes());
        job.initializePages(totalPages);
        repository.save(job);

        ocrTaskExecutor.execute(() -> processJob(jobId, command.pdfBytes()));

        return jobId;
    }

    private void processJob(JobId jobId, byte[] pdfBytes) {
        OcrJob job = repository.findById(jobId).orElseThrow();
        job.startProcessing();
        repository.save(job);

        eventPublisher.publish(StreamEvent.jobStarted(
                jobId.toString(),
                job.getFileName(),
                job.getTotalPages()
        ));

        try {
            for (OcrPage page : job.getPages()) {
                processPage(job, pdfBytes, page);
            }
            job.complete();
            repository.save(job);
            eventPublisher.publish(StreamEvent.jobCompleted(
                    jobId.toString(),
                    OcrJobResponse.from(job)
            ));
        } catch (Exception e) {
            job.fail(e.getMessage());
            repository.save(job);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), null));
        }
    }

    private void processPage(OcrJob job, byte[] pdfBytes, OcrPage page) throws IOException {
        JobId jobId = job.getJobId();
        int pageNum = page.getPageNumber().value();
        int totalPages = job.getTotalPages();

        page.startProcessing();
        repository.save(job);
        eventPublisher.publish(StreamEvent.pageStarted(jobId.toString(), pageNum, totalPages));

        long startMs = System.currentTimeMillis();
        try {
            BufferedImage rendered = pdfPageExtractor.renderPage(
                    pdfBytes,
                    page.getPageIndex(),
                    ocrProperties.renderDpi()
            );
            ImagePreprocessor.PreprocessResult preprocessed = imagePreprocessor.preprocess(
                    rendered,
                    ocrProperties.renderDpi(),
                    ocrProperties.maxImageDimension()
            );
            String base64Png = toBase64Png(preprocessed.image());

            ollamaVisionPort.streamOcr(base64Png, chunk -> {
                page.appendText(chunk);
                repository.save(job);
                eventPublisher.publish(StreamEvent.pageChunk(jobId.toString(), pageNum, chunk));
            });

            long durationMs = System.currentTimeMillis() - startMs;
            PageMetadata metadata = new PageMetadata(
                    preprocessed.metadata().dpi(),
                    preprocessed.metadata().preprocessed(),
                    preprocessed.metadata().width(),
                    preprocessed.metadata().height(),
                    durationMs
            );
            page.complete(metadata);
            repository.save(job);
            eventPublisher.publish(StreamEvent.pageCompleted(
                    jobId.toString(),
                    pageNum,
                    page.getExtractedText(),
                    metadata.toMap()
            ));
        } catch (Exception e) {
            page.fail(e.getMessage());
            repository.save(job);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), pageNum));
            throw e;
        }
    }

    private String toBase64Png(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
