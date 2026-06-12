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

    // Toggle for comprehensive terminal reporting
    private static final boolean DEBUG_MODE = true;
    private static final boolean SAVE_DRAWINGS_ONLY = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
            if (DEBUG_MODE) System.out.println("[INIT] Created output folder: " + outputDir.getPath());
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

        if (files == null || files.length == 0) {
            System.out.println("[ERROR] No targets located in 'pdfs/' directory.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "drawings_and_normalized_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("PROCESSING TARGET FILE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();
                if (DEBUG_MODE) System.out.println(String.format("[PHASE 1] Document initialized. Page Count: %d", totalPages));

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    
                    System.out.println(String.format("\n--- Runtime Trace: Page %d of %d ---", i + 1, totalPages));

                    // ==========================================
                    // PHASE 3: SCRIPT 1 VECTOR DETECTION
                    // ==========================================
                    if (DEBUG_MODE) System.out.println("[PHASE 3] Running Script 1 Vector Detection...");
                    DrawingBoxEngine originalDetectionEngine = new DrawingBoxEngine(page);
                    originalDetectionEngine.processPage(page);
                    List<Rectangle2D> vectorBoxes = originalDetectionEngine.getDetectedBoxes();
                    
                    if (DEBUG_MODE) {
                        System.out.println(String.format("  -> Pre-Removal Snapshot: Found %d raw vector shapes.", vectorBoxes.size()));
                    }

                    // ==========================================
                    // HELPER PHASE: SCRIPT 2 ISOLATED ANALYSIS
                    // ==========================================
                    List<Rectangle2D.Float> linesToKill = new ArrayList<>();
                    
                    if (!vectorBoxes.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[HELPER] Activating Script 2 Analytical Logic...");
                        List<VisualBox> visualBoxes = new ArrayList<>();
                        
                        for (Rectangle2D rb : vectorBoxes) {
                            if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                                visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                            }
                        }
                        
                        if (DEBUG_MODE) System.out.println(String.format("  -> Isolated tracking initialized for %d filtered bounding boxes.", visualBoxes.size()));

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

                            int emptyBlocksCount = 0;
                            for (int b = 0; b < visualBoxes.size(); b++) {
                                VisualBox box = visualBoxes.get(b);
                                String contentText = stripper.getTextForRegion("box_" + b).trim();
                                
                                boolean isPureTextEmpty = contentText.isEmpty();
                                boolean isStructuralGapColumn = (box.bounds.width > 3.0f && box.bounds.width < 22.0f);

                                if (isPureTextEmpty || isStructuralGapColumn) {
                                    box.isEmpty = true;
                                    emptyBlocksCount++;
                                }
                            }
                            
                            if (DEBUG_MODE) {
                                System.out.println(String.format("  -> Area text stripping completed. Detected %d empty grid spaces.", emptyBlocksCount));
                                System.out.println("  -> Mapping horizontal and vertical spatial relationships...");
                            }

                            // Identify line coordinate spans to purge
                            identifyTargetLines(visualBoxes, linesToKill);
                        }
                    }

                    // ==========================================
                    // INSERTED STEP: EXECUTE STREAM PURGE FIRST
                    // ==========================================
                    if (!linesToKill.isEmpty()) {
                        System.out.println(String.format("[REMOVAL STEP] Executing stream suppression for %d targeted segments...", linesToKill.size()));
                        
                        ContentExclusionEngine filterEngine = new ContentExclusionEngine(page, linesToKill);
                        filterEngine.processPage(page);

                        if (DEBUG_MODE) {
                            // Verify post-removal metric by running a fresh scan across the newly stripped page stream
                            System.out.println("  -> Running verification scan post-removal...");
                            DrawingBoxEngine verificationEngine = new DrawingBoxEngine(page);
                            verificationEngine.processPage(page);
                            int postCount = verificationEngine.getDetectedBoxes().size();
                            System.out.println(String.format("  -> Structural Verification: [Pre-Removal Vectors: %d] | [Post-Removal Vectors: %d]", 
                                    vectorBoxes.size(), postCount));
                        }
                    } else {
                        if (DEBUG_MODE) System.out.println("[REMOVAL STEP] Zero match paths found. Skipping line suppression step.");
                    }

                    // ==========================================
                    // PHASE 4: SCRIPT 1 GREEN OVERLAY INJECTION
                    // ==========================================
                    if (!vectorBoxes.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[PHASE 4] Script 1 drawing original unedited green box profiles to overlay stream...");
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (Rectangle2D rect : vectorBoxes) {
                                contentStream.addRect((float) rect.getX(), (float) rect.getY(), 
                                                      (float) rect.getWidth(), (float) rect.getHeight());
                                contentStream.stroke();
                            }
                        }
                        if (DEBUG_MODE) System.out.println("  -> Overlay complete: Green markings successfully written into page structure.");
                    }
                }

                // ==========================================
                // PHASE 5: FILE SERIALIZATION
                // ==========================================
                if (SAVE_DRAWINGS_ONLY) {
                    System.out.println("\n[PHASE 5] Writing modified memory allocations to disk output...");
                    document.save(outputFile);
                    System.out.println("SUCCESS: File compiled cleanly at location: " + outputFile.getAbsolutePath());
                }

            } catch (IOException e) {
                System.err.println("[CRITICAL ERROR] Core pipeline processing failed: " + e.getMessage());
            }
        }
    }

    private static class VisualBox {
        Rectangle2D.Float bounds;
        boolean isEmpty = false;

        VisualBox(float x, float y, float w, float h) {
            this.bounds = new Rectangle2D.Float(x, y, w, h);
        }
    }

    // Isolated worker logging specific line definitions directly to the console output
    private static void identifyTargetLines(List<VisualBox> boxes, List<Rectangle2D.Float> killList) {
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        int leftLinesCount = 0;
        int topLinesCount = 0;
        int isolatedCount = 0;

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            if (!target.isEmpty) continue; 

            boolean immediateRowRepeat = false;
            boolean immediateColRepeat = false;

            // Row trajectory check
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

            // Column trajectory check
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

            // Flag targeted line arrays for elimination and log the coordinates explicitly
            if (immediateRowRepeat && !immediateColRepeat) {
                Rectangle2D.Float mask = new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, 2.0f, target.bounds.height + 2.0f);
                killList.add(mask);
                leftLinesCount++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [FLAGGED FOR REMOVAL] Item #%d (Continuous Row) -> Left Line Mask at [X=%.1f, Y=%.1f, H=%.1f]", 
                            i, target.bounds.x, target.bounds.y, target.bounds.height));
                }
            } else if (immediateColRepeat && !immediateRowRepeat) {
                Rectangle2D.Float mask = new Rectangle2D.Float(target.bounds.x - 1.0f, (target.bounds.y + target.bounds.height) - 1.0f, target.bounds.width + 2.0f, 2.0f);
                killList.add(mask);
                topLinesCount++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [FLAGGED FOR REMOVAL] Item #%d (Column Stack) -> Top Line Mask at [X=%.1f, Y=%.1f, W=%.1f]", 
                            i, target.bounds.x, target.bounds.y + target.bounds.height, target.bounds.width));
                }
            } else if (immediateRowRepeat && immediateColRepeat) {
                if (target.bounds.width < 22.0f) {
                    Rectangle2D.Float mask = new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, 2.0f, target.bounds.height + 2.0f);
                    killList.add(mask);
                    leftLinesCount++;
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [FLAGGED FOR REMOVAL] Item #%d (Cross Grid Narrow) -> Left Line Override Mask at [X=%.1f, Y=%.1f]", 
                                i, target.bounds.x, target.bounds.y));
                    }
                }
            } else {
                Rectangle2D.Float mask = new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, target.bounds.width + 2.0f, target.bounds.height + 2.0f);
                killList.add(mask);
                isolatedCount++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [FLAGGED FOR REMOVAL] Item #%d (Isolated Frame) -> Full Perimeter Wipe Mask at [X=%.1f, Y=%.1f, W=%.1f, H=%.1f]", 
                            i, target.bounds.x, target.bounds.y, target.bounds.width, target.bounds.height));
                }
            }
        }

        if (DEBUG_MODE) {
            System.out.println(String.format("  -> Target Breakdown Handed over to Script 1: %d Left Lines, %d Top Lines, %d Perimeter Outlines.", 
                    leftLinesCount, topLinesCount, isolatedCount));
        }
    }

    // Stream Interception Suppression Class
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
                double minX = Math.min(currentX, x);
                double minY = Math.min(currentY, y);
                double w = Math.max(Math.abs(x - currentX), 1.0);
                double h = Math.max(Math.abs(y - currentY), 1.0);
                Rectangle2D.Float structuralSegment = new Rectangle2D.Float((float)minX, (float)minY, (float)w, (float)h);

                for (Rectangle2D.Float mask : exclusions) {
                    if (mask.intersects(structuralSegment)) {
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

        @Override public void moveTo(float x, float y) throws IOException { currentX = (double)x; currentY = (double)y; }
        @Override public void lineTo(float x, float y) throws IOException { testVectorCoordinates(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException { testVectorCoordinates(x3, y3); }

        @Override public void strokePath() throws IOException {
            if (skipActivePathElement) {
                skipActivePathElement = false; // Intercept draw token
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
