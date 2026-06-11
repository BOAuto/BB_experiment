package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    private static final boolean SAVE_DRAWINGS_ONLY = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

        if (files == null || files.length == 0) {
            System.out.println("No PDF files found in 'pdfs/' directory.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "normalized_" + inputFile.getName());
            System.out.println("\n------------------------------------------------");
            System.out.println("Processing File: " + inputFile.getName());
            System.out.println("------------------------------------------------");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    
                    DrawingBoxEngine engine = new DrawingBoxEngine(page);
                    engine.processPage(page);
                    List<Rectangle2D> rawBoxes = engine.getDetectedBoxes();

                    List<VisualBox> visualBoxes = new ArrayList<>();
                    for (Rectangle2D rb : rawBoxes) {
                        // Preserves your exact conditional check for empty boxes
                        if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                            visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                        }
                    }

                    if (!visualBoxes.isEmpty()) {
                        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                        stripper.setSortByPosition(true);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            Rectangle2D.Float bnd = visualBoxes.get(b).bounds;
                            float awtY = pageHeight - bnd.y - bnd.height;
                            stripper.addRegion("box_" + b, new Rectangle2D.Float(
                                    bnd.x + 1.0f, awtY + 1.0f, bnd.width - 2.0f, bnd.height - 2.0f));
                        }

                        stripper.extractRegions(page);

                        int targetedEmptyCount = 0;
                        for (int b = 0; b < visualBoxes.size(); b++) {
                            VisualBox box = visualBoxes.get(b);
                            String contentText = stripper.getTextForRegion("box_" + b).trim();
                            
                            boolean isPureTextEmpty = contentText.isEmpty();
                            boolean isStructuralGapColumn = (box.bounds.width > 3.0f && box.bounds.width < 22.0f);

                            if (isPureTextEmpty || isStructuralGapColumn) {
                                box.isEmpty = true;
                                targetedEmptyCount++;
                            }
                        }

                        System.out.println(String.format("\n--- Chain Neighbors Trace for Page %d ---", i + 1));
                        NormalizationMetrics metrics = applyChainLinkedNormalization(visualBoxes);

                        // Output phase: Modified to draw pure, independent lines
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (VisualBox box : visualBoxes) {
                                // Left Line
                                if (box.drawLeft) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                }
                                // Top Line
                                if (box.drawTop) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                }
                                // Right Line
                                if (box.drawRight) {
                                    contentStream.moveTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                }
                                // Bottom Line
                                if (box.drawBottom) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                }
                                
                                // Strokes the lines exactly as they were created without sealing a closed box shape
                                contentStream.stroke();
                            }
                        }

                        System.out.println(String.format("\nPage %d Analysis Metrics Report:", i + 1));
                        System.out.println(String.format("  -> Exact Visual Boxes Tracked: %d", visualBoxes.size()));
                        System.out.println(String.format("  -> Normalized Target Boxes Identified: %d", targetedEmptyCount));
                        System.out.println(String.format("  -> Left-to-Right Flow Normalization (Identified Left Line): %d", metrics.leftToRightCount));
                        System.out.println(String.format("  -> Top-to-Bottom Flow Normalization (Identified Top Line): %d", metrics.topToBottomCount));
                        System.out.println(String.format("  -> Bidirectional Table Flow (Identified Matrix Blocks): %d", metrics.bidirectionalCount));
                        System.out.println(String.format("  -> Completely Isolated Layout Boxes (Identified Empty/Spacers): %d", metrics.isolatedCount));
                        System.out.println(String.format("  -> Total Grid Lines Identified (None Removed): %d", metrics.totalLinesIdentified));
                    } else {
                        System.out.println(String.format("Page %d: No drawn outline boxes were recorded.", i + 1));
                    }
                }

                if (SAVE_DRAWINGS_ONLY) {
                    document.save(outputFile);
                    System.out.println(" -> Successfully saved output to: " + outputFile.getAbsolutePath());
                }

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    private static class VisualBox {
        Rectangle2D.Float bounds;
        boolean isEmpty = false;
        boolean drawLeft = true;
        boolean drawTop = true;
        boolean drawRight = true;
        boolean drawBottom = true;

        VisualBox(float x, float y, float w, float h) {
            this.bounds = new Rectangle2D.Float(x, y, w, h);
        }
    }

    private static class NormalizationMetrics {
        int leftToRightCount = 0;
        int topToBottomCount = 0;
        int bidirectionalCount = 0;
        int isolatedCount = 0;
        int totalLinesIdentified = 0;
    }

    private static NormalizationMetrics applyChainLinkedNormalization(List<VisualBox> boxes) {
        NormalizationMetrics stats = new NormalizationMetrics();
        
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        for (int i
