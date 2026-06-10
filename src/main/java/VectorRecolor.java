import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.SetStrokingRGBColor;
import org.apache.pdfbox.contentstream.operator.state.SetNonStrokingRGBColor;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.*;
import java.util.*;

public class VectorRecolor extends PDFStreamEngine {

    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("Usage: java VectorRecolor input.pdf output.pdf");
            return;
        }

        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {

            VectorRecolor engine = new VectorRecolor();

            for (PDPage page : doc.getPages()) {
                engine.processPage(page);

                processXObjects(page.getResources());
            }

            doc.save(args[1]);
        }

        System.out.println("Saved: " + args[1]);
    }

    private static void processXObjects(PDResources resources) throws Exception {

        if (resources == null) return;

        for (COSName name : resources.getXObjectNames()) {

            PDXObject obj = resources.getXObject(name);

            if (obj instanceof PDFormXObject form) {

                VectorRecolor engine = new VectorRecolor();
                engine.processPage(form);

                processXObjects(form.getResources());
            }
        }
    }

    // This is the ONLY stable interception point in PDFBox 3.x
    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) {

        String op = operator.getName();

        try {

            // Force color before vector drawing ops
            if (isPaint(op)) {

                // force RGB black
                getGraphicsState().setStrokingColor(0, 0, 0);
                getGraphicsState().setNonStrokingColor(0, 0, 0);
            }

            super.processOperator(operator, operands);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isPaint(String op) {
        return op.equals("S") || op.equals("s") ||
               op.equals("f") || op.equals("F") || op.equals("f*") ||
               op.equals("B") || op.equals("B*") ||
               op.equals("b") || op.equals("b*");
    }
}
