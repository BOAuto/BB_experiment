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
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
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
            System.out.println("Processing Structure for: " + inputFile.getName() + "...");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    
                    // Step 1: Extract raw vector lines
                    LineExtractorEngine lineEngine = new LineExtractorEngine(page);
                    lineEngine.processPage(page);
                    List<Line2D> allLines = lineEngine.getExtractedLines();

                    // Step 2: Assemble visual rectangles from intersecting lines
                    List<VisualRect> visualBoxes = GridStructureParser.findVisualRectangles(allLines);

                    if (!visualBoxes.isEmpty()) {
                        // Step 3: Evaluate which boxes are empty using PDFTextStripperByArea
                        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                        for (int b = 0; b < visualBoxes.size(); b++) {
                            stripper.addRegion("box_" + b, visualBoxes.get(b).bounds);
                        }
                        stripper.extractRegions(page);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            String textInside = stripper.getTextForRegion("box_" + b).trim();
                            visualBoxes.get(b).hasContent = !textInside.isEmpty();
                        }

                        // Step 4: Apply Normalization rules based on structural neighbors
                        GridStructureParser.applyNormalizationRules(visualBoxes);

                        // Step 5: Render remaining valid boxes back out onto the page
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (VisualRect box : visualBoxes) {
                                // Draw remaining boundaries that survived normalization
                                if (box.drawLeft) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                }
                                if (box.drawTop) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                }
                                if (box.drawRight) {
                                    contentStream.moveTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                }
                                if (box.drawBottom) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                }
                                contentStream.stroke();
                            }
                        }
                    }
                }
                document.save(outputFile);
                System.out.println(" -> Saved structural normalization to: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Error analyzing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    // Container representing a visually parsed cell/box block
    private static class VisualRect {
        Rectangle2D.Float bounds;
        boolean hasContent = false;
        boolean drawLeft = true;
        boolean drawTop = true;
        boolean drawRight = true;
        boolean drawBottom = true;

        VisualRect(float x, float y, float w, float h) {
            this.bounds = new Rectangle2D.Float(x, y, w, h);
        }
    }

    // Step 1: Engine focused purely on harvesting vector segment endpoints
    private static class LineExtractorEngine extends PDFGraphicsStreamEngine {
        private final List<Line2D> extractedLines = new ArrayList<>();
        private Point2D currentPoint = new Point2D.Float(0, 0);

        protected LineExtractorEngine(PDPage page) { super(page); }
        public List<Line2D> getExtractedLines() { return extractedLines; }

        @Override
        public void moveTo(float x, float y) { this.currentPoint = new Point2D.Float(x, y); }

        @Override
        public void lineTo(float x, float y) {
            extractedLines.add(new Line2D.Float((float)currentPoint.getX(), (float)currentPoint.getY(), x, y));
            this.currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            extractedLines.add(new Line2D.Double(p0, p1));
            extractedLines.add(new Line2D.Double(p1, p2));
            extractedLines.add(new Line2D.Double(p2, p3));
            extractedLines.add(new Line2D.Double(p3, p0));
        }

        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) {}
        @Override public void clip(int windingRule) {}
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public Point2D getCurrentPoint() { return currentPoint; }
        @Override public void shadingFill(COSName shadingName) {}
    }

    // Core Logical Analyzer for Table/Grid Layout structures
    private static class GridStructureParser {
        
        // Simple geometric sweeper to find closed four-sided bounding blocks from line endpoints
        public static List<VisualRect> findVisualRectangles(List<Line2D> lines) {
            List<VisualRect> rects = new ArrayList<>();
            float tolerance = 2.0f; // Snapping tolerance for disconnected vector ends

            // Filter lines into horizontal and vertical collections
            List<Line2D> horiz = new ArrayList<>();
            List<Line2D> vert = new ArrayList<>();
            for (Line2D line : lines) {
                if (Math.abs(line.getY1() - line.getY2()) <= tolerance) horiz.add(line);
                else if (Math.abs(line.getX1() - line.getX2()) <= tolerance) vert.add(line);
            }

            // Cross-reference intersections to construct closed bounding structures
            for (Line2D hTop : horiz) {
                for (Line2D hBot : horiz) {
                    if (hTop.getY1() <= hBot.getY1()) continue; // Keep top above bottom

                    for (Line2D vLeft : vert) {
                        for (Line2D vRight : vert) {
                            if (vLeft.getX1() >= vRight.getX1()) continue;

                            // Check structural overlaps to verify they complete a visual cell ring
                            float minX = (float) vLeft.getX1();
                            float maxX = (float) vRight.getX1();
                            float minY = (float) hBot.getY1();
                            float maxY = (float) hTop.getY1();

                            // Validate bounds logic
                            if (hTop.getX1() - tolerance <= minX && hTop.getX2() + tolerance >= maxX &&
                                hBot.getX1() - tolerance <= minX && hBot.getX2() + tolerance >= maxX) {
                                
                                VisualRect detected = new VisualRect(minX, minY, maxX - minX, maxY - minY);
                                // Deduplicate matching coordinates
                                if (rects.stream().noneMatch(r -> r.bounds.distance(detected.bounds.x, detected.bounds.y) < 3)) {
                                    rects.add(detected);
                                }
                            }
                        }
                    }
                }
            }
            return rects;
        }

        // Applies your spatial rule matrix over empty cells
        public static void applyNormalizationRules(List<VisualRect> boxes) {
            float proximityThreshold = 5.0f; // Padding to verify adjacent box borders

            for (VisualRect target : boxes) {
                if (target.hasContent) continue; // Rules only apply to empty visual rects

                boolean hasHorizontalFlow = false;
                boolean hasVerticalFlow = false;

                for (VisualRect neighbor : boxes) {
                    if (target == neighbor) continue;

                    // Check if neighbor aligns horizontally (Left or Right side neighbor)
                    boolean alignY = Math.abs(target.bounds.y - neighbor.bounds.y) < proximityThreshold;
                    if (alignY) {
                        hasHorizontalFlow = true;
                    }

                    // Check if neighbor aligns vertically (Top or Bottom neighbor)
                    boolean alignX = Math.abs(target.bounds.x - neighbor.bounds.x) < proximityThreshold;
                    if (alignX) {
                        hasVerticalFlow = true;
                    }
                }

                // Apply Normalization Strategy Rules Matrix
                if (hasHorizontalFlow && !hasVerticalFlow) {
                    target.drawLeft = false; // Remove left border line
                } else if (hasVerticalFlow && !hasHorizontalFlow) {
                    target.drawTop = false;  // Remove top border line
                } else if (!hasHorizontalFlow && !hasVerticalFlow) {
                    // Isolated empty box -> Wipe out all 4 lines
                    target.drawLeft = false;
                    target.drawTop = false;
                    target.drawRight = false;
                    target.drawBottom = false;
                }
                // If both are true (bidirectional flow), lines are preserved untouched
            }
        }
    }
}
