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

    // Global toggle controlling detailed runtime trace output
    private static final boolean DEBUG_MODE = true;
    private static final boolean SAVE_DRAWINGS_ONLY = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
            if (DEBUG_MODE) System.out.println("[INIT] Created output directory: " + outputDir.getPath());
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

        if (files == null || files.length == 0) {
            System.out.println("[ERROR] No PDF files found in 'pdfs/' directory.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "drawings_and_normalized_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("PROCESSING FILE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();
                if (DEBUG_MODE) System.out.println(String.format("[PHASE 1] Document opened. In-memory page count: %d", totalPages));

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    
                    System.out.println(String.format("\n--- Processing Page %d of %d ---", i + 1, totalPages));

                    // ==========================================
                    // PHASE 3: SCRIPT 1 VECTOR DETECTION
                    // ==========================================
                    if (DEBUG_MODE) System.out.println("[PHASE 3] Initializing Script 1 DrawingBoxEngine...");
                    DrawingBoxEngine engine = new DrawingBoxEngine(page);
                    engine.processPage(page);
                    List<Rectangle2D> vectorBoxes = engine.getDetectedBoxes();
                    
                    if (DEBUG_MODE) {
                        System.out.println(String.format("  -> Step Outcome: Found %d raw drawing vectors on page context.", vectorBoxes.size()));
                    }

                    // ==========================================
                    // HELPER PHASE: SCRIPT 2 ISOLATED ANALYSIS
                    // ==========================================
                    List<Rectangle2D.Float> linesToKill = new ArrayList<>();
                    
                    if (!vectorBoxes.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[HELPER] Script 2 Analysis activated in isolation.");
                        List<VisualBox> visualBoxes = new ArrayList<>();
                        
                        // Step A: Convert vectors to isolation tracking boxes
                        for (Rectangle2D rb : vectorBoxes) {
                            if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                                visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                            }
                        }
                        
                        if (DEBUG_MODE) {
                            System.out.println(String.format("  -> Step 1: Filtered out minor visual anomalies. Tracking %d boxes.", visualBoxes.size()));
                        }

                        if (!visualBoxes.isEmpty()) {
                            if (DEBUG_MODE) System.out.println("  -> Step 2: Extracting spatial text content mappings via Area Stripper...");
                            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                            stripper.setSortByPosition(true);

                            for (int b = 0; b < visualBoxes.size(); b++) {
                                Rectangle2D.Float bnd = visualBoxes.get(b).bounds;
                                float awtY = pageHeight - bnd.y - bnd.height;
                                stripper.addRegion("box_" + b, new Rectangle2D.Float(
                                        bnd.x + 1.0f, awtY + 1.0f, bnd.width - 2.0f, bnd.height - 2.0f));
                            }

                            stripper.extractRegions(page);

                            int emptyCount = 0;
                            for (int b = 0; b < visualBoxes.size(); b++) {
                                VisualBox box = visualBoxes.get(b);
                                String contentText = stripper.getTextForRegion("box_" + b).trim();
                                
                                boolean isPureTextEmpty = contentText.isEmpty();
                                boolean isStructuralGapColumn = (box.bounds.width > 3.0f && box.bounds.width < 22.0f);

                                if (isPureTextEmpty || isStructuralGapColumn) {
                                    box.isEmpty = true;
                                    emptyCount++;
                                }
                            }
                            
                            if (DEBUG_MODE) {
                                System.out.println(String.format("  -> Step 3: Text evaluation complete. Identified %d target empty blocks.", emptyCount));
                                System.out.println("  -> Step 4: Scanning chain structures to map distinct deletion lines...");
                            }

                            // Run structural mapping to collect targets directly as lines
                            identifyTargetLines(visualBoxes, linesToKill);
                        }
                    }

                    // ==========================================
                    // PHASE 4: SCRIPT 1 GREEN BOX ONDEMAND DRAWING
                    // ==========================================
                    if (!vectorBoxes.isEmpty()) {
                        if (DEBUG_MODE) System.out.println("[PHASE 4] Executing Script 1 Drawing Overlays (Untouched Framework)...");
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
                        if (DEBUG_MODE) System.out.println("  -> Step Outcome: All original shapes overlayed with green boundaries.");
                    }

                    // ==========================================
                    // INSERTED STEP: EXECUTE LINE SUPPRESSION
                    // ==========================================
                    if (!linesToKill.isEmpty()) {
                        System.out.println(String.format("[REMOVAL STEP] Feeding %d targeted line segments into Exclusion Engine...", linesToKill.size()));
                        ContentExclusionEngine filterEngine = new ContentExclusionEngine(page, linesToKill);
                        filterEngine.processPage(page);
                        System.out.println("  -> Step Outcome: Stream interception completed successfully.");
                    } else {
                        if (DEBUG_MODE) System.out.println("[REMOVAL STEP] No matching lines were marked for suppression on this page.");
                    }
                }

                // ==========================================
                // PHASE 5: FILE SERIALIZATION
                // ==========================================
                if (SAVE_DRAWINGS_ONLY) {
                    System.out.println("\n[PHASE 5] Committing modifications and writing file data to disk...");
                    document.save(outputFile);
                    System.out.println("SUCCESS: File output generated at: " + outputFile.getAbsolutePath());
                }

            } catch (IOException e) {
                System.err.println("[EXC-FATAL] Error occurred during processing pipeline: " + e.getMessage());
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

    // Isolated structural analyst identifying directional targets and logging findings to terminal
    private static void identifyTargetLines(List<VisualBox> boxes, List<Rectangle2D.Float> killList) {
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        int leftLinesFlagged = 0;
        int topLinesFlagged = 0;
        int completeWipesFlagged = 0;

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            if (!target.isEmpty) continue; 

            boolean immediateRowRepeat = false;
            boolean immediateColRepeat = false;

            // Horizontal evaluation path
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

            // Vertical evaluation path
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

            // Map structural patterns to distinct coordinates for deletion handing
            if (immediateRowRepeat && !immediateColRepeat) {
                Rectangle2D.Float leftLineMask = new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, 2.0f, target.bounds.height + 2.0f);
                killList.add(leftLineMask);
                leftLinesFlagged++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [TARGET MATCH] Box #%d Row Flow. Flagging Left Vertical Line: [X=%.2f, Y=%.2f, H=%.2f]", 
                            i, target.bounds.x, target.bounds.y, target.bounds.height));
                }
            } else if (immediateColRepeat && !immediateRowRepeat) {
                Rectangle2D.Float topLineMask = new Rectangle2D.Float(target.bounds.x - 1.0f, (target.bounds.y + target.bounds.height) - 1.0f, target.bounds.width + 2.0f, 2.0f);
                killList.add(topLineMask);
                topLinesFlagged++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [TARGET MATCH] Box #%d Col Flow. Flagging Top Horizontal Line: [X=%.2f, Y=%.2f, W=%.2f]", 
                            i, target.bounds.x, target.bounds.y + target.bounds.height, target.bounds.width));
                }
            } else if (immediateRowRepeat && immediateColRepeat) {
                if (target.bounds.width < 22.0f) {
                    Rectangle2D.Float narrowLeftMask = new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, 2.0f, target.bounds.height + 2.0f);
                    killList.add(narrowLeftMask);
                    leftLinesFlagged++;
                    if (DEBUG_MODE) {
                        System.out.println(String.format("    -> [TARGET MATCH] Box #%d Narrow Cross. Flagging Left Vertical Line: [X=%.2f, Y=%.2f]", 
                                i, target.bounds.x, target.bounds.y));
                    }
                }
            } else {
                Rectangle2D.Float completeBoxMask = new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, target.bounds.width + 2.0f, target.bounds.height + 2.0f);
                killList.add(completeBoxMask);
                completeWipesFlagged++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("    -> [TARGET MATCH] Box #%d Isolated Frame. Flagging Complete Mask: [X=%.2f, Y=%.2f, W=%.2f, H=%.2f]", 
                            i, target.bounds.x, target.bounds.y, target.bounds.width, target.bounds.height));
                }
            }
        }

        if (DEBUG_MODE) {
            System.out.println(String.format("  -> Helper Summary: Handing over %d Left-Lines, %d Top-Lines, and %d Whole Frames.", 
                    leftLinesFlagged, topLinesFlagged, completeWipesFlagged));
        }
    }

    // Non-destructive Content Suppression Engine processing operators sequentially
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
                skipActivePathElement = false; // Supress execution of primitive segment
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
