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
                    
                    // Step 1: Run the deep, flattened inheritance parser engine
                    DrawingBoxEngine engine = new DrawingBoxEngine(page);
                    engine.processPage(page);
                    List<Rectangle2D> deeplyInheritedBoxes = engine.getDetectedBoxes();

                    List<VisualBox> visualBoxes = new ArrayList<>();
                    for (Rectangle2D rb : deeplyInheritedBoxes) {
                        // Keep strict structural dimensional filters
                        if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                            visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                        }
                    }

                    if (!visualBoxes.isEmpty()) {
                        // Step 2: Set up our strict Area Text Stripper
                        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                        stripper.setSortByPosition(true);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            Rectangle2D.Float bnd = visualBoxes.get(b).bounds;
                            float awtY = pageHeight - bnd.y - bnd.height;
                            // 1.0pt padding window prevents edge characters from bleeding over
                            stripper.addRegion("box_" + b, new Rectangle2D.Float(
                                    bnd.x + 1.0f, awtY + 1.0f, bnd.width - 2.0f, bnd.height - 2.0f));
                        }

                        stripper.extractRegions(page);

                        int verifiedEmptyCount = 0;
                        for (int b = 0; b < visualBoxes.size(); b++) {
                            VisualBox box = visualBoxes.get(b);
                            String contentText = stripper.getTextForRegion("box_" + b).trim();
                            
                            // Check if the area contains any real text content
                            boolean isTextEmpty = contentText.isEmpty();
                            
                            // Step 3: Run the graphic content fortress validation check
                            // If a box intersects with background vector marks, images, or real text, it's NOT empty.
                            boolean hasInterferenceNoise = engine.doesRegionContainContent(box.bounds);

                            if (isTextEmpty && !hasInterferenceNoise) {
                                box.isEmpty = true;
                                verifiedEmptyCount++;
                            }
                        }

                        System.out.println(String.format("\n--- Deep Continuous Chain Neighbors Trace for Page %d ---", i + 1));
                        // Step 4: Run immediate chain-linked repetition normalization
                        NormalizationMetrics metrics = applyChainLinkedNormalization(visualBoxes);

                        // Step 5: Render structural normalized results to output stream
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (VisualBox box : visualBoxes) {
                                if (box.drawLeft) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                }
                                if (box.drawTop) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                }
                                if (box.drawRight) {
                                    contentStream.moveTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                }
                                if (box.drawBottom) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                }
                                contentStream.stroke();
                            }
                        }

                        System.out.println(String.format("\nPage %d Analysis Metrics Report:", i + 1));
                        System.out.println(String.format("  -> Exact Visual Boxes Tracked: %d", visualBoxes.size()));
                        System.out.println(String.format("  -> Confirmed True Empty Boxes: %d", verifiedEmptyCount));
                        System.out.println(String.format("  -> Left-to-Right Flow Normalization (Removed Left Line): %d", metrics.leftToRightCount));
                        System.out.println(String.format("  -> Top-to-Bottom Flow Normalization (Removed Top Line): %d", metrics.topToBottomCount));
                        System.out.println(String.format("  -> Bidirectional Table Flow (No lines altered): %d", metrics.bidirectionalCount));
                        System.out.println(String.format("  -> Completely Isolated Layout Boxes (Wiped all 4 Lines): %d", metrics.isolatedCount));
                        System.out.println(String.format("  -> Total Grid Lines Safely Removed: %d", metrics.totalLinesRemoved));
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
        int totalLinesRemoved = 0;
    }

    private static NormalizationMetrics applyChainLinkedNormalization(List<VisualBox> boxes) {
        NormalizationMetrics stats = new NormalizationMetrics();
        
        float alignmentTolerance = 4.0f;  
        float sizeMatchTolerance = 1.5f;  
        float gapSearchLimit = 12.0f;     

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            if (!target.isEmpty) continue; 

            boolean immediateRowRepeat = false;
            boolean immediateColRepeat = false;

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

            for (VisualBox neighbor : boxes) {
                if (target == neighbor) continue;

                boolean onSameCol = Math.abs(target.bounds.x - neighbor.bounds.x) < alignmentTolerance;
                boolean matchWidth = Math.abs(target.bounds.width - neighbor.bounds.width) < sizeMatchTolerance;

                if (onSameCol && matchWidth) {
                    float distanceAbove = target.bounds.y - (neighbor.bounds.y + neighbor.bounds.height);
                    float distanceBelow = neighbor.bounds.y - (target.bounds.y + target.bounds.height);

                    if ((distanceAbove >= -alignmentTolerance && distanceAbove <= gapSearchLimit) || 
                        (distanceBelow >= -alignmentTolerance && distanceBelow <= gapSearchLimit)) {
                        immediateColRepeat = true;
                        break; 
                    }
                }
            }

            if (immediateRowRepeat && !immediateColRepeat) {
                target.drawLeft = false;
                stats.leftToRightCount++;
                stats.totalLinesRemoved += 1;
                System.out.println(String.format(" Box #%d [W=%.1f, H=%.1f] -> RESOLVED: Continuous Row Flow (Removed Left Line)", i, target.bounds.width, target.bounds.height));
            } else if (immediateColRepeat && !immediateRowRepeat) {
                target.drawTop = false;
                stats.topToBottomCount++;
                stats.totalLinesRemoved += 1;
                System.out.println(String.format(" Box #%d [W=%.1f, H=%.1f] -> RESOLVED: Continuous Column Stack (Removed Top Line)", i, target.bounds.width, target.bounds.height));
            } else if (immediateRowRepeat && immediateColRepeat) {
                if (target.bounds.width < 22.0f) {
                    target.drawLeft = false;
                    stats.leftToRightCount++;
                    stats.totalLinesRemoved += 1;
                    System.out.println(String.format(" Box #%d [W=%.1f, H=%.1f] -> RESOLVED (Override): Grid Cross Gap (Removed Left Line)", i, target.bounds.width, target.bounds.height));
                } else {
                    stats.bidirectionalCount++;
                }
            } else {
                target.drawLeft = false;
                target.drawTop = false;
                target.drawRight = false;
                target.drawBottom = false;
                stats.isolatedCount++;
                stats.totalLinesRemoved += 4;
                System.out.println(String.format(" Box #%d [W=%.1f, H=%.1f] -> RESOLVED: Isolated Box (Wiped Completely)", i, target.bounds.width, target.bounds.height));
            }
        }
        return stats;
    }

    // --- FIX: High-fidelity graphics engine exploring paths to complete depth ---
    private static class DrawingBoxEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private final List<Rectangle2D> contentInterferenceRegions = new ArrayList<>();
        private Double minX, minY, maxX, maxY;

        protected DrawingBoxEngine(PDPage page) { 
            super(page); 
        }

        public List<Rectangle2D> getDetectedBoxes() { 
            return detectedBoxes; 
        }

        // Validates if a target box overlaps any drawn vectors, glyph frames, or image boundaries
        public boolean doesRegionContainContent(Rectangle2D.Float targetBox) {
            for (Rectangle2D contentRect : contentInterferenceRegions) {
                // Check if the content rectangle sits firmly inside the candidate layout box
                if (targetBox.intersects(contentRect)) {
                    // Avoid catching the box's own borders
                    double padding = 2.0;
                    if (contentRect.getX() > targetBox.getX() + padding && 
                        contentRect.getX() + contentRect.getWidth() < targetBox.getX() + targetBox.getWidth() - padding) {
                        return true; // True hidden graphic elements caught inside!
                    }
                }
            }
            return false;
        }

        private void recordUnconditionalVectorSegment(double x, double y) {
            if (minX != null) {
                double w = Math.abs(x - minX);
                double h = Math.abs(y - minY);
                // Deep extraction: Track every atomic line rendering event instantly as an independent child node
                if (w > 2.0 && h > 4.0) {
                    detectedBoxes.add(new Rectangle2D.Double(Math.min(minX, x), Math.min(minY, y), w, h));
                } else {
                    // Microscopic nodes, background marks, and line endpoints are registered as interference noise
                    contentInterferenceRegions.add(new Rectangle2D.Double(Math.min(minX, x), Math.min(minY, y), Math.max(w, 1.0), Math.max(h, 1.0)));
                }
            }
            minX = x;
            minY = y;
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
            double minXCoord = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            double maxXCoord = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            double minYCoord = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            double maxYCoord = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            
            detectedBoxes.add(new Rectangle2D.Double(minXCoord, minYCoord, maxXCoord - minXCoord, maxYCoord - minYCoord));
        }

        @Override public void moveTo(float x, float y) throws IOException { 
            minX = (double)x; 
            minY = (double)y; 
        }

        @Override public void lineTo(float x, float y) throws IOException { 
            recordUnconditionalVectorSegment(x, y); 
        }

        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            recordUnconditionalVectorSegment(x3, y3);
        }

        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) throws IOException {
            // Register raster image placements directly as content interferences
            contentInterferenceRegions.add(new Rectangle2D.Double(0, 0, 1000, 1000));
        }

        @Override public void strokePath() throws IOException { minX = minY = null; }
        @Override public void fillPath(int windingRule) throws IOException { minX = minY = null; }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { minX = minY = null; }
        @Override public void clip(int windingRule) throws IOException {}
        @Override public void closePath() throws IOException {}
        @Override public void endPath() throws IOException { minX = minY = null; }
        @Override public Point2D getCurrentPoint() throws IOException { return new Point2D.Float(0, 0); }
        @Override public void shadingFill(COSName shadingName) throws IOException {}
    }
}
