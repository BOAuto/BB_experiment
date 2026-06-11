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
            System.out.println("[-] No PDF files found in 'pdfs/' directory.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "normalized_" + inputFile.getName());
            System.out.println("\n========================================================");
            System.out.println("[START] Processing Structure for: " + inputFile.getName());
            System.out.println("========================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    System.out.println(String.format("\n--- PAGE %d (Height: %.2f) ---", i + 1, pageHeight));
                    
                    // Step 1: Extract raw lines using your reliable GraphicsEngine wrapper
                    LineExtractorEngine lineEngine = new LineExtractorEngine(page);
                    lineEngine.processPage(page);
                    List<Line2D> allLines = lineEngine.getExtractedLines();
                    System.out.println(String.format("[DEBUG] Extracted %d raw vector path lines.", allLines.size()));

                    // Step 2: Assemble visual closed rectangles
                    List<VisualRect> visualBoxes = GridStructureParser.findVisualRectangles(allLines);
                    System.out.println(String.format("[DEBUG] Formed %d visual closed rectangles.", visualBoxes.size()));

                    if (!visualBoxes.isEmpty()) {
                        // Step 3: Check regions for text content
                        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                        stripper.setSortByPosition(true);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            Rectangle2D.Float bounds = visualBoxes.get(b).bounds;
                            float awtY = pageHeight - bounds.y - bounds.height;
                            
                            // 1-point inward padding to avoid scraping border vectors as text
                            stripper.addRegion("box_" + b, new Rectangle2D.Float(
                                bounds.x + 1.0f, awtY + 1.0f, bounds.width - 2.0f, bounds.height - 2.0f
                            ));
                        }
                        
                        stripper.extractRegions(page);

                        System.out.println("\n--- Box Content Diagnostics ---");
                        for (int b = 0; b < visualBoxes.size(); b++) {
                            VisualRect box = visualBoxes.get(b);
                            String textInside = stripper.getTextForRegion("box_" + b).trim();
                            box.hasContent = !textInside.isEmpty();
                            
                            System.out.println(String.format(" Box #%d -> Bounds[x=%.1f, y=%.1f, w=%.1f, h=%.1f] | Has Content: %b | Extracted Text: '%s'", 
                                b, box.bounds.x, box.bounds.y, box.bounds.width, box.bounds.height, box.hasContent, textInside));
                        }

                        // Step 4: Run proximity analysis and apply flow normalization
                        System.out.println("\n--- Normalization Logic Diagnostics ---");
                        GridStructureParser.applyNormalizationRules(visualBoxes);

                        // Step 5: High-Precision Surgical Mask Overlay Layer
                        // Instead of appending green lines, we mask out the canceled lines using the background color (White)
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            // We set the mask color to white and slightly widen the stroke to cleanly neutralize the black background line
                            contentStream.setStrokingColor(Color.WHITE);
                            contentStream.setLineWidth(1.5f);

                            for (VisualRect box : visualBoxes) {
                                if (!box.drawLeft) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.stroke();
                                }
                                if (!box.drawTop) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y + box.bounds.height);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                    contentStream.stroke();
                                }
                                if (!box.drawRight) {
                                    contentStream.moveTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y + box.bounds.height);
                                    contentStream.stroke();
                                }
                                if (!box.drawBottom) {
                                    contentStream.moveTo(box.bounds.x, box.bounds.y);
                                    contentStream.lineTo(box.bounds.x + box.bounds.width, box.bounds.y);
                                    contentStream.stroke();
                                }
                            }
                        }
                    } else {
                        System.out.println("[WARN] No closed 4-sided structural boxes detected on this page.");
                    }
                }
                document.save(outputFile);
                System.out.println("\n[SUCCESS] Generated: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("[-] Error analyzing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

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

    private static class GridStructureParser {
        
        public static List<VisualRect> findVisualRectangles(List<Line2D> lines) {
            List<VisualRect> rects = new ArrayList<>();
            float snapTolerance = 4.0f; 

            List<Line2D> horiz = new ArrayList<>();
            List<Line2D> vert = new ArrayList<>();
            for (Line2D line : lines) {
                if (Math.abs(line.getY1() - line.getY2()) <= snapTolerance) horiz.add(line);
                else if (Math.abs(line.getX1() - line.getX2()) <= snapTolerance) vert.add(line);
            }

            for (Line2D hTop : horiz) {
                for (Line2D hBot : horiz) {
                    if (hTop.getY1() <= hBot.getY1()) continue;

                    for (Line2D vLeft : vert) {
                        for (Line2D vRight : vert) {
                            if (vLeft.getX1() >= vRight.getX1()) continue;

                            float minX = (float) vLeft.getX1();
                            float maxX = (float) vRight.getX1();
                            float minY = (float) hBot.getY1();
                            float maxY = (float) hTop.getY1();

                            if (hTop.getX1() - snapTolerance <= minX && hTop.getX2() + snapTolerance >= maxX &&
                                hBot.getX1() - snapTolerance <= minX && hBot.getX2() + snapTolerance >= maxX) {
                                
                                float width = maxX - minX;
                                float height = maxY - minY;
                                
                                if (width > 4.0f && height > 4.0f) {
                                    VisualRect detected = new VisualRect(minX, minY, width, height);
                                    if (rects.stream().noneMatch(r -> Math.abs(r.bounds.x - detected.bounds.x) < 3.0f 
                                                                  && Math.abs(r.bounds.y - detected.bounds.y) < 3.0f)) {
                                        rects.add(detected);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return rects;
        }

        public static void applyNormalizationRules(List<VisualRect> boxes) {
            float flowAlignmentThreshold = 6.0f; 

            for (int i = 0; i < boxes.size(); i++) {
                VisualRect target = boxes.get(i);
                if (target.hasContent) continue; 

                boolean hasHorizontalFlow = false;
                boolean hasVerticalFlow = false;

                for (VisualRect neighbor : boxes) {
                    if (target == neighbor) continue;

                    boolean sameRow = Math.abs(target.bounds.y - neighbor.bounds.y) < flowAlignmentThreshold;
                    if (sameRow) {
                        hasHorizontalFlow = true;
                    }

                    boolean sameColumn = Math.abs(target.bounds.x - neighbor.bounds.x) < flowAlignmentThreshold;
                    if (sameColumn) {
                        hasVerticalFlow = true;
                    }
                }

                System.out.print(String.format(" -> Evaluating Empty Box #%d: HorizNeighbor=%b, VertNeighbor=%b", i, hasHorizontalFlow, hasVerticalFlow));
                
                if (hasHorizontalFlow && !hasVerticalFlow) {
                    target.drawLeft = false; 
                    System.out.println(" -> Result: [Horizontal Flow Detected] Wiping Left Border.");
                } else if (hasVerticalFlow && !hasHorizontalFlow) {
                    target.drawTop = false;  
                    System.out.println(" -> Result: [Vertical Flow Detected] Wiping Top Border.");
                } else if (!hasHorizontalFlow && !hasVerticalFlow) {
                    target.drawLeft = false;
                    target.drawTop = false;
                    target.drawRight = false;
                    target.drawBottom = false;
                    System.out.println(" -> Result: [Isolated Box Detected] Wiping all 4 borders.");
                } else {
                    System.out.println(" -> Result: [Bidirectional Flow Detected] Leaving borders untouched.");
                }
            }
        }
    }
}
