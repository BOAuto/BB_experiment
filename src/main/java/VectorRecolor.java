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
                    
                    // Pass 1: Extract layout cell coordinates
                    GeometryScanner scanner = new GeometryScanner(page);
                    scanner.processPage(page);
                    List<Rectangle2D> rawBoxes = scanner.getDetectedBoxes();

                    List<VisualBox> visualBoxes = new ArrayList<>();
                    for (Rectangle2D rb : rawBoxes) {
                        if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                            visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                        }
                    }

                    List<Rectangle2D.Float> linesToKill = new ArrayList<>();

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

                        System.out.println(String.format("\n--- Line Interception Trace for Page %d ---", i + 1));
                        NormalizationMetrics metrics = identifyTargetLines(visualBoxes, linesToKill);

                        // Pass 2: Re-process the graphics stream using our structural exclusion rules
                        // This updates the internal canvas by processing operators and stripping matched lines
                        ContentExclusionEngine filterEngine = new ContentExclusionEngine(page, linesToKill);
                        filterEngine.processPage(page);

                        // Optional validation overlay: renders remaining active cell lines as green matrices
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (VisualBox box : visualBoxes) {
                                if (box.drawLeft) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                }
                                contentStream.stroke();
                            }
                        }

                        System.out.println(String.format("\nPage %d Analysis Metrics Report:", i + 1));
                        System.out.println(String.format("  -> Exact Visual Boxes Tracked: %d", visualBoxes.size()));
                        System.out.println(String.format("  -> Normalized Target Boxes Identified: %d", targetedEmptyCount));
                        System.out.println(String.format("  -> Left-to-Right Flow Normalization (Suppressed Left Lines): %d", metrics.leftToRightCount));
                        System.out.println(String.format("  -> Total Grid Lines Safely Removed: %d", metrics.totalLinesRemoved));
                    } else {
                        System.out.println(String.format("Page %d: No drawn outline boxes were recorded.", i + 1));
                    }
                }

                document.save(outputFile);
                System.out.println(" -> Successfully saved output to: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    private static class VisualBox {
        Rectangle2D.Float bounds;
        boolean isEmpty = false;
        boolean drawLeft = true;

        VisualBox(float x, float y, float w, float h) {
            this.bounds = new Rectangle2D.Float(x, y, w, h);
        }
    }

    private static class NormalizationMetrics {
        int leftToRightCount = 0;
        int totalLinesRemoved = 0;
    }

    private static NormalizationMetrics identifyTargetLines(List<VisualBox> boxes, List<Rectangle2D.Float> killList) {
        NormalizationMetrics stats = new NormalizationMetrics();
        
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            if (!target.isEmpty) continue; 

            boolean immediateRowRepeat = false;

            for (VisualBox neighbor : boxes) {
                if (target == neighbor) continue;

                boolean onSameRow = Math.abs(target.bounds.y - neighbor.bounds.y) < alignmentTolerance;
                boolean matchHeight = Math.abs(target.bounds.height - neighbor.bounds.height) < sizeMatchTolerance;
                
                if (onSameRow && matchHeight) {
                    float distanceLeft = target.bounds.x - (neighbor.bounds.x + neighbor.bounds.width);
                    float distanceRight = neighbor.bounds.x - (target.bounds.x + target.bounds.width);
                    
                    if ((distanceLeft >= -alignmentTolerance && distanceLeft <= gapSearchLimit) || 
                        (distanceRight >= -alignmentTolerance && distanceRight <= gapSearchLimit)) {
                        immediateRowRepeat = true;
                        break; 
                    }
                }
            }

            if (immediateRowRepeat) {
                target.drawLeft = false; 
                // Build a strict masking window for intercepting the physical stream vectors
                killList.add(new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, 2.0f, target.bounds.height + 2.0f));
                
                stats.leftToRightCount++;
                stats.totalLinesRemoved++;
                System.out.println(String.format(" Target Match -> Suppressing Left Line boundary for Box #%d [X=%.1f, Y=%.1f]", i, target.bounds.x, target.bounds.y));
            }
        }
        return stats;
    }

    // --- FIX: Active Content Exclusion Engine suppressing matching primitives ---
    private static class ContentExclusionEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D.Float> exclusions;
        private Double currentX, currentY;
        private boolean skipActivePathElement = false;

        protected ContentExclusionEngine(PDPage page, List<Rectangle2D.Float> exclusions) {
            super(page);
            this.exclusions = exclusions;
        }

        private void testVectorCoordinates(double x, double y) {
            if (currentX != null && currentY != null) {
                // Construct a virtual vector line bounding descriptor
                double minX = Math.min(currentX, x);
                double minY = Math.min(currentY, y);
                double w = Math.max(Math.abs(x - currentX), 1.0);
                double h = Math.max(Math.abs(y - currentY), 1.0);
                Rectangle2D.Float structuralSegment = new Rectangle2D.Float((float)minX, (float)minY, (float)w, (float)h);

                for (Rectangle2D.Float mask : exclusions) {
                    if (mask.intersects(structuralSegment)) {
                        // Match confirmed! Trigger suppression to dump execution of this primitive stroke
                        skipActivePathElement = true;
                        break;
                    }
                }
            }
            currentX = x;
            currentY = y;
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
            currentX = p0.getX();
            currentY = p0.getY();
            testVectorCoordinates(p2.getX(), p2.getY());
        }

        @Override public void moveTo(float x, float y) throws IOException { 
            currentX = (double)x; 
            currentY = (double)y; 
        }

        @Override public void lineTo(float x, float y) throws IOException { 
            testVectorCoordinates(x, y); 
        }

        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            testVectorCoordinates(x3, y3);
        }

        @Override public void strokePath() throws IOException {
            if (skipActivePathElement) {
                // Drop this operation entirely — lines are actively erased from stream context
                skipActivePathElement = false;
            }
            currentX = currentY = null;
        }

        @Override public void fillPath(int windingRule) throws IOException { skipActivePathElement = false; currentX = currentY = null; }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { skipActivePathElement = false; currentX = currentY = null; }
        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) throws IOException {}
        @Override public void clip(int windingRule) throws IOException {}
        @Override public void closePath() throws IOException {}
        @Override public void endPath() throws IOException { skipActivePathElement = false; currentX = currentY = null; }
        @Override public Point2D getCurrentPoint() throws IOException { return new Point2D.Float(0, 0); }
        @Override public void shadingFill(COSName shadingName) throws IOException {}
    }

    private static class GeometryScanner extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private Double minX, minY, maxX, maxY;

        protected GeometryScanner(PDPage page) { super(page); }
        public List<Rectangle2D> getDetectedBoxes() { return detectedBoxes; }

        private void updateBounds(double x, double y) {
            if (minX == null) {
                minX = maxX = x;
                minY = maxY = y;
            } else {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        private void flushPath() {
            if (minX != null) {
                detectedBoxes.add(new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY));
                minX = minY = maxX = maxY = null;
            }
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
            updateBounds(p0.getX(), p0.getY());
            updateBounds(p2.getX(), p2.getY());
        }

        @Override public void moveTo(float x, float y) throws IOException { updateBounds(x, y); }
        @Override public void lineTo(float x, float y) throws IOException { updateBounds(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            updateBounds(x1, y1); updateBounds(x3, y3);
        }
        @Override public void strokePath() throws IOException { flushPath(); }
        @Override public void fillPath(int windingRule) throws IOException { flushPath(); }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { flushPath(); }
        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) throws IOException {}
        @Override public void clip(int windingRule) throws IOException {}
        @Override public void closePath() throws IOException {}
        @Override public void endPath() throws IOException { minX = minY = maxX = maxY = null; }
        @Override public Point2D getCurrentPoint() throws IOException { return new Point2D.Float(0, 0); }
        @Override public void shadingFill(COSName shadingName) throws IOException {}
    }
}
