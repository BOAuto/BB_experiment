import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.*;
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
                processCOS(page.getCOSObject());
                processResources(page.getResources());
            }

            doc.save(args[1]);
        }

        System.out.println("Saved: " + args[1]);
    }

    private static void processResources(PDResources resources) throws Exception {

        if (resources == null) return;

        for (COSName name : resources.getXObjectNames()) {

            PDXObject xobj = resources.getXObject(name);

            if (xobj instanceof PDFormXObject form) {
                processCOS(form.getCOSObject());
                processResources(form.getResources());
            }
        }
    }

    private static void processCOS(COSBase base) throws Exception {

        if (!(base instanceof COSStream cosStream)) return;

        PDFStreamParser parser = new PDFStreamParser(cosStream);
        List<Object> tokens = parser.parse();

        List<Object> out = new ArrayList<>();

        boolean insideText = false;

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                if ("BT".equals(name)) insideText = true;
                if ("ET".equals(name)) insideText = false;

                if (!insideText && isPaint(name)) {
                    inject(out);
                }
            }

            out.add(token);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ContentStreamWriter writer = new ContentStreamWriter(baos);
        writer.writeTokens(out);

        cosStream.setUnfilteredStream(baos.toByteArray());
    }

    private static boolean isPaint(String op) {
        return op.equals("S") || op.equals("s") ||
               op.equals("f") || op.equals("F") || op.equals("f*") ||
               op.equals("B") || op.equals("B*") ||
               op.equals("b") || op.equals("b*");
    }

    private static void inject(List<Object> out) {

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
