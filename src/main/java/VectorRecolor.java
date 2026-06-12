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
            System.out.println("LOG ENGINE INITIALIZED FOR FILE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> PROCESSING PAGE %d OF %d <<<", i + 1, totalPages));

                    System.out.println("[STAGE 1] Invoking Isolated Analysis Engine with active reference maps...");
                    Script2Analyst analyst = new Script2Analyst(page);
                    List<Rectangle2D> linesToKeep = analyst.generateApprovedSnapshot();

                    System.out.println(String.format("[STAGE 2] Beginning production rendering phase for %d approved shapes...", linesToKeep.size()));
                    if (!linesToKeep.isEmpty()) {
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            outputStream.setStrokingColor(Color.GREEN);
                            outputStream.setLineWidth(1.0f);

                            int paintCount = 0;
                            for (Rectangle2D cleanLine : linesToKeep) {
                                paintCount++;
                                if (DEBUG_MODE) {
                                    System.out.println(String.format("  [RENDER-EXEC] Drawing Green Shape #%d -> X=%.3f, Y=%.3f, W=%.3f, H=%.3f", 
                                            paintCount, cleanLine.getX(), cleanLine.getY(), cleanLine.getWidth(), cleanLine.getHeight()));
                                }
                                
                                outputStream.addRect(
                                    (float) cleanLine.getX(), 
                                    (float) cleanLine.getY(), 
                                    (float) cleanLine.getWidth(), 
                                    (float) cleanLine.getHeight()
                                );
                                outputStream.stroke();
                            }
                        }
                    }
                    System.out.println(String.format(">>> PAGE %d PROCESSING COMPLETE <<<\n", i + 1));
                }

                System.out.println("[FINALIZE] Writing output to disk...");
                document.save(outputFile);
                System.out.println("[SUCCESS] System telemetry saved down at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[FATAL SYSTEM CRASH] Pipeline stopped: " + e.getMessage());
            }
        }
    }

    /**
     * SCRIPT 2: UNCONSTRAINED REFERENCE ANALYST (NO GATES)
     */
    private static class Script2Analyst {
        private final PDPage page;
        private final float pageHeight;

        public Script2Analyst(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> generateApprovedSnapshot() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();
            
            System.out.println(String.format("  [SCOUT-OUTCOME] Extracted %d total raw vector paths from stream content.", rawScoutedVectors.size()));
            
            List<Rectangle2D> approvedKeepList = new ArrayList<>();
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();
            
            // The exclusion registry tracks EVERY single vector reference found on the page
            Map<Rectangle2D, Boolean> exclusionRegistry = new IdentityHashMap<>();

            // ==========================================================================
            // STEP 1: UNIFIED INITIAL REGISTRATION
            // Every vector path is marked safe ('false') by default. No sizing gates.
            // ==========================================================================
            for (Rectangle2D shape : rawScoutedVectors) {
                exclusionRegistry.put(shape, false); 

                // Size metrics are used strictly to protect the PDFTextStripper boundary space,
                // NEVER to keep a path segment from being swept up and dropped later.
                if (shape.getWidth() > 2.0 && shape.getHeight() > 2.0) {
                    targetBoxesToAudit.add(new VisualBox(shape, shape)); 
                }
            }

            // ==========================================================================
            // STEP 2: ARTIFACT MATRIX PATTERN EVALUATION
            // ==========================================================================
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

                // If a container matches an artifact grid pattern, its registry reference switches to TRUE (EXCLUDE)
                filterSnapshotObjects(targetBoxesToAudit, exclusionRegistry);
            }

            // ==========================================================================
            // STEP 3: HIGH-PRECISION LOOKBACK INTERCEPTOR
            // Catch lines of ANY scale or rotation that snap horizontally to a dropped box area.
            // ==========================================================================
            float spatialSnappingTolerance = 3.0f; 

            for (Rectangle2D originalVector : rawScoutedVectors) {
                Boolean isMarkedForExclusion = exclusionRegistry.get(originalVector);
                
                // Primary check: Drop immediately if the map registered an explicit instance removal
                if (isMarkedForExclusion != null && isMarkedForExclusion) {
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [PRIMARY DROP SUCCESS] Excluded flagged container instance at X=%.3f, Y=%.3f", 
                                originalVector.getX(), originalVector.getY()));
                    }
                    continue; 
                }

                // Secondary Deep Spatial Check: Check if this path matches coordinates of a dropped container
                boolean matchesBannedCoordinates = false;
                for (VisualBox auditedBox : targetBoxesToAudit) {
                    if (auditedBox.isArtifactLine) { 
                        
                        // Check if the lines align horizontally on the layout grid
                        boolean sameXColumn = Math.abs(originalVector.getX() - auditedBox.transformedShape.getX()) < spatialSnappingTolerance;
                        
                        // Check if the line falls inside or right on the vertical span perimeter of that cell
                        boolean insideVerticalSpan = originalVector.getY() >= auditedBox.transformedShape.getY() - spatialSnappingTolerance &&
                                                     originalVector.getY() <= (auditedBox.transformedShape.getY() + auditedBox.transformedShape.getHeight() + spatialSnappingTolerance);

                        if (sameXColumn && insideVerticalSpan) {
                            matchesBannedCoordinates = true;
                            break;
                        }
                    }
                }

                if (matchesBannedCoordinates) {
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [RENDER BLOCK] Blocked stray border/fragment line at X=%.3f, Y=%.3f (H=%.3f)", 
                                originalVector.getX(), originalVector.getY(), originalVector.getHeight()));
                    }
                    continue; // Skip appending to green line pass completely!
                }

                approvedKeepList.add(originalVector);
            }

            System.out.println(String.format("  [METRICS SUMMARY] Discovered: %d | Sent to Render Loop: %d | Denied/Dropped: %d", 
                    rawScoutedVectors.size(), approvedKeepList.size(), (rawScoutedVectors.size() - approvedKeepList.size())));

            return approvedKeepList;
        }

        private void filterSnapshotObjects(List<VisualBox> boxes, Map<Rectangle2D, Boolean> exclusionRegistry) {
            float alignmentTolerance = 5.0f;  
            float sizeMatchTolerance = 2.0f;  
            float gapSearchLimit = 15.0f;     

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

                if ((immediateRowRepeat && !immediateColRepeat) || (immediateColRepeat && !immediateRowRepeat)) {
                    target.isArtifactLine = true; 
                    exclusionRegistry.put(target.rawSourceLineReference, true); 
                }
            }
        }
    }

    private static class VisualBox {
        final Rectangle2D transformedShape;
        final Rectangle2D rawSourceLineReference;
        boolean isEmptyArea = false;
        boolean isArtifactLine = false; 
        
        VisualBox(Rectangle2D transformed, Rectangle2D rawSource) { 
            this.transformedShape = transformed; 
            this.rawSourceLineReference = rawSource;
        }
    }

    /**
     * SCRIPT 1: RAW VECTORS SUB-LEVEL INTERCEPTOR
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
                Rectangle2D pathSegment = new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
                detectedBoxes.add(pathSegment);
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
