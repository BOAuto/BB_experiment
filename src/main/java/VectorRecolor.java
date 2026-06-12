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

    private static final boolean VERBOSE_LOG = true;

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
            File outputFile = new File(outputDir, "stitched_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("STRUCTURAL STITCHING COMPILER ACTIVE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> COMPILED ANALYSIS FOR PAGE %d <<<", i + 1));

                    // STAGE 1: Structural Analysis & Layout Flow Detection
                    LayoutCompiler compiler = new LayoutCompiler(page);
                    compiler.analyzeLayoutFlow();
                    
                    List<Rectangle2D> finalOptimizedLines = compiler.getStitchedLayoutLines();

                    // STAGE 2: Render Phase
                    System.out.println("[STAGE 2] Committing optimized and stitched vector paths to canvas...");
                    if (!finalOptimizedLines.isEmpty()) {
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            outputStream.setStrokingColor(Color.GREEN);
                            outputStream.setLineWidth(1.0f);
                            int paintCount = 0;

                            for (Rectangle2D path : finalOptimizedLines) {
                                paintCount++;
                                outputStream.addRect(
                                    (float) path.getX(), 
                                    (float) path.getY(), 
                                    (float) path.getWidth(), 
                                    (float) path.getHeight()
                                );
                                outputStream.stroke();
                            }
                            System.out.println(String.format("  -> Render complete. Painted %d structural lines.", paintCount));
                        }
                    }
                }

                System.out.println("[FINALIZE] Saving file...");
                document.save(outputFile);
                System.out.println("[SUCCESS] Stitched document generated at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[FATAL SYSTEM CRASH] " + e.getMessage());
            }
        }
    }

    /**
     * ADVANCED STRUCTURAL LAYOUT COMPILER
     */
    private static class LayoutCompiler {
        private final PDPage page;
        private final float pageHeight;
        private final List<Rectangle2D> rawVectors = new ArrayList<>();
        private final List<Rectangle2D> stitchedLinesOutput = new ArrayList<>();

        public LayoutCompiler(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getStitchedLayoutLines() {
            return stitchedLinesOutput;
        }

        public void analyzeLayoutFlow() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            rawVectors.addAll(scout.getDetectedBoxes());

            List<VisualCell> allCells = new ArrayList<>();
            for (Rectangle2D vector : rawVectors) {
                if (vector.getWidth() > 2.0 && vector.getHeight() > 2.0) {
                    allCells.add(new VisualCell(vector));
                } else {
                    // Retain standalone pure line paths instantly
                    stitchedLinesOutput.add(vector);
                }
            }

            if (allCells.isEmpty()) return;

            // Extract Text Content for accurate emptiness validation
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            for (int c = 0; c < allCells.size(); c++) {
                Rectangle2D r = allCells.get(c).bounds;
                float awtY = pageHeight - (float)r.getY() - (float)r.getHeight();
                stripper.addRegion("c_" + c, new Rectangle2D.Float(
                        (float)r.getX() + 0.5f, awtY + 0.5f, (float)r.getWidth() - 1.0f, (float)r.getHeight() - 1.0f));
            }
            stripper.extractRegions(page);

            for (int c = 0; c < allCells.size(); c++) {
                VisualCell cell = allCells.get(c);
                cell.isEmpty = stripper.getTextForRegion("c_" + c).trim().isEmpty();
            }

            // Determine Layout Flows & Adjacency Matrix
            float tolerance = 1.5f;
            for (VisualCell current : allCells) {
                boolean rowRepeat = false;
                boolean colRepeat = false;
                VisualCell neighborToStitch = null;

                for (VisualCell neighbor : allCells) {
                    if (current == neighbor) continue;

                    boolean sameRow = Math.abs(current.bounds.getY() - neighbor.bounds.getY()) < tolerance;
                    boolean sameCol = Math.abs(current.bounds.getX() - neighbor.bounds.getX()) < tolerance;

                    if (sameRow) {
                        rowRepeat = true;
                        // Track left-to-right neighbor adjacency for stitching extensions
                        if (Math.abs((current.bounds.getX() + current.bounds.getWidth()) - neighbor.bounds.getX()) < 3.0f ||
                            Math.abs((neighbor.bounds.getX() + neighbor.bounds.getWidth()) - current.bounds.getX()) < 3.0f) {
                            if (!neighbor.isEmpty) neighborToStitch = neighbor;
                        }
                    }
                    if (sameCol) {
                        colRepeat = true;
                        // Track top-to-bottom neighbor adjacency for stitching extensions
                        if (Math.abs((current.bounds.getY() + current.bounds.getHeight()) - neighbor.bounds.getY()) < 3.0f ||
                            Math.abs((neighbor.bounds.getY() + neighbor.bounds.getHeight()) - current.bounds.getY()) < 3.0f) {
                            if (!neighbor.isEmpty) neighborToStitch = neighbor;
                        }
                    }
                }

                // Categorize Flow State Rules
                if (rowRepeat && colRepeat)      current.flow = TableFlow.BIDIRECTIONAL;
                else if (rowRepeat)              current.flow = TableFlow.LEFT_TO_RIGHT;
                else if (colRepeat)              current.flow = TableFlow.TOP_TO_BOTTOM;
                else                             current.flow = TableFlow.NOT_A_TABLE;

                current.adjacentDataCell = neighborToStitch;
            }

            // Process line extractions and layout stitching overrides
            for (VisualCell cell : allCells) {
                if (!cell.isEmpty) {
                    // Valid cell: Keep all 4 lines as standard structural grid lines
                    addBoxEdgesToList(cell.bounds);
                    continue;
                }

                if (VERBOSE_LOG) {
                    System.out.println(String.format("[FLOW EVAL] Empty Box discovered at X=%.3f, Y=%.3f | Flow Type Detected: %s", 
                            cell.bounds.getX(), cell.bounds.getY(), cell.flow));
                }

                // Apply targeted edge stripping and stitching mechanics based on Flow Type
                switch (cell.flow) {
                    case LEFT_TO_RIGHT:
                        if (VERBOSE_LOG) System.out.println("  -> [RULE ACTION] Left-to-Right: Stripping LEFT edge. Preserving top, bottom, right.");
                        // Strip Left: Send only top, bottom, and right to output list
                        stitchedLinesOutput.add(new Rectangle2D.Double(cell.bounds.getX(), cell.bounds.getY() + cell.bounds.getHeight(), cell.bounds.getWidth(), 0.75)); // Top
                        stitchedLinesOutput.add(new Rectangle2D.Double(cell.bounds.getX(), cell.bounds.getY(), cell.bounds.getWidth(), 0.75)); // Bottom
                        stitchedLinesOutput.add(new Rectangle2D.Double(cell.bounds.getX() + cell.bounds.getWidth(), cell.bounds.getY(), 0.75, cell.bounds.getHeight())); // Right

                        // STITCH EXTENSION: Extend neighboring valid table cell lines horizontally
                        if (cell.adjacentDataCell != null) {
                            if (VERBOSE_LOG) {
                                System.out.println(String.format("  -> [STITCH MACHINE] Extending neighboring valid table cell (X=%.3f) lines horizontally by Width: %.3f", 
                                        cell.adjacentDataCell.bounds.getX(), cell.bounds.getWidth()));
                            }
                            cell.adjacentDataCell.bounds.setRect(
                                Math.min(cell.adjacentDataCell.bounds.getX(), cell.bounds.getX()),
                                cell.adjacentDataCell.bounds.getY(),
                                cell.adjacentDataCell.bounds.getWidth() + cell.bounds.getWidth(),
                                cell.adjacentDataCell.bounds.getHeight()
                            );
                        }
                        break;

                    case TOP_TO_BOTTOM:
                        if (VERBOSE_LOG) System.out.println("  -> [RULE ACTION] Top-to-Bottom: Stripping TOP edge. Preserving bottom, left, right.");
                        // Strip Top: Send only bottom, left, and right to output list
                        stitchedLinesOutput.add(new Rectangle2D.Double(cell.bounds.getX(), cell.bounds.getY(), cell.bounds.getWidth(), 0.75)); // Bottom
                        stitchedLinesOutput.add(new Rectangle2D.Double(cell.bounds.getX(), cell.bounds.getY(), 0.75, cell.bounds.getHeight())); // Left
                        stitchedLinesOutput.add(new Rectangle2D.Double(cell.bounds.getX() + cell.bounds.getWidth(), cell.bounds.getY(), 0.75, cell.bounds.getHeight())); // Right

                        // STITCH EXTENSION: Extend neighboring valid table cell lines vertically
                        if (cell.adjacentDataCell != null) {
                            if (VERBOSE_LOG) {
                                System.out.println(String.format("  -> [STITCH MACHINE] Extending neighboring valid table cell (Y=%.3f) lines vertically by Height: %.3f", 
                                        cell.adjacentDataCell.bounds.getY(), cell.bounds.getHeight()));
                            }
                            cell.adjacentDataCell.bounds.setRect(
                                cell.adjacentDataCell.bounds.getX(),
                                Math.min(cell.adjacentDataCell.bounds.getY(), cell.bounds.getY()),
                                cell.adjacentDataCell.bounds.getWidth(),
                                cell.adjacentDataCell.bounds.getHeight() + cell.bounds.getHeight()
                            );
                        }
                        break;

                    case BIDIRECTIONAL:
                        if (VERBOSE_LOG) System.out.println("  -> [RULE ACTION] Bidirectional Flow: Protecting all lines. No removal executed.");
                        addBoxEdgesToList(cell.bounds);
                        break;

                    case NOT_A_TABLE:
                        if (VERBOSE_LOG) System.out.println("  -> [RULE ACTION] Isolated Non-Table Artifact: Stripping ALL 4 lines for removal.");
                        // Do not add any edge segments to stitchedLinesOutput, effectively erasing the object
                        break;
                }
            }
        }

        private void addBoxEdgesToList(Rectangle2D box) {
            stitchedLinesOutput.add(new Rectangle2D.Double(box.getX(), box.getY() + box.getHeight(), box.getWidth(), 0.75)); // Top
            stitchedLinesOutput.add(new Rectangle2D.Double(box.getX(), box.getY(), box.getWidth(), 0.75)); // Bottom
            stitchedLinesOutput.add(new Rectangle2D.Double(box.getX(), box.getY(), 0.75, box.getHeight())); // Left
            stitchedLinesOutput.add(new Rectangle2D.Double(box.getX() + box.getWidth(), box.getY(), 0.75, box.getHeight())); // Right
        }
    }

    private enum TableFlow { LEFT_TO_RIGHT, TOP_TO_BOTTOM, BIDIRECTIONAL, NOT_A_TABLE }

    private static class VisualCell {
        final Rectangle2D bounds;
        boolean isEmpty = false;
        TableFlow flow = TableFlow.NOT_A_TABLE;
        VisualCell adjacentDataCell = null;

        VisualCell(Rectangle2D bounds) {
            this.bounds = bounds;
        }
    }

    /**
     * SCRIPT 1: RAW VECTOR EXTRACTION ENGINE
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
