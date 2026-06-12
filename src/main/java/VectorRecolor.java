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

            // Container to pass our clean, filtered dimensions across isolated document instances
            List<List<Rectangle2D>> documentLevelKeepList = new ArrayList<>();

            // ==========================================================================
            // PHASE 1: COMPLETELY ISOLATED TRACKING (DOCUMENT INSTANCE 1)
            // This instance is used purely for calculation and is immediately discarded.
            // ==========================================================================
            try (PDDocument scoutDocument = Loader.loadPDF(inputFile)) {
                int totalPages = scoutDocument.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage scoutPage = scoutDocument.getPage(i);
                    if (DEBUG_MODE) System.out.println(String.format("[SCOUT] Analyzing Page %d in complete isolation...", i + 1));
                    
                    Script2Analyst analyst = new Script2Analyst(scoutPage);
                    List<Rectangle2D> approvedKeepList = analyst.generateApprovedSnapshot();
                    
                    // Save the 1,563 clean coordinates safely outside this document's scope
                    documentLevelKeepList.add(approvedKeepList);
                }
            } catch (IOException e) {
                System.err.println("[PIPELINE FATAL] Isolated Scout failed: " + e.getMessage());
                continue;
            }

            // ==========================================================================
            // PHASE 2: PRODUCTION TARGET WRITER (DOCUMENT INSTANCE 2)
            // A fresh load where no graphics engines or token iterations have touched the state.
            // ==========================================================================
            try (PDDocument targetDocument = Loader.loadPDF(inputFile)) {
                int totalPages = targetDocument.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage targetPage = targetDocument.getPage(i);
                    List<Rectangle2D> approvedKeepList = documentLevelKeepList.get(i);

                    if (approvedKeepList != null && !approvedKeepList.isEmpty()) {
                        if (DEBUG_MODE) {
                            System.out.println(String.format("[WRITE] Drawing exactly %d approved lines onto fresh Page %d...", 
                                    approvedKeepList.size(), i + 1));
                        }
                        
                        // Open the visual stream for the *first and only* time in this runtime scope
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                targetDocument, targetPage, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            outputStream.setStrokingColor(Color.GREEN);
                            outputStream.setLineWidth(1.0f);

                            for (Rectangle2D drawingTarget : approvedKeepList) {
                                outputStream.addRect((float) drawingTarget.getX(), (float) drawingTarget.getY(), 
                                                     (float) drawingTarget.getWidth(), (float) drawingTarget.getHeight());
                                outputStream.stroke();
                            }
                        }
                    }
                }

                System.out.println("\n[FINALIZE] Committing fresh visual changes to disk...");
                targetDocument.save(outputFile);
                System.out.println("SUCCESS: File cleanly generated at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[PIPELINE FATAL] Production writer failed: " + e.getMessage());
            }
        }
    }

    /**
     * SCRIPT 2: THE ISOLATED MATRIX ANALYST
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
            
            List<Rectangle2D> approvedKeepList = new ArrayList<>();
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();

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

                filterSnapshotObjects(targetBoxesToAudit, approvedKeepList);
            }

            if (DEBUG_MODE) {
                int elementsDropped = rawScoutedVectors.size() - approvedKeepList.size();
                System.out.println(String.format("  -> [Snapshot Metrics] Discovered: %d | Approved: %d | Omitted: %d", 
                        rawScoutedVectors.size(), approvedKeepList.size(), elementsDropped));
            }

            return approvedKeepList;
        }

        private void filterSnapshotObjects(List<VisualBox> boxes, List<Rectangle2D> approvedKeepList) {
            float alignmentTolerance = 5.0f;  
            float sizeMatchTolerance = 2.0f;  
            float gapSearchLimit = 15.0f;     

            int skipCount = 0;

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
                    skipCount++;
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [FILTER TARGET] Correctly dropping artifact line at X=%.1f, Y=%.1f", 
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
     * SCRIPT 1: RAW GRAPHICS VECTOR DISCOVERY PASS (SCOUT ENGINE)
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
