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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class VectorRecolor {

    private static final boolean DEBUG_MODE = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        if (files == null || files.length == 0) {
            System.out.println("[CRITICAL] No target PDF documents discovered in 'pdfs/' folder.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "drawings_and_normalized_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("TWIN-LINE INTERCEPTOR ACTIVE FOR FILE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> PROCESSING PAGE %d OF %d <<<", i + 1, totalPages));

                    // STAGE 1: Run analysis to locate locked references and target vertical lines
                    Script2Analyst analyst = new Script2Analyst(page);
                    List<Rectangle2D> linesToKeep = analyst.generateApprovedSnapshot();
                    
                    Map<Rectangle2D, Boolean> exclusionRegistry = analyst.getExclusionRegistry();
                    List<Rectangle2D> lockedLineCoordinates = analyst.getLockedLineCoordinates();

                    // STAGE 2: Precision Render Loop
                    System.out.println(String.format("[STAGE 2] Running rendering loop. Dropping only locked lines and overlapping twins..."));
                    if (!linesToKeep.isEmpty()) {
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            outputStream.setStrokingColor(Color.GREEN);
                            outputStream.setLineWidth(1.0f);

                            float coordinateTolerance = 1.0f; // Sharp precision matching
                            int paintCount = 0;

                            for (Rectangle2D cleanLine : linesToKeep) {
                                boolean shouldDrop = false;

                                // 1. Check the Registry Lock directly (Line 1)
                                Boolean isExplicitlyLocked = exclusionRegistry.get(cleanLine);
                                if (isExplicitlyLocked != null && isExplicitlyLocked) {
                                    shouldDrop = true;
                                }

                                // 2. Check for the Overlapping Twin (Line 2)
                                if (!shouldDrop) {
                                    for (Rectangle2D lockedCoord : lockedLineCoordinates) {
                                        // Only match if it's a vertical line segment sharing the exact X column and vertical space
                                        boolean xMatches = Math.abs(cleanLine.getX() - lockedCoord.getX()) < coordinateTolerance;
                                        boolean yMatches = Math.abs(cleanLine.getY() - lockedCoord.getY()) < coordinateTolerance;
                                        boolean heightMatches = Math.abs(cleanLine.getHeight() - lockedCoord.getHeight()) < coordinateTolerance;

                                        if (xMatches && yMatches && heightMatches) {
                                            shouldDrop = true;
                                            break;
                                        }
                                    }
                                }

                                // Target Drop Execution
                                if (shouldDrop) {
                                    if (DEBUG_MODE) {
                                        System.out.println(String.format("  [TARGETED DROP SUCCESS] Dropped line at -> X=%.3f, Y=%.3f (W=%.3f, H=%.3f)", 
                                                cleanLine.getX(), cleanLine.getY(), cleanLine.getWidth(), cleanLine.getHeight()));
                                    }
                                    continue; 
                                }

                                // Draw legitimate table headers, text boxes, top/bottom borders safely
                                paintCount++;
                                outputStream.addRect(
                                    (float) cleanLine.getX(), 
                                    (float) cleanLine.getY(), 
                                    (float) cleanLine.getWidth(), 
                                    (float) cleanLine.getHeight()
                                );
                                outputStream.stroke();
                            }
                            System.out.println(String.format("  -> Render loop finished. Approved paths painted: %d", paintCount));
                        }
                    }
                    System.out.println(String.format(">>> PAGE %d PROCESSING COMPLETE <<<\n", i + 1));
                }

                System.out.println("[FINALIZE] Saving PDF...");
                document.save(outputFile);
                System.out.println("[SUCCESS] Processing completed. Output available at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[FATAL SYSTEM ERROR] Pipeline aborted: " + e.getMessage());
            }
        }
    }

    /**
     * SCRIPT 2: REGISTRY LOCK ANALYST
     */
    private static class Script2Analyst {
        private final PDPage page;
        private final float pageHeight;
        private final Map<Rectangle2D, Boolean> exclusionRegistry = new IdentityHashMap<>();
        private final List<Rectangle2D> lockedLineCoordinates = new ArrayList<>();

        public Script2Analyst(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public Map<Rectangle2D, Boolean> getExclusionRegistry() {
            return exclusionRegistry;
        }

        public List<Rectangle2D> getLockedLineCoordinates() {
            return lockedLineCoordinates;
        }

        public List<Rectangle2D> generateApprovedSnapshot() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();
            
            List<Rectangle2D> approvedKeepList = new ArrayList<>();
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();

            for (Rectangle2D shape : rawScoutedVectors) {
                exclusionRegistry.put(shape, false); 
                if (shape.getWidth() > 2.0 && shape.getHeight() > 2.0) {
                    targetBoxesToAudit.add(new VisualBox(shape, shape)); 
                }
            }

            if (!targetBoxesToAudit.isEmpty()) {
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    Rectangle2D rawBounds = targetBoxesToAudit.get(b).transformedShape;
                    float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                    stripper.addRegion("reg_" + b, new Rectangle2D.Float(
                            (float)rawBounds.getX() + 0.5f, awtY + 0.5f, (float)rawBounds.getWidth() - 1.0f, (float)rawBounds.getHeight() - 1.0f));
                }

                stripper.extractRegions(page);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    VisualBox box = targetBoxesToAudit.get(b);
                    String extractedText = stripper.getTextForRegion("reg_" + b).trim();
                    
                    boolean textIsEmpty = extractedText.isEmpty();
                    boolean isStructuralGapColumn = (box.transformedShape.getWidth() > 3.0 && box.transformedShape.getWidth() < 22.0);

                    if (textIsEmpty || isStructuralGapColumn) {
                        box.isEmptyArea = true;
                    }
                }

                filterSnapshotObjects(targetBoxesToAudit);
            }

            for (Rectangle2D originalVector : rawScoutedVectors) {
                approvedKeepList.add(originalVector);
            }

            return approvedKeepList;
        }

        private void filterSnapshotObjects(List<VisualBox> boxes) {
            float alignmentTolerance = 1.0f;  
            float sizeMatchTolerance = 1.0f;  
            float gapSearchLimit = 0.5f; // Force adjacency matching

            for (int i = 0; i < boxes.size(); i++) {
                VisualBox target = boxes.get(i);
                if (!target.isEmptyArea) continue;

                boolean immediateRowRepeat = false;
                boolean immediateColRepeat = false;

                for (VisualBox neighbor : boxes) {
                    if (target == neighbor) continue;
                    boolean onSameRow = Math.abs(target.transformedShape.getY() - neighbor.transformedShape.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(target.transformedShape.getHeight() - neighbor.transformedShape.getHeight()) < sizeMatchTolerance;
                    
                    if (onSameRow && matchHeight) {
                        double distanceLeft = target.transformedShape.getX() - (neighbor.transformedShape.getX() + neighbor.transformedShape.getWidth());
                        double distanceRight = neighbor.transformedShape.getX() - (target.transformedShape.getX() + target.transformedShape.getWidth());
                        if ((distanceLeft >= -alignmentTolerance && distanceLeft <= gapSearchLimit) || 
                            (distanceRight >= -alignmentTolerance && distanceRight <= gapSearchLimit)) {
                            immediateRowRepeat = true;
                            break; 
                        }
                    }
                }

                for (VisualBox neighbor : boxes) {
                    if (target == neighbor) continue;
                    boolean onSameCol = Math.abs(target.transformedShape.getX() - neighbor.transformedShape.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(target.transformedShape.getWidth() - neighbor.transformedShape.getWidth()) < sizeMatchTolerance;

                    if (onSameCol && matchWidth) {
                        double distanceAbove = target.transformedShape.getY() - (neighbor.transformedShape.getY() + neighbor.transformedShape.getHeight());
                        double distanceBelow = neighbor.transformedShape.getY() - (target.transformedShape.getY() + target.transformedShape.getHeight());
                        if ((distanceAbove >= -alignmentTolerance && distanceAbove <= gapSearchLimit) || 
                            (distanceBelow >= -alignmentTolerance && distanceBelow <= gapSearchLimit)) {
                            immediateColRepeat = true;
                            break; 
                        }
                    }
                }

                // If it hits the row layout repeat structure, log it explicitly
                if ((immediateRowRepeat && !immediateColRepeat) || (immediateColRepeat && !immediateRowRepeat)) {
                    exclusionRegistry.put(target.rawSourceLineReference, true); 
                    lockedLineCoordinates.add(target.rawSourceLineReference);
                }
            }
        }
    }

    private static class VisualBox {
        final Rectangle2D transformedShape;
        final Rectangle2D rawSourceLineReference;
        boolean isEmptyArea = false;
        
        VisualBox(Rectangle2D transformed, Rectangle2D rawSource) { 
            this.transformedShape = transformed; 
            this.rawSourceLineReference = rawSource;
        }
    }

    /**
     * SCRIPT 1: CORE VECTOR PATH SCOUT
     */
    private static class DrawingBoxEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private Double minX, minY, maxX, maxY;

        protected DrawingBoxEngine(PDPage page) { super(page); }
        public List<Rectangle2D> getDetectedBoxes() { return detectedBoxes; }

        private void updateBounds(double x, double y) {
            if (minX == null) { minX = maxX = x; minY = maxY = y; } 
            else { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
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
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException { updateBounds(x1, y1); updateBounds(x3, y3); }
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
