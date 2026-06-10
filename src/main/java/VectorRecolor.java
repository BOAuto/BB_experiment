import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.File;

public class VectorRecolor {

    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("Usage: java VectorRecolor input.pdf output.pdf");
            return;
        }

        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {

            PDFRenderer renderer = new PDFRenderer(doc);

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);

                CustomDrawer drawer = new CustomDrawer(doc, page);

                drawer.drawPage(page);
            }

            doc.save(args[1]);
        }

        System.out.println("Saved: " + args[1]);
    }

    // Custom renderer that forces vector color override
    static class CustomDrawer extends PageDrawer {

        public CustomDrawer(PDDocument document, PDPage page) {
            super(document, page);
        }

        @Override
        protected void setStrokingColorSpace(org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace colorSpace) {
            super.setStrokingColorSpace(PDDeviceRGB.INSTANCE);
        }

        @Override
        protected void setNonStrokingColorSpace(org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace colorSpace) {
            super.setNonStrokingColorSpace(PDDeviceRGB.INSTANCE);
        }

        @Override
        protected void setStrokingColor(PDColor color) {
            super.setStrokingColor(new PDColor(new float[]{0, 0, 0}, PDDeviceRGB.INSTANCE));
        }

        @Override
        protected void setNonStrokingColor(PDColor color) {
            super.setNonStrokingColor(new PDColor(new float[]{0, 0, 0}, PDDeviceRGB.INSTANCE));
        }
    }
}
