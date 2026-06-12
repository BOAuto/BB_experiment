package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.common.PDStream;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
                    // PHASE 1: TOTALLY ISOLATED SCOUT & MATRIX AUDIT (SCRIPT 2 INTERNAL RUN)
                    // This pass runs Script 1 completely in memory to isolate the 20 artifact lines.
                    // Absolutely no graphic changes or content streams are opened here.
                    // ==========================================================================
                    if (DEBUG_MODE) System.out.println("[PIPELINE] Initializing Script 2 Isolation Analysis Engine...");
                    Script2Analyst analyst = new Script2Analyst(page);
                    
                    // Generate the removal blacklist (the 20 artifact lines)
                    List<Rectangle2D> blacklist = analyst.generateBlacklist();

                    // ==========================================================================
                    // PHASE 2: SURGICAL STREAM PURGE (THE DELETION)
                    // Intercepts the page's raw graphic instructions and drops the blacklisted paths.
                    // ==========================================================================
                    if (!blacklist.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[PIPELINE] Executing Low-Level Stream Purge on targeted paths...");
                        surgicallyRemoveStreams(page, blacklist);
                    }

                    // ==========================================================================
                    // PHASE 3: THE SINGLE GRAPHIC SNAPSHOT (THE VALDIATION LAYER)
                    // Opens a single append stream to draw only the approved 1,563 lines in green.
                    // ==========================================================================
                    List<Rectangle2D> approvedKeepList = analyst.getLastApprovedKeepList();
                    if (!approvedKeepList.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[PIPELINE] Drawing final unified verification snapshot...");
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
                        if (DEBUG_MODE) System.out.println("  -> Single Graphic Snapshot committed to page state successfully.");
                    }
                }

                System.out.println("\n[FINALIZE] Writing modified data blocks to output target...");
                document.save(outputFile);
                System.out.println("SUCCESS: Processed file safely committed at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[PIPELINE FATAL] IO Operation failed: " + e.getMessage());
            }
        }
    }

    /**
     * SURGICAL STREAM REDACTION ENGINE
     * Parses the underlying content stream token by token, tracks geometry changes,
     * and deletes targeted vector sequences directly from the native page syntax.
     */
    private static void surgicallyRemoveStreams(PDPage page, List<Rectangle2D> blacklist) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page);
        List<Object> newTokens = new ArrayList<>();
        List<Object> currentPathTokens = new ArrayList<>();
        
        Double minX = null, minY = null, maxX = null, maxY = null;
        float alignmentTolerance = 2.0f; // Precision tolerance for raw token stream matching

        Object token = parser.parseNextToken();
        while (token != null) {
            currentPathTokens.add(token);

            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String opName = op.getName();

                // Track inline pen translations to capture path bounds dynamically
                if (opName.equals("m") || opName.equals("l")) { 
                    int size = currentPathTokens.size();
                    if (size >= 3 && currentPathTokens.get(size - 2) instanceof COSNumber && currentPathTokens.get(size - 3) instanceof COSNumber) {
                        double x = ((COSNumber) currentPathTokens.get(size - 3)).doubleValue();
                        double y = ((COSNumber) currentPathTokens.get(size - 2)).doubleValue();
                        if (minX == null) { minX = maxX = x; minY = maxY = y; } 
                        else { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
                    }
                } 
                else if (opName.equals("re")) { 
                    int size = currentPathTokens.size();
                    if (size >= 5 && currentPathTokens.get(size - 2) instanceof COSNumber && currentPathTokens.get(size - 5) instanceof COSNumber) {
                        double x = ((COSNumber) currentPathTokens.get(size - 5)).doubleValue();
                        double y = ((COSNumber) currentPathTokens.get(size - 4)).doubleValue();
                        double w = ((COSNumber) currentPathTokens.get(size - 3)).doubleValue();
                        double h = ((COSNumber) currentPathTokens.get(size - 2)).doubleValue();
                        if (minX == null) { minX = x; minY = y; maxX = x + w; maxY = y + h; } 
                        else { minX = Math.min(minX, x); maxX = Math.max(maxX, x + w); minY = Math.min(minY, y); maxY = Math.max(maxY, y + h); }
                    }
                }
                // When path drawing terminates, check against the 20 blacklisted coordinate bounding shapes
                else if (opName.equals("S") || opName.equals("f") || opName.equals("F") || opName.equals("b") || opName.equals("B")) {
                    boolean matchesBlacklist = false;
                    if (minX != null) {
                        double width = maxX - minX;
                        double height = maxY - minY;

                        for (Rectangle2D blackItem : blacklist) {
                            if (Math.abs(minX - blackItem.getX()) < alignmentTolerance &&
                                Math.abs(minY - blackItem.getY()) < alignmentTolerance &&
                                Math.abs(width - blackItem.getWidth()) < alignmentTolerance &&
                                Math.abs(height - blackItem.getHeight()) < alignmentTolerance) {
                                matchesBlacklist = true;
                                break;
                            }
                        }
                    }

                    if (matchesBlacklist) {
                        // SURGICAL OMISSION: Clear the accumulated path tokens completely without writing them back
                        if (DEBUG_MODE) {
                            System.out.println(String.format("    -> [STREAM NATIVE PURGE] Successfully stripped structural line sequence from content stream at X=%.1f, Y=%.1f", minX, minY));
                        }
                    } else {
                        newTokens.addAll(currentPathTokens);
                    }
                    
                    currentPathTokens.clear();
                    minX = minY = maxX = maxY = null;
                }
                else if (opName.equals("n") || opName.equals("h")) { 
                    newTokens.addAll(currentPathTokens);
                    currentPathTokens.clear();
                    minX = minY = maxX = maxY = null;
                }
            }
            token = parser.parseNextToken();
        }
        
        newTokens.addAll(currentPathTokens);

        // Rewrite the newly sanitized token list directly back to the PDF object dictionary
        PDStream updatedStream = new PDStream(page.getCOSObject().getDoc());
        try (OutputStream os = updatedStream.createOutputStream()) {
            for (Object obj : newTokens) {
                if (obj instanceof Operator) {
                    os.write(((Operator) obj).getName().getBytes("ISO-8859-1"));
                    os.write('\n');
                } else if (obj instanceof COSBase) {
                    ((COSBase) obj).writePDF(os);
                    os.write(' ');
                }
            }
        }
        page.setContents(updatedStream);
    }

    /**
     * SCRIPT 2: THE ISOLATED MATRIX ANALYST
     */
    private static class Script2Analyst {
        private final PDPage page;
        private final float pageHeight;
        private final List<Rectangle2D> lastApprovedKeepList = new ArrayList<>();

        public Script2Analyst(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getLastApprovedKeepList() { 
            return lastApprovedKeepList; 
        }

        public List<Rectangle2D> generateBlacklist() throws IOException {
            // SCRIPT 1 INTERNAL EXECUTION: Pure memory capture. No content stream manipulation.
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();
            
            List<Rectangle2D> blacklist = new ArrayList<>();
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();

            for (Rectangle2D shape : rawScoutedVectors) {
                if (shape.getWidth() > 2.0 && shape.getHeight() > 4.0) {
                    targetBoxesToAudit.add(new VisualBox(shape));
                } else {
                    lastApprovedKeepList.add(shape);
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

                filterSnapshotObjects(targetBoxesToAudit, blacklist);
            }

            if (DEBUG_MODE) {
                System.out.println(String.format("  -> [Isolation Matrix Verification] Extracted Blacklist Size: %d paths to purge.", blacklist.size()));
            }

            return blacklist;
        }

        private void filterSnapshotObjects(List<VisualBox> boxes, List<Rectangle2D> blacklist) {
            float alignmentTolerance = 5.0f;  
            float sizeMatchTolerance = 2.0f;  
            float gapSearchLimit = 15.0f;     

            for (int i = 0; i < boxes.size(); i++) {
                VisualBox target = boxes.get(i);
                
                if (!target.isEmptyArea) {
                    lastApprovedKeepList.add(target.originalShape);
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
                    blacklist.add(target.originalShape);
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [AUDIT COMPLETED] Isolated Grid Artifact Line at X=%.1f, Y=%.1f", 
                                target.originalShape.getX(), target.originalShape.getY()));
                    }
                } else {
                    lastApprovedKeepList.add(target.originalShape);
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
     * SCRIPT 1: GRAPHICS SYSTEM VECTOR RECOGNITION PASS (SCOUT ENGINE)
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
