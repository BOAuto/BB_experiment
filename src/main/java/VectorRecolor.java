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

    private static final boolean DEBUG_MODE = true;

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
            File outputFile = new File(outputDir, "tuned_stitched_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("TUNED COUPLING ENGINE ACTIVE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> PROCESSING PAGE %d OF %d <<<", i + 1, totalPages));

                    // STAGE 1: Topology Analysis using identity references
                    LayoutCompiler compiler = new LayoutCompiler(page);
                    compiler.executeTopologyAnalysis();
                    
                    List<Rectangle2D> finalCleanBoxes = compiler.getFinalLayoutBoxes();
                    List<Rectangle2D> finalCleanLines = compiler.getFinalLayoutLines();

                    // STAGE 2: Precision Render Loop (Keeps unique structural counts minimal)
                    System.out.println("[STAGE 2] Committing optimized layout paths to canvas...");
                    try (PDPageContentStream outputStream = new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        
                        outputStream.setStrokingColor(Color.GREEN);
                        outputStream.setLineWidth(1.0f);
                        int paintCount = 0;

                        // 1. Draw Legitimate, Stretched Closed Table Cells
                        for (Rectangle2D box : finalCleanBoxes) {
                            paintCount++;
                            outputStream.addRect((float)box.getX(), (float)box.getY(), (float)box.getWidth(), (float)box.getHeight());
                            outputStream.stroke();
                        }

                        // 2. Draw Standalone Structural Lines Natively
                        for (Rectangle2D line : finalCleanLines) {
                            paintCount++;
                            outputStream.addRect((float)line.getX(), (float)line.getY(), (float)line.getWidth(), (float)line.getHeight());
                            outputStream.stroke();
                        }
                        
                        System.out.println(String.format("  -> Render complete. Total unique structural paths painted: %d", paintCount));
                    }
                }

                System.out.println("[FINALIZE] Saving PDF...");
                document.save(outputFile);
                System.out.println("[SUCCESS] Processing completed. Output available at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[FATAL SYSTEM ERROR] Pipeline aborted: " + e.getMessage());
            }
        }
    }

    /**
     * TOPOLOGY MATRIX MACHINE
     */
    private static class LayoutCompiler {
        private final PDPage page;
        private final float pageHeight;
        
        // Preserve your original structures exactly
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

        public void executeTopologyAnalysis() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();

            // Clear Identity Separation upfront
            for (Rectangle2D shape : rawScoutedVectors) {
                exclusionRegistry.put(shape, false); 
                if (shape.getWidth() > 2.0 && shape.getHeight() > 2.0) {
                    targetBoxesToAudit.add(new VisualCell(shape)); 
                } else {
                    pureStandaloneLines.add(shape);
                }
            }

            if (targetBoxesToAudit.isEmpty()) {
                finalLayoutLines.addAll(pureStandaloneLines);
                return;
            }

            // Run Text Audit over current page frame context
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);

            for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                Rectangle2D rawBounds = targetBoxesToAudit.get(b).bounds;
                float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                stripper.addRegion("reg_" + b, new Rectangle2D.Float(
                        (float)rawBounds.getX() + 0.5f, awtY + 0.5f, (float)rawBounds.getWidth() - 1.0f, (float)rawBounds.getHeight() - 1.0f));
            }
            stripper.extractRegions(page);

            for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                VisualCell cell = targetBoxesToAudit.get(b);
                String extractedText = stripper.getTextForRegion("reg_" + b).trim();
                
                boolean textIsEmpty = extractedText.isEmpty();
                boolean isStructuralGapColumn = (cell.bounds.getWidth() > 3.0 && cell.bounds.getWidth() < 22.0);

                if (textIsEmpty || isStructuralGapColumn) {
                    cell.isEmptyArea = true;
                }
            }

            // Static Neighborhood Flow Identification Pass
            float alignmentTolerance = 1.0f;  
            float sizeMatchTolerance = 1.0f;  
            float gapSearchLimit = 0.5f; 

            for (VisualCell current : targetBoxesToAudit) {
                if (!current.isEmptyArea) continue;

                boolean immediateRowRepeat = false;
                boolean immediateColRepeat = false;
                VisualCell targetPartner = null;

                // Scan Horizontal Flow Neighborhood
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

                // Scan Vertical Flow Neighborhood
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

                // Map flow constraints accurately using asymmetric mapping
                if (immediateRowRepeat && !immediateColRepeat) {
                    current.flow = TableFlow.LEFT_TO_RIGHT;
                    current.adjacentDataCell = targetPartner;
                } else if (immediateColRepeat && !immediateRowRepeat) {
                    current.flow = TableFlow.TOP_TO_BOTTOM;
                    current.adjacentDataCell = targetPartner;
                } else if (immediateRowRepeat && immediateColRepeat) {
                    current.flow = TableFlow.BIDIRECTIONAL;
                }
            }

            // Target-Specific Stitch Processing Pass
            int occuranceCounter = 0;
            for (VisualCell cell : targetBoxesToAudit) {
                if (!cell.isEmptyArea) {
                    if (!finalLayoutBoxes.contains(cell.bounds)) {
                        finalLayoutBoxes.add(cell.bounds);
                    }
                    continue;
                }

                occuranceCounter++;
                if (DEBUG_MODE) {
                    System.out.println(String.format("  [TARGETED DETECTION SUCCESS] Found box instance %d at -> X=%.3f, Y=%.3f | Flow Type: %s", 
                            occuranceCounter, cell.bounds.getX(), cell.bounds.getY(), cell.flow));
                }

                // Process the structural box using our non-destructive expansion mechanism
                switch (cell.flow) {
                    case LEFT_TO_RIGHT:
                        if (cell.adjacentDataCell != null) {
                            VisualCell dataCell = cell.adjacentDataCell;
                            if (DEBUG_MODE) {
                                System.out.println(String.format("    -> [STITCH MACHINE] Extending valid cell X=%.3f width by %.3f", 
                                        dataCell.bounds.getX(), cell.bounds.getWidth()));
                            }
                            double newX = Math.min(dataCell.bounds.getX(), cell.bounds.getX());
                            double newWidth = dataCell.bounds.getWidth() + cell.bounds.getWidth();
                            dataCell.bounds.setRect(newX, dataCell.bounds.getY(), newWidth, dataCell.bounds.getHeight());
                            
                            if (!finalLayoutBoxes.contains(dataCell.bounds)) {
                                finalLayoutBoxes.add(dataCell.bounds);
                            }
                        }
                        break;

                    case TOP_TO_BOTTOM:
                        if (cell.adjacentDataCell != null) {
                            VisualCell dataCell = cell.adjacentDataCell;
                            if (DEBUG_MODE) {
                                System.out.println(String.format("    -> [STITCH MACHINE] Extending valid cell Y=%.3f height by %.3f", 
                                        dataCell.bounds.getY(), cell.bounds.getHeight()));
                            }
                            double newY = Math.min(dataCell.bounds.getY(), cell.bounds.getY());
                            double newHeight = dataCell.bounds.getHeight() + cell.bounds.getHeight();
                            dataCell.bounds.setRect(dataCell.bounds.getX(), newY, dataCell.bounds.getWidth(), newHeight);

                            if (!finalLayoutBoxes.contains(dataCell.bounds)) {
                                finalLayoutBoxes.add(dataCell.bounds);
                            }
                        }
                        break;

                    case BIDIRECTIONAL:
                        if (!finalLayoutBoxes.contains(cell.bounds)) {
                            finalLayoutBoxes.add(cell.bounds);
                        }
                        break;

                    case NOT_A_TABLE:
                        // Drops empty isolated containers cleanly
                        break;
                }
            }

            finalLayoutLines.addAll(pureStandaloneLines);
        }
    }

    private enum TableFlow { LEFT_TO_RIGHT, TOP_TO_BOTTOM, BIDIRECTIONAL, NOT_A_TABLE }

    private static class VisualCell {
        final Rectangle2D bounds;
        boolean isEmptyArea = false;
        TableFlow flow = TableFlow.NOT_A_TABLE;
        VisualCell adjacentDataCell = null;
        
        VisualCell(Rectangle2D bounds) { 
            this.bounds = bounds; 
        }
    }

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
