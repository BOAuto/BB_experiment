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
            File outputFile = new File(outputDir, "box_stitched_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("ELEGANT BOX-STITCHING COMPILER ACTIVE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> COMPILED ANALYSIS FOR PAGE %d <<<", i + 1));

                    // STAGE 1: Process structures natively as distinct lines or layout containers
                    LayoutCompiler compiler = new LayoutCompiler(page);
                    compiler.executeStitchingPass();
                    
                    List<Rectangle2D> finalCleanBoxes = compiler.getFinalLayoutBoxes();
                    List<Rectangle2D> finalCleanLines = compiler.getFinalLayoutLines();

                    // STAGE 2: Render Phase (Keeps Object Count Natively Minimal)
                    System.out.println("[STAGE 2] Rendering stitched boxes and structural standalone lines...");
                    try (PDPageContentStream outputStream = new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        
                        outputStream.setStrokingColor(Color.GREEN);
                        outputStream.setLineWidth(1.0f);
                        int totalPainted = 0;

                        // 1. Draw Legitimate, Stretched Table Cells
                        for (Rectangle2D box : finalCleanBoxes) {
                            totalPainted++;
                            outputStream.addRect((float)box.getX(), (float)box.getY(), (float)box.getWidth(), (float)box.getHeight());
                            outputStream.stroke();
                        }

                        // 2. Draw Standalone Structural Lines Natively
                        for (Rectangle2D line : finalCleanLines) {
                            totalPainted++;
                            outputStream.addRect((float)line.getX(), (float)line.getY(), (float)line.getWidth(), (float)line.getHeight());
                            outputStream.stroke();
                        }
                        
                        System.out.println(String.format("  -> Render complete. Natively painted structural objects: %d", totalPainted));
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
     * NATIVE BOX-STITCHING LAYOUT ENGINE
     */
    private static class LayoutCompiler {
        private final PDPage page;
        private final float pageHeight;
        private final List<Rectangle2D> pureStandaloneLines = new ArrayList<>();
        private final List<VisualCell> tableCellsRegistry = new ArrayList<>();
        
        private final List<Rectangle2D> outputCleanBoxes = new ArrayList<>();
        private final List<Rectangle2D> outputCleanLines = new ArrayList<>();

        public LayoutCompiler(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getFinalLayoutBoxes() { return outputCleanBoxes; }
        public List<Rectangle2D> getFinalLayoutLines() { return outputCleanLines; }

        public void executeStitchingPass() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawObjects = scout.getDetectedBoxes();

            // Clear Separation: Separate container boxes from raw lines upfront
            for (Rectangle2D obj : rawObjects) {
                if (obj.getWidth() > 2.0 && obj.getHeight() > 2.0) {
                    tableCellsRegistry.add(new VisualCell(obj));
                } else {
                    pureStandaloneLines.add(obj);
                }
            }

            if (tableCellsRegistry.isEmpty()) {
                outputCleanLines.addAll(pureStandaloneLines);
                return;
            }

            // Run Text Audit to isolate empty artifact blocks
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            for (int c = 0; c < tableCellsRegistry.size(); c++) {
                Rectangle2D r = tableCellsRegistry.get(c).bounds;
                float awtY = pageHeight - (float)r.getY() - (float)r.getHeight();
                stripper.addRegion("c_" + c, new Rectangle2D.Float(
                        (float)r.getX() + 0.5f, awtY + 0.5f, (float)r.getWidth() - 1.0f, (float)r.getHeight() - 1.0f));
            }
            stripper.extractRegions(page);

            for (int c = 0; c < tableCellsRegistry.size(); c++) {
                VisualCell cell = tableCellsRegistry.get(c);
                cell.isEmpty = stripper.getTextForRegion("c_" + c).trim().isEmpty();
            }

            // Map Flow & Pair Adjacency using zero-gap tolerances
            float alignmentTolerance = 1.0f;
            float sizeMatchTolerance = 1.0f;

            for (VisualCell current : tableCellsRegistry) {
                boolean horizontalMatch = false;
                boolean verticalMatch = false;
                VisualCell targetPartner = null;

                for (VisualCell neighbor : tableCellsRegistry) {
                    if (current == neighbor) continue;

                    // Horizontal check (Left-to-Right)
                    boolean sameRow = Math.abs(current.bounds.getY() - neighbor.bounds.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(current.bounds.getHeight() - neighbor.bounds.getHeight()) < sizeMatchTolerance;
                    if (sameRow && matchHeight) {
                        double distLeft = current.bounds.getX() - (neighbor.bounds.getX() + neighbor.bounds.getWidth());
                        double distRight = neighbor.bounds.getX() - (current.bounds.getX() + current.bounds.getWidth());
                        
                        if (Math.abs(distLeft) < 0.5f || Math.abs(distRight) < 0.5f) {
                            horizontalMatch = true;
                            // Track valid data cells to stretch into us
                            if (!neighbor.isEmpty) {
                                // Prefer the data cell to our left if flow goes left-to-right
                                if (neighbor.bounds.getX() < current.bounds.getX()) {
                                    targetPartner = neighbor;
                                } else if (targetPartner == null) {
                                    targetPartner = neighbor;
                                }
                            }
                        }
                    }

                    // Vertical check (Top-to-Bottom)
                    boolean sameCol = Math.abs(current.bounds.getX() - neighbor.bounds.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(current.bounds.getWidth() - neighbor.bounds.getWidth()) < sizeMatchTolerance;
                    if (sameCol && matchWidth) {
                        double distAbove = current.bounds.getY() - (neighbor.bounds.getY() + neighbor.bounds.getHeight());
                        double distBelow = neighbor.bounds.getY() - (current.bounds.getY() + current.bounds.getHeight());
                        
                        if (Math.abs(distAbove) < 0.5f || Math.abs(distBelow) < 0.5f) {
                            verticalMatch = true;
                            if (!neighbor.isEmpty) {
                                // Prefer the data cell directly above us
                                if (neighbor.bounds.getY() > current.bounds.getY()) {
                                    targetPartner = neighbor;
                                } else if (targetPartner == null) {
                                    targetPartner = neighbor;
                                }
                            }
                        }
                    }
                }

                // Strictly enforce structural flow types
                if (horizontalMatch && !verticalMatch) current.flow = TableFlow.LEFT_TO_RIGHT;
                else if (verticalMatch && !horizontalMatch) current.flow = TableFlow.TOP_TO_BOTTOM;
                else if (horizontalMatch && verticalMatch) current.flow = TableFlow.BIDIRECTIONAL;
                else current.flow = TableFlow.NOT_A_TABLE;

                current.adjacentDataCell = targetPartner;
            }

            // STITCH COMPILING OVERRIDES
            Map<VisualCell, Boolean> processedRegistry = new IdentityHashMap<>();
            for (VisualCell cell : tableCellsRegistry) {
                processedRegistry.put(cell, false);
            }

            for (VisualCell cell : tableCellsRegistry) {
                if (processedRegistry.get(cell)) continue;

                if (!cell.isEmpty) {
                    // Valid cell: Keep it natively as a container box shape
                    outputCleanBoxes.add(cell.bounds);
                    processedRegistry.put(cell, true);
                    continue;
                }

                if (VERBOSE_LOG) {
                    System.out.println(String.format("[FLOW EVAL] Empty Box discovered at X=%.3f, Y=%.3f | Flow: %s", 
                            cell.bounds.getX(), cell.bounds.getY(), cell.flow));
                }

                switch (cell.flow) {
                    case LEFT_TO_RIGHT:
                        if (cell.adjacentDataCell != null) {
                            VisualCell dataCell = cell.adjacentDataCell;
                            if (VERBOSE_LOG) {
                                System.out.println(String.format("  -> [STITCH ELEGANT] Extending valid cell X=%.3f width by %.3f to swallow empty artifact.", 
                                        dataCell.bounds.getX(), cell.bounds.getWidth()));
                            }
                            
                            // Stretch Valid Box's width to seamlessly encompass the empty box footprint
                            double newX = Math.min(dataCell.bounds.getX(), cell.bounds.getX());
                            double newWidth = dataCell.bounds.getWidth() + cell.bounds.getWidth();
                            dataCell.bounds.setRect(newX, dataCell.bounds.getY(), newWidth, dataCell.bounds.getHeight());
                            
                            // Prevent duplicate processing of the data cell since it's now updated in place
                            if (!outputCleanBoxes.contains(dataCell.bounds)) {
                                outputCleanBoxes.add(dataCell.bounds);
                            }
                        }
                        processedRegistry.put(cell, true); // Erases the empty box natively by not adding it to output
                        break;

                    case TOP_TO_BOTTOM:
                        if (cell.adjacentDataCell != null) {
                            VisualCell dataCell = cell.adjacentDataCell;
                            if (VERBOSE_LOG) {
                                System.out.println(String.format("  -> [STITCH ELEGANT] Extending valid cell Y=%.3f height by %.3f to swallow empty artifact.", 
                                        dataCell.bounds.getY(), cell.bounds.getHeight()));
                            }

                            // Stretch Valid Box's height to seamlessly encompass the empty box footprint
                            double newY = Math.min(dataCell.bounds.getY(), cell.bounds.getY());
                            double newHeight = dataCell.bounds.getHeight() + cell.bounds.getHeight();
                            dataCell.bounds.setRect(dataCell.bounds.getX(), newY, dataCell.bounds.getWidth(), newHeight);

                            if (!outputCleanBoxes.contains(dataCell.bounds)) {
                                outputCleanBoxes.add(dataCell.bounds);
                            }
                        }
                        processedRegistry.put(cell, true); // Erases the empty box natively by not adding it to output
                        break;

                    case BIDIRECTIONAL:
                        if (VERBOSE_LOG) System.out.println("  -> [RULE ACTION] Bidirectional Flow: Preserving box footprint.");
                        outputCleanBoxes.add(cell.bounds);
                        processedRegistry.put(cell, true);
                        break;

                    case NOT_A_TABLE:
                        if (VERBOSE_LOG) System.out.println("  -> [RULE ACTION] Isolated Non-Table Artifact: Deleting box footprint entirely.");
                        processedRegistry.put(cell, true); // Drops box entirely
                        break;
                }
            }

            // Route any decoupled pure lines safely to the final rendering pipeline
            outputCleanLines.addAll(pureStandaloneLines);
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
     * SCRIPT 1: RAW PATH PARSER
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
