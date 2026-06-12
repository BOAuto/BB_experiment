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

    // Central control for verbose trace logging
    private static final boolean DEBUG_MODE = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
            if (DEBUG_MODE) System.out.println("[INIT] Created output target directory: " + outputDir.getPath());
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
                if (DEBUG_MODE) System.out.println(String.format("[PHASE 1] File loaded into volatile memory. Pages: %d", totalPages));

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    
                    System.out.println(String.format("\n--- Execution Loop: Page %d of %d ---", i + 1, totalPages));

                    // ==========================================================
                    // PIPELINE STEP 1: THE DISCOVERY RUN (SCOUT PHASE)
                    // ==========================================================
                    if (DEBUG_MODE) System.out.println("[STEP 1] Deploying DrawingBoxEngine as Scout to map raw vectors...");
                    DrawingBoxEngine scoutEngine = new DrawingBoxEngine(page);
                    scoutEngine.processPage(page);
                    List<Rectangle2D> rawScoutedVectors = scoutEngine.getDetectedBoxes();
                    
                    System.out.println(String.format("  -> Step 1 Outcome: Scout mapped %d vector profiles from raw stream.", rawScoutedVectors.size()));

                    // ==========================================================
                    // PIPELINE STEP 2: THE ISOLATED AUDIT (TEXT EVALUATION)
                    // ==========================================================
                    List<Rectangle2D> approvedKeepList = new ArrayList<>();
                    
                    if (!rawScoutedVectors.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[STEP 2] Initiating isolated layout text area analysis...");
                        List<VisualBox> targetBoxesToAudit = new ArrayList<>();
                        
                        for (Rectangle2D shape : rawScoutedVectors) {
                            // Filter out completely insignificant hair-thin noise metrics up front
                            if (shape.getWidth() > 2.0 && shape.getHeight() > 4.0) {
                                targetBoxesToAudit.add(new VisualBox(shape));
                            } else {
                                // Add minor elements straight to keep list to preserve default structure safely
                                approvedKeepList.add(shape);
                            }
                        }

                        if (!targetBoxesToAudit.isEmpty()) {
                            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                            stripper.setSortByPosition(true);

                            // Register coordinate bounding spaces to extract
                            for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                                Rectangle2D rawBounds = targetBoxesToAudit.get(b).originalShape;
                                float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                                stripper.addRegion("region_" + b, new Rectangle2D.Float(
                                        (float)rawBounds.getX() + 1.0f, awtY + 1.0f, (float)rawBounds.getWidth() - 2.0f, (float)rawBounds.getHeight() - 2.0f));
                            }

                            stripper.extractRegions(page);

                            // Set state variables based on inner structural data presence
                            for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                                VisualBox box = targetBoxesToAudit.get(b);
                                String extractedText = stripper.getTextForRegion("region_" + b).trim();
                                
                                boolean textIsEmpty = extractedText.isEmpty();
                                boolean isStructuralGapColumn = (box.originalShape.getWidth() > 3.0 && box.originalShape.getWidth() < 22.0);

                                if (textIsEmpty || isStructuralGapColumn) {
                                    box.isEmptyArea = true;
                                }
                            }

                            // ==========================================================
                            // PIPELINE STEP 3: THE FILTRATION PHASE (SKIP UNWANTED COORDS)
                            // ==========================================================
                            if (DEBUG_MODE) System.out.println("[STEP 3] Running filtration matrix to isolate unwanted layout line patterns...");
                            executeSnapshotFiltration(targetBoxesToAudit, approvedKeepList);
                        }
                    }

                    // Log snapshot variations to console output to confirm vector reductions
                    if (DEBUG_MODE) {
                        int elementsDropped = rawScoutedVectors.size() - approvedKeepList.size();
                        System.out.println("  -> [METRIC VERIFICATION SNAPSHOT]");
                        System.out.println(String.format("     * Pre-Evaluation Vectors discovered : %d", rawScoutedVectors.size()));
                        System.out.println(String.format("     * Filtered Keep-List count          : %d", approvedKeepList.size()));
                        System.out.println(String.format("     * Vector elements safely SKIPPED    : %d", elementsDropped));
                    }

                    // ==========================================================
                    // PIPELINE STEP 4: THE EXECUTION RUN (GREEN OVERLAY WRITE)
                    // ==========================================================
                    if (!approvedKeepList.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[STEP 4] Writing filtered approved snapshot vectors as clean green objects...");
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            outputStream.setStrokingColor(Color.GREEN);
                            outputStream.setLineWidth(1.0f);

                            for (Rectangle2D drawingTarget : approvedKeepList) {
                                outputStream.addRect((float) drawingTarget.getX(), (float) drawingTarget.getY(), 
                                                     (float) drawingTarget.getWidth(), (float) drawingTarget.getHeight());
                                outputStream.stroke();
                            }
                        }
                        if (DEBUG_MODE) System.out.println("  -> Step 4 Outcome: Stream append successful for current page context.");
                    }
                }

                // Persist the changes directly to the compiled target output path
                System.out.println("\n[FINALIZE] Executing IO file compilation and write...");
                document.save(outputFile);
                System.out.println("SUCCESS: Processed file committed and saved at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[PIPELINE FATAL] IO Operation failed in main thread loop: " + e.getMessage());
            }
        }
    }

    private static class VisualBox {
        Rectangle2D originalShape;
        boolean isEmptyArea = false;

        VisualBox(Rectangle2D shape) {
            this.originalShape = shape;
        }
    }

    // Evaluates grid spatial alignments and determines what items populate the memory keep-list
    private static void executeSnapshotFiltration(List<VisualBox> boxes, List<Rectangle2D> approvedKeepList) {
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        int skipLeftLineCounter = 0;
        int skipTopLineCounter = 0;

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            
            // Rule 1: If it contains layout business text content, it's instantly preserved
            if (!target.isEmptyArea) {
                approvedKeepList.add(target.originalShape);
                continue;
            }

            boolean immediateRowRepeat = false;
            boolean immediateColRepeat = false;

            // X-Axis Horizontal Trajectory Scan
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

            // Y-Axis Vertical Stack Trajectory Scan
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

            // Rule 2: Evaluate relational trends to decide whether to map element to keep-list or drop it completely
            if (immediateRowRepeat && !immediateColRepeat) {
                skipLeftLineCounter++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [SKIPPED LINE] Target #%d dropped from keep-list (Row Grid Left Border) at [X=%.1f, Y=%.1f]", 
                            i, target.originalShape.getX(), target.originalShape.getY()));
                }
            } else if (immediateColRepeat && !immediateRowRepeat) {
                skipTopLineCounter++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [SKIPPED LINE] Target #%d dropped from keep-list (Col Grid Top Border) at [X=%.1f, Y=%.1f]", 
                            i, target.originalShape.getX(), target.originalShape.getY()));
                }
            } else {
                // Keep independent layout fields, standalone boundaries, or text blocks
                approvedKeepList.add(target.originalShape);
            }
        }

        if (DEBUG_MODE) {
            System.out.println(String.format("  -> Filtration Logic Summary: Omitted %d Vertical and %d Horizontal Grid anomalies.", 
                    skipLeftLineCounter, skipTopLineCounter));
        }
    }

    // Standard low-level token reading engine mapping shapes cleanly to tracking context
    private static class DrawingBoxEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private Double minX, minY, maxX, maxY;

        protected DrawingBoxEngine(PDPage page) { super(page); }
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
