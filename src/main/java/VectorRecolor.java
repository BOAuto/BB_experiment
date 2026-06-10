import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.contentstream.PDContentStream;

import java.io.*;
import java.util.*;

public class VectorRecolor {

    private static final float R = 0f;
    private static final float G = 0f;
    private static final float B = 0f;

    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("Usage: java VectorRecolor input.pdf output.pdf");
            return;
        }

        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {

            for (PDPage page : doc.getPages()) {
                processPage(page);
            }

            doc.save(args[1]);
        }

        System.out.println("Saved: " + args[1]);
    }

    private static void processPage(PDPage page) throws Exception {

        for (PDStream stream : page.getContentStreams()) {
            rewriteStream(stream);
        }

        processResources(page.getResources());
    }

    private static void processResources(PDResources resources) throws Exception {

        if (resources == null) return;

        for (COSName name : resources.getXObjectNames()) {

            PDXObject xobj = resources.getXObject(name);

            if (xobj instanceof PDFormXObject form) {

                for (PDStream stream : form.getContentStreams()) {
                    rewriteStream(stream);
                }

                processResources(form.getResources());
            }
        }
    }

    private static void rewriteStream(PDStream stream) throws Exception {

        PDContentStream cs = stream;

        PDFStreamParser parser = new PDFStreamParser(cs);
        List<Object> tokens = parser.parse();

        List<Object> output = new ArrayList<>();

        boolean insideText = false;

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                if ("BT".equals(name)) insideText = true;
                if ("ET".equals(name)) insideText = false;

                if (!insideText && isPaintOperator(name)) {
                    injectColor(output);
                }
            }

            output.add(token);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ContentStreamWriter writer = new ContentStreamWriter(baos);
        writer.writeTokens(output);

        try (OutputStream os = stream.createOutputStream()) {
            os.write(baos.toByteArray());
        }
    }

    private static boolean isPaintOperator(String op) {
        return op.equals("S") || op.equals("s") ||
               op.equals("f") || op.equals("F") || op.equals("f*") ||
               op.equals("B") || op.equals("B*") ||
               op.equals("b") || op.equals("b*");
    }

    private static void injectColor(List<Object> out) {

        out.add(new COSFloat(R));
        out.add(new COSFloat(G));
        out.add(new COSFloat(B));
        out.add(Operator.getOperator("RG"));

        out.add(new COSFloat(R));
        out.add(new COSFloat(G));
        out.add(new COSFloat(B));
        out.add(Operator.getOperator("rg"));
    }
}
