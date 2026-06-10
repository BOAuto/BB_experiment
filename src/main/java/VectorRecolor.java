import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    public static void main(String[] args) throws Exception {

        String input = "pdfs/tcs_po3_rewritten.pdf";
        String output = "pdfs/output.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(input))) {

            for (PDPage page : doc.getPages()) {
                recolorPage(doc, page);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    private static void recolorPage(PDDocument doc, PDPage page) throws Exception {

        ColorRewriter engine = new ColorRewriter();
        engine.processPage(page);

        List<Object> rewritten = engine.getRewrittenContent();

        // overwrite page content stream safely
        try (PDPageContentStream cs =
                     new PDPageContentStream(doc, page,
                             PDPageContentStream.AppendMode.OVERWRITE, false)) {

            for (Object o : rewritten) {
                if (o instanceof String s) {
                    cs.writeOperator(() -> s);
                } else {
                    cs.appendRawCommands(o.toString() + " ");
                }
            }
        }
    }

    /**
     * Content stream processor
     */
    static class ColorRewriter extends PDFStreamEngine {

        private final List<Object> output = new ArrayList<>();

        public List<Object> getRewrittenContent() {
            return output;
        }

        @Override
        protected void processOperator(Operator operator, List<java.awt.geom.GeneralPath> operands) {

            String op = operator.getName();

            try {

                switch (op) {

                    // stroke RGB
                    case "RG":
                    case "rg":
                        output.add("0 0 0 " + op);
                        return;

                    // grayscale stroke/fill
                    case "G":
                    case "g":
                        output.add("0 " + op);
                        return;

                    default:
                        output.add(operator.getName());
                }

            } catch (Exception e) {
                output.add(operator.getName());
            }
        }
    }
}
