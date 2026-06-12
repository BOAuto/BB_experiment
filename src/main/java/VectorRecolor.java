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
                                System.out.println(String.format("  [RENDER-EXEC] Drawing Green Shape #%d -> X=%.3f, Y=%.3f, W=%.3f, H=%.3f", 
                                        paintCount, cleanLine.getX(), cleanLine.getY(), cleanLine.getWidth(), cleanLine.getHeight()));
                                
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
     * SCRIPT 2: DEEP-TELEMETRY MATRIX ANALYST
     */
    private static class Script2Analyst {
        private final PDPage page;
        private final float pageHeight;

        public Script2Analyst(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> generateApprovedSnapshot() throws IOException {
            System.out.println("  [ANALYST-START] Bootstrapping Engine Subroutines...");
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();
            
            System.out.println(String.format("  [SCOUT-OUTCOME] Extracted %d total raw vector paths from stream content.", rawScoutedVectors.size()));
            
            List<Rectangle2D> approvedKeepList = new ArrayList<>();
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();
            Map<Rectangle2D, Boolean> exclusionRegistry = new IdentityHashMap<>();

            System.out.println("  [FILTER-PASS 1] Sorting elements by target size rules (W > 2.0 && H > 4.0)...");
            int rawIdx = 0;
            for (Rectangle2D shape : rawScoutedVectors) {
                rawIdx++;
                if (shape.getWidth() > 2.0 && shape.getHeight() > 4.0) {
                    System.out.println(String.format("    -> Raw Element #%d [UPGRADED TO AUDIT BOX]: X=%.3f, Y=%.3f, W=%.3f, H=%.3f", 
                            rawIdx, shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight()));
                    targetBoxesToAudit.add(new VisualBox(shape, shape));
                } else {
                    System.out.println(String.format("    -> Raw Element #%d [FALLBACK PATH]: X=%.3f, Y=%.3f, W=%.3f, H=%.3f (Fails size bounds, marked KEEP by default)", 
                            rawIdx, shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight()));
                    exclusionRegistry.put(shape, false);
                }
            }

            if (!targetBoxesToAudit.isEmpty()) {
                System.out.println(String.format("  [TEXT-STRIPPER] Injecting %d spatial regions for OCR evaluation...", targetBoxesToAudit.size()));
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    Rectangle2D rawBounds = targetBoxesToAudit.get(b).transformedShape;
                    float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                    stripper.addRegion("reg_" + b, new Rectangle2D.Float(
                            (float)rawBounds.getX() + 1.0f, awtY + 1.0f, (float)rawBounds.getWidth() - 2.0f, (float)rawBounds.getHeight() - 2.0f));
                }

                stripper.extractRegions(page);

                System.out.println("  [CONTENT-VALIDATION] Processing textual results and column patterns...");
                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    VisualBox box = targetBoxesToAudit.get(b);
                    String extractedText = stripper.getTextForRegion("reg_" + b).trim();
                    
                    boolean textIsEmpty = extractedText.isEmpty();
                    boolean isStructuralGapColumn = (box.transformedShape.getWidth() > 3.0 && box.transformedShape.getWidth() < 22.0);

                    System.out.println(String.format("    -> Audit Box #%d [X=%.1f, Y=%.1f] Text Content: '%s' | isEmpty=%b | isGapCol=%b", 
                            b, box.transformedShape.getX(), box.transformedShape.getY(), extractedText, textIsEmpty, isStructuralGapColumn));

                    if (textIsEmpty || isStructuralGapColumn) {
                        box.isEmptyArea = true;
                    }
                }

                System.out.println("  [FILTER-PASS 2] Processing neighbor-proximity matrices...");
                filterSnapshotObjects(targetBoxesToAudit, exclusionRegistry);
            }

            System.out.println("  [LOOKBACK-RESOLVER] Compiling final snapshot using instance reference validation checking...");
            int evaluationCounter = 0;
            for (Rectangle2D originalVector : rawScoutedVectors) {
                evaluationCounter++;
                Boolean isMarkedForExclusion = exclusionRegistry.get(originalVector);
                
                System.out.print(String.format("    -> Reference Check #%d [X=%.3f, Y=%.3f]: MapValue=%s ", 
                        evaluationCounter, originalVector.getX(), originalVector.getY(), isMarkedForExclusion));

                if (isMarkedForExclusion != null && isMarkedForExclusion) {
                    System.out.println("==> [CRITICAL DROP] Found match in exclusion register! Skipping green drawing.");
                    continue; 
                }
                
                System.out.println("==> [PASS] Safe vector. Appending to keep-list.");
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
                System.out.println(String.format("    [MATRIX-EVAL] Inspecting Target Box #%d [X=%.1f, Y=%.1f, W=%.1f, H=%.1f, isEmptyArea=%b]", 
                        i, target.transformedShape.getX(), target.transformedShape.getY(), target.transformedShape.getWidth(), target.transformedShape.getHeight(), target.isEmptyArea));
                
                if (!target.isEmptyArea) {
                    System.out.println("      -> Box is not an empty area. Skipping neighbor check. Registered: KEEP.");
                    exclusionRegistry.put(target.rawSourceLineReference, false);
                    continue;
                }

                boolean immediateRowRepeat = false;
                boolean immediateColRepeat = false;

                // Horizontal Row Evaluation
                for (int n = 0; n < boxes.size(); n++) {
                    VisualBox neighbor = boxes.get(n);
                    if (target == neighbor) continue;
                    
                    boolean onSameRow = Math.abs(target.transformedShape.getY() - neighbor.transformedShape.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(target.transformedShape.getHeight() - neighbor.transformedShape.getHeight()) < sizeMatchTolerance;
                    
                    if (onSameRow && matchHeight) {
                        double distanceLeft = target.transformedShape.getX() - (neighbor.transformedShape.getX() + neighbor.transformedShape.getWidth());
                        double distanceRight = neighbor.transformedShape.getX() - (target.transformedShape.getX() + target.transformedShape.getWidth());
                        
                        boolean leftGapMatch = (distanceLeft >= -alignmentTolerance && distanceLeft <= gapSearchLimit);
                        boolean rightGapMatch = (distanceRight >= -alignmentTolerance && distanceRight <= gapSearchLimit);
                        
                        if (leftGapMatch || rightGapMatch) {
                            System.out.println(String.format("      -> [ROW MATCH] Target #%d linked with Box #%d (DistLeft=%.2f, DistRight=%.2f)", i, n, distanceLeft, distanceRight));
                            immediateRowRepeat = true;
                            break; 
                        }
                    }
                }

                // Vertical Column Evaluation
                for (int n = 0; n < boxes.size(); n++) {
                    VisualBox neighbor = boxes.get(n);
                    if (target == neighbor) continue;
                    
                    boolean onSameCol = Math.abs(target.transformedShape.getX() - neighbor.transformedShape.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(target.transformedShape.getWidth() - neighbor.transformedShape.getWidth()) < sizeMatchTolerance;

                    if (onSameCol && matchWidth) {
                        double distanceAbove = target.transformedShape.getY() - (neighbor.transformedShape.getY() + neighbor.transformedShape.getHeight());
                        double distanceBelow = neighbor.transformedShape.getY() - (target.transformedShape.getY() + target.transformedShape.getHeight());
                        
                        boolean aboveGapMatch = (distanceAbove >= -alignmentTolerance && distanceAbove <= gapSearchLimit);
                        boolean belowGapMatch = (distanceBelow >= -alignmentTolerance && distanceBelow <= gapSearchLimit);

                        if (aboveGapMatch || belowGapMatch) {
                            System.out.println(String.format("      -> [COL MATCH] Target #%d linked with Box #%d (DistAbove=%.2f, DistBelow=%.2f)", i, n, distanceAbove, distanceBelow));
                            immediateColRepeat = true;
                            break; 
                        }
                    }
                }

                // Final Classification Decision
                boolean rowXorCol = (immediateRowRepeat && !immediateColRepeat) || (immediateColRepeat && !immediateRowRepeat);
                System.out.println(String.format("      -> [DECISION] Target #%d RowRepeat=%b, ColRepeat=%b -> RowXorCol=%b", i, immediateRowRepeat, immediateColRepeat, rowXorCol));
                
                if (rowXorCol) {
                    System.out.println(String.format("      ==> ARTIFACT VERIFIED. Flagging reference for REMOVAL at X=%.1f, Y=%.1f", target.transformedShape.getX(), target.transformedShape.getY()));
                    exclusionRegistry.put(target.rawSourceLineReference, true);
                } else {
                    System.out.println("      ==> NOT AN ARTIFACT PATTERN. Registered: KEEP.");
                    exclusionRegistry.put(target.rawSourceLineReference, false);
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
     * SCRIPT 1: RAW VECTORS SUB-LEVEL INTERCEPTOR
     */
    private static class DrawingBoxEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private Double minX, minY, maxX, maxY;
        private int rawOperatorCounter = 0;

        protected DrawingBoxEngine(PDPage page) { super(page); }
        public List<Rectangle2D> getDetectedBoxes() { return detectedBoxes; }

        private void logOperator(String op, double x, double y) {
            rawOperatorCounter++;
            // Un-comment the line below if you want to inspect every single vector node point on the canvas
            // System.out.println(String.format("    [STREAM-OP #%d] %s -> x=%.2f, y=%.2f", rawOperatorCounter, op, x, y));
        }

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
            logOperator("appendRectangle", p0.getX(), p0.getY());
            updateBounds(p0.getX(), p0.getY()); 
            updateBounds(p2.getX(), p2.getY()); 
        }
        
        @Override public void moveTo(float x, float y) throws IOException { logOperator("moveTo", x, y); updateBounds(x, y); }
        @Override public void lineTo(float x, float y) throws IOException { logOperator("lineTo", x, y); updateBounds(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException { logOperator("curveTo", x3, y3); updateBounds(x1, y1); updateBounds(x3, y3); }
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
