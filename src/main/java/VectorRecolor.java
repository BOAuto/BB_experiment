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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            File outputFile = new File(outputDir, "perfect_stitch_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("GLOBAL CANVAS COMPILER ACTIVE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> GLOBAL ANALYSIS FOR PAGE %d <<<", i + 1));

                    LayoutCompiler compiler = new LayoutCompiler(page);
                    compiler.compileGlobalCanvas();
                    
                    List<Rectangle2D> renderBoxes = compiler.getFinalLayoutBoxes();
                    List<Rectangle2D> renderLines = compiler.getFinalLayoutLines();

                    System.out.println("[STAGE 2] Drawing optimized structural canvas...");
                    try (PDPageContentStream outputStream = new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        
                        outputStream.setStrokingColor(Color.GREEN);
                        outputStream.setLineWidth(1.0f);
                        int totalPainted = 0;

                        for (Rectangle2D box : renderBoxes) {
                            totalPainted++;
                            outputStream.addRect((float)box.getX(), (float)box.getY(), (float)box.getWidth(), (float)box.getHeight());
                            outputStream.stroke();
                        }

                        for (Rectangle2D line : renderLines) {
                            totalPainted++;
                            outputStream.addRect((float)line.getX(), (float)line.getY(), (float)line.getWidth(), (float)line.getHeight());
                            outputStream.stroke();
                        }
                        
                        System.out.println(String.format("  -> Render complete. Total unique structural paths painted: %d", totalPainted));
                    }
                }

                System.out.println("[FINALIZE] Saving file...");
                document.save(outputFile);
                System.out.println("[SUCCESS] Processing finished: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[FATAL SYSTEM CRASH] " + e.getMessage());
            }
        }
    }

    private static class LayoutCompiler {
        private final PDPage page;
        private final float pageHeight;
        private final List<Rectangle2D> pureLines = new ArrayList<>();
        private final List<VisualCell> detectedCells = new ArrayList<>();
        
        private final List<Rectangle2D> finalCanvasBoxes = new ArrayList<>();
        private final List<Rectangle2D> finalCanvasLines = new ArrayList<>();

        public LayoutCompiler(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getFinalLayoutBoxes() { return finalCanvasBoxes; }
        public List<Rectangle2D> getFinalLayoutLines() { return finalCanvasLines; }

        public void compileGlobalCanvas() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawObjects = scout.getDetectedBoxes();

            for (Rectangle2D obj : rawObjects) {
                if (obj.getWidth() > 2.0 && obj.getHeight() > 2.0) {
                    detectedCells.add(new VisualCell(obj));
                } else {
                    pureLines.add(obj);
                }
            }

            if (detectedCells.isEmpty()) {
                finalCanvasLines.addAll(pureLines);
                return;
            }

            // Text Extractor Audit
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            for (int c = 0; c < detectedCells.size(); c++) {
                Rectangle2D r = detectedCells.get(c).bounds;
                float awtY = pageHeight - (float)r.getY() - (float)r.getHeight();
                stripper.addRegion("c_" + c, new Rectangle2D.Float(
                        (float)r.getX() + 0.5f, awtY + 0.5f, (float)r.getWidth() - 1.0f, (float)r.getHeight() - 1.0f));
            }
            stripper.extractRegions(page);

            for (int c = 0; c < detectedCells.size(); c++) {
                VisualCell cell = detectedCells.get(c);
                cell.isEmpty = stripper.getTextForRegion("c_" + c).trim().isEmpty();
            }

            // Structural Neighborhood Scan Matrix
            float alignmentTolerance = 1.0f;
            float sizeMatchTolerance = 1.0f;

            for (VisualCell current : detectedCells) {
                boolean horizontalMatch = false;
                boolean verticalMatch = false;
                VisualCell partner = null;

                for (VisualCell neighbor : detectedCells) {
                    if (current == neighbor) continue;

                    // Row Scan (Left-to-Right)
                    boolean sameRow = Math.abs(current.bounds.getY() - neighbor.bounds.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(current.bounds.getHeight() - neighbor.bounds.getHeight()) < sizeMatchTolerance;
                    if (sameRow && matchHeight) {
                        double distLeft = current.bounds.getX() - (neighbor.bounds.getX() + neighbor.bounds.getWidth());
                        double distRight = neighbor.bounds.getX() - (current.bounds.getX() + current.bounds.getWidth());
                        
                        if (Math.abs(distLeft) < 0.5f || Math.abs(distRight) < 0.5f) {
                            horizontalMatch = true;
                            if (!neighbor.isEmpty) {
                                if (neighbor.bounds.getX() < current.bounds.getX()) {
                                    partner = neighbor;
                                } else if (partner == null) {
                                    partner = neighbor;
                                }
                            }
                        }
                    }

                    // Column Scan (Top-to-Bottom)
                    boolean sameCol = Math.abs(current.bounds.getX() - neighbor.bounds.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(current.bounds.getWidth() - neighbor.bounds.getWidth()) < sizeMatchTolerance;
                    if (sameCol && matchWidth) {
                        double distAbove = current.bounds.getY() - (neighbor.bounds.getY() + neighbor.bounds.getHeight());
                        double distBelow = neighbor.bounds.getY() - (current.bounds.getY() + current.bounds.getHeight());
                        
                        if (Math.abs(distAbove) < 0.5f || Math.abs(distBelow) < 0.5f) {
                            verticalMatch = true;
                            if (!neighbor.isEmpty) {
                                if (neighbor.bounds.getY() > current.bounds.getY()) {
                                    partner = neighbor;
                                } else if (partner == null) {
                                    partner = neighbor;
                                }
                            }
                        }
                    }
                }

                if (horizontalMatch && !verticalMatch) current.flow = TableFlow.LEFT_TO_RIGHT;
                else if (verticalMatch && !horizontalMatch) current.flow = TableFlow.TOP_TO_BOTTOM;
                else if (horizontalMatch && verticalMatch) current.flow = TableFlow.BIDIRECTIONAL;
                else current.flow = TableFlow.NOT_A_TABLE;

                current.adjacentDataCell = partner;
            }

            // ==========================================================================
            // GLOBAL CANVAS RESOLUTION MATRIX
            // ==========================================================================
            Set<Rectangle2D> blacklistedArtifactBounds = new HashSet<>();
            List<Rectangle2D> compiledStretchedBoxes = new ArrayList<>();

            for (VisualCell cell : detectedCells) {
                if (!cell.isEmpty) continue; // Handle data cells globally below

                // Mark coordinates of empty cells for total deletion pass
                blacklistedArtifactBounds.add(cell.bounds);

                if (VERBOSE_LOG) {
                    System.out.println(String.format("[FLOW EVAL] Target Locked at X=%.3f, Y=%.3f | Flow: %s", 
                            cell.bounds.getX(), cell.bounds.getY(), cell.flow));
                }

                if (cell.flow == TableFlow.LEFT_TO_RIGHT && cell.adjacentDataCell != null) {
                    VisualCell dataCell = cell.adjacentDataCell;
                    double newX = Math.min(dataCell.bounds.getX(), cell.bounds.getX());
                    double newWidth = dataCell.bounds.getWidth() + cell.bounds.getWidth();
                    
                    Rectangle2D stretchedBlueprint = new Rectangle2D.Double(
                        newX, dataCell.bounds.getY(), newWidth, dataCell.bounds.getHeight()
                    );
                    compiledStretchedBoxes.add(stretchedBlueprint);
                    blacklistedArtifactBounds.add(dataCell.bounds); // Blacklist old un-stretched shape size
                    
                    if (VERBOSE_LOG) {
                        System.out.println(String.format("  -> [GLOBAL STITCH] Queued Horizontal Stretch: New Width = %.3f", newWidth));
                    }
                } 
                else if (cell.flow == TableFlow.TOP_TO_BOTTOM && cell.adjacentDataCell != null) {
                    VisualCell dataCell = cell.adjacentDataCell;
                    double newY = Math.min(dataCell.bounds.getY(), cell.bounds.getY());
                    double newHeight = dataCell.bounds.getHeight() + cell.bounds.getHeight();

                    Rectangle2D stretchedBlueprint = new Rectangle2D.Double(
                        dataCell.bounds.getX(), newY, dataCell.bounds.getWidth(), newHeight
                    );
                    compiledStretchedBoxes.add(stretchedBlueprint);
                    blacklistedArtifactBounds.add(dataCell.bounds); // Blacklist old un-stretched shape size
                    
                    if (VERBOSE_LOG) {
                        System.out.println(String.format("  -> [GLOBAL STITCH] Queued Vertical Stretch: New Height = %.3f", newHeight));
                    }
                }
            }

            // Final Filter Pass: Rebuild canvas lists by crushing all matching artifact fragments
            for (VisualCell cell : detectedCells) {
                boolean isMatchesBlacklist = false;
                for (Rectangle2D badBounds : blacklistedArtifactBounds) {
                    boolean xMatch = Math.abs(cell.bounds.getX() - badBounds.getX()) < 0.5f;
                    boolean yMatch = Math.abs(cell.bounds.getY() - badBounds.getY()) < 0.5f;
                    if (xMatch && yMatch) {
                        isMatchesBlacklist = true;
                        break;
                    }
                }
                
                // If the container box isn't a stale duplicate or artifact, it's safe to draw natively
                if (!isMatchesBlacklist) {
                    finalCanvasBoxes.add(cell.bounds);
                } else if (VERBOSE_LOG && cell.isEmpty) {
                    System.out.println(String.format("  -> [DESTRUCTION SUCCESS] Dropped reoccurrence fragment at X=%.3f, Y=%.3f", 
                            cell.bounds.getX(), cell.bounds.getY()));
                }
            }

            // Append all new single-pass stitched layouts safely
            finalCanvasBoxes.addAll(compiledStretchedBoxes);
            finalCanvasLines.addAll(pureLines);
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
