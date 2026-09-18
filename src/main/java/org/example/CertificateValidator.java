package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;

import java.io.File;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class CertificateValidator {

    private CertificateValidator() {
    }

    /**
     * Verifies that the certificate contains a portrait-like candidate photo.
     *
     * Important:
     * Some SMY certificates place the candidate photo inside a nested PDF Form
     * XObject. The old validator checked only top-level page images, so a valid
     * visible photo could be incorrectly reported as missing.
     */
    public static boolean hasCandidatePhoto(File file) {

        if (file == null || !file.isFile() || file.length() == 0) {
            System.out.println("REJECTED: Certificate PDF is missing or empty.");
            return false;
        }

        try (PDDocument document = Loader.loadPDF(file)) {

            Set<COSBase> visited =
                    Collections.newSetFromMap(new IdentityHashMap<>());

            for (PDPage page : document.getPages()) {
                if (containsCandidatePhoto(page.getResources(), visited)) {
                    return true;
                }
            }

            /*
             * Chrome Page.printToPDF can encode a normal HTML <img> as inline
             * page content instead of a PDImageXObject resource. In that case
             * the candidate photo is visibly present in the PDF, but the XObject
             * scan above cannot see it and used to reject every valid SMY
             * certificate.
             *
             * SMY's certificate layout places Candidate Photo in the lower-left
             * part of page 1. Render that region and verify that it contains a
             * substantial block of non-white pixels. A missing photo leaves only
             * the small "Candidate Photo" caption and stays below the threshold.
             */
            if (containsRenderedCandidatePhoto(document)) {
                return true;
            }

        } catch (Exception e) {
            System.err.println(
                    "PDF candidate-photo validation error: " + e.getMessage()
            );
        }

        System.out.println(
                "REJECTED: Candidate photo is missing or broken."
        );
        return false;
    }

    /**
     * Recursively scans normal page resources AND nested Form XObjects.
     */
    private static boolean containsCandidatePhoto(
            PDResources resources,
            Set<COSBase> visited
    ) throws Exception {

        if (resources == null) {
            return false;
        }

        for (COSName name : resources.getXObjectNames()) {

            PDXObject xObject;

            try {
                xObject = resources.getXObject(name);
            } catch (Exception e) {
                // One broken decorative object must not reject the whole PDF.
                continue;
            }

            if (xObject == null) {
                continue;
            }

            COSBase cosObject = xObject.getCOSObject();

            if (cosObject != null && !visited.add(cosObject)) {
                continue;
            }

            if (xObject instanceof PDImageXObject image) {

                int width = image.getWidth();
                int height = image.getHeight();

                if (looksLikeCandidatePortrait(width, height)) {
                    System.out.println(
                            "Candidate photo verified: "
                                    + width + "x" + height
                    );
                    return true;
                }
            }

            if (xObject instanceof PDFormXObject form) {
                if (containsCandidatePhoto(form.getResources(), visited)) {
                    return true;
                }
            }
        }

        return false;
    }


    /**
     * Visual fallback for PDFs produced by Chrome Page.printToPDF.
     *
     * Candidate photo location is stable on the SMY certificate: roughly
     * x=7%-30%, y=66%-92% of page 1. We intentionally look at the rendered
     * page rather than PDF image resources so inline/rasterized images are
     * handled correctly.
     */
    private static boolean containsRenderedCandidatePhoto(PDDocument document) {
        try {
            if (document.getNumberOfPages() < 1) {
                return false;
            }

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 120);

            int width = image.getWidth();
            int height = image.getHeight();

            int x0 = Math.max(0, (int) Math.round(width * 0.07));
            int x1 = Math.min(width, (int) Math.round(width * 0.30));
            int y0 = Math.max(0, (int) Math.round(height * 0.66));
            int y1 = Math.min(height, (int) Math.round(height * 0.92));

            if (x1 <= x0 || y1 <= y0) {
                return false;
            }

            long nonWhite = 0L;
            long total = 0L;

            // Sample every second pixel: plenty accurate and much faster.
            for (int y = y0; y < y1; y += 2) {
                for (int x = x0; x < x1; x += 2) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xFF;
                    int red = (argb >>> 16) & 0xFF;
                    int green = (argb >>> 8) & 0xFF;
                    int blue = argb & 0xFF;

                    if (alpha > 20) {
                        total++;
                        // Text alone occupies very little area. A real candidate
                        // portrait produces a dense block of darker/coloured pixels.
                        if (red < 242 || green < 242 || blue < 242) {
                            nonWhite++;
                        }
                    }
                }
            }

            if (total == 0L) {
                return false;
            }

            double ratio = (double) nonWhite / (double) total;
            System.out.printf(
                    "Candidate photo visual check: %.2f%%%n",
                    ratio * 100.0
            );

            if (ratio >= 0.060) {
                System.out.println("Candidate photo verified by rendered PDF region.");
                return true;
            }

        } catch (Exception e) {
            System.err.println(
                    "Rendered candidate-photo validation error: " + e.getMessage()
            );
        }

        return false;
    }

    /**
     * Candidate photos on SMY certificates are portrait-oriented.
     * This deliberately rejects very wide signature images and most square logos.
     */
    private static boolean looksLikeCandidatePortrait(
            int width,
            int height
    ) {

        if (width < 60 || height < 70) {
            return false;
        }

        double ratio =
                (double) height / (double) width;

        return ratio >= 1.05 && ratio <= 1.90;
    }
}
