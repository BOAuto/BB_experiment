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
                    
                    // 1. Grab both the structural bounding containers AND the pure independent raw lines
                    List<Rectangle2D> rawBoxes = engine.getDetectedBoxes();
                    List<LineSegment> rawLines = engine.getRawLines();

                    List<VisualBox> visualBoxes = new ArrayList<>();
                    for (Rectangle2D rb : rawBoxes) {
                        if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                            visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                        }
                    }

                    if (!visualBoxes.isEmpty() || !rawLines.isEmpty()) {
                        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                        stripper.setSortByPosition(true);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            Rectangle2D.Float bnd = visualBoxes.get(b).bounds;
                            float awtY = pageHeight - bnd.y - bnd.height;
                            stripper.addRegion("box_" + b, new Rectangle2D.Float(
                                    bnd.x + 1.0f, awtY + 1.0f, bnd.width - 2.0f, bnd.height - 2.0f));
                        }

                        if (!visualBoxes.isEmpty()) {
                            stripper.extractRegions(page);
                        }

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

                        // --- DRAWING PHASE ---
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            // A. Draw the structural layout container side components
                            for (VisualBox box : visualBoxes) {
                                if (box.drawLeft) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.stroke();
                                }
                                if (box.drawTop) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                    contentStream.stroke();
                                }
                                if (box.drawRight) {
                                    contentStream.moveTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                    contentStream.stroke();
                                }
                                if (box.drawBottom) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.stroke();
                                }
                            }

                            // B. Also explicitly draw the initial pure raw lines exactly as parsed
                            for (LineSegment line : rawLines) {
                                contentStream.moveTo(line.x1, line.y1);
                                contentStream.lineTo(line.x2, line.y2);
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
                        System.out.println(String.format("Page %d: No drawn outline elements were recorded.", i + 1));
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

    // Pure representation of an un-grouped single line vector coordinate map
    private static class LineSegment {
        float x1, y1, x2, y2;
        LineSegment(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
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

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            
            target.drawLeft = true;
            target.drawTop = true;
            target.drawRight = true;
            target.drawBottom = true;

            if (!target.isEmpty) continue; 

            System.out.print(String.format(" Box #%d [W=%.1f, H=%.1f] -> ", i, target.bounds.width, target.bounds.height));

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
                stats.leftToRightCount++;
                stats.totalLinesIdentified += 1;
                System.out.println("LOGGED: Continuous Row Flow (Identified Left Line)");
            } else if (immediateColRepeat && !immediateRowRepeat) {
                stats.topToBottomCount++;
                stats.totalLinesIdentified += 1;
                System.out.println("LOGGED: Continuous Column Stack (Identified Top Line)");
            } else if (immediateRowRepeat && immediateColRepeat) {
                if (target.bounds.width < 22.0f) {
                    stats.leftToRightCount++;
                    stats.totalLinesIdentified += 1;
                    System.out.println("LOGGED (Narrow Override): Grid Cross Gap (Identified Left Line)");
                } else {
                    stats.bidirectionalCount++;
                    System.out.println("LOGGED: Full Table Matrix Block (Kept Intact)");
                }
            } else {
                stats.isolatedCount++;
                stats.totalLinesIdentified += 4;
                System.out.println("LOGGED: Isolated Box (Identified 4 Lines)");
            }
        }
        return stats;
    }

    private static class DrawingBoxEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private final List<LineSegment> rawLines = new ArrayList<>();
        private Double minX, minY, maxX, maxY;
        private Point2D lastPoint = new Point2D.Float(0, 0);

        protected DrawingBoxEngine(PDPage page) { super(page); }
        public List<Rectangle2D> getDetectedBoxes() { return detectedBoxes; }
        public List<LineSegment> getRawLines() { return rawLines; }

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
            
            // Capture rectangle boundaries as 4 clean line traces instantly
            rawLines.add(new LineSegment((float)p0.getX(), (float)p0.getY(), (float)p1.getX(), (float)p1.getY()));
            rawLines.add(new LineSegment((float)p1.getX(), (float)p1.getY(), (float)p2.getX(), (float)p2.getY()));
            rawLines.add(new LineSegment((float)p2.getX(), (float)p2.getY(), (float)p3.getX(), (float)p3.getY()));
            rawLines.add(new LineSegment((float)p3.getX(), (float)p3.getY(), (float)p0.getX(), (float)p0.getY()));
            this.lastPoint = p3;
        }

        @Override 
        public void moveTo(float x, float y) throws IOException { 
            updateBounds(x, y); 
            this.lastPoint = new Point2D.Float(x, y);
        }
        
        @Override 
        public void lineTo(float x, float y) throws IOException { 
            updateBounds(x, y); 
            // Capture freeform lines as standalone vector segments instantly
            rawLines.add(new LineSegment((float)lastPoint.getX(), (float)lastPoint.getY(), x, y));
            this.lastPoint = new Point2D.Float(x, y);
        }
        
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            updateBounds(x1, y1);
            updateBounds(x3, y3);
            rawLines.add(new LineSegment((float)lastPoint.getX(), (float)lastPoint.getY(), x3, y3));
            this.lastPoint = new Point2D.Float(x3, y3);
        }
        
        @Override public void strokePath() throws IOException { flushPath(); }
        @Override public void fillPath(int windingRule) throws IOException { flushPath(); }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { flushPath(); }
        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) throws IOException {}
        @Override public void clip(int windingRule) throws IOException {}
        @Override public void closePath() throws IOException {}
        @Override public void endPath() throws IOException { minX = minY = maxX = maxY = null; }
        @Override public Point2D getCurrentPoint() throws IOException { return lastPoint; }
        @Override public void shadingFill(COSName shadingName) throws IOException {}
    }
}
