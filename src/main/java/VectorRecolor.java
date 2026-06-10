import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;

import java.io.File;
import java.util.List;

public class VectorRecolor extends PDFStreamEngine {

    private static final PDColor BLACK =
            new PDColor(new float[]{0f, 0f, 0f}, PDDeviceRGB.INSTANCE);

    public static void main(String[] args) throws Exception {

        String inputPath = "pdfs/tcs_po3_rewritten.pdf";
        String outputPath = "pdfs/output.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(inputPath))) {

            VectorRecolor engine = new VectorRecolor();

            for (PDPage page : doc.getPages()) {
                engine.processPage(page);
            }

            doc.save(outputPath);
        }

        System.out.println("Saved: " + outputPath);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) {

        String op = operator.getName();

        try {

            // Vector drawing operators that produce visible shapes
            if (isVectorDrawingOperator(op)) {

                getGraphicsState().setStrokingColor(BLACK);
                getGraphicsState().setNonStrokingColor(BLACK);
            }

            super.processOperator(operator, operands);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isVectorDrawingOperator(String op) {
        return op.equals("S") || op.equals("s") ||   // stroke
               op.equals("f") || op.equals("F") ||   // fill
               op.equals("B") || op.equals("B*") ||  // fill+stroke
               op.equals("b") || op.equals("b*");    // close+fill+stroke
    }
}
