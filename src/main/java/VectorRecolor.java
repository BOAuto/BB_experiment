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
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * <h1>VectorRecolor</h1>
 * Industrial-grade PDF vector preprocessing engine designed to clean up empty table
 * layout boxes by merging them with adjacent text-containing data blocks.
 * * <p>Features a dual-mode execution pipeline, structured utility logging, and an
 * asymmetric path-stretching algorithm to bypass duplicate vector drawing overhead.</p>
 */
public class VectorRecolor {

    // Structured infrastructure logger for unified debug tracing
    private static final Logger LOGGER = Logger.getLogger(VectorRecolor.class.getName());

    // Global pipeline tuning flags
    private static final boolean DEBUG_MODE = true;
    private static final boolean REMOVE_DUMMY_LINES = true; 

    static {
        // Configure standard root logger behaviour for clean, consistent console streams
        LOGGER.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        
        // Dynamically adjust log levels based on the global compilation debug state
        if (DEBUG_MODE) {
            LOGGER.setLevel(Level.FINE);
            handler.setLevel(Level.FINE);
        } else {
            LOGGER.setLevel(Level.INFO);
            handler.setLevel(Level.INFO);
        }
        LOGGER.addHandler(handler);
    }

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        if (files == null || files.length == 0) {
            LOGGER.severe("[CRITICAL] No target PDF documents discovered in 'pdfs/' folder.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "clean_" + inputFile.getName());
            
            LOGGER.info("==========================================================================");
            LOGGER.info("PIPELINE ENGINE INITIALIZED FOR FILE: " + inputFile.getName());
            LOGGER.info("CONFIGURATION MATRIX -> REMOVE_DUMMY_LINES = " + REMOVE_DUMMY_LINES);
            LOGGER.info("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    LOGGER.info(String.format(">>> PROCESSING PAGE %d OF %d <<<", i + 1, totalPages));

                    List<Rectangle2D> finalCleanBoxes = new ArrayList<>();
                    List<Rectangle2D> finalCleanLines = new ArrayList<>();

                    // STAGE 1 & 2: Structural Discovery Pass
                    if (REMOVE_DUMMY_LINES) {
                        LOGGER.fine("[ENGINE TRACE] Booting isolated layout compilation compiler...");
                        LayoutCompiler compiler = new LayoutCompiler(page);
                        compiler.executeTopologyAnalysis();
                        
                        finalCleanBoxes.addAll(compiler.getFinalLayoutBoxes());
                        finalCleanLines.addAll(compiler.getFinalLayoutLines());
                    } else {
                        LOGGER.info("[ENGINE] Pipeline bypass active. Extracting paths directly to drawing cache...");
                        DrawingBoxEngine scout = new DrawingBoxEngine(page);
                        scout.processPage(page);
                        
                        for (Rectangle2D shape : scout.getDetectedBoxes()) {
                            if (shape.getWidth() > 2.0 && shape.getHeight() > 2.0) {
                                finalCleanBoxes.add(shape);
                            } else {
                                finalCleanLines.add(shape);
                            }
                        }
                    }

                    // STAGE 3: Precision Graphic Reconstruction Pass
                    LOGGER.info("[STAGE 3] Committing optimized layout paths to canvas stream...");
                    try (PDPageContentStream outputStream = new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        
                        outputStream.setStrokingColor(Color.GREEN);
                        outputStream.setLineWidth(1.0f);
                        int paintCount = 0;

                        // Commit valid table data boundaries
                        for (Rectangle2D box : finalCleanBoxes) {
                            paintCount++;
                            LOGGER.fine(String.format("  -> [CANVAS RENDER] Painting Closed Box #%d: X=%.3f, Y=%.3f [W=%.3f, H=%.3f]", 
                                    paintCount, box.getX(), box.getY(), box.getWidth(), box.getHeight()));
                            outputStream.addRect((float)box.getX(), (float)box.getY(), (float)box.getWidth(), (float)box.getHeight());
                            outputStream.stroke();
                        }

                        // Commit infrastructure dividers and borders
                        for (Rectangle2D line : finalCleanLines) {
                            paintCount++;
                            LOGGER.fine(String.format("  -> [CANVAS RENDER] Painting Standalone Line #%d: X=%.3f, Y=%.3f [W=%.3f, H=%.3f]", 
                                    paintCount, line.getX(), line.getY(), line.getWidth(), line.getHeight()));
                            outputStream.addRect((float)line.getX(), (float)line.getY(), (float)line.getWidth(), (float)line.getHeight());
                            outputStream.stroke();
                        }
                        
                        LOGGER.info(String.format(">>> PAGE %d COMPLETE. Structural elements painted: %d <<<", i + 1, paintCount));
                    }
                }

                LOGGER.info("[FINALIZE] Flushing output document state...");
                document.save(outputFile);
                LOGGER.info("[SUCCESS] Pipeline terminated clean. Location: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                LOGGER.severe("[FATAL CONTEXT ERROR] Processing aborted unexpectedly: " + e.getMessage());
            }
        }
    }

    /**
     * <h2>LayoutCompiler</h2>
     * Multi-pass space matrix evaluator that groups graphic blocks, runs regional 
     * character extractions, detects layout paths, and stretches valid cells to swallow empty space blocks.
     */
    private static class LayoutCompiler {
        private final PDPage page;
        private final float pageHeight;
        
        // Maps shapes by absolute reference memory identity to maintain perfect overlapping counts
        private final Map<Rectangle2D, Boolean> exclusionRegistry = new IdentityHashMap<>();
        private final List<VisualCell> targetBoxesToAudit = new ArrayList<>();
        private final List<Rectangle2D> pureStandaloneLines = new ArrayList<>();
        
        private final List<Rectangle2D> finalLayoutBoxes = new ArrayList<>();
        private final List<Rectangle2D> finalLayoutLines = new ArrayList<>();

        public LayoutCompiler(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getFinalLayoutBoxes() { return finalLayoutBoxes; }
        public List<Rectangle2D> getFinalLayoutLines() { return finalLayoutLines; }

        /**
         * Orchestrates the parsing passes to safely decouple table lines and boxes.
         */
        public void executeTopologyAnalysis() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();

            LOGGER.fine(String.format("  [PHASE 1: GRAPHICS ANALYSIS] Extracted %d paths from page vector stream.", rawScoutedVectors.size()));

            // Category Separation Pass (Discriminates based on structural thickness)
            for (Rectangle2D shape : rawScoutedVectors) {
                exclusionRegistry.put(shape, false); 
                if (shape.getWidth() > 2.0 && shape.getHeight() > 2.0) {
                    targetBoxesToAudit.add(new VisualCell(shape)); 
                } else {
                    pureStandaloneLines.add(shape);
                }
            }

            LOGGER.fine(String.format("    -> Categorized into %d layout boxes and %d basic vector lines.", 
                    targetBoxesToAudit.size(), pureStandaloneLines.size()));

            if (targetBoxesToAudit.isEmpty()) {
                LOGGER.fine("    -> No closed boxes present. Routing standalone line array straight to end-queue.");
                finalLayoutLines.addAll(pureStandaloneLines);
                return;
            }

            // PHASE 2: Region-Targeted Character Audit
            LOGGER.fine("  [PHASE 2: CHARACTER AUDIT] Mapping regional text zones across candidate spaces...");
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);

            // Register inner bounding box coordinates with safe inner margins
            for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                Rectangle2D rawBounds = targetBoxesToAudit.get(b).bounds;
                float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                stripper.addRegion("reg_" + b, new Rectangle2D.Float(
                        (float)rawBounds.getX() + 0.5f, awtY + 0.5f, (float)rawBounds.getWidth() - 1.0f, (float)rawBounds.getHeight() - 1.0f));
            }
            stripper.extractRegions(page);

            // Flag containers based on regional text density states
            for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                VisualCell cell = targetBoxesToAudit.get(b);
                String extractedText = stripper.getTextForRegion("reg_" + b).trim();
                
                boolean textIsEmpty = extractedText.isEmpty();
                boolean isStructuralGapColumn = (cell.bounds.getWidth() > 3.0 && cell.bounds.getWidth() < 22.0);

                if (textIsEmpty || isStructuralGapColumn) {
                    cell.isEmptyArea = true;
                }
                
                LOGGER.fine(String.format("    * Box #%d [X=%.2f, Y=%.2f]: Content=\"%s\" | IsEmpty=%b | IsGapColumn=%b", 
                        b + 1, cell.bounds.getX(), cell.bounds.getY(), extractedText, textIsEmpty, isStructuralGapColumn));
            }

            // PHASE 3: Spatial Micro-Neighborhood Flow Identification
            LOGGER.fine("  [PHASE 3: NEIGHBORHOOD SEARCH] Calculating structural adjacency map matches...");
            float alignmentTolerance = 1.0f;  
            float sizeMatchTolerance = 1.0f;  
            float gapSearchLimit = 0.5f; // Tight boundary proximity window to avoid wrong row matching

            for (int i = 0; i < targetBoxesToAudit.size(); i++) {
                VisualCell current = targetBoxesToAudit.get(i);
                if (!current.isEmptyArea) continue;

                boolean immediateRowRepeat = false;
                boolean immediateColRepeat = false;
                VisualCell targetPartner = null;

                LOGGER.fine(String.format("    -> Scanning adjacencies for Empty Candidate #%d at [X=%.2f, Y=%.2f]", (i + 1), current.bounds.getX(), current.bounds.getY()));

                // Scan Horizontal Rows for adjacent text blocks
                for (VisualCell neighbor : targetBoxesToAudit) {
                    if (current == neighbor) continue;
                    boolean onSameRow = Math.abs(current.bounds.getY() - neighbor.bounds.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(current.bounds.getHeight() - neighbor.bounds.getHeight()) < sizeMatchTolerance;
                    
                    if (onSameRow && matchHeight) {
                        double distanceLeft = current.bounds.getX() - (neighbor.bounds.getX() + neighbor.bounds.getWidth());
                        double distanceRight = neighbor.bounds.getX() - (current.bounds.getX() + current.bounds.getWidth());
                        if ((distanceLeft >= -alignmentTolerance && distanceLeft <= gapSearchLimit) || 
                            (distanceRight >= -alignmentTolerance && distanceRight <= gapSearchLimit)) {
                            
                            immediateRowRepeat = true;
                            if (!neighbor.isEmptyArea) {
                                if (neighbor.bounds.getX() < current.bounds.getX()) {
                                    targetPartner = neighbor;
                                } else if (targetPartner == null) {
                                    targetPartner = neighbor;
                                }
                            }
                        }
                    }
                }

                // Scan Vertical Columns for adjacent text blocks
                for (VisualCell neighbor : targetBoxesToAudit) {
                    if (current == neighbor) continue;
                    boolean onSameCol = Math.abs(current.bounds.getX() - neighbor.bounds.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(current.bounds.getWidth() - neighbor.bounds.getWidth()) < sizeMatchTolerance;

                    if (onSameCol && matchWidth) {
                        double distanceAbove = current.bounds.getY() - (neighbor.bounds.getY() + neighbor.bounds.getHeight());
                        double distanceBelow = neighbor.bounds.getY() - (current.bounds.getY() + current.bounds.getHeight());
                        if ((distanceAbove >= -alignmentTolerance && distanceAbove <= gapSearchLimit) || 
                            (distanceBelow >= -alignmentTolerance && distanceBelow <= gapSearchLimit)) {
                            
                            immediateColRepeat = true;
                            if (!neighbor.isEmptyArea) {
                                if (neighbor.bounds.getY() > current.bounds.getY()) {
                                    targetPartner = neighbor;
                                } else if (targetPartner == null) {
                                    targetPartner = neighbor;
                                }
                            }
                        }
                    }
                }

                // Finalize cell orientation strategy
                if (immediateRowRepeat && !immediateColRepeat) {
                    current.flow = TableFlow.LEFT_TO_RIGHT;
                    current.adjacentDataCell = targetPartner;
                } else if (immediateColRepeat && !immediateRowRepeat) {
                    current.flow = TableFlow.TOP_TO_BOTTOM;
                    current.adjacentDataCell = targetPartner;
                } else if (immediateRowRepeat && immediateColRepeat) {
                    current.flow = TableFlow.BIDIRECTIONAL;
                }
                
                LOGGER.fine(String.format("        * Structural Flow: %s | Partner: %s", 
                        current.flow, (current.adjacentDataCell != null ? "X=" + current.adjacentDataCell.bounds.getX() : "NONE")));
            }

            // PHASE 4: Asymmetric Geometry Stretching Phase
            LOGGER.fine("  [PHASE 4: RESOLUTION MUTATION] Running footprint space-stretching pass...");
            int occurrenceCounter = 0;
            for (VisualCell cell : targetBoxesToAudit) {
                if (!cell.isEmptyArea) {
                    if (!finalLayoutBoxes.contains(cell.bounds)) {
                        finalLayoutBoxes.add(cell.bounds);
                    }
                    continue;
                }

                occurrenceCounter++;
                LOGGER.fine(String.format("    -> [STITCH OPERATION] Processing instance #%d at [X=%.2f, Y=%.2f]", 
                        occurrenceCounter, cell.bounds.getX(), cell.bounds.getY()));

                switch (cell.flow) {
                    case LEFT_TO_RIGHT:
                        if (cell.adjacentDataCell != null) {
                            VisualCell dataCell = cell.adjacentDataCell;
                            double newX = Math.min(dataCell.bounds.getX(), cell.bounds.getX());
                            double newWidth = dataCell.bounds.getWidth() + cell.bounds.getWidth();
                            
                            // Mutate coordinates in-place to absorb the empty layout gap box
                            dataCell.bounds.setRect(newX, dataCell.bounds.getY(), newWidth, dataCell.bounds.getHeight());
                            
                            LOGGER.fine(String.format("        [MUTATION] Horizontal stretch complete. New Width: %.2f", newWidth));
                            if (!finalLayoutBoxes.contains(dataCell.bounds)) {
                                finalLayoutBoxes.add(dataCell.bounds);
                            }
                        }
                        break;

                    case TOP_TO_BOTTOM:
                        if (cell.adjacentDataCell != null) {
                            VisualCell dataCell = cell.adjacentDataCell;
                            double newY = Math.min(dataCell.bounds.getY(), cell.bounds.getY());
                            double newHeight = dataCell.bounds.getHeight() + cell.bounds.getHeight();
                            
                            // Mutate coordinates in-place to stretch over vertical structural gaps
                            dataCell.bounds.setRect(dataCell.bounds.getX(), newY, dataCell.bounds.getWidth(), newHeight);

                            LOGGER.fine(String.format("        [MUTATION] Vertical stretch complete. New Height: %.2f", newHeight));
                            if (!finalLayoutBoxes.contains(dataCell.bounds)) {
                                finalLayoutBoxes.add(dataCell.bounds);
                            }
                        }
                        break;

                    case BIDIRECTIONAL:
                        LOGGER.fine("        [PRESERVE] Complex multi-directional intersection. Retaining dimensions.");
                        if (!finalLayoutBoxes.contains(cell.bounds)) {
                            finalLayoutBoxes.add(cell.bounds);
                        }
                        break;

                    case NOT_A_TABLE:
                        LOGGER.fine("        [ELIMINATION] Isolated empty bounding frame discarded completely.");
                        break;
                }
            }

            finalLayoutLines.addAll(pureStandaloneLines);
            LOGGER.fine(String.format("  [ANALYSIS COMPLETE] Final execution output queues loaded with %d optimized boxes and %d lines.", 
                    finalLayoutBoxes.size(), finalLayoutLines.size()));
        }
    }

    private enum TableFlow { LEFT_TO_RIGHT, TOP_TO_BOTTOM, BIDIRECTIONAL, NOT_A_TABLE }

    /**
     * Internal data container storing individual box attributes, text states, and layout relationships.
     */
    private static class VisualCell {
        final Rectangle2D bounds;
        boolean isEmptyArea = false;
        TableFlow flow = TableFlow.NOT_A_TABLE;
        VisualCell adjacentDataCell = null;
        
        VisualCell(Rectangle2D bounds) { 
            this.bounds = bounds; 
        }
    }

    /**
     * Intercepts vector graphics from the PDF content stream to compile coordinate bounds.
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
