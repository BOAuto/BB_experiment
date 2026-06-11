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
import java.util.Collections;
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
            System.out.println("[START] Fast-Processing Structure for: " + inputFile.getName());
            System.out.println("========================================================");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    float pageHeight = page.getMediaBox().getHeight();
                    System.out.println(String.format("\n--- PAGE %d ---", i + 1));
                    
                    long startTime = System.currentTimeMillis();

                    // Step 1: Extract lines
                    LineExtractorEngine lineEngine = new LineExtractorEngine(page);
                    lineEngine.processPage(page);
                    List<Line2D> allLines = lineEngine.getExtractedLines();
                    System.out.println(String.format(" -> Extracted %d raw lines.", allLines.size()));

                    // Step 2: High-speed structural cell generation
                    List<VisualRect> visualBoxes = GridStructureParser.findVisualRectanglesOptimized(allLines);
                    System.out.println(String.format(" -> Formed %d visual boxes in %d ms.", visualBoxes.size(), (System.currentTimeMillis() - startTime)));

                    if (!visualBoxes.isEmpty()) {
                        // Step 3: Text Content evaluation
                        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                        stripper.setSortByPosition(true);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            Rectangle2D.Float bounds = visualBoxes.get(b).bounds;
                            float awtY = pageHeight - bounds.y - bounds.height;
                            stripper.addRegion("box_" + b, new Rectangle2D.Float(
                                bounds.x + 1.0f, awtY + 1.0f, bounds.width - 2.0f, bounds.height - 2.0f
                            ));
                        }
                        
                        stripper.extractRegions(page);

                        for (int b = 0; b < visualBoxes.size(); b++) {
                            VisualRect box = visualBoxes.get(b);
                            String textInside = stripper.getTextForRegion("box_" + b).trim();
                            box.hasContent = !textInside.isEmpty();
                        }

                        // Step 4: Apply Normalization Matrix Rules
                        GridStructureParser.applyNormalizationRules(visualBoxes);

                        // Step 5: Render surviving geometry
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (VisualRect box : visualBoxes) {
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
                System.out.println("\n[SUCCESS] Saved to: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("[-] Error: " + e.getMessage());
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
        
        // Optimized spatial sweep method running near O(N log N)
        public static List<VisualRect> findVisualRectanglesOptimized(List<Line2D> lines) {
            List<VisualRect> rects = new ArrayList<>();
            float tolerance = 4.0f;

            List<Line2D> horiz = new ArrayList<>();
            List<Line2D> vert = new ArrayList<>();
            
            for (Line2D line : lines) {
                // Normalize line segment point orders to simplify sweep comparisons
                double x1 = Math.min(line.getX1(), line.getX2());
                double x2 = Math.max(line.getX1(), line.getX2());
                double y1 = Math.min(line.getY1(), line.getY2());
                double y2 = Math.max(line.getY1(), line.getY2());
                
                if (Math.abs(y1 - y2) <= tolerance) {
                    horiz.add(new Line2D.Double(x1, y1, x2, y1));
                } else if (Math.abs(x1 - x2) <= tolerance) {
                    vert.add(new Line2D.Double(x1, y1, x1, y2));
                }
            }

            // Sort lines spatially to optimize searching
            Collections.sort(horiz, (a, b) -> Double.compare(b.getY1(), a.getY1())); // Top-to-bottom
            Collections.sort(vert, (a, b) -> Double.compare(a.getX1(), b.getX1()));   // Left-to-right

            // Loop horizontally but restrict inner verification to overlapping line bounds
            for (int i = 0; i < horiz.size(); i++) {
                Line2D hTop = horiz.get(i);
                
                for (int j = i + 1; j < horiz.size(); j++) {
                    Line2D hBot = horiz.get(j);
                    
                    // Break early if we've gone too far structurally vertically 
                    if (hTop.getY1() - hBot.getY1() > 150.0) break; 
                    if (hTop.getY1() <= hBot.getY1()) continue;

                    // Locate vertical segments spanning across this specific row envelope
                    List<Line2D> candidateVerts = new ArrayList<>();
                    for (Line2D v : vert) {
                        if (v.getY1() - tolerance <= hBot.getY1() && v.getY2() + tolerance >= hTop.getY1()) {
                            // Check horizontal overlap boundaries
                            double overlapLeft = Math.max(hTop.getX1(), hBot.getX1());
                            double overlapRight = Math.min(hTop.getX2(), hBot.getX2());
                            if (v.getX1() >= overlapLeft - tolerance && v.getX1() <= overlapRight + tolerance) {
                                candidateVerts.add(v);
                            }
                        }
                    }

                    // Connect adjacent matching vertical columns inside this slice
                    for (int k = 0; k < candidateVerts.size(); k++) {
                        for (int m = k + 1; m < candidateVerts.size(); m++) {
                            Line2D vLeft = candidateVerts.get(k);
                            Line2D vRight = candidateVerts.get(m);

                            float minX = (float) vLeft.getX1();
                            float maxX = (float) vRight.getX1();
                            float minY = (float) hBot.getY1();
                            float maxY = (float) hTop.getY1();

                            float width = maxX - minX;
                            float height = maxY - minY;

                            if (width > 4.0f && height > 4.0f) {
                                VisualRect detected = new VisualRect(minX, minY, width, height);
                                if (rects.stream().noneMatch(r -> Math.abs(r.bounds.x - detected.bounds.x) < 3.0f 
                                                              && Math.abs(r.bounds.y - detected.bounds.y) < 3.0f)) {
                                    rects.add(detected);
                                    break; // Found immediate column bound, step to next
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

            for (VisualRect target : boxes) {
                if (target.hasContent) continue; 

                boolean hasHorizontalFlow = false;
                boolean hasVerticalFlow = false;

                for (VisualRect neighbor : boxes) {
                    if (target == neighbor) continue;

                    if (Math.abs(target.bounds.y - neighbor.bounds.y) < flowAlignmentThreshold) {
                        hasHorizontalFlow = true;
                    }
                    if (Math.abs(target.bounds.x - neighbor.bounds.x) < flowAlignmentThreshold) {
                        hasVerticalFlow = true;
                    }
                }

                if (hasHorizontalFlow && !hasVerticalFlow) {
                    target.drawLeft = false; 
                } else if (hasVerticalFlow && !hasHorizontalFlow) {
                    target.drawTop = false;  
                } else if (!hasHorizontalFlow && !hasVerticalFlow) {
                    target.drawLeft = false;
                    target.drawTop = false;
                    target.drawRight = false;
                    target.drawBottom = false;
                }
            }
        }
    }
}
