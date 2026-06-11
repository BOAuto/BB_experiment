package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

        if (files == null || files.length == 0) {
            System.out.println("No PDF files found in 'pdfs/' directory.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "normalized_" + inputFile.getName());
            System.out.println("\n------------------------------------------------");
            System.out.println("Processing File: " + inputFile.getName());
            System.out.println("------------------------------------------------");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    
                    // Pass 1: Extract layout cell coordinates cleanly
                    GeometryScanner scanner = new GeometryScanner(page);
                    scanner.processPage(page);
                    List<Rectangle2D> rawBoxes = scanner.getDetectedBoxes();

                    List<VisualBox> visualBoxes = new ArrayList<>();
                    for (Rectangle2D rb : rawBoxes) {
                        if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                            visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                        }
                    }

                    List<Rectangle2D.Float> linesToKill = new ArrayList<>();

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

                        int targetedEmptyCount = 0;
                        for (int b = 0; b < visualBoxes.size(); b++) {
                            VisualBox box = visualBoxes.get(b);
                            String contentText = stripper.getTextForRegion("box_" + b).trim();
                            
                            boolean isPureTextEmpty = contentText.isEmpty();
                            boolean isStructuralGapColumn = (box.bounds.width > 3.0f && box.bounds.width < 22.0f);

                            if (isPureTextEmpty || isStructuralGapColumn) {
                                box.isEmpty = true;
                                targetedEmptyCount++;
                            }
                        }

                        System.out.println(String.format("\n--- Line Interception Trace for Page %d ---", i + 1));
                        NormalizationMetrics metrics = identifyTargetLines(visualBoxes, linesToKill);

                        // Pass 2: Low-Level Operator Token Stream Mutator (Completely avoids invalid super class issues)
                        pruneStreamTokensAtCoordinates(page, linesToKill);

                        System.out.println(String.format("\nPage %d Analysis Metrics Report:", i + 1));
                        System.out.println(String.format("  -> Exact Visual Boxes Tracked: %d", visualBoxes.size()));
                        System.out.println(String.format("  -> Normalized Target Boxes Identified: %d", targetedEmptyCount));
                        System.out.println(String.format("  -> Left-to-Right Flow Normalization (Surgically Erased Lines): %d", metrics.leftToRightCount));
                        System.out.println(String.format("  -> Total Grid Lines Safely Removed: %d", metrics.totalLinesRemoved));
                    } else {
                        System.out.println(String.format("Page %d: No drawn outline boxes were recorded.", i + 1));
                    }
                }

                document.save(outputFile);
                System.out.println(" -> Successfully saved output to: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
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

    private static class NormalizationMetrics {
        int leftToRightCount = 0;
        int totalLinesRemoved = 0;
    }

    private static NormalizationMetrics identifyTargetLines(List<VisualBox> boxes, List<Rectangle2D.Float> killList) {
        NormalizationMetrics stats = new NormalizationMetrics();
        
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            if (!target.isEmpty) continue; 

            boolean immediateRowRepeat = false;

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

            if (immediateRowRepeat) {
                // Precision vector exclusion bounding window matching only the target left vertical line frame
                killList.add(new Rectangle2D.Float(target.bounds.x - 1.0f, target.bounds.y - 1.0f, 2.0f, target.bounds.height + 2.0f));
                
                stats.leftToRightCount++;
                stats.totalLinesRemoved++;
                System.out.println(String.format(" Target Match -> Suppressing Left Line boundary for Box #%d [X=%.1f, Y=%.1f]", i, target.bounds.x, target.bounds.y));
            }
        }
        return stats;
    }

    // --- Token Manipulation Token Stream Mutator Implementation ---
    private static void pruneStreamTokensAtCoordinates(PDPage page, List<Rectangle2D.Float> targetMasks) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page);
        List<Object> finalTokens = new ArrayList<>();
        
        List<COSBase> arguments = new ArrayList<>();
        Double lastCursorX = null;
        Double lastCursorY = null;

        Object token;
        while ((token = parser.parseNextToken()) != null) {
            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String opName = op.getName();

                // Intercept and manipulate drawing subpaths dynamically
                if (opName.equals("m") && arguments.size() >= 2) { // moveTo
                    if (arguments.get(0) instanceof COSNumber && arguments.get(1) instanceof COSNumber) {
                        lastCursorX = ((COSNumber) arguments.get(0)).doubleValue();
                        lastCursorY = ((COSNumber) arguments.get(1)).doubleValue();
                    }
                } else if (opName.equals("l") && arguments.size() >= 2) { // lineTo
                    if (arguments.get(0) instanceof COSNumber && arguments.get(1) instanceof COSNumber && lastCursorX != null && lastCursorY != null) {
                        double targetX = ((COSNumber) arguments.get(0)).doubleValue();
                        double targetY = ((COSNumber) arguments.get(1)).doubleValue();
                        
                        double minX = Math.min(lastCursorX, targetX);
                        double minY = Math.min(lastCursorY, targetY);
                        double w = Math.max(Math.abs(targetX - lastCursorX), 0.5);
                        double h = Math.max(Math.abs(targetY - lastCursorY), 0.5);
                        Rectangle2D.Float currentSegment = new Rectangle2D.Float((float)minX, (float)minY, (float)w, (float)h);

                        boolean hitTargetLine = false;
                        for (Rectangle2D.Float mask : targetMasks) {
                            if (mask.intersects(currentSegment)) {
                                hitTargetLine = true;
                                break;
                            }
                        }

                        if (hitTargetLine) {
                            // Mutation: Change lineTo operator ('l') to moveTo operator ('m') to safely preserve path chains
                            op = Operator.getOperator("m");
                        }
                        lastCursorX = targetX;
                        lastCursorY = targetY;
                    }
                }

                // Write arguments followed by the updated operator
                finalTokens.addAll(arguments);
                finalTokens.add(op);
                arguments.clear();
            } else {
                arguments.add((COSBase) token);
            }
        }
        if (!arguments.isEmpty()) {
            finalTokens.addAll(arguments);
        }

        // Flush modified layout tokens back into the page stream context
        try (OutputStream os = page.getContents().createOutputStream()) {
            org.apache.pdfbox.pdfwriter.ContentStreamWriter writer = new org.apache.pdfbox.pdfwriter.ContentStreamWriter(os);
            writer.writeTokens(finalTokens);
        }
    }

    // --- Clean geometry scanner tracking coordinate boxes correctly ---
    private static class GeometryScanner extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private Double minX, minY, maxX, maxY;

        protected GeometryScanner(PDPage page) { super(page); }
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
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            updateBounds(x1, y1); updateBounds(x3, y3);
        }
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
