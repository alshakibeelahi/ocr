package com.ocr.application.command;

import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.port.JobCallbackPort;
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
import com.ocr.interfaces.config.OllamaProperties;
import com.ocr.shared.pi.PiExtractionNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class StartPiDataExtractionJobHandler {

    private static final Logger log = LoggerFactory.getLogger(StartPiDataExtractionJobHandler.class);

    private final OcrJobRepository repository;
    private final PdfPageExtractor pdfPageExtractor;
    private final ImagePreprocessor imagePreprocessor;
    private final OllamaVisionPort ollamaVisionPort;
    private final StreamEventPublisher eventPublisher;
    private final JobCallbackPort jobCallbackPort;
    private final OcrProperties ocrProperties;
    private final OllamaProperties ollamaProperties;
    private final PiExtractionNormalizer normalizer;
    private final Executor ocrTaskExecutor;

    public StartPiDataExtractionJobHandler(
            OcrJobRepository repository,
            PdfPageExtractor pdfPageExtractor,
            ImagePreprocessor imagePreprocessor,
            OllamaVisionPort ollamaVisionPort,
            StreamEventPublisher eventPublisher,
            JobCallbackPort jobCallbackPort,
            OcrProperties ocrProperties,
            OllamaProperties ollamaProperties,
            PiExtractionNormalizer normalizer,
            @Qualifier("ocrTaskExecutor") Executor ocrTaskExecutor
    ) {
        this.repository = repository;
        this.pdfPageExtractor = pdfPageExtractor;
        this.imagePreprocessor = imagePreprocessor;
        this.ollamaVisionPort = ollamaVisionPort;
        this.eventPublisher = eventPublisher;
        this.jobCallbackPort = jobCallbackPort;
        this.ocrProperties = ocrProperties;
        this.ollamaProperties = ollamaProperties;
        this.normalizer = normalizer;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    public JobId handle(StartPiDataExtractionJobCommand command) throws IOException {
        JobId jobId = JobId.generate();
        OcrJob job = new OcrJob(jobId, command.fileName());
        boolean pdf = isPdf(command);
        int totalPages = pdf ? pdfPageExtractor.countPages(command.pdfBytes()) : 1;
        job.initializePages(1);
        repository.save(job);
        log.info(
                "Queued PI extraction job: jobId={}, fileName={}, inputType={}, sourcePages={}, callbackUrl={}",
                jobId,
                command.fileName(),
                pdf ? "PDF" : "IMAGE",
                totalPages,
                command.callbackUrl()
        );

        ocrTaskExecutor.execute(() -> processJob(command, jobId, totalPages));

        return jobId;
    }

    private void processJob(StartPiDataExtractionJobCommand command, JobId jobId, int sourcePageCount) {
        OcrJob job = repository.findById(jobId).orElseThrow();
        job.startProcessing();
        repository.save(job);
        log.info("Started PI extraction job processing: jobId={}, sourcePages={}", jobId, sourcePageCount);

        eventPublisher.publish(StreamEvent.jobStarted(
                jobId.toString(),
                job.getFileName(),
                sourcePageCount
        ));

        try {
            processDocument(job, command, sourcePageCount);
            job.complete();
            repository.save(job);
            OcrJobResponse jobResponse = OcrJobResponse.from(job);
            log.info("PI extraction job completed successfully: jobId={}, status={}", jobId, jobResponse.status());
            eventPublisher.publish(StreamEvent.jobCompleted(
                    jobId.toString(),
                    jobResponse
            ));
            sendCallback(command.callbackUrl(), jobResponse);
        } catch (Exception e) {
            job.fail(e.getMessage());
            repository.save(job);
            log.error("PI extraction job failed: jobId={}, error={}", jobId, e.getMessage(), e);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), null));
            sendCallback(command.callbackUrl(), OcrJobResponse.from(job));
        }
    }

    private void sendCallback(String callbackUrl, OcrJobResponse jobResponse) {
        try {
            log.info(
                    "Sending PI extraction callback: jobId={}, status={}, callbackUrl={}",
                    jobResponse.jobId(),
                    jobResponse.status(),
                    callbackUrl
            );
            jobCallbackPort.postJobResult(callbackUrl, jobResponse);
            log.info("PI extraction callback finished: jobId={}, callbackUrl={}", jobResponse.jobId(), callbackUrl);
        } catch (Exception callbackException) {
            // Callback delivery must not change the OCR job result after processing finishes.
            log.error("Failed to deliver callback for OCR job {} to {}", jobResponse.jobId(), callbackUrl, callbackException);
        }
    }

    private void processDocument(OcrJob job, StartPiDataExtractionJobCommand command, int sourcePageCount) throws IOException {
        JobId jobId = job.getJobId();
        OcrPage page = job.getPages().get(0);
        int resultNum = page.getPageNumber().value();
        boolean pdf = isPdf(command);

        page.startProcessing();
        repository.save(job);
        eventPublisher.publish(StreamEvent.pageStarted(jobId.toString(), resultNum, 1));
        log.info(
                "Preparing PI extraction document payload: jobId={}, page={}, inputType={}, sourcePages={}",
                jobId,
                resultNum,
                pdf ? "PDF" : "IMAGE",
                sourcePageCount
        );

        long startMs = System.currentTimeMillis();
        try {
            List<String> base64Pages = new ArrayList<>();
            int maxWidth = 0;
            int maxHeight = 0;

            if (pdf) {
                for (int pageIndex = 0; pageIndex < sourcePageCount; pageIndex++) {
                    BufferedImage rendered = pdfPageExtractor.renderPage(
                            command.pdfBytes(),
                            pageIndex,
                            ocrProperties.renderDpi()
                    );
                    ImagePreprocessor.PreprocessResult preprocessed = imagePreprocessor.preprocess(
                            rendered,
                            ocrProperties.renderDpi(),
                            ocrProperties.maxImageDimension()
                    );
                    base64Pages.add(toBase64Png(preprocessed.image()));
                    maxWidth = Math.max(maxWidth, preprocessed.metadata().width());
                    maxHeight = Math.max(maxHeight, preprocessed.metadata().height());
                }
                log.info("Prepared PDF pages for PI extraction: jobId={}, preparedImages={}", jobId, base64Pages.size());
            } else {
                ImagePreprocessor.PreprocessResult preprocessed = preprocessUploadedImage(command.pdfBytes());
                base64Pages.add(toBase64Png(preprocessed.image()));
                maxWidth = preprocessed.metadata().width();
                maxHeight = preprocessed.metadata().height();
                log.info("Prepared image upload for PI extraction: jobId={}, width={}, height={}", jobId, maxWidth, maxHeight);
            }

            log.info("Invoking vision model for PI extraction: jobId={}, imageCount={}", jobId, base64Pages.size());
            // Job-based flow: accumulate the streamed tokens in memory; clients poll the job
            // status instead of consuming per-token events.
            ollamaVisionPort.streamVision(base64Pages, promptForDocument(sourcePageCount), true, page::appendText);
            log.info("Vision model stream finished for PI extraction: jobId={}", jobId);

            long durationMs = System.currentTimeMillis() - startMs;
            page.replaceText(normalizer.normalize(page.getExtractedText()));
            PageMetadata metadata = new PageMetadata(
                    ocrProperties.renderDpi(),
                    true,
                    maxWidth,
                    maxHeight,
                    durationMs
            );
            page.complete(metadata);
            repository.save(job);
            Map<String, Object> eventMetadata = metadata.toMap();
            eventMetadata.put("sourcePageCount", sourcePageCount);
            eventMetadata.put("analyzedAs", "single-document");
            log.info(
                    "PI extraction page completed: jobId={}, page={}, durationMs={}, width={}, height={}",
                    jobId,
                    resultNum,
                    durationMs,
                    maxWidth,
                    maxHeight
            );
            eventPublisher.publish(StreamEvent.pageCompleted(
                    jobId.toString(),
                    resultNum,
                    page.getExtractedText(),
                    eventMetadata
            ));
        } catch (Exception e) {
            page.fail(e.getMessage());
            repository.save(job);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), resultNum));
            throw e;
        }
    }

    private String promptForDocument(int sourcePageCount) {
        return ollamaProperties.piDataExtractionPrompt()
                + "\nThe attached images are the full document in order. Page count: " + sourcePageCount + ".";
    }

    private boolean isPdf(StartPiDataExtractionJobCommand command) {
        String contentType = command.contentType();
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return true;
        }
        String fileName = command.fileName();
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    private ImagePreprocessor.PreprocessResult preprocessUploadedImage(byte[] fileBytes) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (sourceImage == null) {
            throw new IllegalArgumentException("Invalid image file");
        }
        return imagePreprocessor.preprocess(
                sourceImage,
                ocrProperties.renderDpi(),
                ocrProperties.maxImageDimension()
        );
    }

    private String toBase64Png(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
