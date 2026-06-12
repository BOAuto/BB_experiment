package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
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

    // --- PIPELINE CONTROL FLAGS ---
    private static final boolean REMOVE_LINES_MODE = true; // Set to true to execute line-stripping
    private static final boolean SAVE_DRAWINGS_ONLY = true;

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
                
                if (REMOVE_LINES_MODE) {
                    System.out.println("Line removal mode is TRUE. Executing tight in-memory layout analysis...");
                    
                    for (int i = 0; i < document.getNumberOfPages(); i++) {
                        PDPage page = document.getPage(i);
                        float pageHeight = page.getMediaBox().getHeight();
                        
                        // ==========================================
                        // PHASE 1: IN-MEMORY ANALYSIS
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

                            // Run normalization to flag drawing toggles in memory
                            applyChainLinkedNormalization(visualBoxes);

                            // Compile strict target segments to omit from the content stream
                            List<LineSegment> linesToRemove = new ArrayList<>();
                            for (VisualBox box : visualBoxes) {
                                if (!box.drawLeft)   linesToRemove.add(new LineSegment(box.bounds.x, box.bounds.y, box.bounds.x, box.bounds.y + box.bounds.height));
                                if (!box.drawTop)    linesToRemove.add(new LineSegment(box.bounds.x, box.bounds.y + box.bounds.height, box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height));
                                if (!box.drawRight)  linesToRemove.add(new LineSegment(box.bounds.x + box.bounds.width, box.bounds.y, box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height));
                                if (!box.drawBottom) linesToRemove.add(new LineSegment(box.bounds.x, box.bounds.y, box.bounds.x + box.bounds.width, box.bounds.y));
                            }

                            // ==========================================
                            // PHASE 2: TARGETED PATH INTERCEPTION
                            // ==========================================
                            if (!linesToRemove.isEmpty()) {
                                removeLinesFromPage(document, page, linesToRemove);
                            }
                        }
                    }
                } else {
                    System.out.println("Line removal mode is FALSE. Skipping layout analysis, keeping all original lines intact.");
                }

                if (SAVE_DRAWINGS_ONLY) {
                    document.save(outputFile);
                    System.out.println(" -> Successfully saved output to: " + outputFile.getAbsolutePath());
                }

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Intercepts and parses the page stream to drop vector segments matching 
     * our memory-mapped exclusion lists.
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

                // Pattern 1: Track progressive explicit linear pathing
                if (opName.equals("m") || opName.equals("l")) { 
                    int sz = currentPathSubTokens.size();
                    float x = ((Number) currentPathSubTokens.get(sz - 3)).floatValue();
                    float y = ((Number) currentPathSubTokens.get(sz - 2)).floatValue();

                    if (opName.equals("l")) {
                        for (LineSegment target : targets) {
                            if (target.matchesSegment(currentX, currentY, x, y)) {
                                skipCurrentPathSegment = true; 
                                break;
                            }
                        }
                    }
                    currentX = x;
                    currentY = y;
                } 
                // Pattern 2: Intercept atomic rectangle commands
                else if (opName.equals("re")) {
                    int sz = currentPathSubTokens.size();
                    float rx = ((Number) currentPathSubTokens.get(sz - 5)).floatValue();
                    float ry = ((Number) currentPathSubTokens.get(sz - 4)).floatValue();
                    float rw = ((Number) currentPathSubTokens.get(sz - 3)).floatValue();
                    float rh = ((Number) currentPathSubTokens.get(sz - 2)).floatValue();

                    for (LineSegment target : targets) {
                        if (target.matchesSegment(rx, ry, rx, ry + rh)) {
                            skipCurrentPathSegment = true;
                            break;
                        }
                    }
                }
                
                // Path Termination: Decide if we filter or write the collected path operation group
                if (opName.equals("S") || opName.equals("s") || opName.equals("f") || 
                    opName.equals("B") || opName.equals("b") || opName.equals("n")) {
                    
                    if (!skipCurrentPathSegment) {
                        filteredTokens.addAll(currentPathSubTokens);
                    } else {
                        System.out.println(String.format("[REMOVED] Successfully stripped line drawing operator sequence ending in '%s'", opName));
                        currentPathSubTokens.clear();
                        filteredTokens.add(Operator.getOperator("n")); // Output safe path termination token
                    }
                    currentPathSubTokens.clear();
                    skipCurrentPathSegment = false;
                } 
                // Safe Graphic Wrappers: Instantly pass state adjustments out to protect text regions
                else if (opName.equals("cm") || opName.equals("Q") || opName.equals("q") || opName.equals("BT") || opName.equals("ET")) {
                    filteredTokens.addAll(currentPathSubTokens);
                    currentPathSubTokens.clear();
                }
            }
        }
        
        if (!currentPathSubTokens.isEmpty()) {
            filteredTokens.addAll(currentPathSubTokens);
        }

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
            // Standardize orientation to make lookup foolproof
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
        }

        boolean matchesSegment(float sx1, float sy1, float sx2, float sy2) {
            float msx1 = Math.min(sx1, sx2);
            float msy1 = Math.min(sy1, sy2);
            float msx2 = Math.max(sx1, sx2);
            float msy2 = Math.max(sy1, sy2);

            float tolerance = 0.5f; 
            
            return (Math.abs(this.x1 - msx1) < tolerance && 
                    Math.abs(this.y1 - msy1) < tolerance && 
                    Math.abs(this.x2 - msx2) < tolerance && 
                    Math.abs(this.y2 - msy2) < tolerance);
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
