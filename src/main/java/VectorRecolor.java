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
            File outputFile = new File(outputDir, "drawings_and_normalized_" + inputFile.getName());
            
            System.out.println("\n==========================================================================");
            System.out.println("COORDINATE INTERCEPTOR ACTIVE FOR FILE: " + inputFile.getName());
            System.out.println("==========================================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                int totalPages = document.getNumberOfPages();

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    System.out.println(String.format("\n>>> PROCESSING PAGE %d OF %d <<<", i + 1, totalPages));

                    // STAGE 1: Gather approved paths and identify blacklisted coordinate zones
                    Script2Analyst analyst = new Script2Analyst(page);
                    List<Rectangle2D> linesToKeep = analyst.generateApprovedSnapshot();
                    List<Rectangle2D> strictBannedZones = analyst.getBannedCoordinateZones();

                    // STAGE 2: Render phase with an unbypassable spatial block filter
                    System.out.println(String.format("[STAGE 2] Running rendering loop. Filtering paths against explicit coordinates..."));
                    if (!linesToKeep.isEmpty()) {
                        try (PDPageContentStream outputStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            outputStream.setStrokingColor(Color.GREEN);
                            outputStream.setLineWidth(1.0f);

                            float coordinateSnappingWindow = 1.5f; // Hard alignment tolerance window
                            int paintCount = 0;

                            for (Rectangle2D cleanLine : linesToKeep) {
                                boolean coordinateMatchFound = false;

                                // Evaluate this exact line coordinate against our target blacklist
                                for (Rectangle2D bannedZone : strictBannedZones) {
                                    
                                    // 1. Does the X position align precisely with the artifact column?
                                    boolean xMatches = Math.abs(cleanLine.getX() - bannedZone.getX()) < coordinateSnappingWindow;
                                    
                                    // 2. Does it fall within the vertical span of that artifact area?
                                    boolean insideVerticalBounds = cleanLine.getY() >= (bannedZone.getY() - coordinateSnappingWindow) &&
                                                                   cleanLine.getY() <= (bannedZone.getY() + bannedZone.getHeight() + coordinateSnappingWindow);

                                    if (xMatches && insideVerticalBounds) {
                                        coordinateMatchFound = true;
                                        break;
                                    }
                                }

                                // HARD BLOCK INTERCEPT: If coordinates match, do not draw it under any circumstance
                                if (coordinateMatchFound) {
                                    System.out.println(String.format("  [INTERCEPT SUCCESS] Blocked drawing green line at exact coordinate -> X=%.3f, Y=%.3f (H=%.3f)", 
                                            cleanLine.getX(), cleanLine.getY(), cleanLine.getHeight()));
                                        continue; 
                                }

                                // Draw line if it passes the spatial coordinate barrier
                                paintCount++;
                                outputStream.addRect(
                                    (float) cleanLine.getX(), 
                                    (float) cleanLine.getY(), 
                                    (float) cleanLine.getWidth(), 
                                    (float) cleanLine.getHeight()
                                );
                                outputStream.stroke();
                            }
                            System.out.println(String.format("  -> Render finished. Drew %d green lines safely.", paintCount));
                        }
                    }
                    System.out.println(String.format(">>> PAGE %d PROCESSING COMPLETE <<<\n", i + 1));
                }

                System.out.println("[FINALIZE] Saving file...");
                document.save(outputFile);
                System.out.println("[SUCCESS] Clean output file generated at: " + outputFile.getAbsolutePath());

            } catch (IOException e) {
                System.err.println("[FATAL CRASH] Pipeline stopped: " + e.getMessage());
            }
        }
    }

    /**
     * SCRIPT 2: COORDINATE EXTRACTION ANALYST
     */
    private static class Script2Analyst {
        private final PDPage page;
        private final float pageHeight;
        private final List<Rectangle2D> bannedCoordinateZones = new ArrayList<>();

        public Script2Analyst(PDPage page) {
            this.page = page;
            this.pageHeight = page.getMediaBox().getHeight();
        }

        public List<Rectangle2D> getBannedCoordinateZones() {
            return bannedCoordinateZones;
        }

        public List<Rectangle2D> generateApprovedSnapshot() throws IOException {
            DrawingBoxEngine scout = new DrawingBoxEngine(page);
            scout.processPage(page);
            List<Rectangle2D> rawScoutedVectors = scout.getDetectedBoxes();
            
            List<Rectangle2D> approvedKeepList = new ArrayList<>();
            List<VisualBox> targetBoxesToAudit = new ArrayList<>();
            Map<Rectangle2D, Boolean> exclusionRegistry = new IdentityHashMap<>();

            for (Rectangle2D shape : rawScoutedVectors) {
                exclusionRegistry.put(shape, false); 
                if (shape.getWidth() > 2.0 && shape.getHeight() > 2.0) {
                    targetBoxesToAudit.add(new VisualBox(shape, shape)); 
                }
            }

            if (!targetBoxesToAudit.isEmpty()) {
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    Rectangle2D rawBounds = targetBoxesToAudit.get(b).transformedShape;
                    float awtY = pageHeight - (float)rawBounds.getY() - (float)rawBounds.getHeight();
                    stripper.addRegion("reg_" + b, new Rectangle2D.Float(
                            (float)rawBounds.getX() + 0.5f, awtY + 0.5f, (float)rawBounds.getWidth() - 1.0f, (float)rawBounds.getHeight() - 1.0f));
                }

                stripper.extractRegions(page);

                for (int b = 0; b < targetBoxesToAudit.size(); b++) {
                    VisualBox box = targetBoxesToAudit.get(b);
                    String extractedText = stripper.getTextForRegion("reg_" + b).trim();
                    
                    boolean textIsEmpty = extractedText.isEmpty();
                    boolean isStructuralGapColumn = (box.transformedShape.getWidth() > 3.0 && box.transformedShape.getWidth() < 22.0);

                    if (textIsEmpty || isStructuralGapColumn) {
                        box.isEmptyArea = true;
                    }
                }

                filterSnapshotObjects(targetBoxesToAudit, exclusionRegistry);
            }

            // Populate cleanly back into our array list loop
            for (Rectangle2D originalVector : rawScoutedVectors) {
                approvedKeepList.add(originalVector);
            }

            return approvedKeepList;
        }

        private void filterSnapshotObjects(List<VisualBox> boxes, Map<Rectangle2D, Boolean> exclusionRegistry) {
            float alignmentTolerance = 5.0f;  
            float sizeMatchTolerance = 2.0f;  
            float gapSearchLimit = 15.0f;     

            for (int i = 0; i < boxes.size(); i++) {
                VisualBox target = boxes.get(i);
                if (!target.isEmptyArea) continue;

                boolean immediateRowRepeat = false;
                boolean immediateColRepeat = false;

                for (VisualBox neighbor : boxes) {
                    if (target == neighbor) continue;
                    boolean onSameRow = Math.abs(target.transformedShape.getY() - neighbor.transformedShape.getY()) < alignmentTolerance;
                    boolean matchHeight = Math.abs(target.transformedShape.getHeight() - neighbor.transformedShape.getHeight()) < sizeMatchTolerance;
                    
                    if (onSameRow && matchHeight) {
                        double distanceLeft = target.transformedShape.getX() - (neighbor.transformedShape.getX() + neighbor.transformedShape.getWidth());
                        double distanceRight = neighbor.transformedShape.getX() - (target.transformedShape.getX() + target.transformedShape.getWidth());
                        if ((distanceLeft >= -alignmentTolerance && distanceLeft <= gapSearchLimit) || 
                            (distanceRight >= -alignmentTolerance && distanceRight <= gapSearchLimit)) {
                            immediateRowRepeat = true;
                            break; 
                        }
                    }
                }

                for (VisualBox neighbor : boxes) {
                    if (target == neighbor) continue;
                    boolean onSameCol = Math.abs(target.transformedShape.getX() - neighbor.transformedShape.getX()) < alignmentTolerance;
                    boolean matchWidth = Math.abs(target.transformedShape.getWidth() - neighbor.transformedShape.getWidth()) < sizeMatchTolerance;

                    if (onSameCol && matchWidth) {
                        double distanceAbove = target.transformedShape.getY() - (neighbor.transformedShape.getY() + neighbor.transformedShape.getHeight());
                        double distanceBelow = neighbor.transformedShape.getY() - (target.transformedShape.getY() + target.transformedShape.getHeight());
                        if ((distanceAbove >= -alignmentTolerance && distanceAbove <= gapSearchLimit) || 
                            (distanceBelow >= -alignmentTolerance && distanceBelow <= gapSearchLimit)) {
                            immediateColRepeat = true;
                            break; 
                        }
                    }
                }

                if ((immediateRowRepeat && !immediateColRepeat) || (immediateColRepeat && !immediateRowRepeat)) {
                    exclusionRegistry.put(target.rawSourceLineReference, true); 
                    // Lock these coordinates into our master blacklist zone map
                    bannedCoordinateZones.add(target.transformedShape);
                }
            }
        }
    }

    private static class VisualBox {
        final Rectangle2D transformedShape;
        final Rectangle2D rawSourceLineReference;
        boolean isEmptyArea = false;
        
        VisualBox(Rectangle2D transformed, Rectangle2D rawSource) { 
            this.transformedShape = transformed; 
            this.rawSourceLineReference = rawSource;
        }
    }

    /**
     * SCRIPT 1: RAW VECTORS EXTRACTOR
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
