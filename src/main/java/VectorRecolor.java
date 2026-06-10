import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;

import java.io.File;
import java.util.List;

public class VectorRecolor extends PDFStreamEngine {

    public static void main(String[] args) throws Exception {

        String input = "pdfs/tcs_po3_rewritten.pdf";
        String output = "pdfs/output.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(input))) {

            VectorRecolor engine = new VectorRecolor();

            for (PDPage page : doc.getPages()) {
                engine.processPage(page);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) {

        String op = operator.getName();

        try {

            // FORCE COLOR OPERATORS TO BLACK
            switch (op) {

                case "rg": // fill RGB
                case "RG": // stroke RGB
                    operands.clear();
                    operands.add(new org.apache.pdfbox.cos.COSFloat(0));
                    operands.add(new org.apache.pdfbox.cos.COSFloat(0));
                    operands.add(new org.apache.pdfbox.cos.COSFloat(0));
                    break;

                case "g": // grayscale fill
                case "G": // grayscale stroke
                    operands.clear();
                    operands.add(new org.apache.pdfbox.cos.COSFloat(0));
                    break;
            }

            super.processOperator(operator, operands);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
