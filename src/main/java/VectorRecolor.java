package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.text.PDFTextStripperByArea;
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
                    
                    // ==========================================
                    // PHASE 1: IN-MEMORY ANALYSIS (No File Changes)
                    // ==========================================
                    DrawingBoxEngine engine = new DrawingBoxEngine(page);
                    engine.processPage(page);
                    List<Rectangle2D> rawBoxes = engine.getDetectedBoxes();

                    List<VisualBox> visualBoxes = new ArrayList<>();
                    for (Rectangle2D rb : rawBoxes) {
                        if (rb.getWidth() > 2.0 && rb.getHeight() > 4.0) {
                            visualBoxes.add(new VisualBox((float)rb.getX(), (float)rb.getY(), (float)rb.getWidth(), (float)rb.getHeight()));
                        }
                    }

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

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            VisualBox box = visualBoxes.get(b);
                            String contentText = stripper.getTextForRegion("box_" + b).trim();
                            
                            boolean isPureTextEmpty = contentText.isEmpty();
                            boolean isStructuralGapColumn = (box.bounds.width > 3.0f && box.bounds.width < 22.0f);

                            if (isPureTextEmpty || isStructuralGapColumn) {
                                box.isEmpty = true;
                            }
                        }

                        // Run normalization to flag exactly which lines should be removed in memory
                        applyChainLinkedNormalization(visualBoxes);

                        // Compile a list of dead line segments we want to remove from the file
                        List<LineSegment> linesToRemove = new ArrayList<>();
                        for (VisualBox box : visualBoxes) {
                            if (!box.drawLeft)   linesToRemove.add(new LineSegment(box.bounds.x, box.bounds.y, box.bounds.x, box.bounds.y + box.bounds.height));
                            if (!box.drawTop)    linesToRemove.add(new LineSegment(box.bounds.x, box.bounds.y + box.bounds.height, box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height));
                            if (!box.drawRight)  linesToRemove.add(new LineSegment(box.bounds.x + box.bounds.width, box.bounds.y, box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height));
                            if (!box.drawBottom) linesToRemove.add(new LineSegment(box.bounds.x, box.bounds.y, box.bounds.x + box.bounds.width, box.bounds.y));
                        }

                        // ==========================================
                        // PHASE 2: TARGETED REMOVAL FROM ORIGINAL STREAM
                        // ==========================================
                        if (!linesToRemove.isEmpty()) {
                            removeLinesFromPage(document, page, linesToRemove);
                        }
                    }
                }

                document.save(outputFile);
                System.out.println(" -> Successfully saved modified output to: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Deep-scans the low-level content tokens of the original PDF page.
     * If a drawing path operator matches our memory-mapped removal segments, it gets deleted.
     */
    private static void removeLinesFromPage(PDDocument doc, PDPage page, List<LineSegment> targets) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page);
        List<Object> tokens = parser.parse();
        List<Object> filteredTokens = new ArrayList<>();

        float currentX = 0, currentY = 0;
        List<Object> currentPathSubTokens = new ArrayList<>();
        boolean skipCurrentPathSegment = false;

        for (Object token : tokens) {
            currentPathSubTokens.add(token);

            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String opName = op.getName();

                // Track coordinates from structural vector tokens
                if (opName.equals("m") || opName.equals("l")) { // moveTo (m) or lineTo (l)
                    float x = ((Number) currentPathSubTokens.get(currentPathSubTokens.size() - 3)).floatValue();
                    float y = ((Number) currentPathSubTokens.get(currentPathSubTokens.size() - 2)).floatValue();

                    if (opName.equals("l")) {
                        // Check if this explicit line segment matches an in-memory target marked for deletion
                        for (LineSegment target : targets) {
                            if (target.matches(currentX, currentY, x, y)) {
                                skipCurrentPathSegment = true; 
                                break;
                            }
                        }
                    }
                    currentX = x;
                    currentY = y;
                } 
                
                // Clear state when the path segment draws or ends
                if (opName.equals("S") || opName.equals("s") || opName.equals("f") || opName.equals("B") || opName.equals("b") || opName.equals("n")) {
                    if (!skipCurrentPathSegment) {
                        filteredTokens.addAll(currentPathSubTokens);
                    } else {
                        // Strip out the stroke/fill operator to kill the line, but append a safe path termination token
                        currentPathSubTokens.clear();
                        PDFStreamParser endParser = new PDFStreamParser(new PDStream(doc));
                        filteredTokens.add(Operator.getOperator("n")); // New path / No-op termination
                    }
                    currentPathSubTokens.clear();
                    skipCurrentPathSegment = false;
                } else if (opName.equals("cm") || opName.equals("Q") || opName.equals("q") || opName.equals("BT") || opName.equals("ET")) {
                    // Always allow text matrices and graphic states to pass cleanly
                    filteredTokens.addAll(currentPathSubTokens);
                    currentPathSubTokens.clear();
                }
            }
        }
        
        if (!currentPathSubTokens.isEmpty()) {
            filteredTokens.addAll(currentPathSubTokens);
        }

        // Flush the clean stream back to the original page architecture
        PDStream updatedStream = new PDStream(doc);
        try (OutputStream os = updatedStream.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(os);
            writer.writeTokens(filteredTokens);
        }
        page.setContents(updatedStream);
    }

    private static class VisualBox {
        Rectangle2D.Float bounds;
        boolean isEmpty = false;
        boolean drawLeft = true;
        boolean drawTop = true;
        boolean drawRight = true;
        boolean drawBottom = true;

        VisualBox(float x, float y, float w, float h) {
            this.bounds = new Rectangle2D.Float(x, y, w, h);
        }
    }

    private static class LineSegment {
        float x1, y1, x2, y2;
        LineSegment(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
        }

        // Tolerance matcher to evaluate variations between engine math and low-level streams
        boolean matches(float sx1, float sy1, float sx2, float sy2) {
            float tolerance = 1.5f;
            return (Math.abs(x1 - sx1) < tolerance && Math.abs(y1 - sy1) < tolerance && Math.abs(x2 - sx2) < tolerance && Math.abs(y2 - sy2) < tolerance) ||
                   (Math.abs(x1 - sx2) < tolerance && Math.abs(y1 - sy2) < tolerance && Math.abs(x2 - sx1) < tolerance && Math.abs(y2 - sy1) < tolerance);
        }
    }

    private static void applyChainLinkedNormalization(List<VisualBox> boxes) {
        float alignmentTolerance = 5.0f;  
        float sizeMatchTolerance = 2.0f;  
        float gapSearchLimit = 15.0f;     

        for (int i = 0; i < boxes.size(); i++) {
            VisualBox target = boxes.get(i);
            if (!target.isEmpty) continue; 

            boolean immediateRowRepeat = false;
            boolean immediateColRepeat = false;

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

            // Flags flip false to signal that the line should be removed during the filter pass
            if (immediateRowRepeat && !immediateColRepeat) {
                target.drawTop = false;
                target.drawRight = false;
                target.drawBottom = false;
            } else if (immediateColRepeat && !immediateRowRepeat) {
                target.drawLeft = false;
                target.drawRight = false;
                target.drawBottom = false;
            } else if (immediateRowRepeat && immediateColRepeat) {
                if (target.bounds.width < 22.0f) {
                    target.drawTop = false;
                    target.drawRight = false;
                    target.drawBottom = false;
                }
            }
        }
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
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            updateBounds(x1, y1);
            updateBounds(x3, y3);
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
