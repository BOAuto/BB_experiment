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

    private static final boolean DEBUG_MODE = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        if (files == null || files.length == 0) {
            System.out.println("[ERROR] No target PDF documents discovered in 'pdfs/' folder.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "drawings_and_normalized_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("PIPELINE START: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n--- Execution Loop: Page %d of %d ---", i + 1, totalPages));

                    // ==========================================================================
                    // STEP 1: ISOLATED METRIC DETECTION PASS
                    // Script 1 and Script 2 run entirely in-memory to classify our vectors.
                    // Absolutely zero graphic or stream operations happen here.
                    // ==========================================================================
                    if (DEBUG_MODE) System.out.println("[PIPELINE] Running Isolated Spatial Analysis Engine...");
                    Script2Analyst analyst = new Script2Analyst(page);
                    analyst.executeAnalysisPipeline();
                    
                    List<Rectangle2D> linesToKill = analyst.getBlacklistLinesToKill();
                    List<Rectangle2D> linesToKeep = analyst.getApprovedKeepList();

                    // ==========================================================================
                    // STEP 2: UNIFIED VISUAL EXECUTION PASS
                    // We open a single content stream to perform the erasure and the draw pass.
                    // ==========================================================================
                    if (!linesToKill.isEmpty() || !linesToKeep.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[PIPELINE] Opening graphics stream layer for unified updates...");
                        
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            // ------------------------------------------------------------------
                            // SUB-STEP A: THE WHITE-OUT ERASER
                            // Active white masking shields the original background artifacts from view
                            // ------------------------------------------------------------------
                            if (!linesToKill.isEmpty()) {
                                if (DEBUG_MODE) System.out.println(String.format("  -> Masking out %d artifact zones with opaque white fill...", linesToKill.size()));
                                outputStream.setNonStrokingColor(Color.WHITE);
                                
                                for (Rectangle2D badLine : linesToKill) {
                                    // Apply a microscopic padding boundary (0.5 point) to prevent anti-aliasing color bleed
                                    float padding = 0.5f;
                                    outputStream.addRect(
                                        (float) badLine.getX() - padding, 
                                        (float) badLine.getY() - padding, 
                                        (float) badLine.getWidth() + (padding * 2), 
                                        (float) badLine.getHeight() + (padding * 2)
                                    );
                                    outputStream.fill(); // Solid fill commits the white mask over the old coordinates
                                }
                            }

                            // ------------------------------------------------------------------
                            // SUB-STEP B: TARGETED RECOLOR PLOT
                            // Draws our 1,563 approved vector locations cleanly in green right on top
                            // ------------------------------------------------------------------
                            if (!linesToKeep.isEmpty()) {
                                if (DEBUG_MODE) System.out.println(String.format("  -> Drawing %d approved snapshot paths in green...", linesToKeep.size()));
                                outputStream.setStrokingColor(Color.GREEN);
                                outputStream.setLineWidth(1.0f);

                                for (Rectangle2D cleanLine : linesToKeep) {
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
                        if (DEBUG_MODE) System.out.println("  -> Unified White-Out and Drawing Pass committed successfully.");
                    }
                }

                System.out.println("\n[FINALIZE] Committing memory state alterations to storage...");
                document.save(outputFile);
                System.out.println("SUCCESS: Cleaned snapshot document compiled at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[PIPELINE FATAL] Document processing crashed: " + e.getMessage());
            }
        }
    }

    /**
     * SCRIPT 2: THE ISOLATED MATRIX ANALYST
     */
    private static class Script2Analyst {
        private final PDPage page;
        private final float pageHeight;
        private final List<Rectangle2D> approvedKeepList = new ArrayList<>();
        private final List<Rectangle2D> blacklistLinesToKill = new ArrayList<>();

        public Script2Analyst(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getApprovedKeepList() { return approvedKeepList; }
        public List<Rectangle2D> getBlacklistLinesToKill() { return blacklistLinesToKill; }

        public void executeAnalysisPipeline() throws IOException {
            // Run Script 1 internally strictly to harvest coordinate references in memory
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();
            
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();

            // Map and categorize by size dimensions matching your custom structural table fields
            for (Rectangle2D shape : rawScoutedVectors) {
                if (shape.getWidth() > 2.0 && shape.getHeight() > 4.0) {
                    targetBoxesToAudit.add(new VisualBox(shape));
                } else {
                    approvedKeepList.add(shape);
                }
            }

            if (!targetBoxesToAudit.isEmpty()) {
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    Rectangle2D rawBounds = targetBoxesToAudit.get(b).originalShape;
                    float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                    
                    stripper.addRegion("reg_" + b, new Rectangle2D.Float(
                            (float)rawBounds.getX() + 1.0f, awtY + 1.0f, (float)rawBounds.getWidth() - 2.0f, (float)rawBounds.getHeight() - 2.0f));
                }

                stripper.extractRegions(page);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    VisualBox box = targetBoxesToAudit.get(b);
                    String extractedText = stripper.getTextForRegion("reg_" + b).trim();
                    
                    boolean textIsEmpty = extractedText.isEmpty();
                    boolean isStructuralGapColumn = (box.originalShape.getWidth() > 3.0 && box.originalShape.getWidth() < 22.0);

                    if (textIsEmpty || isStructuralGapColumn) {
                        box.isEmptyArea = true;
                    }
                }

                filterSnapshotObjects(targetBoxesToAudit);
            }

            if (DEBUG_MODE) {
                System.out.println(String.format("  -> [Metrics Evaluation] Scouted: %d | Approved (Keep): %d | Isolated (Kill): %d", 
                        rawScoutedVectors.size(), approvedKeepList.size(), blacklistLinesToKill.size()));
            }
        }

        private void filterSnapshotObjects(List<VisualBox> boxes) {
            float alignmentTolerance = 5.0f;  
            float sizeMatchTolerance = 2.0f;  
            float gapSearchLimit = 15.0f;     

            for (int i = 0; i < boxes.size(); i++) {
                VisualBox target = boxes.get(i);
                
                if (!target.isEmptyArea) {
                    approvedKeepList.add(target.originalShape);
                    continue;
                }

                boolean immediateRowRepeat = false;
                boolean immediateColRepeat = false;

                for (VisualBox neighbor : boxes) {
                    if (target == neighbor) continue;
                    boolean onSameRow = Math.abs(target.originalShape.getY() - neighbor.originalShape.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(target.originalShape.getHeight() - neighbor.originalShape.getHeight()) < sizeMatchTolerance;
                    
                    if (onSameRow && matchHeight) {
                        double distanceLeft = target.originalShape.getX() - (neighbor.originalShape.getX() + neighbor.originalShape.getWidth());
                        double distanceRight = neighbor.originalShape.getX() - (target.originalShape.getX() + target.originalShape.getWidth());
                        if ((distanceLeft >= -alignmentTolerance && distanceLeft <= gapSearchLimit) || 
                            (distanceRight >= -alignmentTolerance && distanceRight <= gapSearchLimit)) {
                            immediateRowRepeat = true;
                            break; 
                        }
                    }
                }

                for (VisualBox neighbor : boxes) {
                    if (target == neighbor) continue;
                    boolean onSameCol = Math.abs(target.originalShape.getX() - neighbor.originalShape.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(target.originalShape.getWidth() - neighbor.originalShape.getWidth()) < sizeMatchTolerance;

                    if (onSameCol && matchWidth) {
                        double distanceAbove = target.originalShape.getY() - (neighbor.originalShape.getY() + neighbor.originalShape.getHeight());
                        double distanceBelow = neighbor.originalShape.getY() - (target.originalShape.getY() + target.originalShape.getHeight());
                        if ((distanceAbove >= -alignmentTolerance && distanceAbove <= gapSearchLimit) || 
                            (distanceBelow >= -alignmentTolerance && distanceBelow <= gapSearchLimit)) {
                            immediateColRepeat = true;
                            break; 
                        }
                    }
                }

                if ((immediateRowRepeat && !immediateColRepeat) || (immediateColRepeat && !immediateRowRepeat)) {
                    blacklistLinesToKill.add(target.originalShape);
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [CLASSIFIED ARTIFACT] Queued for erasure at X=%.1f, Y=%.1f", 
                                target.originalShape.getX(), target.originalShape.getY()));
                    }
                } else {
                    approvedKeepList.add(target.originalShape);
                }
            }
        }
    }

    private static class VisualBox {
        Rectangle2D originalShape;
        boolean isEmptyArea = false;
        VisualBox(Rectangle2D shape) { this.originalShape = shape; }
    }

    /**
     * SCRIPT 1: NATIVE GRAPHICS STREAM ENGINE INTERCEPTOR
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
            if (minX != null) { detectedBoxes.add(new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)); minX = minY = maxX = maxY = null; }
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException { updateBounds(p0.getX(), p0.getY()); updateBounds(p2.getX(), p2.getY()); }
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
