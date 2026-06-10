import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

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
        PDStream contents = page.getContentStreams().next();
        if (contents != null) {
            rewriteStream(contents);
        }
        processResources(page.getResources());
    }

    private static void processResources(PDResources resources) throws Exception {
        if (resources == null) return;

        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobj = resources.getXObject(name);

            if (xobj instanceof PDFormXObject form) {
                rewriteStream(form.getContentStream());
                processResources(form.getResources());
            }
        }
    }

    private static void rewriteStream(PDStream stream) throws Exception {

        PDFStreamParser parser = new PDFStreamParser(stream);
        List<Object> tokens = parser.parse();

        List<Object> newTokens = new ArrayList<>();

        boolean insideText = false;

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                if ("BT".equals(name)) insideText = true;
                if ("ET".equals(name)) insideText = false;

                if (!insideText && isVectorPaintOp(name)) {
                    injectBlackColor(newTokens);
                }
            }

            newTokens.add(token);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ContentStreamWriter writer = new ContentStreamWriter(out);
        writer.writeTokens(newTokens);

        stream.setData(out.toByteArray());
    }

    private static boolean isVectorPaintOp(String op) {
        return Set.of(
                "S", "s",
                "f", "F", "f*",
                "B", "B*", "b", "b*"
        ).contains(op);
    }

    private static void injectBlackColor(List<Object> list) {
        list.add(new COSFloat(R));
        list.add(new COSFloat(G));
        list.add(new COSFloat(B));
        list.add(Operator.getOperator("RG")); // stroke

        list.add(new COSFloat(R));
        list.add(new COSFloat(G));
        list.add(new COSFloat(B));
        list.add(Operator.getOperator("rg")); // fill
    }
}
